#!/usr/bin/env python3
"""Build the fixed-scope workspace update using the verified deployed baseline."""
import hashlib
import json
from pathlib import Path
import sys
from zipfile import ZipFile, ZIP_DEFLATED

root = Path(__file__).resolve().parents[1]
baseline, destination = map(Path, sys.argv[1:3])
assert (baseline / 'VERSION').read_text().strip() == '0.41.0'
files = ['src/Application.php', 'src/CategoryLayouts.php', 'src/DeviceInspection.php', 'public/index.html']
files += ['public/assets/' + name for name in [
    'app.js', 'app.css', 'workspace.css', 'studio.css', 'dashboard.js', 'layout-editor.js',
    'device-workspace.js', 'workspace-library.js', 'app-branding.js', 'trial-workspace.js',
    'trial-setup.js', 'trial-self-service.js', 'renewals-settings.js']]
content = {name: (root / name).read_bytes() for name in files}
content['VERSION'] = b'0.42.0\n'
content['install-live-workspace.py'] = (root / 'deploy/install-live-workspace.py').read_bytes()
content['README.md'] = (root / 'deploy/LIVE-WORKSPACE-0.42.0.md').read_bytes()
content['BASELINE.json'] = json.dumps({name: hashlib.sha256((baseline / name).read_bytes()).hexdigest()
    for name in ['src/Application.php', 'src/Auth.php', 'src/CategoryLayouts.php']}, indent=2).encode()
content['MANIFEST.sha256'] = ''.join(hashlib.sha256(data).hexdigest() + '  ' + name + '\n'
    for name, data in sorted(content.items())).encode()
with ZipFile(destination, 'w', ZIP_DEFLATED) as z:
    for name, data in sorted(content.items()):
        z.writestr(name, data)
print(destination)
print(hashlib.sha256(destination.read_bytes()).hexdigest())
