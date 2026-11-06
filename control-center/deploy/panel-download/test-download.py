"""Fixture-only panel installation, license gating and release selection checks."""
import importlib.util
import json
from pathlib import Path
import shutil
import socket
import subprocess
import tempfile
import time
import urllib.request
import urllib.error

base=Path(__file__).resolve().parent
root=base.parents[2]
spec=importlib.util.spec_from_file_location('installer',base/'install-latest-my-hub.py');m=importlib.util.module_from_spec(spec);spec.loader.exec_module(m)
php=['bash',str(root/'control-center/dev/php.sh')]
fixture=Path('/home/studio/my-hub-panel-workspace/server/panel')
def asset(v):
    name='My-Hub-'+v+'-testing.apk'
    return {'name':name,'state':'uploaded','size':100,'browser_download_url':'https://github.com/derangedchief-crypto/ARVIO/releases/download/staging-testing/'+name}
with tempfile.TemporaryDirectory(dir='/tmp/opencode') as tmp:
    site=Path(tmp)/'site';panel=site/'am-app';panel.mkdir(parents=True);(site/'includes').mkdir();private=Path(tmp)/'private';private.mkdir()
    for name in ['my-hub.php','my-hub-showcase.php']:shutil.copy2(fixture/name,panel/name)
    m.install(site,base,Path(tmp)/'backup',php);m.install(site,base,Path(tmp)/'backup',php)
    assert (Path(tmp)/'backup/my-hub.php').read_bytes()==(fixture/'my-hub.php').read_bytes()
    (site/'includes/bootstrap.php').write_text("<?php function require_login(){return ['id'=>'12'];} function call_auth_worker($p,$a){return ['ok'=>true,'is_enabled'=>true];} function url($s){return '/'.$s;} function e($s){return htmlspecialchars($s,ENT_QUOTES);} function csrf_field(){return '';} function csrf_check(){}")
    (panel/'MyHub.php').write_text("<?php namespace AmAccount; class MyHub{static function licenses($c){return new Licenses();}} class Licenses{function current($id){return ['expires'=>null];}static function state($row){return $_GET['state']??'active';}}")
    (panel/'settings-config.php').write_text("<?php return ['my_hub_database'=>"+repr(str(private/'licenses.sqlite'))+"];")
    release={'tag_name':'staging-testing','draft':False,'assets':[asset('0.1.8'),asset('0.1.10'),asset('0.1.9')]}
    (private/'my-hub-release.json').write_text(json.dumps(release))
    code='require '+repr(str(panel/'MyHubRelease.php'))+'; $r=json_decode(stream_get_contents(STDIN),true); echo json_encode(\\AmAccount\\MyHubRelease::select($r));'
    result=subprocess.check_output(php+['-r',code],input=json.dumps(release).encode());assert json.loads(result)['version']=='0.1.10'
    release['assets'][-1]['name']='Extreme-TV-99.0.0.apk'
    bad=asset('99.0.0');bad['browser_download_url']='https://example.test/evil.apk';release['assets'].append(bad)
    result=subprocess.check_output(php+['-r',code],input=json.dumps(release).encode());assert json.loads(result)['version']=='0.1.10'
    probe=socket.socket();probe.bind(('127.0.0.1',0));port=probe.getsockname()[1];probe.close()
    with (Path(tmp)/'server.log').open('wb') as log:
        process=subprocess.Popen(php+['-S',f'127.0.0.1:{port}','-t',str(site)],stdout=log,stderr=log)
        try:
            for _ in range(100):
                try:
                    with socket.create_connection(('127.0.0.1',port),timeout=.1):break
                except OSError:time.sleep(.05)
            class NoRedirect(urllib.request.HTTPRedirectHandler):
                def redirect_request(self,*args):return None
            opener=urllib.request.build_opener(NoRedirect())
            origin=f'http://127.0.0.1:{port}/am-app/my-hub.php'
            html=urllib.request.urlopen(origin).read().decode();assert 'my-hub.php?download=1' in html and '0.1.2' not in html
            for state in ['pending','expired','revoked','unlicensed']:
                try:opener.open(origin+'?download=1&state='+state)
                except urllib.error.HTTPError as e:assert e.code==403
                else:raise AssertionError('Expected license denial')
            try:opener.open(origin+'?download=1')
            except urllib.error.HTTPError as e:assert e.code==302 and e.headers['Location']==asset('0.1.10')['browser_download_url']
            else:raise AssertionError('Expected latest APK redirect')
            print('PASS install, repeat install, numeric version selection, URL validation, active download and inactive license denial')
        finally:process.terminate();process.wait(timeout=10)
