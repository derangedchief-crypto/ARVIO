#!/usr/bin/env python3
"""Exercise deployment file handling in a private fixture, never a real vhost."""
from pathlib import Path
from zipfile import ZipFile
import hashlib,os,subprocess,tempfile,stat
root=Path(__file__).resolve().parents[1]
source=(root/'deploy/install-ui-cleanup.sh').read_text()
block=source.split("<<'PY'\n",1)[1].split('\nPY\n',1)[0]
assert 'site=/www/wwwroot/control.fecxbrfzku.net' in source
assert 'https://control.fecxbrfzku.net' in source and '$(pwd -P)' in source
assert 'systemctl' not in source and 'secret("application.key",false)' in source
with tempfile.TemporaryDirectory(prefix='cleanup-install-',dir='/tmp/opencode') as tmp:
 base=Path(tmp);archive=base/'update.zip'
 assets=['app.js','workspace.css','compact.css','renewals-settings.js','trial-workspace.js','trial-setup.js','trial-self-service.js','app-branding.js','workspace-library.js','renewal-checkout.js','renewal-checkout.css']
 files={'VERSION':b'0.35.0\n','public/index.html':b'<script src="/assets/app.js"></script>','public/renew.html':b'<script src="/assets/renewal-checkout.js"></script>','src/DO-NOT-INSTALL.php':b'UNRELATED BACKEND CONTENT'}
 files.update({'public/assets/'+name:(root/'public/assets'/name).read_bytes() for name in assets})
 files['MANIFEST.sha256']=''.join(hashlib.sha256(v).hexdigest()+'  '+k+'\n' for k,v in files.items()).encode()
 with ZipFile(archive,'w') as z:
  for name,data in files.items():z.writestr(name,data)
 def fixture(name):
  site=base/name;site.mkdir();(site/'public/assets').mkdir(parents=True);(site/'src').mkdir();(site/'storage').mkdir()
  for path,data in {'VERSION':b'0.34.0\n','public/index.html':b'OLD INDEX','public/renew.html':b'OLD CHECKOUT','public/assets/app.js':b'OLD ASSET FOR OPEN CLIENTS','src/keep.php':b'KEEP BACKEND','config.php':b'KEEP PRIVATE CONFIG','storage/application.key':b'KEEP ORIGINAL KEY'}.items():(site/path).write_bytes(data)
  backup=base/(name+'-backup');backup.mkdir();return site,backup
 def run(site,backup,php='/bin/true'):
  return subprocess.run(['/usr/bin/python3','-',str(site),str(archive),str(backup),php],input=block,text=True,capture_output=True)
 site,backup=fixture('success');mask=os.umask(0o077)
 try:r=run(site,backup)
 finally:os.umask(mask)
 assert r.returncode==0,r.stderr
 assert (site/'VERSION').read_bytes()==b'0.35.0\n'
 assert b'/assets/portal-0.35.0/app.js' in (site/'public/index.html').read_bytes()
 assert (site/'public/assets/app.js').read_bytes()==b'OLD ASSET FOR OPEN CLIENTS'
 assert (site/'src/keep.php').read_bytes()==b'KEEP BACKEND' and not (site/'src/DO-NOT-INSTALL.php').exists()
 assert (site/'config.php').read_bytes()==b'KEEP PRIVATE CONFIG' and (site/'storage/application.key').read_bytes()==b'KEEP ORIGINAL KEY'
 assert stat.S_IMODE((site/'public/assets/portal-0.35.0').stat().st_mode)==0o755
 assert stat.S_IMODE((site/'public/assets/portal-0.35.0/app.js').stat().st_mode)==0o644
 assert (backup/'frontend-before.zip').is_file()
 print('PASS versioned frontend staging preserves old assets backend private keys and readable permissions')
 backup2=base/'reinstall';backup2.mkdir();assert run(site,backup2).returncode==0
 (site/'public/assets/portal-0.35.0/app.js').write_bytes(b'USER MODIFIED')
 backup3=base/'modified';backup3.mkdir();r=run(site,backup3);assert r.returncode!=0 and (site/'public/assets/portal-0.35.0/app.js').read_bytes()==b'USER MODIFIED'
 print('PASS identical reinstall is safe and modified release assets are preserved')
 site,backup=fixture('rollback');fail=base/'fail-php';fail.write_text('#!/bin/sh\nexit 1\n');fail.chmod(0o700);assert run(site,backup,str(fail)).returncode!=0
 assert (site/'VERSION').read_bytes()==b'0.34.0\n' and (site/'public/index.html').read_bytes()==b'OLD INDEX'
 assert (site/'storage/application.key').read_bytes()==b'KEEP ORIGINAL KEY'
 print('PASS failed read-only check rolls back only frontend pointers, never financial or private data')
 site,backup=fixture('symlink');other=base/'another-site';other.mkdir();(site/'public/assets/portal-0.35.0').symlink_to(other,target_is_directory=True)
 assert run(site,backup).returncode!=0 and not list(other.iterdir()) and (site/'VERSION').read_bytes()==b'0.34.0\n'
 print('PASS a path into another site is rejected before any frontend modification')
