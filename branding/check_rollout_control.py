#!/usr/bin/env python3
"""Read-only publication preflight when reseller fanout is enabled."""
import json
import re
from urllib.request import Request,urlopen

def ready(value):
    if not isinstance(value,dict) or value.get('configured') is not True or value.get('installed') is not True:return False
    version=value.get('version')
    return isinstance(version,str) and re.fullmatch(r'\d+\.\d+\.\d+',version) is not None and tuple(map(int,version.split('.')))>=(0,36,0)

def main():
    request=Request('https://control.fecxbrfzku.net/api/status',headers={'Accept':'application/json','Cache-Control':'no-cache','User-Agent':'Approved-release-readiness/1.0'})
    with urlopen(request,timeout=15) as response:
        raw=response.read(65537)
        if response.status!=200 or len(raw)>65536:raise ValueError('Control status unavailable')
        if not ready(json.loads(raw)):raise ValueError('Install control-center 0.36.0+ before approving a release with reseller fanout')
    print('Compatible reseller rollout control is installed (read-only check).')

if __name__=='__main__':
    try:main()
    except Exception:raise SystemExit('Reseller rollout preflight failed. Install/verify the matching website update first; no customer publication authorized.')
