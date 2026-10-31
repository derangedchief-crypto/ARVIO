#!/usr/bin/env python3
"""Exercise the 0.36 fixed-site installer without touching a real website."""
from pathlib import Path
from zipfile import ZipFile
import hashlib,os,subprocess,tempfile,stat
root=Path(__file__).resolve().parents[1]
source=(root/'deploy/install-control-036.sh').read_text()
block=source.split("<<'PY'\n",1)[1].split('\nPY\n',1)[0]
assert 'systemctl' not in source and 'secret("application.key",false)' in source
assert '033_trial_conversion_defaults.sql' in source
with tempfile.TemporaryDirectory(prefix='control036-',dir='/tmp/opencode') as tmp:
 base=Path(tmp);archive=base/'update.zip'
 assets=['app.js','workspace.css','compact.css','renewals-settings.js','trial-workspace.js','trial-setup.js','trial-self-service.js','app-branding.js','workspace-library.js','renewal-checkout.js','renewal-checkout.css']
 backend=['src/AppBrands.php','src/BrandBuilder.php','public/index.php']
 files={'VERSION':b'0.36.0\n','public/index.html':b'<script src="/assets/app.js"></script>','public/renew.html':b'<script src="/assets/renewal-checkout.js"></script>','src/DO-NOT-INSTALL.php':b'UNRELATED'}
 files.update({n:(root/n).read_bytes() for n in backend})
 files.update({'public/assets/'+n:(root/'public/assets'/n).read_bytes() for n in assets})
 files['MANIFEST.sha256']=''.join(hashlib.sha256(v).hexdigest()+'  '+k+'\n' for k,v in files.items()).encode()
 with ZipFile(archive,'w') as z:
  for n,v in files.items():z.writestr(n,v)
 def fixture(name):
  site=base/name;site.mkdir();(site/'public/assets').mkdir(parents=True);(site/'src').mkdir();(site/'storage').mkdir()
  initial={'VERSION':b'0.35.0\n','public/index.html':b'OLD INDEX','public/renew.html':b'OLD CHECKOUT','public/assets/app.js':b'OLD ASSET','src/keep.php':b'KEEP BACKEND','config.php':b'KEEP CONFIG','storage/application.key':b'KEEP KEY',**{n:b'OLD PHP' for n in backend}}
  for n,v in initial.items():(site/n).write_bytes(v)
  backup=base/(name+'-backup');backup.mkdir();return site,backup,initial
 def run(site,backup,php='/bin/true'):
  return subprocess.run(['/usr/bin/python3','-',str(site),str(archive),str(backup),php],input=block,text=True,capture_output=True)
 site,backup,initial=fixture('success');mask=os.umask(0o077)
 try:r=run(site,backup)
 finally:os.umask(mask)
 assert r.returncode==0,r.stderr
 for n in backend+['VERSION']:assert (site/n).read_bytes()==files[n]
 for n in ['src/keep.php','config.php','storage/application.key','public/assets/app.js']:assert (site/n).read_bytes()==initial[n]
 assert not (site/'src/DO-NOT-INSTALL.php').exists()
 assert b'/assets/portal-0.36.0/app.js' in (site/'public/index.html').read_bytes()
 assert stat.S_IMODE((site/'public/assets/portal-0.36.0/app.js').stat().st_mode)==0o644
 assert (backup/'website-before.zip').is_file()
 print('PASS exact backend allowlist, old assets and private files preserved; versioned assets readable')
 backup2=base/'reinstall';backup2.mkdir();assert run(site,backup2).returncode==0
 (site/'public/assets/portal-0.36.0/app.js').write_bytes(b'USER EDIT')
 backup3=base/'modified';backup3.mkdir();assert run(site,backup3).returncode!=0
 print('PASS repeat installation and preservation of modified release assets')
 site,backup,initial=fixture('rollback');fail=base/'fail-php';fail.write_text('#!/bin/sh\ncase "$*" in *" -l "*) exit 0;; *) exit 1;; esac\n');fail.chmod(0o700)
 assert run(site,backup,str(fail)).returncode!=0
 for n,v in initial.items():assert (site/n).read_bytes()==v,n
 print('PASS post-install check failure rolls back our PHP/HTML/version only')
 site,backup,initial=fixture('symlink');other=base/'another-site';other.mkdir();(site/'public/assets/portal-0.36.0').symlink_to(other,target_is_directory=True)
 assert run(site,backup).returncode!=0 and not list(other.iterdir())
 print('PASS another-site symlink rejected before writes')
