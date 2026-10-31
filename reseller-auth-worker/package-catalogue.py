#!/usr/bin/env python3
"""Allowlisted READ-ONLY source bundle; no main/config/trial/paid worker files."""
from pathlib import Path
import hashlib
import re
import sys
import zipfile

root=Path(__file__).resolve().parent
version=(root.parent/'control-center/VERSION').read_text().strip()
if not re.fullmatch(r'\d+\.\d+\.\d+',version):raise SystemExit('Invalid catalogue delivery version')
out=Path(sys.argv[1]) if len(sys.argv)>1 else root.parent/'control-center/dist'/('Extreme-Renewal-Catalogue-ReadOnly-'+version+'.zip')
out.parent.mkdir(parents=True,exist_ok=True)
names=['install-catalogue.sh','catalogue_source.py','branded_renewal_catalogue.py','renewal_packages.py','CATALOGUE-ONLY.md']
if any((root/name).is_symlink() for name in names) or (root.parent/'LICENSE').is_symlink():raise SystemExit('Refusing symlink in catalogue delivery source')
entries={name:(root/name).read_bytes() for name in names};entries['LICENSE']=(root.parent/'LICENSE').read_bytes()
entries['MANIFEST.sha256']=''.join(hashlib.sha256(data).hexdigest()+'  '+name+'\n' for name,data in sorted(entries.items())).encode()
with zipfile.ZipFile(out,'w',zipfile.ZIP_DEFLATED,compresslevel=9) as archive:
 for name,data in sorted(entries.items()):
  info=zipfile.ZipInfo(name,(2026,1,2,0,0,0));info.create_system=3;info.external_attr=(0o100755 if name.endswith('.sh') else 0o100644)<<16;info.compress_type=zipfile.ZIP_DEFLATED;archive.writestr(info,data)
print(str(out));print('SHA256',hashlib.sha256(out.read_bytes()).hexdigest());print('Catalogue only; no config, credentials, trial module or paid-renewal adapter included.')
