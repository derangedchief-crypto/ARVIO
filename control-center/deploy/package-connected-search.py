#!/usr/bin/env python3
import hashlib
import json
from pathlib import Path
import subprocess
import sys
from zipfile import ZipFile, ZIP_DEFLATED

root=Path(__file__).resolve().parents[2]
data=(root/'control-center/src/Management.php').read_bytes()
before=subprocess.check_output(['git','show','6b013ca:control-center/src/Management.php'],cwd=root)
manifest={'before':[hashlib.sha256(before).hexdigest()],'after':hashlib.sha256(data).hexdigest()}
with ZipFile(sys.argv[1],'w',ZIP_DEFLATED) as z:
    z.writestr('Management.php',data)
    z.writestr('hashes.json',json.dumps(manifest))
    z.writestr('install-connected-search.py',(root/'control-center/deploy/install-connected-search.py').read_bytes())
print(hashlib.sha256(Path(sys.argv[1]).read_bytes()).hexdigest())
