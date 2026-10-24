import copy
import base64
from contextlib import redirect_stdout
import importlib.util
import io
import json
import os
from pathlib import Path
import tempfile
from types import SimpleNamespace
import unittest
from unittest.mock import patch

spec=importlib.util.spec_from_file_location('central_builder',Path(__file__).with_name('central_builder.py'))
cb=importlib.util.module_from_spec(spec);spec.loader.exec_module(cb)
spec2=importlib.util.spec_from_file_location('configure_runner',Path(__file__).with_name('configure_runner.py'))
config=importlib.util.module_from_spec(spec2);spec2.loader.exec_module(config)
SHA='a'*40
BRAND='b'*32


def fixture():
    version='2.0.114.2-test';tag='brand-'+BRAND+'-testing';name='Brand-'+version+'.apk'
    expected={'channel':'testing','package_name':'com.extreme.brand.b'+BRAND+'.test','version_name':version,'version_code':43000002,'trial_protocol':1,'signer_sha256':'c'*64,'release_tag':tag,'asset_name':name,'artifact_url':f'https://github.com/{cb.REPO}/releases/download/{tag}/{name}'}
    return {'build_id':'d'*32,'lease_token':'e'*64,'source_sha':SHA,'channel':'testing','expected':expected,'bundle':{'manifest':{'brand_id':BRAND,'brand_version':2,'package_name':'com.extreme.brand.b'+BRAND,'app_version':'2.0.114','app_version_code':430},'images':{}}}


def xml(expected,**changes):
    p=dict(expected,**changes)
    return f'''<manifest xmlns:android="http://schemas.android.com/apk/res/android" package="{p['package_name']}" android:versionName="{p['version_name']}" android:versionCode="{p['version_code']}"><application android:debuggable="{p.get('debuggable','false')}"><provider android:authorities="{p.get('authority',p['package_name']+'.provider')}"/><meta-data android:name="com.extreme.public_trial_protocol" android:value="{p['trial_protocol']}"/></application></manifest>'''


class VerificationTests(unittest.TestCase):
    def test_source_channel_package_and_identity_are_all_pinned(self):
        c=fixture();self.assertEqual(c['expected'],cb.validate_context(c,SHA,'testing'))
        for field,value in [('source_sha','f'*40),('channel','production')]:
            bad=copy.deepcopy(c);bad[field]=value
            with self.assertRaises(ValueError):cb.validate_context(bad,SHA,'testing')
        for field,value in [('package_name','com.arvio.tv.test'),('artifact_url','https://evil.test/x.apk'),('release_tag','staging-testing'),('version_name','2.0.114-test'),('version_code',430)]:
            bad=copy.deepcopy(c);bad['expected'][field]=value
            with self.assertRaises(ValueError):cb.validate_context(bad,SHA,'testing')
    def test_actual_manifest_not_reported_filename_proves_package_and_version(self):
        e=fixture()['expected'];cb.manifest_identity(xml(e),e)
        for changes in [{'package_name':'com.arvio.tv.test'},{'version_code':430},{'version_name':'2.0.114.1-test'},{'debuggable':'true'},{'trial_protocol':0},{'authority':'com.arvio.tv.provider'}]:
            with self.assertRaises(ValueError):cb.manifest_identity(xml(e,**changes),e)
    def test_signature_verifier_requires_exactly_one_central_signer(self):
        good='Signer #1 certificate SHA-256 digest: '+'c'*64+'\n'
        cb.verify_signer(good,'c'*64)
        for text in ['',good.replace('c'*64,'0'*64),good+good.replace('#1','#2')]:
            with self.assertRaises(ValueError):cb.verify_signer(text,'c'*64)
    def test_newer_or_generic_channel_metadata_is_never_overwritten(self):
        e=fixture()['expected'];good={'tagName':e['release_tag'],'name':'2.0.114.1-test','isPrerelease':True,'isDraft':False,'assets':[]}
        cb.check_release(good,e)
        for changes in [{'tagName':'v2.0.101'},{'isPrerelease':False},{'name':'2.0.115.1-test'},{'name':'random'}]:
            with self.assertRaises(ValueError):cb.check_release(dict(good,**changes),e)
    def test_complete_paginated_inventory_is_required_before_inferencing_absence(self):
        calls=[]
        def run(args):calls.append(args);return json.dumps([[],[{'tag_name':fixture()['expected']['release_tag'],'name':'2.0.114.2-test','draft':False,'prerelease':True,'assets':[]}]])
        self.assertIsNotNone(cb.release_info(fixture()['expected']['release_tag'],run));self.assertIn('--paginate',calls[0]);self.assertIn('--slurp',calls[0])
        def failed(args):raise RuntimeError('SYNTHETIC unavailable')
        with self.assertRaises(RuntimeError):cb.release_info('brand-x',failed)
    def test_property_escaping_preserves_java_properties_syntax_and_unicode(self):
        self.assertEqual(r'a\ b\:\=\\',config.prop('a b:=\\'))
        self.assertEqual(r'\u00e9',config.prop('é'))
        self.assertEqual(r'\ud83d\ude00',config.prop('😀'))
        with self.assertRaises(ValueError):config.prop('value\nnew_setting=bad')
    def test_runner_inputs_are_private_exclusive_and_separate_from_brand_inputs(self):
        with tempfile.TemporaryDirectory() as directory:
            root=Path(directory);(root/'app').mkdir();(root/'branding').mkdir();temp=root/'private';temp.mkdir();(root/'secrets.defaults.properties').write_text('TMDB_API_KEY=\n')
            env={'RUNNER_TEMP':str(temp),'BRAND_BUILDER_SSH_PRIVATE_KEY':'SYNTHETIC SSH KEY','BRAND_BUILDER_SSH_KNOWN_HOSTS':'SYNTHETIC HOST PIN','RELEASE_KEYSTORE_BASE64':base64.b64encode(b'SYNTHETIC KEYSTORE').decode()+'\n','RELEASE_KEYSTORE_PASSWORD':'SYNTHETIC:é','RELEASE_KEY_ALIAS':'test','RELEASE_KEY_PASSWORD':'SYNTHETIC','TMDB_API_KEY':'SYNTHETIC'}
            with patch.object(config,'__file__',str(root/'branding/configure_runner.py')),patch.dict(os.environ,env,clear=True):
                config.main()
                with self.assertRaises(FileExistsError):config.main()
            for path in [root/'app/release.keystore',root/'keystore.properties',root/'secrets.properties',temp/'brand-builder-ssh-key',temp/'brand-builder-known-hosts']:self.assertEqual(0o600,path.stat().st_mode&0o777)
            self.assertIn(r'storePassword=SYNTHETIC\:\u00e9',(root/'keystore.properties').read_text())


class PublicationTests(unittest.TestCase):
    def setup_files(self,work):
        c=fixture();apk=work/c['expected']['asset_name'];apk.write_bytes(b'APK'*1000);provenance=work/(apk.name+'.provenance.json');provenance.write_text('{}');c['receipt']={'apk_sha256':cb.digest(apk)}
        return c,apk,provenance
    def test_upload_is_immutable_assets_first_title_last_and_never_latest(self):
        with tempfile.TemporaryDirectory() as directory:
            work=Path(directory);c,apk,p=self.setup_files(work);calls=[]
            def run(args):calls.append(args);return ''
            info={'tagName':c['expected']['release_tag'],'name':c['expected']['release_tag'],'isDraft':True,'isPrerelease':True,'assets':[]}
            guards=[]
            with patch.object(cb,'release_info',side_effect=[None,info]):cb.publish(work,c,apk,p,run,guard=lambda:guards.append(True))
            self.assertEqual(['create','upload','upload','edit'],[a[2] for a in calls]);self.assertEqual(4,len(guards))
            self.assertTrue(all('--clobber' not in a for a in calls));self.assertIn('--latest=false',calls[0]);self.assertIn('--latest=false',calls[-1]);self.assertIn('--draft=false',calls[-1])
    def test_existing_version_with_different_bytes_never_clobbers_or_updates_title(self):
        with tempfile.TemporaryDirectory() as directory:
            work=Path(directory);c,apk,p=self.setup_files(work);calls=[];e=c['expected']
            info={'tagName':e['release_tag'],'name':e['version_name'],'isDraft':False,'isPrerelease':True,'assets':[{'name':apk.name}]}
            def run(args):
                calls.append(args)
                if args[2]=='download':(Path(args[args.index('--dir')+1])/apk.name).write_bytes(b'wrong')
                return ''
            with patch.object(cb,'release_info',return_value=info):
                with self.assertRaises(ValueError):cb.publish(work,c,apk,p,run)
            self.assertEqual(['download'],[a[2] for a in calls])
    def test_publication_lease_loss_stops_before_github_write(self):
        with tempfile.TemporaryDirectory() as directory:
            work=Path(directory);c,apk,p=self.setup_files(work);calls=[]
            def guard():raise RuntimeError('SYNTHETIC stale lease')
            with patch.object(cb,'release_info',return_value=None):
                with self.assertRaises(RuntimeError):cb.publish(work,c,apk,p,lambda args:calls.append(args),guard)
            self.assertEqual([],calls)
    def test_post_upload_download_must_match_frozen_bytes_and_provenance(self):
        with tempfile.TemporaryDirectory() as directory:
            work=Path(directory);c,apk,p=self.setup_files(work);e=c['expected'];c['receipt']=cb.receipt(e,SHA,{'apk_sha256':cb.digest(apk),'apk_size_bytes':apk.stat().st_size},p)
            info={'tagName':e['release_tag'],'name':e['version_name'],'isDraft':False,'isPrerelease':True,'assets':[]}
            def run(args):
                dest=Path(args[args.index('--dir')+1]);name=args[args.index('--pattern')+1]
                (dest/name).write_bytes(apk.read_bytes() if name==apk.name else b'{"altered":true}')
                return ''
            with patch.object(cb,'release_info',return_value=info),patch.object(cb,'verify_apk',return_value={'apk_sha256':cb.digest(apk),'apk_size_bytes':apk.stat().st_size}):
                with self.assertRaises(ValueError):cb.download_verified(work,c,run)
    def test_prepared_resume_reuses_verified_actions_bytes_not_a_rebuild(self):
        with tempfile.TemporaryDirectory() as directory:
            work=Path(directory);c,apk,p=self.setup_files(work);c['prepared_run_id']='1234';measured={'apk_sha256':cb.digest(apk),'apk_size_bytes':apk.stat().st_size};c['prepared_receipt']=cb.receipt(c['expected'],SHA,measured,p);calls=[]
            def run(args):
                calls.append(args);dest=Path(args[args.index('--dir')+1]);(dest/apk.name).write_bytes(apk.read_bytes());(dest/p.name).write_bytes(p.read_bytes());return ''
            with patch.object(cb,'verify_apk',return_value=measured):
                actual,provenance,result=cb.retained_artifact(work,c,run)
            self.assertEqual(c['prepared_receipt'],result);self.assertEqual(cb.digest(apk),cb.digest(actual));self.assertEqual(1,len(calls));self.assertEqual(['gh','run','download','1234'],calls[0][:4]);self.assertNotIn('gradlew',str(calls))
    def test_missing_prepared_run_identity_never_falls_back_to_rebuilding(self):
        with tempfile.TemporaryDirectory() as directory:
            calls=[]
            with self.assertRaises(ValueError):cb.retained_artifact(Path(directory),fixture(),lambda args:calls.append(args))
            self.assertEqual([],calls)


class PipelineTests(unittest.TestCase):
    def run_pipeline(self,publish=False,fail_upload=False):
        temp=tempfile.TemporaryDirectory();self.addCleanup(temp.cleanup);root=Path(temp.name);(root/'branding').mkdir();(root/'app/build/outputs/apk/sideload/internalTest').mkdir(parents=True)
        (root/'app/build/outputs/apk/sideload/internalTest/app.apk').write_bytes(b'APK'*1000)
        c=fixture();calls=[]
        class Bridge:
            def upload(self,context,apk):calls.append(('site_copy',{}))
            def call(self,action,value):
                calls.append((action,value))
                return copy.deepcopy(c) if action=='claim' else {'state':'okay'}
        def run(args,**kwargs):return SHA if args[:3]==['git','rev-parse','HEAD'] else ''
        args=SimpleNamespace(mode='run',workdir=str(root/'job'),build_id=c['build_id'],channel='testing',publish=publish)
        def upload(*args,**kwargs):
            if fail_upload:raise RuntimeError('SYNTHETIC upload response lost')
        with redirect_stdout(io.StringIO()),patch.dict(os.environ,{'GITHUB_RUN_ID':'123456'}),patch.object(cb,'__file__',str(root/'branding/central_builder.py')),patch.object(cb,'verify_apk',return_value={'apk_sha256':'a'*64,'apk_size_bytes':3000}),patch.object(cb,'publish',side_effect=upload),patch.object(cb,'download_verified',side_effect=lambda work,context,run:context['receipt']):
            if fail_upload:
                with self.assertRaises(RuntimeError):cb.run_job(args,Bridge(),run)
            else:cb.run_job(args,Bridge(),run)
        return root,c,calls
    def test_default_build_verifies_but_does_not_authorize_publish_or_enroll(self):
        root,c,calls=self.run_pipeline();self.assertEqual(['claim','prepared'],[x[0] for x in calls]);self.assertEqual(0o600,(root/'job/lease.json').stat().st_mode&0o777)
        provenance=json.loads(next((root/'job').glob('*.provenance.json')).read_text());self.assertNotIn('lease_token',json.dumps(provenance));self.assertNotIn(c['lease_token'],json.dumps(provenance))
    def test_explicit_publish_finishes_only_after_download_verification(self):
        _,_,calls=self.run_pipeline(publish=True);self.assertEqual(['claim','prepared','delivery','authorize','site_copy','finish'],[x[0] for x in calls])
    def test_uncertain_upload_notifies_server_and_never_rebuilds_automatically(self):
        _,_,calls=self.run_pipeline(publish=True,fail_upload=True);self.assertEqual(['claim','prepared','delivery','authorize','fail'],[x[0] for x in calls])
    def test_authorized_brand_workflow_is_isolated_and_retains_before_complete(self):
        root=Path(__file__).resolve().parent.parent
        workflow=(root/'.github/workflows/central-brand-builder.yml').read_text()
        self.assertIn('central-brand-publisher',workflow)
        self.assertIn('cancel-in-progress: false',workflow)
        self.assertNotIn('staging-testing',workflow)
        self.assertNotIn('--clobber',workflow)
        self.assertIn('ref: ${{ github.sha }}',workflow)
        self.assertLess(workflow.index('uses: actions/upload-artifact@v4'),workflow.index('central_builder.py complete'))
        self.assertNotIn('brand-job/**',workflow)


if __name__=='__main__':unittest.main()
