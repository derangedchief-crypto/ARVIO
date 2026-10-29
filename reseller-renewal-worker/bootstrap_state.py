"""Installer-only private state initialization. Never resets an existing ledger."""
import hashlib
import json
import os
from pathlib import Path
import pwd
import re
import secrets
import stat
import sys


def provision(environment,state,export,user):
    environment=Path(environment);state=Path(state);export=Path(export);account=pwd.getpwnam(user)
    for path in [environment,state,export,state/'renewal.key',state/'renewals.sqlite']:
        if path.is_symlink():raise RuntimeError('Unsafe renewal setup path; original files retained')
    if environment.exists():
        if environment.stat().st_uid!=0 or stat.S_IMODE(environment.stat().st_mode)!=0o600:raise RuntimeError('Renewal bridge environment must remain root0600')
        lines=[line for line in environment.read_text().splitlines() if line and not line.startswith('#')]
        values=dict(line.split('=',1) for line in lines)
        if len(lines)!=3 or set(values)!={'RENEWAL_BRIDGE_SECRET','RENEWAL_STATE_DIR','RENEWAL_LEDGER_KEY_SHA256'} or values['RENEWAL_STATE_DIR']!=str(state) or not re.fullmatch('[a-f0-9]{64}',values['RENEWAL_BRIDGE_SECRET']) or not re.fullmatch('[a-f0-9]{64}',values['RENEWAL_LEDGER_KEY_SHA256']):raise RuntimeError('Existing renewal bridge environment differs; it was not replaced')
        if not (state/'renewal.key').is_file() or not (state/'renewals.sqlite').is_file():raise RuntimeError('Restore the original renewal state; no replacement identity is generated')
        key=(state/'renewal.key').read_bytes()
        if len(key)!=32 or hashlib.sha256(key).hexdigest()!=values['RENEWAL_LEDGER_KEY_SHA256']:raise RuntimeError('Original renewal ledger anchor does not match')
    else:
        if state.exists() and any(state.iterdir()):raise RuntimeError('Existing renewal state without its environment must be restored/reviewed, never reset')
        state.mkdir(mode=0o700,parents=True,exist_ok=True);os.chown(state,account.pw_uid,account.pw_gid);state.chmod(0o700)
        key=secrets.token_bytes(32)
        for name,data in [('renewal.key',key),('renewals.sqlite',b'')]:
            path=state/name
            with open(path,'xb',opener=lambda p,f:os.open(p,f|os.O_NOFOLLOW,0o600)) as stream:stream.write(data);stream.flush();os.fsync(stream.fileno())
            os.chown(path,account.pw_uid,account.pw_gid)
        values={'RENEWAL_BRIDGE_SECRET':secrets.token_hex(32),'RENEWAL_STATE_DIR':str(state),'RENEWAL_LEDGER_KEY_SHA256':hashlib.sha256(key).hexdigest()}
        with open(environment,'x',opener=lambda p,f:os.open(p,f|os.O_NOFOLLOW,0o600)) as out:
            out.write(''.join(k+'='+v+'\n' for k,v in values.items()));out.flush();os.fsync(out.fileno())
    if state.stat().st_uid!=account.pw_uid or stat.S_IMODE(state.stat().st_mode)!=0o700:raise RuntimeError('Original renewal state permissions need review')
    for path in [state/'renewal.key',state/'renewals.sqlite']:
        if path.stat().st_uid!=account.pw_uid or stat.S_IMODE(path.stat().st_mode)!=0o600:raise RuntimeError('Original renewal state file permissions need review')
    document={'base_url':'https://resellerworker.extremeiptv.net','shared_secret':values['RENEWAL_BRIDGE_SECRET'],'mode':'renewals'}
    if export.exists():
        if export.stat().st_uid!=0 or stat.S_IMODE(export.stat().st_mode)!=0o600 or json.loads(export.read_text())!=document:raise RuntimeError('Existing private export differs; it was not replaced')
    else:
        with open(export,'x',opener=lambda p,f:os.open(p,f|os.O_NOFOLLOW,0o600)) as out:json.dump(document,out)


if __name__=='__main__':
    try:
        if len(sys.argv)!=5:raise RuntimeError('Use the renewal-worker installer')
        provision(*sys.argv[1:])
    except Exception as error:
        print(str(error) if isinstance(error,RuntimeError) else 'Private renewal state setup failed; values omitted',file=sys.stderr);sys.exit(1)
