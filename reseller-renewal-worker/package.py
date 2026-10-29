#!/usr/bin/env python3
"""Build the NEW service bundle. Private .env/config.py are copied ON HOST only."""
import argparse
import hashlib
from pathlib import Path
import re
from zipfile import ZipFile,ZipInfo,ZIP_DEFLATED
from build_native import build

root=Path(__file__).resolve().parent;repo=root.parent
p=argparse.ArgumentParser();p.add_argument('--source',required=True,help='Supplied main.py or original ZIP (source only, not config)');p.add_argument('--output');args=p.parse_args()
source=Path(args.source)
if source.suffix.lower()=='.zip':
    with ZipFile(source) as z:data=z.read('new auth/main.py')
else:data=source.read_bytes()
version=(repo/'control-center/VERSION').read_text().strip()
if not re.fullmatch(r'\d+\.\d+\.\d+',version):raise SystemExit('Invalid delivery version')
out=Path(args.output) if args.output else repo/'control-center/dist'/('Extreme-Renewal-Worker-'+version+'.zip');out.parent.mkdir(parents=True,exist_ok=True)
entries={}
for name in ['main.py','adapter.py','bootstrap_state.py','install.sh','iptv-renewal-worker.service','configure_proxy.py','nginx-location.conf','README.md']:
    path=root/name
    if path.is_symlink():raise SystemExit('Refusing source symlink')
    entries[name]=path.read_bytes()
for name in ['branded_renewals.py','branded_renewals_runtime.py','renewal_packages.py']:
    path=repo/'reseller-auth-worker'/name
    if path.is_symlink():raise SystemExit('Refusing shared source symlink')
    entries[name]=path.read_bytes()
entries['native_panel.py']=build(data).encode();entries['LICENSE']=(repo/'LICENSE').read_bytes()
entries['MANIFEST.sha256']=''.join(hashlib.sha256(v).hexdigest()+'  '+k+'\n' for k,v in sorted(entries.items())).encode()
with ZipFile(out,'w',ZIP_DEFLATED,compresslevel=9) as z:
    for name,value in sorted(entries.items()):
        item=ZipInfo(name,(2026,1,3,0,0,0));item.create_system=3;item.external_attr=(0o100755 if name.endswith('.sh') else 0o100644)<<16;item.compress_type=ZIP_DEFLATED;z.writestr(item,value)
print(out);print('SHA256',hashlib.sha256(out.read_bytes()).hexdigest());print('NEW renewal service; no private config/env, trial module or legacy portal app shipped.')
