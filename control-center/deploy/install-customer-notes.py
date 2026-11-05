#!/usr/bin/env python3
"""Replace the customer Reference column with saved Notes on workspace 0.42.0."""
import os
from pathlib import Path
import re
import shutil
import sys
import tempfile


def install(site, backup):
    site, backup = Path(site), Path(backup)
    index = site / 'public/index.html'
    if index.is_symlink() or (site/'VERSION').read_text().strip() != '0.42.0':
        raise RuntimeError('Expected the 0.42.0 workspace; stopped')
    before = index.read_text()
    matches = re.findall(r'/assets/portal-0\.42\.0/app(?:-online)?\.js', before)
    if len(matches) != 1:
        raise RuntimeError('Expected one original or online-filter app asset; stopped')
    old_url = matches[0]
    asset = site / ('public' + old_url)
    if asset.is_symlink() or not asset.resolve().is_relative_to(site.resolve()):
        raise RuntimeError('Unexpected asset path; stopped')
    code = asset.read_text()
    replacements = {
        "['Line username / customer', 'Workspace', 'Reference', 'Actions']":
        "['Line username / customer', 'Workspace', 'Notes', 'Actions']",
        "[cellName(name, c.notes), c.tenant_name, c.external_reference || '—', actions]":
        "[cellName(name), c.tenant_name, el('span',c.notes || '—','notes'), actions]",
    }
    for old, new in replacements.items():
        if code.count(old) != 1:
            raise RuntimeError('Customer table differs from the expected version; stopped')
        code = code.replace(old, new)
    target = asset.parent / 'app-customer-notes.js'
    if target.exists() or target.is_symlink():
        raise RuntimeError('Notes asset already exists; stopped')
    backup.mkdir(mode=0o700, parents=True, exist_ok=True)
    shutil.copy2(index, backup/'index.html')
    shutil.copy2(asset, backup/asset.name)

    def atomic(path, content, owner):
        fd, tmp = tempfile.mkstemp(prefix='.customer-notes-', dir=path.parent)
        try:
            with os.fdopen(fd, 'w') as f:
                f.write(content); f.flush(); os.fsync(f.fileno()); os.fchmod(f.fileno(), 0o644)
                if os.geteuid() == 0: os.fchown(f.fileno(), owner.st_uid, owner.st_gid)
            os.replace(tmp, path)
        finally:
            if os.path.exists(tmp): os.unlink(tmp)
    atomic(target, code, asset.stat())
    atomic(index, before.replace(old_url, '/assets/portal-0.42.0/app-customer-notes.js'), index.stat())
    print('Customer Notes column installed. Backup: ' + str(backup))


if __name__ == '__main__':
    if os.geteuid() != 0: sys.exit('Run this installer as root on the control-panel host.')
    install('/www/wwwroot/control.fecxbrfzku.net', tempfile.mkdtemp(prefix='customer-notes-before-', dir='/root'))
