#!/usr/bin/env python3
"""Install the read-only notes route, restoring source if startup fails."""
import ast
import json
import os
from pathlib import Path
import shutil
import subprocess
import sys
import tempfile
import time
from urllib.request import urlopen

REGISTRATION = '\n\nfrom panel_line_notes import install_line_notes_routes\ninstall_line_notes_routes(app, settings, panel_cursor)\n'


def install(app, bundle, restart=True):
    app, bundle = Path(app), Path(bundle)
    main, module = app/'main.py', app/'panel_line_notes.py'
    if main.is_symlink() or module.is_symlink(): raise RuntimeError('Unexpected worker symlink')
    before = main.read_bytes()
    tree = ast.parse(before)
    if not any(isinstance(n, (ast.FunctionDef, ast.AsyncFunctionDef)) and n.name == 'panel_cursor' for n in tree.body):
        raise RuntimeError('Unknown worker layout')
    names = {getattr(n, 'name', '') for n in ast.parse((app/'management_enrollment.py').read_bytes()).body}
    if not {'AttemptLimiter', 'bridge_signature', 'configured'} <= names: raise RuntimeError('Management bridge required')
    data = (bundle/'panel_line_notes.py').read_bytes()
    after = before if REGISTRATION.strip().encode() in before else before + REGISTRATION.encode()
    ast.parse(after); ast.parse(data)
    if module.exists() and module.read_bytes()==data and before==after: return
    backup = Path(tempfile.mkdtemp(prefix='.panel-notes-backup-', dir=app))
    shutil.copy2(main, backup/'main.py')
    if module.exists(): shutil.copy2(module, backup/module.name)
    def write(path, content):
        metadata = path.stat() if path.exists() else main.stat()
        fd, tmp = tempfile.mkstemp(dir=app, prefix='.notes-')
        try:
            with os.fdopen(fd, 'wb') as f:
                f.write(content); f.flush(); os.fsync(f.fileno()); os.fchmod(f.fileno(), metadata.st_mode & 0o777)
                if os.geteuid()==0: os.fchown(f.fileno(),metadata.st_uid,metadata.st_gid)
            os.replace(tmp,path)
        finally:
            if os.path.exists(tmp): os.unlink(tmp)
    try:
        if main.read_bytes()!=before: raise RuntimeError('Worker changed during preparation')
        write(module,data); write(main,after)
        if restart:
            subprocess.run(['systemctl','restart','iptv-activation-worker.service'],check=True)
            for _ in range(60):
                try:
                    with urlopen('http://127.0.0.1:9092/management/line-notes-health',timeout=2) as r: health=json.loads(r.read(4096))
                    if health=={'schema_version':1,'source':'customers.reseller_notes'}: break
                except Exception: pass
                time.sleep(.5)
            else: raise RuntimeError('Notes route startup check failed')
    except BaseException:
        write(main,before)
        if (backup/module.name).exists(): write(module,(backup/module.name).read_bytes())
        elif module.exists(): module.unlink()
        if restart: subprocess.run(['systemctl','restart','iptv-activation-worker.service'],check=False)
        raise
    print('IPTV panel notes route installed. Backup: '+str(backup))


if __name__=='__main__':
    if os.geteuid()!=0: sys.exit('Run as root on the activation-worker host')
    install(sys.argv[1] if len(sys.argv)>1 else '/opt/iptv-activation-worker',Path(__file__).resolve().parent)
