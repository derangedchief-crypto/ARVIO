from pathlib import Path
from zipfile import ZipFile
import hashlib,subprocess,tempfile
root=Path(__file__).resolve().parents[1]
source=(root/'deploy/install-control-0400.sh').read_text()
block=source.split("<<'PY'\n",1)[1].split('\nPY\n',1)[0]
assert 'site=/www/wwwroot/control.fecxbrfzku.net' in source and '$(pwd -P)' in source
with tempfile.TemporaryDirectory(dir='/tmp/opencode',prefix='control0400-') as tmp:
    base=Path(tmp);archive=base/'update.zip'
    subprocess.run(['python3',str(root/'tools/package.py'),'--update',str(archive)],check=True,capture_output=True)
    php=base/'php';php.write_text('#!/bin/sh\nexec bash '+str(root/'dev/php.sh')+' "$@"\n');php.chmod(0o700)
    def fixture(name):
        site=base/name
        for folder in ['src','public/assets','cli','storage']:(site/folder).mkdir(parents=True,exist_ok=True)
        files={'VERSION':b'0.38.1\n','public/index.html':b'old index','public/renew.html':b'old renew','public/assets/app.js':b'old asset','src/Management.php':b'old management','src/Enrollment.php':b'keep enrollment','config.php':b'keep config','storage/application.key':b'keep key'}
        for n,data in files.items():(site/n).write_bytes(data)
        backup=base/(name+'-backup');backup.mkdir();return site,backup,files
    def run(site,backup,zipfile=archive):return subprocess.run(['python3','-',str(site),str(zipfile),str(backup),str(php)],input=block,text=True,capture_output=True)
    site,backup,files=fixture('success');result=run(site,backup);assert result.returncode==0,result.stderr
    for n in ['src/Enrollment.php','config.php','storage/application.key','public/assets/app.js']:assert (site/n).read_bytes()==files[n]
    with ZipFile(archive) as z:
        for name in ['dashboard.js','app.js','workspace.css','device-workspace.js','renewals-settings.js','app-branding.js','renewal-checkout.css']:
            assert (site/('public/assets/portal-0.40.0/'+name)).read_bytes()==z.read('public/assets/'+name)
        assert (site/'src/Management.php').read_bytes()==z.read('src/Management.php')
    assert '/assets/portal-0.40.0/dashboard.js' in (site/'public/index.html').read_text()
    assert (site/'VERSION').read_text().strip()=='0.40.0'
    with ZipFile(backup/'website-before.zip') as z:
        for n in ['VERSION','src/Management.php','public/index.html','public/renew.html']:assert z.read(n)==files[n]
    backup2=base/'repeat';backup2.mkdir();assert run(site,backup2).returncode==0
    (site/'public/assets/portal-0.40.0/dashboard.js').write_text('user edit')
    backup3=base/'edited';backup3.mkdir();assert run(site,backup3).returncode!=0
    print('PASS fixed-site read-only dashboard upgrade, backup, immutable rerun and edited-asset refusal')
    site,backup,files=fixture('symlink');other=base/'other-site';other.mkdir();(site/'public/assets/portal-0.40.0').symlink_to(other,target_is_directory=True)
    assert run(site,backup).returncode!=0 and not list(other.iterdir())
    for n,data in files.items():assert (site/n).read_bytes()==data
    print('PASS other-site isolation before writes')
    broken=base/'broken.zip'
    with ZipFile(archive) as z:entries={name:z.read(name) for name in z.namelist() if name!='MANIFEST.sha256'}
    entries['src/Management.php']=b'<?php invalid PHP + ;'
    entries['MANIFEST.sha256']=''.join(hashlib.sha256(data).hexdigest()+'  '+name+'\n' for name,data in sorted(entries.items())).encode()
    with ZipFile(broken,'w') as z:
        for name,data in entries.items():z.writestr(name,data)
    site,backup,files=fixture('badphp');assert run(site,backup,broken).returncode!=0
    for n,data in files.items():assert (site/n).read_bytes()==data
    assert not (site/'public/assets/portal-0.40.0').exists()
    print('PASS invalid PHP refused before modifying website files')
