from pathlib import Path
from zipfile import ZipFile
import subprocess,tempfile
root=Path(__file__).resolve().parents[1]
source=(root/'deploy/install-control-0390.sh').read_text()
block=source.split("<<'PY'\n",1)[1].split('\nPY\n',1)[0]
assert 'site=/www/wwwroot/control.fecxbrfzku.net' in source and '$(pwd -P)' in source
with tempfile.TemporaryDirectory(dir='/tmp/opencode',prefix='control0390-') as tmp:
    base=Path(tmp);archive=base/'update.zip'
    subprocess.run(['python3',str(root/'tools/package.py'),'--update',str(archive)],check=True,capture_output=True)
    def fixture(name):
        site=base/name
        for folder in ['src','public/assets','cli','storage']:(site/folder).mkdir(parents=True,exist_ok=True)
        files={'VERSION':b'0.38.1\n','public/index.html':b'old index','public/renew.html':b'old renew','public/assets/app.js':b'old asset','src/Enrollment.php':b'keep backend','config.php':b'keep config','storage/application.key':b'keep key'}
        for n,data in files.items():(site/n).write_bytes(data)
        backup=base/(name+'-backup');backup.mkdir();return site,backup,files
    def run(site,backup):return subprocess.run(['python3','-',str(site),str(archive),str(backup)],input=block,text=True,capture_output=True)
    site,backup,files=fixture('success');result=run(site,backup);assert result.returncode==0,result.stderr
    for n in ['src/Enrollment.php','config.php','storage/application.key','public/assets/app.js']:assert (site/n).read_bytes()==files[n]
    with ZipFile(archive) as z:
        for name in ['app.js','workspace.css','device-workspace.js','renewals-settings.js','app-branding.js','renewal-checkout.css']:
            assert (site/('public/assets/portal-0.39.0/'+name)).read_bytes()==z.read('public/assets/'+name)
    assert '/assets/portal-0.39.0/app.js' in (site/'public/index.html').read_text()
    assert 'id="bottom-nav"' in (site/'public/index.html').read_text()
    assert (site/'VERSION').read_text().strip()=='0.39.0'
    with ZipFile(backup/'website-before.zip') as z:
        for n in ['VERSION','public/index.html','public/renew.html']:assert z.read(n)==files[n]
    backup2=base/'repeat';backup2.mkdir();assert run(site,backup2).returncode==0
    (site/'public/assets/portal-0.39.0/app.js').write_text('user edit')
    backup3=base/'edited';backup3.mkdir();assert run(site,backup3).returncode!=0
    print('PASS fixed-site frontend install, backup, immutable rerun and modified-asset refusal')
    site,backup,files=fixture('symlink');other=base/'other-site';other.mkdir();(site/'public/assets/portal-0.39.0').symlink_to(other,target_is_directory=True)
    assert run(site,backup).returncode!=0 and not list(other.iterdir())
    for n,data in files.items():assert (site/n).read_bytes()==data
    print('PASS other-site isolation before writes')
