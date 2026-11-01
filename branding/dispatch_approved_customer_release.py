#!/usr/bin/env python3
"""One-time source/time/readiness-fenced dispatch. Never builds/uploads APKs itself."""
import argparse
from datetime import datetime,timezone
import json
import re
import subprocess
from urllib.request import Request,urlopen
from zoneinfo import ZoneInfo

REPO='derangedchief-crypto/ARVIO'
VERSION='2.0.131'
CODE=447

def allowed_time(now):
    local=now.astimezone(ZoneInfo('America/Toronto'))
    return local.date().isoformat()=='2026-11-01' and local.hour==3 and 30<=local.minute<40

def check_ready(expected,actual,operator,website,gradle):
    if not re.fullmatch('[a-f0-9]{40}',expected) or actual!=expected or operator!=expected:
        raise ValueError('Approved main source/operator changed; publication refused.')
    if website.get('configured') is not True or website.get('installed') is not True:
        raise ValueError('Website unavailable or unconfigured.')
    version=website.get('version','')
    if not isinstance(version,str) or not re.fullmatch(r'\d+\.\d+\.\d+',version) or tuple(map(int,version.split('.')))<(0,37,0):
        raise ValueError('Install the compatible recovery website first.')
    if not re.search(r'^val platformVersionName = "'+re.escape(VERSION)+r'"$',gradle,re.M) or not re.search(r'^val platformVersionCode = '+str(CODE)+r'$',gradle,re.M):
        raise ValueError('Unexpected customer release version.')

def gh(*args):
    return subprocess.check_output(['gh',*args],text=True,timeout=60).strip()

def main():
    parser=argparse.ArgumentParser();parser.add_argument('--expected-main',required=True)
    args=parser.parse_args()
    if not allowed_time(datetime.now(timezone.utc)):
        raise ValueError('Outside the one-time approved Toronto publication window; do not catch up automatically.')
    actual=json.loads(gh('api','repos/'+REPO+'/git/ref/heads/main'))['object']['sha']
    operator=gh('variable','get','BRAND_BUILDER_OPERATOR_SHA','--repo',REPO)
    if gh('variable','get','BRAND_MAIN_ROLLOUT_ENABLED','--repo',REPO)!='true' or gh('variable','get','BRAND_MAIN_UPDATE_CHANNEL','--repo',REPO)!='production':
        raise ValueError('Approved production reseller fanout is not configured.')
    with urlopen(Request('https://control.fecxbrfzku.net/api/status',headers={'Cache-Control':'no-cache'}),timeout=20) as response:
        raw=response.read(65537)
        if response.status!=200 or len(raw)>65536:raise ValueError('Website readiness unavailable.')
        website=json.loads(raw)
    # Read the approved source from GitHub, never a dirty scheduled checkout.
    import base64
    value=json.loads(gh('api','repos/'+REPO+'/contents/app/build.gradle.kts?ref='+args.expected_main))
    gradle=base64.b64decode(value['content']).decode()
    check_ready(args.expected_main,actual,operator,website,gradle)
    existing=subprocess.run(['gh','api','repos/'+REPO+'/releases/tags/v'+VERSION],capture_output=True,text=True,timeout=60)
    if existing.returncode==0 or '(HTTP 404)' not in existing.stderr:
        raise ValueError('Release already exists or lookup unavailable; no replacement/retry authorized.')
    active=json.loads(gh('run','list','--repo',REPO,'--workflow','publish-release.yml','--limit','100','--json','status,headSha,createdAt'))
    if any(r['headSha']==args.expected_main and r['status']!='completed' for r in active):
        raise ValueError('Publication already queued/running; do not dispatch twice.')
    # Recheck time and source at the final mutation; workflow enforces source too.
    if not allowed_time(datetime.now(timezone.utc)) or json.loads(gh('api','repos/'+REPO+'/git/ref/heads/main'))['object']['sha']!=args.expected_main:
        raise ValueError('Publication time/source changed during preflight.')
    print(gh('workflow','run','publish-release.yml','--repo',REPO,'--ref','main',
        '-f','confirmed_publication=true','-f','expected_source_sha='+args.expected_main,
        '-f','release_notes=Device recovery, startup responsiveness, keyboard and network reliability improvements. Saved app data and existing app identities are retained.'))
    print('Approved customer workflow dispatched once; production reseller updates follow only verified customer publication.')

if __name__=='__main__':
    try:main()
    except Exception as e:raise SystemExit('STOPPED: '+str(e))
