#!/usr/bin/env python3
"""Targeted, reversible route registration. Never replace config/env/ledgers."""
import ast
from datetime import datetime, timezone
import os
from pathlib import Path
import py_compile
import shutil
import stat
import sys
import uuid
import json
import time
from urllib.request import urlopen

REGISTRATION = '\n\n# Primary line guard: read-only signed status.\nfrom primary_line_status import install_primary_status_routes\ninstall_primary_status_routes(app, settings, panel_cursor)\n'


def atomic_write(path, data, metadata):
    temporary = path.with_name(path.name + '.primary-update-' + uuid.uuid4().hex)
    try:
        with temporary.open('xb') as handle:
            handle.write(data)
            handle.flush(); os.fsync(handle.fileno())
        os.chmod(temporary, stat.S_IMODE(metadata.st_mode))
        if os.geteuid() == 0: os.chown(temporary, metadata.st_uid, metadata.st_gid)
        os.replace(temporary, path)
    finally:
        if temporary.exists(): temporary.unlink()


def restore(backup):
    backup = Path(backup)
    app = Path((backup / 'app-path').read_text())
    shutil.copy2(backup / 'main.py', app / 'main.py')
    if (backup / 'primary_line_status.py').exists(): shutil.copy2(backup / 'primary_line_status.py', app / 'primary_line_status.py')
    elif (app / 'primary_line_status.py').exists(): (app / 'primary_line_status.py').unlink()


def patch(app, bundle):
    app, bundle = Path(app).resolve(), Path(bundle).resolve()
    main, module = app / 'main.py', app / 'primary_line_status.py'
    if not main.is_file() or main.is_symlink(): raise RuntimeError('Existing regular main.py is required')
    before = main.read_bytes(); text = before.decode()
    tree = ast.parse(text)
    if not any(isinstance(n, (ast.FunctionDef, ast.AsyncFunctionDef)) and n.name == 'panel_cursor' for n in tree.body): raise RuntimeError('Unknown worker layout; do not overwrite it')
    support = app / 'management_enrollment.py'
    names = {getattr(n, 'name', '') for n in ast.parse(support.read_text()).body}
    if not {'AttemptLimiter','bridge_signature','configured','credentials_match','client_ip'} <= names: raise RuntimeError('Upgrade the existing management bridge first')
    data = (bundle / 'primary_line_status.py').read_bytes()
    if module.is_symlink(): raise RuntimeError('Refusing a symlinked module')
    if module.exists() and (b'def install_primary_status_routes(' not in module.read_bytes() or not module.read_bytes().startswith(b'"""Read-only, authenticated line status for the control center. No panel writes."""')): raise RuntimeError('Unknown existing primary module; review it first')
    updated = text if 'install_primary_status_routes(app, settings, panel_cursor)' in text else text + REGISTRATION
    if updated.encode() == before and module.exists() and module.read_bytes() == data: return None
    untouched = {name: (app / name).read_bytes() for name in ['.env','config.py'] if (app / name).is_file()}
    backup = app / ('.primary-status-backup-' + datetime.now(timezone.utc).strftime('%Y%m%d%H%M%S') + '-' + uuid.uuid4().hex[:8])
    backup.mkdir(mode=0o700)
    (backup / 'app-path').write_text(str(app))
    shutil.copy2(main, backup / 'main.py')
    if module.exists(): shutil.copy2(module, backup / 'primary_line_status.py')
    metadata = main.stat()
    if main.read_bytes()!=before: raise RuntimeError('Worker source changed during preparation; stopped without overwriting it')
    if module.exists() and (backup / 'primary_line_status.py').read_bytes()!=module.read_bytes(): raise RuntimeError('Primary module changed during preparation; stopped')
    try:
        atomic_write(module, data, metadata)
        atomic_write(main, updated.encode(), metadata)
        py_compile.compile(str(main), cfile=str(backup / 'checked-main.pyc'), doraise=True)
        py_compile.compile(str(module), cfile=str(backup / 'checked-status.pyc'), doraise=True)
        for name, original in untouched.items():
            if (app / name).read_bytes() != original: raise RuntimeError('Unrelated configuration changed; stopped')
    except BaseException:
        restore(backup)
        raise
    return backup


if __name__ == '__main__':
    if len(sys.argv)==3 and sys.argv[1]=='--health':
        for _ in range(60):
            try:
                with urlopen(sys.argv[2],timeout=2) as response:
                    result=json.loads(response.read(4096))
                if result.get('schema_version')==1 and result.get('credential_validation_version')==1:break
            except Exception:pass
            time.sleep(0.5)
        else:raise SystemExit('Primary route startup health check failed')
    elif len(sys.argv) == 3 and sys.argv[1] == '--restore': restore(sys.argv[2])
    elif len(sys.argv) == 3:
        result = patch(sys.argv[1], sys.argv[2]); print(result or 'UNCHANGED')
    else: raise SystemExit('Usage: patch-primary-status.py WORKER_DIRECTORY BUNDLE_DIRECTORY')
