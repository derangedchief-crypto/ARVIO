#!/usr/bin/env python3
"""Final official-APK identity check; no signing, publication, tags or feed writes."""
import os
from pathlib import Path
import re
import subprocess
import sys
from zipfile import ZipFile
from rollout_main import platform

SIGNER='3273d0b0294a689565ab4b0c8ef7e1887e2ca680240718f5521628b5c46328b7'

def verify(version,code,badging,certificates,entries):
    package=re.search(r"^package: name='([^']+)' versionCode='(\d+)' versionName='([^']+)'",badging,re.M)
    if not package or package[1]!='com.arvio.tv.beta' or int(package[2])!=code or package[3]!=version+'-beta':
        raise ValueError('Official customer package/version differs from its installed update identity')
    sdk=re.search(r"^sdkVersion:'(\d+)'",badging,re.M)
    if not sdk or int(sdk[1])!=26:raise ValueError('Customer update differs from the approved Android 8+ baseline')
    signers={s.lower() for s in re.findall(r'certificate SHA-256 digest: ([a-fA-F0-9]{64})',certificates)}
    if signers!={SIGNER}:raise ValueError('Official APK certificate differs from the installed signer')
    for abi in ['armeabi-v7a','arm64-v8a']:
        if not any(p.startswith('lib/'+abi+'/') and p.endswith('.so') for p in entries):raise ValueError('Customer update is missing an approved ARM ABI')

def main():
    apk=Path(sys.argv[1]);identity=platform(Path('app/build.gradle.kts').read_text())
    if identity is None or not apk.is_file():raise ValueError('Missing official source identity or APK')
    home=os.environ.get('ANDROID_HOME') or os.environ.get('ANDROID_SDK_ROOT')
    if not home:raise ValueError('Android SDK is not configured')
    tools=sorted((Path(home)/'build-tools').glob('*'),reverse=True)
    tools=next((p for p in tools if (p/'aapt2').is_file() and (p/'apksigner').is_file()),None)
    if tools is None:raise ValueError('APK inspection tools are unavailable')
    badging=subprocess.check_output([str(tools/'aapt2'),'dump','badging',str(apk)],text=True)
    cert=subprocess.check_output([str(tools/'apksigner'),'verify','--print-certs',str(apk)],text=True)
    with ZipFile(apk) as z:verify(*identity,badging,cert,z.namelist())
    print('Official APK package, increasing source version, installed signer and ARM/SDK support verified.')

if __name__=='__main__':
    try:main()
    except Exception:raise SystemExit('Official APK validation failed; publication refused. No release or feed changed.')
