#!/usr/bin/env python3
"""Install the My Hub-compatible 0.42.0 workspace over the verified 0.41.0 backend."""
import hashlib
import json
import os
from pathlib import Path
import stat
import subprocess
import sys
import tempfile
from zipfile import ZipFile, ZIP_DEFLATED

VERSION = '0.42.0'


def digest(data):
    return hashlib.sha256(data).hexdigest()


def install(site, archive, backup, php):
    site, archive, backup = map(Path, (site, archive, backup))
    site = site.resolve()
    backup.mkdir(mode=0o700, parents=True, exist_ok=True)

    def safe(path):
        if not path.resolve().is_relative_to(site) or any(p.is_symlink() for p in [path, *path.parents] if p != site.parent):
            raise RuntimeError('Unexpected site symlink; stopped')

    for name in ['VERSION', 'config.php', 'storage/application.key', 'src/Application.php', 'src/Auth.php']:
        safe(site / name)
        if not (site / name).is_file():
            raise RuntimeError('Missing required site file: ' + name)
    if (site / 'VERSION').read_text().strip() != '0.41.0':
        raise RuntimeError('Expected the deployed 0.41.0 My Hub backend; stopped')
    with ZipFile(archive) as z:
        if z.read('VERSION').decode().strip() != VERSION:
            raise RuntimeError('Wrong update version')
        hashes = dict(line.split('  ', 1)[::-1] for line in z.read('MANIFEST.sha256').decode().splitlines())
        for name, expected in hashes.items():
            if digest(z.read(name)) != expected:
                raise RuntimeError('Archive checksum mismatch: ' + name)
        baseline = json.loads(z.read('BASELINE.json'))
        for name, expected in baseline.items():
            safe(site / name)
            if digest((site / name).read_bytes()) != expected:
                raise RuntimeError('Deployed backend differs from the My Hub baseline: ' + name)
        assets = {name.removeprefix('public/assets/'): z.read(name) for name in hashes if name.startswith('public/assets/')}
        if any('/' in name or not name.endswith(('.js', '.css')) for name in assets):
            raise RuntimeError('Unexpected asset path')
        updates = {name: z.read(name) for name in ['src/Application.php', 'src/CategoryLayouts.php', 'src/DeviceInspection.php', 'public/index.html', 'VERSION']}
    if (site / 'src/DeviceInspection.php').exists():
        raise RuntimeError('Existing inspection implementation preserved; review required')
    protected = {name: digest((site / name).read_bytes()) for name in ['config.php', 'storage/application.key', 'src/Auth.php']}
    for name in ['src/Application.php', 'src/CategoryLayouts.php', 'src/DeviceInspection.php']:
        candidate = backup / Path(name).name
        candidate.write_bytes(updates[name])
        subprocess.run([*php, '-l', str(candidate)], check=True)
    release = site / ('public/assets/portal-' + VERSION)
    safe(release)
    if release.exists():
        raise RuntimeError('Release directory already exists; stopped')
    text = updates['public/index.html'].decode()
    for name in assets:
        text = text.replace('/assets/' + name, '/assets/portal-' + VERSION + '/' + name)
    updates['public/index.html'] = text.encode()
    originals = {name: (site / name).read_bytes() if (site / name).exists() else None for name in updates}
    with ZipFile(backup / 'website-before.zip', 'w', ZIP_DEFLATED) as z:
        for name, data in originals.items():
            if data is not None:
                z.writestr(name, data)
    (backup / 'added-files.json').write_text(json.dumps([name for name, data in originals.items() if data is None]))

    def atomic(path, data):
        safe(path)
        owner = path.stat() if path.exists() else path.parent.stat()
        fd, temporary = tempfile.mkstemp(prefix='.workspace-update-', dir=path.parent)
        try:
            with os.fdopen(fd, 'wb') as output:
                output.write(data)
                output.flush()
                os.fsync(output.fileno())
                os.fchmod(output.fileno(), stat.S_IMODE(owner.st_mode) if path.exists() else 0o644)
                if os.geteuid() == 0:
                    os.fchown(output.fileno(), owner.st_uid, owner.st_gid)
            os.replace(temporary, path)
        finally:
            if os.path.exists(temporary):
                os.unlink(temporary)

    release.mkdir(mode=0o755)
    os.chmod(release, 0o755)
    for name, data in assets.items():
        atomic(release / name, data)
    installed = []
    try:
        # Install the coordinator before registering its route, and publish the UI last.
        for name in ['src/DeviceInspection.php', 'src/CategoryLayouts.php', 'src/Application.php', 'VERSION', 'public/index.html']:
            atomic(site / name, updates[name])
            installed.append(name)
        if any(digest((site / name).read_bytes()) != expected for name, expected in protected.items()):
            raise RuntimeError('Protected configuration changed during installation')
    except BaseException:
        for name in reversed(installed):
            if digest((site / name).read_bytes()) == digest(updates[name]):
                if originals[name] is None:
                    (site / name).unlink()
                else:
                    atomic(site / name, originals[name])
        raise
    print('Installed My Hub live workspace ' + VERSION)
    print('Rollback backup: ' + str(backup))


if __name__ == '__main__':
    if os.geteuid() != 0 or len(sys.argv) != 2:
        sys.exit('Run as root: python3 install-live-workspace.py VERIFIED_UPDATE.zip')
    install('/www/wwwroot/control.fecxbrfzku.net', sys.argv[1],
            tempfile.mkdtemp(prefix='control-0420-before-', dir='/root'),
            ['/www/server/php/84/bin/php', '-d', 'opcache.enable_cli=0'])
