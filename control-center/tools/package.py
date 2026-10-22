#!/usr/bin/env python3
"""Deterministic, allowlisted delivery ZIP. Never recurse into runtime storage."""
from pathlib import Path
import hashlib
import re
import sys
import zipfile
import argparse

ROOT = Path(__file__).resolve().parents[1]
version = (ROOT / 'VERSION').read_text().strip()
if not re.fullmatch(r'\d+\.\d+\.\d+', version):
    raise SystemExit('Invalid backend version')
parser = argparse.ArgumentParser()
parser.add_argument('output', nargs='?')
parser.add_argument('--update', action='store_true', help='Preserve the installed config.php by omitting it from the ZIP')
args = parser.parse_args()
suffix = '-Update' if args.update else ''
out = Path(args.output) if args.output else ROOT / 'dist' / f'Extreme-Control-Center-{version}{suffix}.zip'
out.parent.mkdir(parents=True, exist_ok=True)
files = [ROOT / name for name in ['VERSION', 'README.md', 'VALIDATION.md']]
for folder in ['src', 'public', 'database', 'deploy', 'cli']:
    for file in sorted((ROOT / folder).rglob('*')):
        if file.is_symlink():
            raise SystemExit(f'Refusing symlink: {file}')
        if file.is_file():
            files.append(file)
entries = {file.relative_to(ROOT).as_posix(): file.read_bytes() for file in sorted(files)}
if not args.update:
    stub = (ROOT / 'tools/config.stub.php').read_bytes()
    if not re.search(rb"'password'\s*=>\s*''", stub):
        raise SystemExit('The shipped config.php must have a blank password')
    entries['config.php'] = stub
entries['LICENSE'] = (ROOT.parent / 'LICENSE').read_bytes()
entries['storage/.gitkeep'] = b''
manifest = ''.join(f'{hashlib.sha256(data).hexdigest()}  {name}\n' for name, data in sorted(entries.items()))
entries['MANIFEST.sha256'] = manifest.encode()
for name in entries:
    if name == 'config.local.php' or name.startswith(('dev/', 'tests/', 'test-results/')) or name.endswith(('.key', '.log', '.zip')):
        raise SystemExit(f'Refusing private/development file in delivery: {name}')
with zipfile.ZipFile(out, 'w', zipfile.ZIP_DEFLATED, compresslevel=9) as archive:
    storage = zipfile.ZipInfo('storage/', (2026, 1, 1, 0, 0, 0))
    storage.create_system = 3
    storage.external_attr = (0o40700 << 16) | 0x10
    archive.writestr(storage, b'')
    for name, data in sorted(entries.items()):
        entry = zipfile.ZipInfo(name, (2026, 1, 1, 0, 0, 0))
        entry.create_system = 3
        entry.external_attr = (0o100600 if name.startswith('storage/') or name == 'config.php' else 0o100644) << 16
        entry.compress_type = zipfile.ZIP_DEFLATED
        archive.writestr(entry, data)
print(f'{out}\nSHA256 {hashlib.sha256(out.read_bytes()).hexdigest()}\n{len(entries)} files; no runtime secrets or test fixtures')
