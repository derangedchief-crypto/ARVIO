#!/usr/bin/env python3
"""Owner-only worker-host setup bundle; allowlist only, no runtime config/secrets."""
from pathlib import Path
import hashlib
import sys
import zipfile

root=Path(__file__).resolve().parent
output=Path(sys.argv[1]) if len(sys.argv)>1 else Path('/tmp/opencode/Brand-Dispatcher-Setup.zip')
entries={
    'LICENSE':root.parent/'LICENSE',
    'install.sh':root/'deploy/install-dispatcher.sh',
    'upgrade.sh':root/'deploy/upgrade-dispatcher.sh',
    'configure-poll.py':root/'deploy/configure-poll.py',
    'branding/dispatch_github.py':root/'dispatch_github.py',
    'branding/deploy/brand-dispatch.service':root/'deploy/brand-dispatch.service',
    'branding/deploy/brand-dispatch.timer':root/'deploy/brand-dispatch.timer',
    'branding/GITHUB_RUNNER.md':root/'GITHUB_RUNNER.md',
    'branding/MAIN_UPDATES.md':root/'MAIN_UPDATES.md',
    'branding/CENTRAL_BUILDER.md':root/'CENTRAL_BUILDER.md',
}
with zipfile.ZipFile(output,'w',zipfile.ZIP_DEFLATED) as archive:
    for name,path in sorted(entries.items()):
        info=zipfile.ZipInfo(name,(2026,1,1,0,0,0));info.create_system=3
        info.external_attr=(0o100700 if name=='install.sh' else 0o100644)<<16
        info.compress_type=zipfile.ZIP_DEFLATED;archive.writestr(info,path.read_bytes())
print(str(output)+'\nSHA256 '+hashlib.sha256(output.read_bytes()).hexdigest())
