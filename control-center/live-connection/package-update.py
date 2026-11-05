#!/usr/bin/env python3
"""Package the fixed 0.40.0 baseline update without credentials or local files."""
import hashlib
import subprocess
import sys
import tempfile
from pathlib import Path
from zipfile import ZipFile, ZIP_DEFLATED

root = Path(__file__).resolve().parents[2]
backend = root / 'control-center'
baseline = '5d29bf9'
replaced = ['src/Auth.php', 'src/Application.php', 'src/Database.php', 'src/DeviceProfiles.php', 'VERSION']
files = replaced + ['src/LiveConnection.php'] + [
    'live-connection/' + name for name in (
        'install.sh', 'service.py', 'requirements.txt',
        'extreme-live-connection.service', 'nginx-location.conf')]
contents = {name: (backend / name).read_bytes() for name in files}
old = {name: subprocess.check_output(['git', 'show', f'{baseline}:control-center/{name}'], cwd=root)
       for name in replaced}
assert old['VERSION'].strip() == b'0.40.0'
assert contents['VERSION'].strip() == b'0.41.0'
# Optional exact released My Hub baseline: preserve its routes and auth changes.
if len(sys.argv) > 2:
    hub_archive = Path(sys.argv[2]).read_bytes()
    assert hashlib.sha256(hub_archive).hexdigest() == 'e3fce802e365a9e9caec727e1b25848ae1ca020e4bc0c15061cf9fb8845644f4', 'Unexpected My Hub archive'
    with ZipFile(sys.argv[2]) as hub, tempfile.TemporaryDirectory(dir='/tmp/opencode') as tmp:
        for name in ('src/Auth.php', 'src/Application.php'):
            deployed = hub.read('my-hub-backend/payload/hub/' + name)
            paths = [Path(tmp) / part for part in ('live', 'base', 'hub')]
            for path, data in zip(paths, (contents[name], old[name], deployed)):
                path.write_bytes(data)
            contents[name] = subprocess.check_output(['git', 'merge-file', '-p', *map(str, paths)])
            old[name] = deployed
def manifest(items):
    return ''.join(f'{hashlib.sha256(data).hexdigest()}  {name}\n' for name, data in sorted(items.items())).encode()
contents['BASELINE.sha256'] = manifest(old)
contents['README.md'] = b'''# Extreme Control live connection update 0.41.0

For the existing 0.40.0 control panel, not the marketing website.
Requires root, Python 3.10+ with venv/pip, internet access for the pinned
dependency, and the existing aaPanel PHP 8.4/Nginx paths.

Upload and extract this archive into a new private directory on the server.
From the extracted directory run:

    sha256sum -c MANIFEST.sha256
    bash live-connection/install.sh

The installer checks baseline hashes before writes and prints the backup path.
Custom/multiple-server Nginx configurations require manual review.
Do not upload this archive over public_html or the marketing website.

After installation check:

    systemctl status extreme-live-connection --no-pager
    curl --fail http://127.0.0.1:19095/health

Deploy a matching signed app update in place, preserving app data and identity.
Check message delivery during playback and after five minutes without input,
then check Wi-Fi reconnect and relay-outage fallback on a testing device.
This archive contains no APK. Installed-device acceptance is still required.
'''
contents['MANIFEST.sha256'] = manifest(contents)
destination = Path(sys.argv[1]).resolve()
with ZipFile(destination, 'w', ZIP_DEFLATED) as archive:
    for name, data in sorted(contents.items()):
        archive.writestr(name, data)
with ZipFile(destination) as archive:
    assert archive.testzip() is None
print(destination)
print(hashlib.sha256(destination.read_bytes()).hexdigest())
