#!/usr/bin/env python3
"""Worker-host dispatcher. Holds no signing key; requires a scoped Actions token.

Environment: BRAND_SOURCE_SHA, BRAND_WORKFLOW_REF, BRAND_DISPATCH_STATE, GH_TOKEN.
The operator must protect the chosen ref and configure workflow environments first.
Nothing runs on import. Timer installation/publication are separate opt-ins.
"""
import argparse
import fcntl
import json
import os
from pathlib import Path
import re
import subprocess
import sys
import time
import tempfile
import urllib.parse
import urllib.request

REPO = 'derangedchief-crypto/ARVIO'
WORKFLOW = 'central-brand-builder.yml'


def gh(args):
    result = subprocess.run(['gh', *args], stdout=subprocess.PIPE, stderr=subprocess.PIPE, timeout=60)
    if result.returncode:raise RuntimeError('GitHub dispatch unavailable; check workflow registration, token and owner policy.')
    return result.stdout.decode()


class NoRedirect(urllib.request.HTTPRedirectHandler):
    def redirect_request(self, *args, **kwargs):
        raise ValueError('Queue redirects are forbidden.')


def queue(source, channel, mode='build'):
    url=os.environ.get('BRAND_QUEUE_URL','');token=os.environ.get('BRAND_QUEUE_TOKEN','')
    parsed=urllib.parse.urlsplit(url)
    if parsed.scheme!='https' or not parsed.hostname or parsed.username or parsed.password or parsed.port not in (None,443) or parsed.path!='/api/brand-dispatch/queue' or parsed.query or parsed.fragment:
        raise ValueError('Configure the HTTPS website queue endpoint.')
    if not re.fullmatch('[a-f0-9]{64}',token):raise ValueError('Configure the dedicated read-only queue token.')
    query={'source_sha':source,'channel':channel}
    if mode=='publish':query['mode']='publish'
    elif mode!='build':raise ValueError('Unsupported automatic operation.')
    request=urllib.request.Request(url+'?'+urllib.parse.urlencode(query),headers={'Authorization':'Bearer '+token,'Accept':'application/json'})
    with urllib.request.build_opener(NoRedirect).open(request,timeout=15) as response:
        if response.status!=200:raise ValueError('Queue check unavailable.')
        raw=response.read(2049)
    if len(raw)>2048:raise ValueError('Oversized queue response.')
    value=json.loads(raw)
    if value=={'state':'no_job'}:return None
    keys={'state','build_id','channel','source_sha'}|({'mode'} if mode=='publish' else set())
    if not isinstance(value,dict) or set(value)!=keys or (mode=='publish' and value.get('mode')!='publish') or value['state']!='queued' or value['channel']!=channel or value['source_sha']!=source or not isinstance(value['build_id'],str) or not re.fullmatch('[a-f0-9]{32}',value['build_id']):
        raise ValueError('Invalid pinned queue hint.')
    return value['build_id']


def fence(state, source, channel, build_id):
    path=state/'poll-dispatch.json';now=time.time()
    if path.is_symlink():raise ValueError('Invalid dispatch fence.')
    if path.exists():
        if path.stat().st_mode & 0o077 or path.stat().st_size>2048:raise ValueError('Invalid dispatch fence.')
        previous=json.loads(path.read_text())
        if not isinstance(previous,dict) or type(previous.get('until')) not in (int,float):raise ValueError('Invalid dispatch fence.')
        if previous['until']>now:return False
    # Persist BEFORE the API call: ambiguous dispatch failures must not cause a
    # fresh workflow every30seconds. GitHub active-run checks add a second fence.
    with tempfile.NamedTemporaryFile(mode='w',dir=state,delete=False) as stream:
        temp=Path(stream.name)
        try:
            os.chmod(temp,0o600)
            json.dump({'until':now+300,'build_id':build_id,'source_sha':source,'channel':channel},stream)
            stream.flush();os.fsync(stream.fileno());os.replace(temp,path)
        finally:temp.unlink(missing_ok=True)
    return True


def dispatch(args):
    source = os.environ.get('BRAND_SOURCE_SHA', '')
    ref = os.environ.get('BRAND_WORKFLOW_REF', '')
    if not re.fullmatch('[a-f0-9]{40}', source) or not re.fullmatch('[a-zA-Z0-9][a-zA-Z0-9._/-]{0,150}', ref) or '..' in ref:
        raise ValueError('Configure an owner-reviewed source and protected branch ref.')
    if args.build_id and not re.fullmatch('[a-f0-9]{32}', args.build_id):raise ValueError('Invalid build ID.')
    state = Path(os.environ['BRAND_DISPATCH_STATE']).resolve(strict=True)
    if state.stat().st_mode & 0o077:raise ValueError('Dispatcher state must be private (0700).')
    with (state/'.dispatch.lock').open('a') as lock:
        fcntl.flock(lock, fcntl.LOCK_EX | fcntl.LOCK_NB)
        polling=getattr(args,'poll',False)
        mode=args.mode
        build_id=args.build_id
        if polling:
            if args.mode!='build' or args.channel!='testing' or build_id:raise ValueError('Polling supports build-only testing with server-selected jobs.')
            automatic=os.environ.get('BRAND_AUTO_PUBLISH_TESTING','false')
            if automatic not in ('true','false'):raise ValueError('Invalid automatic testing publication policy.')
            if automatic=='true':mode='publish'
            build_id=queue(source,args.channel,mode)
            if build_id is None:
                print('No eligible queued testing build; GitHub was not contacted.')
                return
        actual = json.loads(gh(['api', f'repos/{REPO}/git/ref/heads/{ref}']))['object']['sha']
        if actual != source:raise ValueError('Dispatch branch moved; review and repin before dispatch.')
        runs = json.loads(gh(['run','list','--repo',REPO,'--workflow',WORKFLOW,'--limit','100','--json','status']))
        if any(run['status'] != 'completed' for run in runs):
            print('A branded operation is active or awaiting approval; no additional dispatch.')
            return
        if polling and not fence(state,source,args.channel,build_id):
            print('Recent dispatch is fenced; no additional workflow.')
            return
        gh(['workflow','run',WORKFLOW,'--repo',REPO,'--ref',ref,
            '-f','mode='+mode,'-f','channel='+args.channel,'-f','build_id='+build_id])
        print('Branded operation dispatched. Signing stays inside GitHub.')


def main():
    os.umask(0o077)
    parser=argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--mode', choices=['build','publish','reconcile'], default='build')
    parser.add_argument('--channel', choices=['testing','production'], default='testing')
    parser.add_argument('--build-id', default='')
    parser.add_argument('--poll', action='store_true', help='Read-only website queue check before contacting GitHub; testing build-only.')
    args=parser.parse_args()
    if args.mode=='reconcile' and not args.build_id:parser.error('Reconcile needs an exact build ID.')
    try:dispatch(args)
    except Exception:
        print('Dispatch stopped; review owner source/ref, GitHub access and private configuration.',file=sys.stderr)
        return 1
    return 0


if __name__=='__main__':sys.exit(main())
