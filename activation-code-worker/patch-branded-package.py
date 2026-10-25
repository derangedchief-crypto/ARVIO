#!/usr/bin/env python3
"""Narrow in-place model patch. No database, environment or portal config writes."""
import ast
from pathlib import Path
import stat
import os
import tempfile

OLD=r'^com\.arvio\.tv(?:\.beta|\.test)?$'
NEW=r'^(?:com\.arvio\.tv(?:\.beta|\.test)?|com\.extreme\.brand\.b[a-f0-9]{32}(?:\.test)?)$'
BRAND=r'com\.extreme\.brand\.b[a-f0-9]{32}(?:\.test)?'

def write(path,updated):
    ast.parse(updated);before=path.stat();fd,name=tempfile.mkstemp(prefix='.branded-proof-',dir=path.parent)
    try:
        with os.fdopen(fd,'wb') as out:out.write(updated.encode());out.flush();os.fsync(out.fileno())
        os.chmod(name,stat.S_IMODE(before.st_mode));os.chown(name,before.st_uid,before.st_gid)
        for key in os.listxattr(path):os.setxattr(name,key,os.getxattr(path,key))
        os.replace(name,path)
    finally:Path(name).unlink(missing_ok=True)


def patch(path):
    if path.is_symlink() or not path.is_file():raise ValueError('Original regular worker source required.')
    raw=path.read_bytes();text=raw.decode('utf-8');tree=ast.parse(text)
    models=[node for node in tree.body if isinstance(node,ast.ClassDef) and node.name=='ManagementProofRequest']
    if len(models)!=1:raise ValueError('Proof model differs; preserve it for owner review.')
    fields=[n for n in models[0].body if isinstance(n,ast.AnnAssign) and isinstance(n.target,ast.Name) and n.target.id=='app_package']
    if len(fields)!=1 or not isinstance(fields[0].value,ast.Call):raise ValueError('Proof package field differs.')
    patterns=[k.value for k in fields[0].value.keywords if k.arg=='pattern']
    if len(patterns)!=1 or not isinstance(patterns[0],ast.Constant):raise ValueError('Proof package pattern differs.')
    node=patterns[0]
    if node.value==NEW:return False
    if node.value!=OLD:raise ValueError('Unknown package policy; no source overwritten.')
    literal=ast.get_source_segment(text,node)
    if not literal or text.count(literal)!=1:raise ValueError('Ambiguous package policy; stop for review.')
    updated=text.replace(literal,'r"'+NEW+'"',1);ast.parse(updated)
    write(path,updated)
    return True

def patch_module(path):
    if path.is_symlink() or not path.is_file():raise ValueError('Original regular proof module required.')
    text=path.read_bytes().decode('utf-8');tree=ast.parse(text);formats=[n for n in tree.body if isinstance(n,ast.FunctionDef) and n.name=='package_format']
    helper='\n\ndef package_format(app_package):\n    return isinstance(app_package,str) and (app_package in PACKAGES or re.fullmatch(r"'+BRAND+'",app_package) is not None)\n'
    if formats:
        if len(formats)==1 and ast.dump(formats[0],include_attributes=False)==ast.dump(ast.parse(helper).body[0],include_attributes=False):return False
        raise ValueError('Existing package policy differs; stop for review.')
    needle='app_package not in PACKAGES'
    packages=[n for n in tree.body if isinstance(n,ast.Assign) and any(isinstance(t,ast.Name) and t.id=='PACKAGES' for t in n.targets)]
    if len(packages)!=1 or text.count(needle)!=1 or not any(isinstance(n,ast.Import) and any(a.name=='re' for a in n.names) for n in tree.body):raise ValueError('Unknown original proof module.')
    lines=text.splitlines(keepends=True);at=sum(len(line) for line in lines[:packages[0].end_lineno])
    updated=(text[:at]+helper+text[at:]).replace(needle,'not package_format(app_package)',1);write(path,updated);return True


if __name__=='__main__':
    import sys
    try:
        changed=patch(Path(sys.argv[1]));changed=(patch_module(Path(sys.argv[2])) or changed) if len(sys.argv)>2 else changed
        print('Branded package proof validator updated.' if changed else 'Branded package proof validator already current.')
    except Exception:raise SystemExit('Package validator update refused. Original configuration/history were not replaced.')
