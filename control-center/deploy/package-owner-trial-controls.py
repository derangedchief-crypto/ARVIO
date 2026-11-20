#!/usr/bin/env python3
import hashlib
import json
from pathlib import Path
import subprocess
import sys
from zipfile import ZipFile,ZIP_DEFLATED

root=Path(__file__).resolve().parents[2]
def old(ref,path):return subprocess.check_output(['git','show',ref+':'+path],cwd=root)
def digest(data):return hashlib.sha256(data).hexdigest()
jspath='control-center/public/assets/trial-self-service.js';phppath='control-center/src/TrialSelfService.php'
js=(root/jspath).read_bytes();php=(root/phppath).read_bytes()
manifest={'before_js':[digest(old(ref,jspath)) for ref in ['5006ae5','76d06df']],'before_php':digest(old('76d06df',phppath)),'after_js':digest(js),'after_php':digest(php)}
with ZipFile(sys.argv[1],'w',ZIP_DEFLATED) as z:
    z.writestr('manifest.json',json.dumps(manifest));z.writestr('trial-self-service.js',js);z.writestr('TrialSelfService.php',php)
    z.write(root/'control-center/deploy/install-owner-trial-controls.py','install-owner-trial-controls.py')
