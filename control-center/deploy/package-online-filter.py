#!/usr/bin/env python3
"""Package only the online-card fix over the published 0.42.0 workspace."""
import hashlib
import json
from pathlib import Path
import subprocess
import sys
from zipfile import ZipFile, ZIP_DEFLATED

root=Path(__file__).resolve().parents[2]
files={'src/Management.php':'control-center/src/Management.php',
       'app.js':'control-center/public/assets/app.js',
       'dashboard.js':'control-center/public/assets/dashboard.js'}
contents={name:(root/path).read_bytes() for name,path in files.items()}
hashes={name:{'before':hashlib.sha256(subprocess.check_output(['git','show','47ae81f:'+path],cwd=root)).hexdigest(),
              'after':hashlib.sha256(contents[name]).hexdigest()} for name,path in files.items()}
contents['hashes.json']=json.dumps(hashes,indent=2).encode()
contents['install-online-filter.py']=(root/'control-center/deploy/install-online-filter.py').read_bytes()
with ZipFile(sys.argv[1],'w',ZIP_DEFLATED) as z:
    for name,data in contents.items(): z.writestr(name,data)
print(hashlib.sha256(Path(sys.argv[1]).read_bytes()).hexdigest())
