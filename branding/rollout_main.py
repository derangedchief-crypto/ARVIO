#!/usr/bin/env python3
"""Fan out only a successfully published customer release. No signing here."""
import base64
import json
import os
from pathlib import Path
import re
import subprocess
import sys

from central_builder import Bridge, REPO, ToolFailure, command


def platform(text):
    name=re.search(r'^val platformVersionName = "([0-9]+\.[0-9]+\.[0-9]+)"$',text,re.M)
    code=re.search(r'^val platformVersionCode = ([0-9]+)$',text,re.M)
    if not name or not code or 'val brandDirectory' not in text:return None
    version=name[1];number=int(code[1])
    if len(version)>24 or tuple(map(int,version.split('.')))<=(2,0,115) or not 431<number<=21474:return None
    if not re.search(r'^\s*versionCode = '+str(number)+r'$',text,re.M) or not re.search(r'^\s*versionName = "'+re.escape(version)+r'"$',text,re.M):raise ValueError('Main platform version literals do not match.')
    return version,number


def gh_json(path):return json.loads(command(['gh','api','repos/'+REPO+'/'+path]))


def published_customer_source(run_id):
    if not re.fullmatch('[1-9][0-9]{0,19}',run_id):raise ValueError('A customer publication run is required.')
    run=gh_json('actions/runs/'+run_id)
    if (str(run.get('id'))!=run_id or run.get('status')!='completed' or run.get('conclusion')!='success'
            or run.get('event')!='workflow_dispatch' or run.get('head_branch')!='main'
            or run.get('path')!='.github/workflows/publish-release.yml'
            or run.get('head_repository',{}).get('full_name')!=REPO
            or run.get('repository',{}).get('full_name')!=REPO):
        raise ValueError('Only a successful main customer publication may update brands.')
    source=run.get('head_sha','')
    if not re.fullmatch('[a-f0-9]{40}',source):raise ValueError('Invalid released source.')
    document=gh_json('contents/app/build.gradle.kts?ref='+source)
    identity=platform(base64.b64decode(document['content'],validate=False).decode())
    if identity is None:raise ValueError('Released source is not branding-compatible.')
    version,code=identity
    tag='v'+version
    release=gh_json('releases/tags/'+tag)
    assets=[a for a in release.get('assets',[]) if a.get('name','').lower().endswith('.apk')]
    if (release.get('tag_name')!=tag or release.get('draft') is not False or release.get('prerelease') is not False
            or not release.get('published_at') or len(assets)!=1
            or assets[0].get('name')!='ExtremeTV-'+version+'.apk' or assets[0].get('state')!='uploaded'
            or type(assets[0].get('size')) is not int or assets[0]['size']<=0
            or not re.fullmatch('sha256:[a-f0-9]{64}',assets[0].get('digest',''))):
        raise ValueError('A completed customer APK publication is required.')
    if gh_json('commits/'+tag).get('sha')!=source:raise ValueError('Customer release tag/source mismatch.')
    return source,version,code


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
    operator=os.environ.get('BRAND_OPERATOR_SHA','')
    channel=os.environ.get('BRAND_MAIN_UPDATE_CHANNEL','production')
    if channel!='production':raise ValueError('Customer releases update production brands only.')
    if not re.fullmatch('[a-f0-9]{40}',operator):raise ValueError('Reviewed operator identity required.')
    if command(['git','rev-parse','HEAD']).strip()!=operator:raise ValueError('Operator checkout must match the protected source pin.')
    print('Verifying completed customer publication.',flush=True)
    source,version,code=published_customer_source(os.environ.get('BRAND_CUSTOMER_RELEASE_RUN_ID',''))
    gh_json('contents/branding/central_builder.py?ref='+source)
    tag='brand-source-'+version+'-'+source
    print('Verifying immutable released-source tag.',flush=True)
    immutable_tag(tag,source)
    temp=Path(os.environ['RUNNER_TEMP'])
    key=temp/'brand-main-bridge-key';hosts=temp/'brand-main-known-hosts'
    try:
        for path,value in [(key,os.environ['BRAND_BUILDER_SSH_PRIVATE_KEY']),(hosts,os.environ['BRAND_BUILDER_SSH_KNOWN_HOSTS'])]:
            with open(path,'x',opener=lambda p,f:os.open(p,f,0o600)) as stream:stream.write(value.rstrip()+'\n')
        os.environ['BRAND_BUILDER_SSH_KEY_FILE']=str(key);os.environ['BRAND_BUILDER_KNOWN_HOSTS_FILE']=str(hosts)
        bridge=Bridge();total=0
        print('Submitting production branded queue batches.',flush=True)
        for _ in range(1000):
            result=bridge.call('fanout',{'source_sha':source,'workflow_ref':tag,'app_version':version,'version_code':code,**({'channel':'production'} if channel=='production' else {})})
            if set(result)!={'complete','queued'} or type(result['complete']) is not bool or type(result['queued']) is not int or not 0<=result['queued']<=100:raise ValueError('Invalid bounded fanout response.')
            total+=result['queued']
            if result['complete']:
                print('Main '+channel+' rollout queued '+str(total)+' branded builds. Published artwork/package identities preserved.');return
        raise RuntimeError('Fanout batch bound reached; cursor retained for deliberate resume.')
    finally:key.unlink(missing_ok=True);hosts.unlink(missing_ok=True)


if __name__=='__main__':
    try:main()
    except ToolFailure as error:raise SystemExit('Brand fanout tool failure: '+error.category+' (exit '+str(error.code)+'); private output withheld.')
    except Exception:raise SystemExit('Main-to-brand fanout stopped safely; no unapproved rollout or private output.')
