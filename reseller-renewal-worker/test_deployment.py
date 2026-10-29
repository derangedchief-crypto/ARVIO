import ast
import hashlib
from pathlib import Path
import re
import subprocess
import tempfile
import unittest
from zipfile import ZipFile
from configure_proxy import plan

ROOT=Path(__file__).resolve().parent.parent


class DeploymentTests(unittest.TestCase):
    def test_actual_installer_loopback_handshake_bypasses_inherited_proxy(self):
        import hmac,http.server,json,os,threading
        secret='a'*64
        class Handler(http.server.BaseHTTPRequestHandler):
            def log_message(self,*args):pass
            def do_POST(self):
                raw=self.rfile.read(int(self.headers['Content-Length']))
                stamp=self.headers['X-Renewal-Timestamp'];nonce=self.headers['X-Renewal-Nonce']
                expected=hmac.new(secret.encode(),b'branded-renewal-request-v1\n'+stamp.encode()+b'\n'+nonce.encode()+b'\n'+raw,hashlib.sha256).hexdigest()
                if self.headers['X-Renewal-Signature']!=expected:self.send_error(403);return
                payload=json.dumps({'mode':'renewals','writes_enabled':True,'atomic_renewal':1,'current_package_only':True})
                reply=json.dumps({'payload':payload,'signature':hmac.new(secret.encode(),('branded-renewal-result-v1\n'+stamp+'\n'+nonce+'\n'+payload).encode(),hashlib.sha256).hexdigest()}).encode()
                self.send_response(200);self.send_header('Content-Length',str(len(reply)));self.end_headers();self.wfile.write(reply)
        server=http.server.HTTPServer(('127.0.0.1',0),Handler);thread=threading.Thread(target=server.serve_forever,daemon=True);thread.start()
        try:
            script=(ROOT/'reseller-renewal-worker/install.sh').read_text()
            block=next(b for b in re.findall(r"<<'PY'\n(.*?)\nPY",script,re.S) if 'loopback_readiness' in b)
            block=block.replace("HTTPConnection('127.0.0.1',9093",f"HTTPConnection('127.0.0.1',{server.server_port}")
            with tempfile.TemporaryDirectory(dir='/tmp/opencode') as folder:
                envfile=Path(folder)/'environment';envfile.write_text('RENEWAL_BRIDGE_SECRET='+secret+'\n')
                env={**os.environ,'http_proxy':'http://127.0.0.1:1','HTTP_PROXY':'http://127.0.0.1:1','ALL_PROXY':'http://127.0.0.1:1','no_proxy':'','NO_PROXY':''}
                result=subprocess.run(['/usr/bin/python3','-I','-S','-',str(envfile)],input=block,text=True,capture_output=True,env=env,timeout=10)
                self.assertEqual(0,result.returncode,result.stderr);self.assertNotIn(secret,result.stdout+result.stderr)
        finally:server.shutdown();server.server_close();thread.join()
    def test_new_bundle_has_no_original_config_env_trial_or_legacy_portal_app(self):
        with tempfile.TemporaryDirectory(dir='/tmp/opencode') as folder:
            target=Path(folder)/'new.zip'
            subprocess.run(['python3',str(ROOT/'reseller-renewal-worker/package.py'),'--source','/home/studio/Extreme Hub Tivimate Rework/new auth.zip','--output',str(target)],check=True,capture_output=True)
            with ZipFile(target) as z:
                self.assertNotIn('config.py',z.namelist());self.assertNotIn('.env',z.namelist());self.assertNotIn('branded_trials.py',z.namelist())
                self.assertIn('native_panel.py',z.namelist());self.assertIn('iptv-renewal-worker.service',z.namelist())
                unit=z.read('iptv-renewal-worker.service').decode();self.assertIn('User=iptvrenewal',unit);self.assertIn('/opt/iptv-renewal-worker/venv/bin/python',unit);self.assertNotIn('/opt/iptv-auth-worker/venv',unit)
                for line in z.read('MANIFEST.sha256').decode().splitlines():
                    sha,name=line.split('  ',1);self.assertEqual(sha,hashlib.sha256(z.read(name)).hexdigest())
                script=z.read('install.sh').decode();subprocess.run(['bash','-n'],input=script.encode(),check=True)
                self.assertNotIn('systemctl restart iptv-auth-worker',script);self.assertNotIn('systemctl stop iptv-auth-worker',script)
                self.assertIn('trap rollback EXIT',script);self.assertIn('auth_env_before',script)
                for block in re.findall(r"<<'PY'\n(.*?)\nPY",script,re.S):ast.parse(block)
                for name in z.namelist():
                    if name.endswith('.py'):ast.parse(z.read(name))
    def test_proxy_change_is_only_a_new_prefix_in_the_existing_single_host_https_block(self):
        original='''http { server { listen 80; server_name resellerworker.extremeiptv.net; return 301 https://$host$request_uri; }
server { listen 443 ssl; server_name resellerworker.extremeiptv.net; location / { proxy_pass http://127.0.0.1:9091; } }
server { listen 443 ssl; server_name control.fecxbrfzku.net; location / { root /site/public; } } }'''
        snippet=(ROOT/'reseller-renewal-worker/nginx-location.conf').read_text();updated=plan(original,snippet)
        self.assertIn('proxy_pass http://127.0.0.1:9091;',updated);self.assertIn('proxy_pass http://127.0.0.1:9093;',updated)
        self.assertEqual(original.count('server_name'),updated.count('server_name'))
        self.assertEqual(updated,plan(updated,snippet))
        without=updated.replace('\n# BEGIN MANAGED IPTV RENEWAL WORKER\n'+snippet.strip()+'\n# END MANAGED IPTV RENEWAL WORKER\n','')
        self.assertEqual(original,without)
    def test_ambiguous_shared_or_existing_unmanaged_routes_are_never_overwritten(self):
        snippet='location ^~ /branded-renewals/ { proxy_pass http://127.0.0.1:9093; }'
        base='server { listen 443 ssl; server_name resellerworker.extremeiptv.net; }'
        for text in [base+base,base.replace('server_name resellerworker.extremeiptv.net;','server_name resellerworker.extremeiptv.net other.example.test;'),base.replace(' }',' location /branded-renewals/ { proxy_pass http://127.0.0.1:9091; } }')]:
            with self.assertRaises(ValueError):plan(text,snippet)
    def test_existing_http_origin_behind_tls_proxy_is_supported_without_changing_transport_policy(self):
        text='server { listen 80; server_name resellerworker.extremeiptv.net; location / { proxy_pass http://127.0.0.1:9091; } }'
        snippet='location ^~ /branded-renewals/ { proxy_pass http://127.0.0.1:9093; }'
        updated=plan(text,snippet);self.assertIn('proxy_pass http://127.0.0.1:9091;',updated);self.assertIn(snippet,updated)
        self.assertNotIn('listen 443',updated)
    def test_dependency_pinning_reads_old_metadata_without_executing_old_env_or_private_config(self):
        script=(ROOT/'reseller-renewal-worker/install.sh').read_text()
        block=next(b for b in re.findall(r"<<'PY'\n(.*?)\nPY",script,re.S) if 'importlib.metadata' in b)
        with tempfile.TemporaryDirectory(dir='/tmp/opencode') as folder:
            root=Path(folder);auth=root/'auth';site=auth/'venv/lib/python3.12/site-packages';site.mkdir(parents=True);out=root/'output';out.mkdir()
            marker=root/'EXECUTED';(site/'sitecustomize.py').write_text(f'open({str(marker)!r},"w").write("BAD")')
            config=auth/'config.py';config.write_text(f'from pydantic import BaseModel\nopen({str(marker)!r},"w").write("BAD")\nDB_PASSWORD="SYNTHETIC-PRIVATE-VALUE"\n')
            for name,version,top in [('fastapi','0.115.6','fastapi'),('uvicorn','0.34.0','uvicorn'),('mysql-connector-python','9.1.0','mysql'),('sshtunnel','0.4.0','sshtunnel'),('paramiko','2.12.0','paramiko'),('pydantic','2.10.4','pydantic')]:
                d=site/(name.replace('-','_')+'-'+version+'.dist-info');d.mkdir();(d/'METADATA').write_text('Name: '+name+'\nVersion: '+version+'\n');(d/'top_level.txt').write_text(top+'\n')
            result=subprocess.run(['/usr/bin/python3','-I','-S','-',str(config),str(out)],input=block,text=True,capture_output=True,check=True)
            self.assertFalse(marker.exists());self.assertNotIn('SYNTHETIC',result.stdout+result.stderr);self.assertIn('mysql-connector-python==9.1.0',(out/'constraints.txt').read_text());self.assertIn('fastapi',(out/'requirements.txt').read_text())


if __name__=='__main__':unittest.main()
