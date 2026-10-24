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

from branding.test_github_builder import dispatcher

SHA='a'*40
JOB='b'*32
TOKEN='c'*64


class PollTests(unittest.TestCase):
    def test_empty_or_unavailable_queue_never_contacts_github(self):
        for failure in [False,True]:
            with tempfile.TemporaryDirectory() as temporary:
                state=Path(temporary);state.chmod(0o700)
                env={'BRAND_SOURCE_SHA':SHA,'BRAND_WORKFLOW_REF':'reseller-control-center','BRAND_DISPATCH_STATE':str(state)}
                with patch.dict(os.environ,env),patch.object(dispatcher,'queue',side_effect=RuntimeError('private') if failure else None,return_value=None),patch.object(dispatcher,'gh') as gh,redirect_stdout(io.StringIO()):
                    args=SimpleNamespace(mode='build',channel='testing',build_id='',poll=True)
                    if failure:
                        with self.assertRaises(RuntimeError):dispatcher.dispatch(args)
                    else:dispatcher.dispatch(args)
                    gh.assert_not_called()

    def test_poll_dispatches_exact_job_once_even_before_run_list_catches_up(self):
        with tempfile.TemporaryDirectory() as temporary:
            state=Path(temporary);state.chmod(0o700);calls=[]
            def gh(args):
                calls.append(args)
                return json.dumps({'object':{'sha':SHA}}) if args[0]=='api' else '[]' if args[0]=='run' else ''
            env={'BRAND_SOURCE_SHA':SHA,'BRAND_WORKFLOW_REF':'reseller-control-center','BRAND_DISPATCH_STATE':str(state)}
            with patch.dict(os.environ,env),patch.object(dispatcher,'queue',return_value=JOB),patch.object(dispatcher,'gh',side_effect=gh),redirect_stdout(io.StringIO()):
                args=SimpleNamespace(mode='build',channel='testing',build_id='',poll=True)
                dispatcher.dispatch(args);dispatcher.dispatch(args)
            workflows=[c for c in calls if c[0]=='workflow'];self.assertEqual(1,len(workflows))
            self.assertIn('build_id='+JOB,workflows[0]);self.assertIn('mode=build',workflows[0]);self.assertIn('channel=testing',workflows[0])
            self.assertEqual(0o600,(state/'poll-dispatch.json').stat().st_mode&0o777)

    def test_uncertain_dispatch_is_fenced_before_api_call(self):
        with tempfile.TemporaryDirectory() as temporary:
            state=Path(temporary);state.chmod(0o700)
            self.assertTrue(dispatcher.fence(state,SHA,'testing',JOB))
            self.assertFalse(dispatcher.fence(state,SHA,'testing',JOB))
            with patch.object(dispatcher.time,'time',return_value=dispatcher.time.time()+301):
                self.assertTrue(dispatcher.fence(state,SHA,'testing',JOB))

    def test_polling_refuses_publication_production_and_caller_job(self):
        with tempfile.TemporaryDirectory() as temporary:
            state=Path(temporary);state.chmod(0o700)
            env={'BRAND_SOURCE_SHA':SHA,'BRAND_WORKFLOW_REF':'reseller-control-center','BRAND_DISPATCH_STATE':str(state)}
            with patch.dict(os.environ,env),patch.object(dispatcher,'queue') as queue,patch.object(dispatcher,'gh') as gh:
                for mode,channel,job in [('publish','testing',''),('build','production',''),('build','testing',JOB)]:
                    with self.assertRaises(ValueError):dispatcher.dispatch(SimpleNamespace(mode=mode,channel=channel,build_id=job,poll=True))
                queue.assert_not_called();gh.assert_not_called()

    def test_https_hint_is_bounded_authenticated_source_pinned_and_no_redirects(self):
        class Response(io.BytesIO):status=200
        requests=[]
        value={'state':'queued','build_id':JOB,'channel':'testing','source_sha':SHA}
        env={'BRAND_QUEUE_URL':'https://site.example/api/brand-dispatch/queue','BRAND_QUEUE_TOKEN':TOKEN}
        def opener(raw):
            def open(request,**kwargs):requests.append(request);return Response(raw)
            return SimpleNamespace(open=open)
        for raw,okay in [(json.dumps(value).encode(),True),(b'{"state":"no_job"}',True),(json.dumps(dict(value,source_sha='f'*40)).encode(),False),(json.dumps(dict(value,channel='production')).encode(),False),(b'x'*2049,False),(b'[]',False)]:
            with patch.dict(os.environ,env),patch.object(dispatcher.urllib.request,'build_opener',return_value=opener(raw)):
                if okay:self.assertIn(dispatcher.queue(SHA,'testing'),[None,JOB])
                else:
                    with self.assertRaises((ValueError,TypeError)):dispatcher.queue(SHA,'testing')
        self.assertNotIn(TOKEN,requests[0].full_url)
        self.assertEqual('Bearer '+TOKEN,requests[0].get_header('Authorization'))
        with self.assertRaises(ValueError):dispatcher.NoRedirect().redirect_request(None,None,None,None,None,None)
        for url in ['http://site.example/api/brand-dispatch/queue','https://user:pass@site.example/api/brand-dispatch/queue','https://site.example/api/brand-dispatch/queue?token=x']:
            with patch.dict(os.environ,dict(env,BRAND_QUEUE_URL=url)):
                with self.assertRaises(ValueError):dispatcher.queue(SHA,'testing')

    def test_private_worker_setup_preserves_github_token_and_reuses_queue_secret(self):
        spec=importlib.util.spec_from_file_location('configure_poll',Path(__file__).parent/'deploy/configure-poll.py')
        module=importlib.util.module_from_spec(spec);spec.loader.exec_module(module)
        with tempfile.TemporaryDirectory() as temporary:
            path=Path(temporary)/'worker.env';original='GH_TOKEN=synthetic-private-github-token\nBRAND_SOURCE_SHA='+('f'*40)+'\nBRAND_WORKFLOW_REF=reseller-control-center\n'
            path.write_text(original);path.chmod(0o600)
            digest=module.configure(path,SHA);first=path.read_text()
            self.assertEqual(digest,module.configure(path,SHA));self.assertEqual(first,path.read_text())
            self.assertIn('GH_TOKEN=synthetic-private-github-token\n',first)
            self.assertEqual(original,path.with_name('worker.env.before-poll').read_text())
            self.assertEqual(0o600,path.stat().st_mode&0o777)
            self.assertEqual(64,len(digest));self.assertNotIn(digest,first)

    def test_timer_is_thirty_seconds_and_service_cannot_publish(self):
        base=Path(__file__).parent/'deploy'
        self.assertIn('OnUnitInactiveSec=30s',(base/'brand-dispatch.timer').read_text())
        self.assertIn('--poll --mode build --channel testing',(base/'brand-dispatch.service').read_text())


if __name__=='__main__':unittest.main()
