#!/usr/bin/env python3
import hashlib
import json
from pathlib import Path
import subprocess
import sys
from zipfile import ZipFile,ZIP_DEFLATED

root=Path(__file__).resolve().parents[2]
js=(root/'control-center/public/assets/workspace-library.js').read_bytes()
style=(root/'control-center/public/assets/studio.css').read_text().split('/* layout-template-controls:start */',1)[1].split('/* layout-template-controls:end */',1)[0].encode()
before=subprocess.check_output(['git','show','81ad835:control-center/public/assets/workspace-library.js'],cwd=root)
manifest={'before':hashlib.sha256(before).hexdigest(),'js':hashlib.sha256(js).hexdigest(),'css':hashlib.sha256(style).hexdigest()}
with ZipFile(sys.argv[1],'w',ZIP_DEFLATED) as z:
    z.writestr('workspace-library.js',js);z.writestr('layout-template-controls.css',style);z.writestr('manifest.json',json.dumps(manifest))
    z.writestr('install-layout-drag.py',(root/'control-center/deploy/install-layout-drag.py').read_bytes())
