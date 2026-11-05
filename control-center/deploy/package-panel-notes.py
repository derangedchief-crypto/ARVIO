#!/usr/bin/env python3
"""Build separate control-host and activation-worker notes updates."""
import hashlib
import json
from pathlib import Path
import subprocess
import sys
from zipfile import ZipFile, ZIP_DEFLATED

root=Path(__file__).resolve().parents[2]
out=Path(sys.argv[1]);out.mkdir(parents=True,exist_ok=True)
files={'src/Management.php':'control-center/src/Management.php','src/PanelBridge.php':'control-center/src/PanelBridge.php',
       'app.js':'control-center/public/assets/app.js','dashboard.js':'control-center/public/assets/dashboard.js','studio.css':'control-center/public/assets/studio.css'}
contents={name:(root/path).read_bytes() for name,path in files.items()}
manifest={name:{'before':sorted({hashlib.sha256(subprocess.check_output(['git','show',ref+':'+path],cwd=root)).hexdigest() for ref in ['47ae81f','aa94913','69a78b5','b15b7d4','8732b70']}),'after':hashlib.sha256(contents[name]).hexdigest()} for name,path in files.items()}
# The earlier device-notes installer appends these rules to studio.css.
import importlib.util
spec=importlib.util.spec_from_file_location('old_notes',root/'control-center/deploy/install-device-notes.py');old=importlib.util.module_from_spec(spec);spec.loader.exec_module(old)
css=subprocess.check_output(['git','show','69a78b5:control-center/public/assets/studio.css'],cwd=root)
manifest['studio.css']['before'].append(hashlib.sha256(css+old.STYLE.encode()).hexdigest())
# Earlier targeted installers could be applied with or without the online fix.
for ref in ['47ae81f','aa94913','69a78b5']:
    js=subprocess.check_output(['git','show',ref+':'+files['app.js']],cwd=root).decode()
    customer=js.replace("['Line username / customer', 'Workspace', 'Reference', 'Actions']","['Line username / customer', 'Workspace', 'Notes', 'Actions']").replace("[cellName(name, c.notes), c.tenant_name, c.external_reference || '—', actions]","[cellName(name), c.tenant_name, el('span',c.notes || '—','notes'), actions]")
    for variant in [js,customer]:
        device=variant.replace("['Device','Customer','App access','App contact','Actions']","['Device','Customer','Notes','App access','App contact','Actions']").replace('return[device,customer,badge(d.policy),connection,actions];',"return[device,customer,el('span',d.customer_notes||'—','notes'),badge(d.policy),connection,actions];")
        for code in [variant,device]:manifest['app.js']['before'].append(hashlib.sha256(code.encode()).hexdigest())
    management=subprocess.check_output(['git','show',ref+':'+files['src/Management.php']],cwd=root).replace(b'$fields = "r.*, TIMESTAMPDIFF',b'$fields = "r.*, c.notes AS customer_notes, TIMESTAMPDIFF')
    manifest['src/Management.php']['before'].append(hashlib.sha256(management).hexdigest())
contents['hashes.json']=json.dumps(manifest,indent=2).encode()
contents['install-panel-notes.py']=(root/'control-center/deploy/install-panel-notes.py').read_bytes()
contents['README.md']=(root/'control-center/deploy/IPTV-PANEL-NOTES.md').read_bytes()
with ZipFile(out/'IPTV-Panel-Notes-Control-0.42.0.zip','w',ZIP_DEFLATED) as z:
    for name,data in contents.items(): z.writestr(name,data)
with ZipFile(out/'IPTV-Panel-Notes-Worker.zip','w',ZIP_DEFLATED) as z:
    z.writestr('README.md',contents['README.md'])
    for name in ['panel_line_notes.py','install-panel-notes.py']:z.writestr(name,(root/'activation-code-worker'/name).read_bytes())
for path in out.glob('IPTV-Panel-Notes-*.zip'):print(hashlib.sha256(path.read_bytes()).hexdigest(),path)
