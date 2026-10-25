#!/usr/bin/env python3
"""Single worker-host production cutover bundle, with no private configuration."""
from pathlib import Path
import hashlib
import sys
import zipfile

root=Path(__file__).resolve().parent.parent
out=Path(sys.argv[1])
files={'production.sh':root/'branding/deploy/enable-production-dispatcher.sh','configure-production.py':root/'branding/deploy/configure-production.py','branding/dispatch_github.py':root/'branding/dispatch_github.py','auth/install.sh':root/'reseller-auth-worker/install.sh','auth/branded_trials.py':root/'reseller-auth-worker/branded_trials.py','auth/README.md':root/'reseller-auth-worker/README.md','README.md':root/'branding/PRODUCTION_RELEASES.md','LICENSE':root/'LICENSE'}
files.update({'activation/upgrade-branded-package.sh':root/'activation-code-worker/upgrade-branded-package.sh','activation/patch-branded-package.py':root/'activation-code-worker/patch-branded-package.py'})
entries={}
for name,path in sorted(files.items()):
    if path.is_symlink():raise SystemExit('Refusing delivery symlink.')
    entries[name]=path.read_bytes()
entries['MANIFEST.sha256']=''.join(hashlib.sha256(data).hexdigest()+'  '+name+'\n' for name,data in sorted(entries.items())).encode()
with zipfile.ZipFile(out,'w',zipfile.ZIP_DEFLATED) as archive:
    for name,data in sorted(entries.items()):
        info=zipfile.ZipInfo(name,(2026,1,1,0,0,0));info.create_system=3;info.external_attr=(0o100700 if name.endswith('.sh') else 0o100644)<<16;info.compress_type=zipfile.ZIP_DEFLATED;archive.writestr(info,data)
print(str(out)+'\nSHA256 '+hashlib.sha256(out.read_bytes()).hexdigest())
