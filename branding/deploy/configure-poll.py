#!/usr/bin/env python3
"""Owner-only worker configuration. Generates a QUEUE token, never a signing key."""
import hashlib
import os
from pathlib import Path
import re
import secrets
import sys
import tempfile

URL='https://control.fecxbrfzku.net/api/brand-dispatch/queue'


def configure(path, source, automatic=None):
    if not re.fullmatch('[a-f0-9]{40}', source):raise ValueError('A full reviewed source SHA is required.')
    if path.is_symlink() or not path.is_file() or path.stat().st_mode & 0o077:raise ValueError('Existing private dispatcher environment must be a regular0600 file.')
    original=path.read_text();lines=original.splitlines()
    token_lines=[line for line in lines if line.startswith('BRAND_QUEUE_TOKEN=')]
    if len(token_lines)>1:raise ValueError('Duplicate queue tokens need owner review.')
    token=token_lines[0].split('=',1)[1] if token_lines else secrets.token_hex(32)
    if not re.fullmatch('[a-f0-9]{64}',token):raise ValueError('Existing queue token needs owner review; it was not replaced.')
    if not any(line.startswith('GH_TOKEN=') and line[len('GH_TOKEN='):].strip() for line in lines):raise ValueError('Configure the existing GitHub token privately first.')
    backup=path.with_name(path.name+'.before-poll')
    if backup.is_symlink():raise ValueError('Unsafe backup path.')
    if not backup.exists():
        with open(backup,'x',opener=lambda p,f:os.open(p,f,0o600)) as stream:stream.write(original)
    changes={'BRAND_SOURCE_SHA':source,'BRAND_QUEUE_URL':URL,'BRAND_QUEUE_TOKEN':token}
    if automatic is not None:changes['BRAND_AUTO_PUBLISH_TESTING']='true' if automatic else 'false'
    kept=[line for line in lines if line.split('=',1)[0] not in changes]
    content='\n'.join(kept+[key+'='+value for key,value in changes.items()])+'\n'
    with tempfile.NamedTemporaryFile(mode='w',dir=path.parent,delete=False) as stream:
        temporary=Path(stream.name)
        try:
            os.chmod(temporary,0o600);stream.write(content);stream.flush();os.fsync(stream.fileno());os.replace(temporary,path)
        finally:temporary.unlink(missing_ok=True)
    return hashlib.sha256(token.encode()).hexdigest()


if __name__=='__main__':
    try:
        if os.getuid()!=0 or len(sys.argv) not in (2,3) or (len(sys.argv)==3 and sys.argv[2]!='--enable-testing-publication'):raise ValueError('Run as root: python3 configure-poll.py FULL_REVIEWED_SOURCE_SHA [--enable-testing-publication]')
        value=configure(Path('/etc/brand-dispatch.env'),sys.argv[1],True if len(sys.argv)==3 else None)
        print('Private worker environment configured. GitHub token preserved; no service started.')
        if len(sys.argv)==3:print('Automatic TESTING publication opt-in enabled. Production polling remains forbidden.')
        print('Queue token SHA256 (safe to share): '+value)
    except Exception:
        raise SystemExit('Queue configuration stopped. Check root access, private environment permissions and existing settings; no secret printed.')
