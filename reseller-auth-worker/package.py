#!/usr/bin/env python3
"""Allowlisted trial extension only. Never ships the authoritative worker config."""
import hashlib
from pathlib import Path
import sys
import zipfile

root=Path(__file__).resolve().parent
out=Path(sys.argv[1]) if len(sys.argv)>1 else Path('/tmp/opencode/Brand-Trial-Bridge-Setup.zip')
files={'install.sh':root/'install.sh','branded_trials.py':root/'branded_trials.py','README.md':root/'README.md','LICENSE':root.parent/'LICENSE'}
with zipfile.ZipFile(out,'w',zipfile.ZIP_DEFLATED) as archive:
    for name,path in sorted(files.items()):
        if path.is_symlink():raise SystemExit('Refusing symlink in trial extension package.')
        info=zipfile.ZipInfo(name,(2026,1,1,0,0,0));info.create_system=3
        info.external_attr=(0o100700 if name=='install.sh' else 0o100644)<<16
        info.compress_type=zipfile.ZIP_DEFLATED;archive.writestr(info,path.read_bytes())
print(str(out)+'\nSHA256 '+hashlib.sha256(out.read_bytes()).hexdigest())
