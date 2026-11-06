#!/usr/bin/env python3
import hashlib
import json
from pathlib import Path
import subprocess
import sys
from zipfile import ZipFile,ZIP_DEFLATED

root=Path(__file__).resolve().parents[2]
app=(root/'control-center/src/Application.php').read_text()
anchor="        if($method==='GET'&&$path==='/api/category-layout-templates')Http::json($this->layouts->listing($actor,$_GET));"
start=app.index("        if($method==='GET'&&$path==='/api/layout-catalogue')")
manifest={'anchor':anchor,'routes':app[start:app.index(anchor)],'library_before':hashlib.sha256(subprocess.check_output(['git','show','e1fd0e4:control-center/public/assets/workspace-library.js'],cwd=root)).hexdigest()}
with ZipFile(sys.argv[1],'w',ZIP_DEFLATED) as z:
    z.writestr('manifest.json',json.dumps(manifest))
    for name,path in {'MasterLayoutCatalogue.php':'src/MasterLayoutCatalogue.php','workspace-library.js':'public/assets/workspace-library.js','install-master-layout.py':'deploy/install-master-layout.py','README.md':'deploy/MASTER-LAYOUT.md'}.items():z.writestr(name,(root/'control-center'/path).read_bytes())
