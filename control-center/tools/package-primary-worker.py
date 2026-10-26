#!/usr/bin/env python3
from pathlib import Path
from datetime import datetime, timedelta
import hashlib
import zipfile

root=Path(__file__).resolve().parents[2]
version=(root/'control-center/VERSION').read_text().strip()
parts=list(map(int,version.split('.')))
stamp=datetime(2026,1,1)+timedelta(seconds=((parts[0]*1000+parts[1])*1000+parts[2])*2)
date=(stamp.year,stamp.month,stamp.day,stamp.hour,stamp.minute,stamp.second)
out=root/'control-center/dist'/f'Primary-Line-Guard-Worker-{version}.zip'
out.parent.mkdir(parents=True,exist_ok=True)
with zipfile.ZipFile(out,'w',zipfile.ZIP_DEFLATED,compresslevel=9) as z:
    for name in ['primary_line_status.py','patch-primary-status.py','upgrade-primary-status.sh','PRIMARY-STATUS-UPDATE.txt']:
        info=zipfile.ZipInfo(name,date);info.external_attr=(0o100755 if name.endswith('.sh') else 0o100644)<<16;info.compress_type=zipfile.ZIP_DEFLATED
        z.writestr(info,(root/'activation-code-worker'/name).read_bytes())
    info=zipfile.ZipInfo('LICENSE',date);info.external_attr=0o100644<<16;z.writestr(info,(root/'LICENSE').read_bytes())
print(out);print('SHA256',hashlib.sha256(out.read_bytes()).hexdigest())
