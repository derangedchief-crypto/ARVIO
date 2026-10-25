#!/usr/bin/env python3
"""Trusted operator driver for main-to-brand TESTING fanout. No APK signing here."""
import base64
import json
import os
from pathlib import Path
import re
import subprocess
import sys

from central_builder import Bridge, REPO, command


def platform(text):
    name=re.search(r'^val platformVersionName = "([0-9]+\.[0-9]+\.[0-9]+)"$',text,re.M)
    code=re.search(r'^val platformVersionCode = ([0-9]+)$',text,re.M)
    if not name or not code or 'val brandDirectory' not in text:return None
    version=name[1];number=int(code[1])
    if len(version)>24 or tuple(map(int,version.split('.')))<=(2,0,115) or not 431<number<=21474:return None
    if not re.search(r'^\s*versionCode = '+str(number)+r'$',text,re.M) or not re.search(r'^\s*versionName = "'+re.escape(version)+r'"$',text,re.M):raise ValueError('Main platform version literals do not match.')
    return version,number


def gh_json(path):return json.loads(command(['gh','api','repos/'+REPO+'/'+path]))


def immutable_tag(tag, sha):
    path='repos/'+REPO+'/git/ref/tags/'+tag
    result=subprocess.run(['gh','api',path],capture_output=True,timeout=60)
    if result.returncode:
        if b'(HTTP 404)' not in result.stderr:raise RuntimeError('Source tag lookup unavailable; no tag overwritten.')
        command(['gh','api','--method','POST','repos/'+REPO+'/git/refs','-f','ref=refs/tags/'+tag,'-f','sha='+sha])
    actual=gh_json('git/ref/tags/'+tag)
    if actual.get('object',{}).get('sha')!=sha or actual.get('object',{}).get('type')!='commit':raise ValueError('Immutable main source tag conflict.')


def main():
    os.umask(0o077)
    source=os.environ.get('BRAND_MAIN_SOURCE_SHA','');operator=os.environ.get('BRAND_OPERATOR_SHA','')
    if not re.fullmatch('[a-f0-9]{40}',source) or not re.fullmatch('[a-f0-9]{40}',operator):raise ValueError('Reviewed source identities required.')
    if command(['git','rev-parse','HEAD']).strip()!=operator:raise ValueError('Operator checkout must match the protected source pin.')
    if gh_json('git/ref/heads/main').get('object',{}).get('sha')!=source:raise ValueError('Main moved; only the current exact main source may register a new rollout.')
    document=gh_json('contents/app/build.gradle.kts?ref='+source)
    identity=platform(base64.b64decode(document['content'],validate=False).decode())
    if identity is None:
        print('Main is older or not branding-compatible. No branded downgrade, tag or build queued.');return
    gh_json('contents/branding/central_builder.py?ref='+source)
    version,code=identity;tag='brand-source-'+version+'-'+source
    immutable_tag(tag,source)
    temp=Path(os.environ['RUNNER_TEMP'])
    key=temp/'brand-main-bridge-key';hosts=temp/'brand-main-known-hosts'
    try:
        for path,value in [(key,os.environ['BRAND_BUILDER_SSH_PRIVATE_KEY']),(hosts,os.environ['BRAND_BUILDER_SSH_KNOWN_HOSTS'])]:
            with open(path,'x',opener=lambda p,f:os.open(p,f,0o600)) as stream:stream.write(value.rstrip()+'\n')
        os.environ['BRAND_BUILDER_SSH_KEY_FILE']=str(key);os.environ['BRAND_BUILDER_KNOWN_HOSTS_FILE']=str(hosts)
        bridge=Bridge();total=0
        for _ in range(1000):
            result=bridge.call('fanout',{'source_sha':source,'workflow_ref':tag,'app_version':version,'version_code':code})
            if set(result)!={'complete','queued'} or type(result['complete']) is not bool or type(result['queued']) is not int or not 0<=result['queued']<=100:raise ValueError('Invalid bounded fanout response.')
            total+=result['queued']
            if result['complete']:
                print('Main testing rollout queued '+str(total)+' branded builds. Published artwork/package identities preserved.');return
        raise RuntimeError('Fanout batch bound reached; cursor retained for deliberate resume.')
    finally:key.unlink(missing_ok=True);hosts.unlink(missing_ok=True)


if __name__=='__main__':
    try:main()
    except Exception:raise SystemExit('Main-to-brand testing fanout stopped safely; no production rollout or private output.')
