#!/usr/bin/env python3
"""Package owner setup + one public SSH key only. Never includes runtime secrets."""
import base64
import hashlib
from pathlib import Path
import re
import sys
import zipfile

root=Path(__file__).resolve().parent
if len(sys.argv)!=3:raise SystemExit('Usage: package_website_bridge.py PUBLIC_KEY_FILE OUTPUT_ZIP')
public=Path(sys.argv[1]).read_text().strip()
if not re.fullmatch(r'ssh-ed25519 [A-Za-z0-9+/=]+(?: [a-zA-Z0-9._-]+)?',public):raise SystemExit('Expected one Ed25519 PUBLIC key only.')
raw=base64.b64decode(public.split()[1],validate=True)
if len(raw)!=51 or raw[:19]!=b'\x00\x00\x00\x0bssh-ed25519\x00\x00\x00\x20':raise SystemExit('Invalid public SSH key encoding.')
entries={
    'install.sh':(root/'deploy/install-website-bridge.sh').read_bytes(),
    'README.md':(root/'deploy/WEBSITE_BRIDGE_SETUP.md').read_bytes(),
    'LICENSE':(root.parent/'LICENSE').read_bytes(),
    'bridge-public-key.pub':(public+'\n').encode(),
}
output=Path(sys.argv[2])
with zipfile.ZipFile(output,'w',zipfile.ZIP_DEFLATED) as archive:
    for name,data in sorted(entries.items()):
        info=zipfile.ZipInfo(name,(2026,1,1,0,0,0));info.create_system=3
        info.external_attr=(0o100700 if name=='install.sh' else 0o100644)<<16
        info.compress_type=zipfile.ZIP_DEFLATED;archive.writestr(info,data)
print(str(output)+'\nSHA256 '+hashlib.sha256(output.read_bytes()).hexdigest())
