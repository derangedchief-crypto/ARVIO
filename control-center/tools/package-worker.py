#!/usr/bin/env python3
"""Source-only bridge ZIP; never include the installed worker .env or credentials."""
from pathlib import Path
import hashlib
import zipfile

root = Path(__file__).resolve().parents[2]
output = root / 'control-center/dist/Extreme-Activation-Management-Bridge-Update.zip'
output.parent.mkdir(parents=True, exist_ok=True)
names = ['main.py', 'config.py', 'management_enrollment.py', 'primary_line_status.py', 'update-management-bridge.sh', 'MANAGEMENT-BRIDGE-UPDATE.txt']
with zipfile.ZipFile(output, 'w', zipfile.ZIP_DEFLATED, compresslevel=9) as archive:
    for name in names:
        source = root / 'activation-code-worker' / name
        info = zipfile.ZipInfo(name, (2026, 1, 1, 0, 0, 0))
        info.create_system = 3
        info.external_attr = (0o100755 if name.endswith('.sh') else 0o100644) << 16
        info.compress_type = zipfile.ZIP_DEFLATED
        archive.writestr(info, source.read_bytes())
    info = zipfile.ZipInfo('LICENSE', (2026, 1, 1, 0, 0, 0)); info.external_attr = 0o100644 << 16
    archive.writestr(info, (root / 'LICENSE').read_bytes())
print(str(output))
print('SHA256', hashlib.sha256(output.read_bytes()).hexdigest())
