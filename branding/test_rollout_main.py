import base64
from contextlib import redirect_stdout
import importlib.util
import io
import json
import os
from pathlib import Path
import sys
import tempfile
from types import SimpleNamespace
import unittest
from unittest.mock import patch

sys.path.insert(0,str(Path(__file__).parent))
import rollout_main as rollout
from branding.test_github_builder import dispatcher

SHA='a'*40
TAG='brand-source-2.0.116-'+SHA
GRADLE='''val brandDirectory = providers.gradleProperty("brandDir")
val platformVersionName = "2.0.116"
val platformVersionCode = 432
        versionCode = 432
        versionName = "2.0.116"
'''


class MainRolloutTests(unittest.TestCase):
    def test_platform_requires_brand_support_and_increasing_aligned_versions(self):
        self.assertEqual(('2.0.116',432),rollout.platform(GRADLE))
        for source in [GRADLE.replace('2.0.116','2.0.115'),GRADLE.replace('432','431'),GRADLE.replace('val brandDirectory','val unrelated'),GRADLE.replace('2.0.116','2.0.116-test')]:self.assertIsNone(rollout.platform(source))
        for source in [GRADLE.replace('versionCode = 432','versionCode = 431'),GRADLE.replace('versionName = "2.0.116"','versionName = "2.0.115"')]:
            with self.assertRaises(ValueError):rollout.platform(source)

    def test_tag_conflicts_and_unavailable_github_never_overwrite_source(self):
        for result in [SimpleNamespace(returncode=0,stderr=b''),SimpleNamespace(returncode=1,stderr=b'HTTP 503')]:
            with patch.object(rollout.subprocess,'run',return_value=result),patch.object(rollout,'gh_json',return_value={'object':{'sha':'f'*40,'type':'commit'}}),patch.object(rollout,'command') as command:
                with self.assertRaises((ValueError,RuntimeError)):rollout.immutable_tag(TAG,SHA)
                command.assert_not_called()

    def test_older_main_causes_no_tag_no_bridge_no_build(self):
        def api(path):return {'object':{'sha':SHA}} if path=='git/ref/heads/main' else {'content':base64.b64encode(GRADLE.replace('432','431').encode()).decode()}
        with patch.dict(os.environ,{'BRAND_MAIN_SOURCE_SHA':SHA,'BRAND_OPERATOR_SHA':SHA}),patch.object(rollout,'command',return_value=SHA),patch.object(rollout,'gh_json',side_effect=api),patch.object(rollout,'immutable_tag') as tag,patch.object(rollout,'Bridge') as bridge,redirect_stdout(io.StringIO()):
            rollout.main();tag.assert_not_called();bridge.assert_not_called()

    def test_fanout_is_bounded_private_and_uses_only_testing_bridge_action(self):
        def api(path):return {'object':{'sha':SHA}} if path=='git/ref/heads/main' else {'content':base64.b64encode(GRADLE.encode()).decode()}
        with tempfile.TemporaryDirectory() as directory:
            env={'BRAND_MAIN_SOURCE_SHA':SHA,'BRAND_OPERATOR_SHA':SHA,'RUNNER_TEMP':directory,'BRAND_BUILDER_SSH_PRIVATE_KEY':'SYNTHETIC-PRIVATE\n','BRAND_BUILDER_SSH_KNOWN_HOSTS':'SYNTHETIC-PIN\n'}
            def call(action,value):
                self.assertEqual('fanout',action);self.assertEqual({'source_sha':SHA,'workflow_ref':TAG,'app_version':'2.0.116','version_code':432},value)
                self.assertEqual(0o600,(Path(directory)/'brand-main-bridge-key').stat().st_mode&0o777)
                self.assertEqual('SYNTHETIC-PRIVATE\n',(Path(directory)/'brand-main-bridge-key').read_text())
                return {'complete':True,'queued':2}
            with patch.dict(os.environ,env),patch.object(rollout,'command',return_value=SHA),patch.object(rollout,'gh_json',side_effect=api),patch.object(rollout,'immutable_tag') as tag,patch.object(rollout,'Bridge') as bridge,redirect_stdout(io.StringIO()):
                bridge.return_value.call.side_effect=call;rollout.main();tag.assert_called_once_with(TAG,SHA);bridge.return_value.call.assert_called_once()
            self.assertEqual([],list(Path(directory).iterdir()))

    def test_main_queue_accepts_only_exact_version_sha_tag_and_testing_channel(self):
        class Response(io.BytesIO):status=200
        good={'state':'queued','build_id':'b'*32,'channel':'testing','source_sha':SHA,'workflow_ref':TAG,'mode':'publish'}
        env={'BRAND_QUEUE_URL':'https://site.example/api/brand-dispatch/queue','BRAND_QUEUE_TOKEN':'c'*64}
        for override,okay in [({},True),({'workflow_ref':'main'},False),({'workflow_ref':TAG[:-1]+'f'},False),({'channel':'production'},False),({'mode':'build'},False)]:
            opener=SimpleNamespace(open=lambda *a,**kw:Response(json.dumps(dict(good,**override)).encode()))
            with patch.dict(os.environ,env),patch.object(dispatcher.urllib.request,'build_opener',return_value=opener):
                if okay:self.assertEqual({'build_id':'b'*32,'source_sha':SHA,'workflow_ref':TAG},dispatcher.queue('f'*40,'testing','publish'))
                else:
                    with self.assertRaises(ValueError):dispatcher.queue('f'*40,'testing','publish')

    def test_dispatch_pins_tag_not_old_operator_branch_and_fences_duplicate(self):
        with tempfile.TemporaryDirectory() as directory:
            state=Path(directory);state.chmod(0o700);calls=[]
            def gh(args):
                calls.append(args)
                return json.dumps({'object':{'sha':SHA}}) if args[0]=='api' else '[]' if args[0]=='run' else ''
            env={'BRAND_SOURCE_SHA':'f'*40,'BRAND_WORKFLOW_REF':'reseller-control-center','BRAND_DISPATCH_STATE':directory,'BRAND_AUTO_PUBLISH_TESTING':'true'}
            with patch.dict(os.environ,env),patch.object(dispatcher,'queue',return_value={'build_id':'b'*32,'source_sha':SHA,'workflow_ref':TAG}),patch.object(dispatcher,'gh',side_effect=gh),redirect_stdout(io.StringIO()):
                args=SimpleNamespace(mode='build',channel='testing',build_id='',poll=True);dispatcher.dispatch(args);dispatcher.dispatch(args)
            self.assertIn('/git/ref/tags/'+TAG,calls[0][1]);commands=[c for c in calls if c[0]=='workflow'];self.assertEqual(1,len(commands));self.assertIn(TAG,commands[0]);self.assertIn('mode=publish',commands[0])


if __name__=='__main__':unittest.main()
