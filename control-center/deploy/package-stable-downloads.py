#!/usr/bin/env python3
import hashlib,json,subprocess,sys
from pathlib import Path
from zipfile import ZipFile,ZIP_DEFLATED
root=Path(__file__).resolve().parents[2]
paths={'js':'control-center/public/assets/app-branding.js','php':'control-center/src/BrandDownloads.php'}
manifest={};data={}
for key,path in paths.items():
    data[key]=(root/path).read_bytes()
    manifest['before_'+key]=hashlib.sha256(subprocess.check_output(['git','show','bd74275:'+path],cwd=root)).hexdigest()
    manifest['after_'+key]=hashlib.sha256(data[key]).hexdigest()
with ZipFile(sys.argv[1],'w',ZIP_DEFLATED) as z:
    z.writestr('manifest.json',json.dumps(manifest));z.writestr('app-branding.js',data['js']);z.writestr('BrandDownloads.php',data['php']);z.write(root/'control-center/deploy/install-stable-downloads.py','install-stable-downloads.py')
