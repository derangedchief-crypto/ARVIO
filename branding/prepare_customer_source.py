#!/usr/bin/env python3
"""Pin brand source before customer publication; never modify an existing tag."""
import argparse
import os
from pathlib import Path
import re
import subprocess
from central_builder import REPO, ToolFailure, command
from rollout_main import gh_json, immutable_tag, platform, verify_immutable_tag


def customer_tag_matches(source,version):
    tag='v'+version
    result=subprocess.run(['gh','api','repos/'+REPO+'/git/ref/tags/'+tag],capture_output=True,timeout=60)
    if result.returncode:
        if b'(HTTP 404)' in result.stderr:return
        raise RuntimeError('Customer tag lookup unavailable; publication refused.')
    if gh_json('commits/'+tag).get('sha')!=source:
        raise ValueError('Customer version already belongs to another commit; increase the version first.')


def metadata():
    value=platform(Path('app/build.gradle.kts').read_text())
    if value is None:raise ValueError('Customer source is not compatible with branded builds.')
    return value


def prepare(source,ref,verify_only=False):
    if ref!='refs/heads/main' or not re.fullmatch('[a-f0-9]{40}',source):
        raise ValueError('Customer publication requires an exact reviewed main commit.')
    if command(['git','rev-parse','HEAD']).strip()!=source:
        raise ValueError('Checkout differs from the approved customer source.')
    version,_=metadata()
    customer_tag_matches(source,version)
    tag='brand-source-'+version+'-'+source
    if not verify_only:
        if gh_json('git/ref/heads/main').get('object',{}).get('sha')!=source:
            raise ValueError('Main moved before source pinning; dispatch a fresh approved release.')
        immutable_tag(tag,source)
    verify_immutable_tag(tag,source)
    print('Exact customer/branded source verified: '+source,flush=True)


if __name__=='__main__':
    parser=argparse.ArgumentParser();parser.add_argument('--verify',action='store_true');args=parser.parse_args()
    try:prepare(os.environ.get('GITHUB_SHA',''),os.environ.get('GITHUB_REF',''),args.verify)
    except ToolFailure as error:raise SystemExit('Customer source pin tool failure: '+error.category+' (exit '+str(error.code)+'); no publication authorized.')
    except Exception:raise SystemExit('Customer source pinning failed; no publication authorized and no tag overwritten.')
