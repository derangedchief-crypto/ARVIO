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

REPO = 'derangedchief-crypto/ARVIO'
WORKFLOW = 'central-brand-builder.yml'


def gh(args):
    result = subprocess.run(['gh', *args], stdout=subprocess.PIPE, stderr=subprocess.PIPE, timeout=60)
    if result.returncode:raise RuntimeError('GitHub dispatch unavailable; check workflow registration, token and owner policy.')
    return result.stdout.decode()


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
        actual = json.loads(gh(['api', f'repos/{REPO}/git/ref/heads/{ref}']))['object']['sha']
        if actual != source:raise ValueError('Dispatch branch moved; review and repin before dispatch.')
        runs = json.loads(gh(['run','list','--repo',REPO,'--workflow',WORKFLOW,'--limit','100','--json','status']))
        if any(run['status'] != 'completed' for run in runs):
            print('A branded operation is active or awaiting approval; no additional dispatch.')
            return
        gh(['workflow','run',WORKFLOW,'--repo',REPO,'--ref',ref,
            '-f','mode='+args.mode,'-f','channel='+args.channel,'-f','build_id='+args.build_id])
        print('Branded operation dispatched. Signing stays inside GitHub.')


def main():
    os.umask(0o077)
    parser=argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--mode', choices=['build','publish','reconcile'], default='build')
    parser.add_argument('--channel', choices=['testing','production'], default='testing')
    parser.add_argument('--build-id', default='')
    args=parser.parse_args()
    if args.mode=='reconcile' and not args.build_id:parser.error('Reconcile needs an exact build ID.')
    try:dispatch(args)
    except Exception:
        print('Dispatch stopped; review owner source/ref, GitHub access and private configuration.',file=sys.stderr)
        return 1
    return 0


if __name__=='__main__':sys.exit(main())
