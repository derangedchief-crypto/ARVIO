import copy
from contextlib import redirect_stdout
import importlib.util
import io
import json
import os
from pathlib import Path
from types import SimpleNamespace
import tempfile
import unittest
from unittest.mock import patch

from branding.test_central_builder import cb, fixture, SHA

spec=importlib.util.spec_from_file_location('dispatch_github',Path(__file__).with_name('dispatch_github.py'))
dispatcher=importlib.util.module_from_spec(spec);spec.loader.exec_module(dispatcher)


class StagingTests(unittest.TestCase):
    def stage(self, root):
        (root/'branding').mkdir();output=root/'app/build/outputs/apk/sideload/internalTest';output.mkdir(parents=True)
        (output/'app.apk').write_bytes(b'APK'*1000);context=fixture();events=[]
        class Bridge:
            def call(self,action,value):
                events.append(action)
                return copy.deepcopy(context) if action=='claim' else {'state':'okay'}
        def run(args,**kwargs):return SHA if args[:3]==['git','rev-parse','HEAD'] else ''
        args=SimpleNamespace(mode='run',stage=True,workdir=str(root/'job'),build_id=context['build_id'],channel='testing',publish=False)
        with patch.object(cb,'__file__',str(root/'branding/central_builder.py')),patch.object(cb,'verify_apk',return_value={'apk_sha256':'a'*64,'apk_size_bytes':3000}),patch.dict(os.environ,{'GITHUB_RUN_ID':'123'}),redirect_stdout(io.StringIO()):
            cb.run_job(args,Bridge(),run)
        return args,context,events,run,Bridge()

    def test_stage_does_not_prepare_and_exports_no_bundle_or_lease(self):
        with tempfile.TemporaryDirectory() as temporary:
            root=Path(temporary);args,c,events,_,_=self.stage(root)
            self.assertEqual(['claim','heartbeat'],events)
            self.assertEqual({c['expected']['asset_name'],c['expected']['asset_name']+'.provenance.json'}, {p.name for p in (root/'job/export').iterdir()})
            for p in (root/'job/export').iterdir():self.assertNotIn(c['lease_token'].encode(),p.read_bytes())
            self.assertEqual('123',json.loads((root/'job/staged.json').read_text())['staged_run_id'])

    def complete(self, root, publish=False, failed_retention=False, failed_publish=False):
        args,c,events,run,bridge=self.stage(root);args.publish=publish
        def retained(work,context,run):
            events.append('retained_download')
            if failed_retention:raise RuntimeError('artifact upload missing')
            return root/'job/export'/c['expected']['asset_name'],root/'job/export'/(c['expected']['asset_name']+'.provenance.json'),context['prepared_receipt']
        def upload(*args,**kwargs):
            events.append('upload')
            if failed_publish:raise RuntimeError('response lost')
        def download(work,context,run):events.append('published_download');return context['receipt']
        with patch.object(cb,'__file__',str(root/'branding/central_builder.py')),patch.object(cb,'retained_artifact',side_effect=retained),patch.object(cb,'publish',side_effect=upload),patch.object(cb,'download_verified',side_effect=download),patch.dict(os.environ,{'GITHUB_RUN_ID':'123'}),redirect_stdout(io.StringIO()):
            if failed_retention or failed_publish:
                with self.assertRaises(RuntimeError):cb.complete_staged(args,bridge,run)
            else:cb.complete_staged(args,bridge,run)
        return events

    def test_prepare_requires_successful_retained_download(self):
        with tempfile.TemporaryDirectory() as temporary:
            events=self.complete(Path(temporary));self.assertEqual(['claim','heartbeat','heartbeat','retained_download','prepared'],events)

    def test_retention_failure_never_prepares_or_publishes(self):
        with tempfile.TemporaryDirectory() as temporary:
            events=self.complete(Path(temporary),failed_retention=True)
            self.assertNotIn('prepared',events);self.assertNotIn('authorize',events);self.assertEqual('fail',events[-1])

    def test_publish_finishes_only_after_retained_and_release_downloads(self):
        with tempfile.TemporaryDirectory() as temporary:
            events=self.complete(Path(temporary),publish=True)
            self.assertEqual(['retained_download','prepared','authorize','upload','published_download','finish'],events[-6:])

    def test_uncertain_publication_stops_before_finish(self):
        with tempfile.TemporaryDirectory() as temporary:
            events=self.complete(Path(temporary),publish=True,failed_publish=True)
            self.assertNotIn('finish',events);self.assertEqual('fail',events[-1])

    def test_another_run_cannot_complete_this_private_staging_state(self):
        with tempfile.TemporaryDirectory() as temporary:
            root=Path(temporary);args,_,_,run,bridge=self.stage(root)
            with patch.object(cb,'__file__',str(root/'branding/central_builder.py')),patch.dict(os.environ,{'GITHUB_RUN_ID':'999'}):
                with self.assertRaises(ValueError):cb.complete_staged(args,bridge,run)


class DispatchTests(unittest.TestCase):
    def dispatch(self, source=SHA, status='completed', mode='build'):
        with tempfile.TemporaryDirectory() as temporary:
            state=Path(temporary);state.chmod(0o700);calls=[]
            def gh(args):
                calls.append(args)
                if args[0]=='api':return json.dumps({'object':{'sha':source}})
                if args[:2]==['run','list']:return json.dumps([{'status':status}])
                return ''
            args=SimpleNamespace(mode=mode,channel='testing',build_id='')
            with patch.dict(os.environ,{'BRAND_SOURCE_SHA':SHA,'BRAND_WORKFLOW_REF':'reseller-control-center','BRAND_DISPATCH_STATE':str(state)}),patch.object(dispatcher,'gh',side_effect=gh),redirect_stdout(io.StringIO()):
                if source!=SHA:
                    with self.assertRaises(ValueError):dispatcher.dispatch(args)
                else:dispatcher.dispatch(args)
            return calls

    def test_dispatch_has_no_signing_material_and_defaults_to_build_only(self):
        calls=self.dispatch();self.assertEqual('workflow',calls[-1][0]);self.assertIn('mode=build',calls[-1]);self.assertNotIn('mode=publish',calls[-1])

    def test_moved_source_ref_stops_before_dispatch(self):
        self.assertEqual(1,len(self.dispatch(source='f'*40)))

    def test_active_or_waiting_operation_prevents_more_dispatches(self):
        for status in ['in_progress','queued','waiting','pending']:
            self.assertFalse(any(c[0]=='workflow' for c in self.dispatch(status=status)))


if __name__=='__main__':unittest.main()
