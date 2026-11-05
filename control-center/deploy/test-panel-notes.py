"""Exercise published hotfix variants and rollback without touching a live host."""
import importlib.util
from pathlib import Path
import subprocess
import tempfile
from unittest.mock import patch

ROOT=Path(__file__).resolve().parents[2]
def load(path,name):
    spec=importlib.util.spec_from_file_location(name,path);module=importlib.util.module_from_spec(spec);spec.loader.exec_module(module);return module
installer=load(ROOT/'control-center/deploy/install-panel-notes.py','notes')
worker=load(ROOT/'activation-code-worker/install-panel-notes.py','worker_notes')
old=load(ROOT/'control-center/deploy/install-device-notes.py','old_notes')
php=['bash',str(ROOT/'control-center/dev/php.sh')]
with tempfile.TemporaryDirectory(dir='/tmp/opencode') as tmp:
    base=Path(tmp)
    subprocess.run(['python3',str(ROOT/'control-center/deploy/package-panel-notes.py'),str(base)],check=True)
    archive=base/'IPTV-Panel-Notes-Control-0.42.0.zip'
    for ref,js,css,dashboard in [('47ae81f','app','studio','dashboard'),('aa94913','app-online','studio','dashboard-online'),('69a78b5','app-customer-notes','studio','dashboard-online'),('b15b7d4','app-device-notes','studio-device-notes','dashboard-online')]:
        site=base/ref;assets=site/'public/assets/portal-0.42.0';assets.mkdir(parents=True);(site/'src').mkdir();(site/'VERSION').write_text('0.42.0')
        (site/'public/index.html').write_text(f'<script src="/assets/portal-0.42.0/{js}.js"></script><script src="/assets/portal-0.42.0/{dashboard}.js"></script><link href="/assets/portal-0.42.0/{css}.css">')
        for source,target in [('src/Management.php','src/Management.php'),('src/PanelBridge.php','src/PanelBridge.php'),('public/assets/app.js',f'public/assets/portal-0.42.0/{js}.js'),('public/assets/dashboard.js',f'public/assets/portal-0.42.0/{dashboard}.js'),('public/assets/studio.css',f'public/assets/portal-0.42.0/{css}.css')]:
            data=subprocess.check_output(['git','show',ref+':control-center/'+source],cwd=ROOT)
            if css=='studio-device-notes' and source.endswith('studio.css'):data=subprocess.check_output(['git','show','69a78b5:control-center/'+source],cwd=ROOT)+old.STYLE.encode()
            (site/target).write_bytes(data)
        before={p:p.read_bytes() for p in site.rglob('*') if p.is_file()}
        import os
        original=os.replace;calls=[0]
        def fail_once(a,b):
            calls[0]+=1
            if calls[0]==4:raise OSError('Simulated interrupted installation')
            return original(a,b)
        try:
            with patch.object(installer.os,'replace',fail_once):installer.install(site,archive,base/(ref+'-failed'),php)
        except OSError:pass
        else:raise AssertionError('Expected rollback')
        assert before=={p:p.read_bytes() for p in site.rglob('*') if p.is_file()}
        installer.install(site,archive,base/(ref+'-backup'),php)
        assert 'app-panel-notes.js' in (site/'public/index.html').read_text()
        assert 'withPanelNotes' in (site/'src/Management.php').read_text()
        print('PASS install and rollback: '+ref)
    app=base/'worker';app.mkdir();(app/'main.py').write_text('def panel_cursor(): pass\n');(app/'management_enrollment.py').write_text('class AttemptLimiter: pass\ndef bridge_signature(): pass\ndef configured(): pass\n')
    before=(app/'main.py').read_bytes()
    try:
        with patch.object(worker.subprocess,'run',side_effect=RuntimeError('startup failed')):worker.install(app,ROOT/'activation-code-worker')
    except RuntimeError:pass
    assert (app/'main.py').read_bytes()==before and not (app/'panel_line_notes.py').exists()
    worker.install(app,ROOT/'activation-code-worker',restart=False);worker.install(app,ROOT/'activation-code-worker',restart=False)
    assert (app/'main.py').read_text().count('install_line_notes_routes(app, settings, panel_cursor)')==1
    print('PASS worker registration, repeat install and startup rollback')
