import copy
from contextlib import redirect_stdout
import io
import importlib.util
import os
from pathlib import Path
from types import SimpleNamespace
import tempfile
import unittest
from unittest.mock import patch

from branding.test_central_builder import cb, fixture

spec = importlib.util.spec_from_file_location('standalone_runner', Path(__file__).with_name('standalone_runner.py'))
runner = importlib.util.module_from_spec(spec);spec.loader.exec_module(runner)


class RetentionTests(unittest.TestCase):
    def files(self, root):
        context = fixture();apk = root/context['expected']['asset_name'];apk.write_bytes(b'APK'*1000)
        provenance = root/(apk.name+'.provenance.json');provenance.write_text('{}')
        return context, apk, provenance

    def test_retention_only_contains_exact_private_publication_files_and_is_idempotent(self):
        with tempfile.TemporaryDirectory() as temporary:
            root = Path(temporary);c,apk,provenance = self.files(root);store = root/'store'
            cb.retain_local(store, '123', c, apk, provenance)
            cb.retain_local(store, '123', c, apk, provenance)
            saved = store/'123'/c['build_id']
            self.assertEqual({apk.name,provenance.name}, {p.name for p in saved.iterdir()})
            for source in [apk,provenance]:
                self.assertEqual(source.read_bytes(), (saved/source.name).read_bytes())
                self.assertEqual(0o600, (saved/source.name).stat().st_mode & 0o777)
            apk.write_bytes(b'different')
            with self.assertRaises(ValueError):cb.retain_local(store, '123', c, apk, provenance)

    def test_retained_artifact_is_reverified_without_using_github_or_rebuilding(self):
        with tempfile.TemporaryDirectory() as temporary:
            root = Path(temporary);c,apk,provenance = self.files(root);store = root/'store'
            cb.retain_local(store, '123', c, apk, provenance)
            c['prepared_run_id'] = '123';measured = {'apk_sha256':cb.digest(apk),'apk_size_bytes':apk.stat().st_size}
            c['prepared_receipt'] = cb.receipt(c['expected'], c['source_sha'], measured, provenance)
            work=root/'work';work.mkdir()
            with patch.object(cb,'verify_apk',return_value=measured), patch.object(cb,'command',side_effect=AssertionError('No GitHub retrieval')):
                a,p,result=cb.retained_artifact(work,c,store=store)
                self.assertEqual(c['prepared_receipt'],result)
                self.assertEqual(apk.read_bytes(),a.read_bytes())

    def test_missing_retention_stops_without_a_rebuild(self):
        with tempfile.TemporaryDirectory() as temporary:
            root=Path(temporary);c=fixture();c['prepared_run_id']='123'
            with self.assertRaises(ValueError):cb.retained_artifact(root,c,store=root/'missing')

    def test_wrong_brand_or_symlink_cannot_reuse_another_jobs_retention(self):
        with tempfile.TemporaryDirectory() as temporary:
            root=Path(temporary);c,apk,p=self.files(root);store=root/'store';cb.retain_local(store,'123',c,apk,p)
            c['prepared_run_id']='123';wrong=copy.deepcopy(c);wrong['build_id']='f'*32
            work=root/'wrong';work.mkdir()
            with self.assertRaises(ValueError):cb.retained_artifact(work,wrong,store=store)
            saved=store/'123'/c['build_id']/apk.name;saved.unlink();saved.symlink_to(apk)
            work=root/'symlink';work.mkdir()
            with self.assertRaises(ValueError):cb.retained_artifact(work,c,store=store)

    def test_failed_retention_never_installs_partial_artifact(self):
        with tempfile.TemporaryDirectory() as temporary:
            root=Path(temporary);c,apk,p=self.files(root);store=root/'store'
            with patch.object(cb.shutil,'copyfileobj',side_effect=OSError('disk full')):
                with self.assertRaises(OSError):cb.retain_local(store,'123',c,apk,p)
            self.assertFalse((store/'123'/c['build_id']).exists())


class StandaloneTests(unittest.TestCase):
    def test_operation_uses_pinned_fresh_checkout_and_cleans_private_inputs(self):
        with tempfile.TemporaryDirectory() as temporary:
            state=Path(temporary);state.chmod(0o700);calls=[]
            def command(args,env=None):calls.append((args,env))
            args=SimpleNamespace(mode='run',channel='testing',build_id='',publish=False)
            with patch.dict(os.environ,{'BRAND_SOURCE_SHA':'a'*40,'BRAND_RUNNER_STATE':str(state)}),patch.object(runner,'command',side_effect=command):runner.operate(args)
            self.assertIn('clone',calls[0][0]);self.assertIn('--no-checkout',calls[0][0])
            self.assertEqual('a'*40,calls[1][0][-1]);self.assertIn('--detach',calls[1][0])
            invoke,env=calls[-1];self.assertIn('--artifact-store',invoke);self.assertNotIn('--publish',invoke)
            self.assertNotIn('GITHUB_RUN_ID',env);self.assertTrue(env['BRAND_BUILDER_SSH_KEY_FILE'].endswith('private/brand-builder-ssh-key'))
            self.assertFalse(any(state.glob('job-*')));self.assertTrue((state/'artifacts').is_dir())

    def test_unreviewed_source_or_nonprivate_state_stops_before_clone(self):
        with tempfile.TemporaryDirectory() as temporary:
            state=Path(temporary);args=SimpleNamespace(build_id='')
            with patch.object(runner,'command') as command:
                with patch.dict(os.environ,{'BRAND_SOURCE_SHA':'branch-name','BRAND_RUNNER_STATE':str(state)}):
                    with self.assertRaises(ValueError):runner.operate(args)
                state.chmod(0o755)
                with patch.dict(os.environ,{'BRAND_SOURCE_SHA':'a'*40,'BRAND_RUNNER_STATE':str(state)}):
                    with self.assertRaises(ValueError):runner.operate(args)
                command.assert_not_called()

    def test_publish_is_explicit_and_reconcile_keeps_exact_job(self):
        with tempfile.TemporaryDirectory() as temporary:
            state=Path(temporary);state.chmod(0o700);args=SimpleNamespace(mode='reconcile',channel='testing',build_id='b'*32,publish=False)
            with patch.dict(os.environ,{'BRAND_SOURCE_SHA':'a'*40,'BRAND_RUNNER_STATE':str(state)}),patch.object(runner,'command') as command:runner.operate(args)
            invocation=command.call_args.args[0];self.assertIn('reconcile',invocation);self.assertIn('b'*32,invocation);self.assertNotIn('--publish',invocation)


class LocalPipelineTests(unittest.TestCase):
    def pipeline(self, fail_retention=False, fail_publication=False):
        with tempfile.TemporaryDirectory() as temporary:
            root=Path(temporary);(root/'branding').mkdir();output=root/'app/build/outputs/apk/sideload/internalTest';output.mkdir(parents=True)
            (output/'app.apk').write_bytes(b'APK'*1000)
            context=fixture();events=[];store=root/'artifacts';store.mkdir()
            class Bridge:
                def upload(self,context,apk):events.append('site_copy')
                def call(self, action, value):
                    if action=='prepared':
                        # A real durable copy, not just a mocked retention receipt.
                        saved=store/'123'/context['build_id']/context['expected']['asset_name']
                        self_outer.assertEqual(b'APK'*1000,saved.read_bytes())
                    events.append(action)
                    return copy.deepcopy(context) if action=='claim' else {'state':'okay'}
            self_outer=self
            def run(args,**kwargs):return 'a'*40 if args[:3]==['git','rev-parse','HEAD'] else ''
            args=SimpleNamespace(mode='run',workdir=str(root/'job'),build_id=context['build_id'],channel='testing',publish=True,artifact_store=store,retention_id='123')
            original_retain=cb.retain_local
            def retain(*args):
                if fail_retention:raise OSError('disk full')
                original_retain(*args)
            def publish(*args,**kwargs):
                if fail_publication:raise RuntimeError('response lost')
            with patch.object(cb,'retain_local',side_effect=retain),patch.object(cb,'__file__',str(root/'branding/central_builder.py')),patch.object(cb,'verify_apk',return_value={'apk_sha256':'a'*64,'apk_size_bytes':3000}),patch.object(cb,'publish',side_effect=publish),patch.object(cb,'download_verified',side_effect=lambda work,c,run:c['receipt']),redirect_stdout(io.StringIO()):
                if fail_retention or fail_publication:
                    with self.assertRaises((OSError,RuntimeError)):cb.run_job(args,Bridge(),run)
                else:cb.run_job(args,Bridge(),run)
            return events

    def test_local_publish_retains_before_prepare_and_finishes_after_verification(self):
        self.assertEqual(['claim','prepared','delivery','authorize','site_copy','finish'],self.pipeline())

    def test_disk_full_never_prepares_or_authorizes_publication(self):
        self.assertEqual(['claim','fail'],self.pipeline(fail_retention=True))

    def test_uncertain_publish_stops_without_finish_or_rebuild(self):
        self.assertEqual(['claim','prepared','delivery','authorize','fail'],self.pipeline(fail_publication=True))


if __name__ == '__main__':unittest.main()
