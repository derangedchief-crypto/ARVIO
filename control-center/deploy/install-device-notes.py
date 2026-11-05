#!/usr/bin/env python3
"""Add customer notes to device rows on the 0.42.0 workspace and its hotfixes."""
import os
from pathlib import Path
import re
import shutil
import subprocess
import sys
import tempfile

STYLE = """
.device-records th:nth-child(1){width:22%}.device-records th:nth-child(2){width:16%}.device-records th:nth-child(3){width:22%}.device-records th:nth-child(4){width:10%}.device-records th:nth-child(5){width:18%}.device-records th:nth-child(6){width:12%}
@media(max-width:600px){.device-records.mobile-records tbody tr{grid-template-areas:'device actions' 'customer customer' 'notes notes' 'access contact'}.device-records.mobile-records td:nth-child(3){grid-area:notes}.device-records.mobile-records td:nth-child(4){grid-area:access}.device-records.mobile-records td:nth-child(5){grid-area:contact}.device-records.mobile-records td:last-child{grid-area:actions}}
"""


def install(site, backup, php):
    site, backup = Path(site), Path(backup)
    if (site/'VERSION').read_text().strip() != '0.42.0':
        raise RuntimeError('Expected the 0.42.0 workspace; stopped')
    index = site/'public/index.html'
    original = index.read_text()
    matches = re.findall(r'/assets/portal-0\.42\.0/app(?:-online|-customer-notes)?\.js', original)
    css_url = '/assets/portal-0.42.0/studio.css'
    if len(matches) != 1 or original.count(css_url) != 1:
        raise RuntimeError('Expected workspace asset links were not found; stopped')
    app_url = matches[0]
    app, css, backend = site/('public'+app_url), site/('public'+css_url), site/'src/Management.php'
    for path in [index, app, css, backend]:
        if path.is_symlink() or not path.resolve().is_relative_to(site.resolve()):
            raise RuntimeError('Unexpected site path; stopped')

    def replace_once(text, old, new):
        if text.count(old) != 1: raise RuntimeError('Installed source differs from expected version; stopped')
        return text.replace(old, new)

    js = replace_once(app.read_text(), "['Device','Customer','App access','App contact','Actions']",
                      "['Device','Customer','Notes','App access','App contact','Actions']")
    js = replace_once(js, 'return[device,customer,badge(d.policy),connection,actions];',
                      "return[device,customer,el('span',d.customer_notes||'—','notes'),badge(d.policy),connection,actions];")
    previous_php = backend.read_text()
    updated_php = replace_once(previous_php, '$fields = "r.*, TIMESTAMPDIFF',
                               '$fields = "r.*, c.notes AS customer_notes, TIMESTAMPDIFF')
    app_target, css_target = app.parent/'app-device-notes.js', css.parent/'studio-device-notes.css'
    if any(p.exists() or p.is_symlink() for p in [app_target, css_target]):
        raise RuntimeError('Device notes assets already exist; stopped')
    backup.mkdir(mode=0o700, parents=True, exist_ok=True)
    for path in [index, app, css, backend]: shutil.copy2(path, backup/path.name)
    candidate = backup/'Management.new.php'
    candidate.write_text(updated_php)
    subprocess.run([*php, '-l', str(candidate)], check=True)

    def atomic(path, text, owner):
        fd, tmp = tempfile.mkstemp(prefix='.device-notes-', dir=path.parent)
        try:
            with os.fdopen(fd, 'w') as f:
                f.write(text); f.flush(); os.fsync(f.fileno()); os.fchmod(f.fileno(), 0o644)
                if os.geteuid() == 0: os.fchown(f.fileno(), owner.st_uid, owner.st_gid)
            os.replace(tmp, path)
        finally:
            if os.path.exists(tmp): os.unlink(tmp)
    atomic(app_target, js, app.stat())
    atomic(css_target, css.read_text()+STYLE, css.stat())
    try:
        atomic(backend, updated_php, backend.stat())
        atomic(index, original.replace(app_url, '/assets/portal-0.42.0/app-device-notes.js')
               .replace(css_url, '/assets/portal-0.42.0/studio-device-notes.css'), index.stat())
    except BaseException:
        atomic(backend, previous_php, backend.stat())
        atomic(index, original, index.stat())
        raise
    print('Device customer-notes column installed. Backup: '+str(backup))


if __name__ == '__main__':
    if os.geteuid() != 0: sys.exit('Run as root on the control-panel host.')
    install('/www/wwwroot/control.fecxbrfzku.net', tempfile.mkdtemp(prefix='device-notes-before-', dir='/root'),
            ['/www/server/php/84/bin/php', '-d', 'opcache.enable_cli=0'])
