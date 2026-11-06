#!/usr/bin/env python3
import hashlib
import json
from pathlib import Path
import subprocess
import sys
from zipfile import ZipFile, ZIP_DEFLATED

root=Path(__file__).resolve().parents[2]
files={'Auth.php':('src/Auth.php','    public function loginPanel(','    private function issueSession('),
       'Application.php':('src/Application.php',"        if ($method === 'POST' && $path === '/api/login') {","        if($method==='POST'&&$path==='/api/device/enrollment/challenge')"),
       'app.js':('public/assets/app.js','function loginForm(','function setupForm(')}
patches={}
for name,(path,start,end) in files.items():
    before=subprocess.check_output(['git','show','b3037be:control-center/'+path],cwd=root).decode()
    after=(root/'control-center'/path).read_text()
    patches[name]={'before':before[before.index(start):before.index(end)],'after':after[after.index(start):after.index(end)]}
with ZipFile(sys.argv[1],'w',ZIP_DEFLATED) as z:
    z.writestr('patches.json',json.dumps(patches))
    z.writestr('install-reseller-login.py',(root/'control-center/deploy/install-reseller-login.py').read_bytes())
print(hashlib.sha256(Path(sys.argv[1]).read_bytes()).hexdigest())
