#!/usr/bin/env python3
"""Owner-controlled queue runner. No HTTP builder API and no reseller code inputs.

Nothing runs on import. `run` builds; publication additionally requires --publish,
owner-enabled server policy and the protected GitHub environment. `reconcile` only
downloads/verifies a previously frozen publication; it never builds or uploads.
"""
import argparse
import fcntl
import hashlib
import json
import os
from pathlib import Path
import re
import shutil
import subprocess
import sys
import threading
import tempfile
import time
import xml.etree.ElementTree as ET

REPO = 'derangedchief-crypto/ARVIO'
ANDROID = '{http://schemas.android.com/apk/res/android}'


class ToolFailure(RuntimeError):
    """Only fixed categories and numeric exit status may reach public CI logs."""
    def __init__(self, category, code):
        self.category = category
        self.code = code
        super().__init__('Central build tool failed; private output withheld.')


def digest(path):
    with path.open('rb') as stream:
        return hashlib.file_digest(stream, 'sha256').hexdigest()


def private_json(path, value):
    with open(path, 'x', encoding='utf-8', opener=lambda p, flags: os.open(p, flags, 0o600)) as stream:
        json.dump(value, stream, ensure_ascii=False, sort_keys=True, separators=(',', ':'))


def command(args, *, cwd=None, data=None, timeout=120):
    # Never print raw commands/stdout/stderr: Gradle/SSH may include private inputs.
    result = subprocess.run(args, cwd=cwd, input=data, stdout=subprocess.PIPE,
                            stderr=subprocess.PIPE, timeout=timeout, check=False)
    if result.returncode:
        executable = Path(args[0]).name
        category = executable if executable in ('git', 'ssh', 'gh', 'gradlew', 'apksigner', 'apkanalyzer') else 'other'
        if executable == 'gradlew':
            output = (result.stdout + result.stderr).lower()
            for marker, label in [
                (b'outofmemoryerror', 'gradle-memory'),
                (b'gradle build daemon disappeared', 'gradle-daemon-lost'),
                (b'no space left on device', 'gradle-disk-full'),
                (b'could not resolve all', 'gradle-dependencies'),
                (b'compilation error', 'gradle-compilation'),
                (b'keystore was tampered', 'gradle-keystore-password'),
            ]:
                if marker in output:
                    category = label
                    break
            # Task identifiers and source locations only, never error messages,
            # command lines, URLs or property values from the captured output.
            text = (result.stdout + result.stderr).decode('utf-8', errors='replace')
            tasks = re.findall(r"Execution failed for task '(:[A-Za-z0-9_:.-]{1,160})'", text)
            for task in sorted(set(tasks))[:8]:
                print('Failed Gradle task: ' + task, file=sys.stderr)
        if category == 'ssh':
            # Match known OpenSSH diagnostics, but never echo their text (which
            # can contain addresses, paths, commands or private application data).
            stderr = result.stderr.lower()
            for marker, label in [
                (b'error in libcrypto', 'ssh-key-format'),
                (b'invalid format', 'ssh-key-format'),
                (b'host key verification failed', 'ssh-host-verification'),
                (b'permission denied (publickey', 'ssh-authentication'),
                (b'connection timed out', 'ssh-timeout'),
                (b'connection refused', 'ssh-refused'),
                (b'no route to host', 'ssh-unreachable'),
            ]:
                if marker in stderr:
                    category = label
                    break
        raise ToolFailure(category, result.returncode)
    if len(result.stdout) > 8 * 1024 * 1024:
        raise RuntimeError('Oversized central build tool response.')
    return result.stdout.decode('utf-8')


class Bridge:
    def __init__(self):
        self.target = os.environ.get('BRAND_BUILDER_SSH_TARGET', '')
        if not re.fullmatch(r'[a-zA-Z0-9_-]+@[a-zA-Z0-9][a-zA-Z0-9.-]{0,200}', self.target):
            raise ValueError('Configure the restricted central builder SSH target.')
        self.key = Path(os.environ['BRAND_BUILDER_SSH_KEY_FILE']).resolve(strict=True)
        self.hosts = Path(os.environ['BRAND_BUILDER_KNOWN_HOSTS_FILE']).resolve(strict=True)

    def args(self):
        return ['ssh', '-F', '/dev/null', '-T', '-i', str(self.key), '-o', 'IdentitiesOnly=yes',
                '-o', 'BatchMode=yes', '-o', 'StrictHostKeyChecking=yes', '-o', 'ClearAllForwardings=yes',
                '-o', 'ConnectTimeout=10', '-o', f'UserKnownHostsFile={self.hosts}', self.target, 'brand-builder']
    def call(self, action, value):
        raw = command(self.args(), data=(json.dumps({'action': action, 'input': value}, separators=(',', ':'))+'\n').encode(), timeout=60)
        result = json.loads(raw)
        if not isinstance(result, dict):
            raise ValueError('Invalid central queue response.')
        return result

    def upload(self, context, apk):
        # Disk-backed bounded stream, never a memory-sized APK or a command/path from
        # the reseller. The receiver derives size/hash exclusively from its receipt.
        with tempfile.TemporaryFile(dir=apk.parent) as stream:
            stream.write((json.dumps({'action':'mirror','input':lease(context)},separators=(',',':'))+'\n').encode())
            with apk.open('rb') as source:shutil.copyfileobj(source,stream)
            stream.seek(0)
            result=subprocess.run(self.args(),stdin=stream,stdout=subprocess.PIPE,stderr=subprocess.PIPE,timeout=600)
        if result.returncode or len(result.stdout)>16384:raise RuntimeError('Site APK delivery transfer failed; publication needs review.')
        response=json.loads(result.stdout)
        if response != {'stored':True,'apk_sha256':context['receipt']['apk_sha256'],'apk_size_bytes':context['receipt']['apk_size_bytes']}:
            raise ValueError('Site APK copy did not match the frozen receipt.')


class Heartbeat:
    def __init__(self, bridge, context):
        self.bridge, self.context = bridge, context
        self.stop = threading.Event()
        self.lost = False
        self.thread = threading.Thread(target=self.loop, daemon=True)

    def loop(self):
        while not self.stop.wait(60):
            try:
                self.bridge.call('heartbeat', lease(self.context))
            except Exception:
                self.lost = True
                return

    def __enter__(self):
        self.thread.start()
        return self

    def check(self):
        if self.lost:
            raise RuntimeError('Build lease could not be renewed. Publication stopped.')

    def __exit__(self, *_):
        self.stop.set()
        self.thread.join(timeout=65)


def lease(context):
    return {'build_id': context['build_id'], 'lease_token': context['lease_token']}


def validate_context(c, source, channel, reconcile=False):
    if c.get('source_sha') != source or c.get('channel') != channel:
        raise ValueError('Queue source/channel did not match this trusted runner.')
    for field, size in [('build_id', 32), ('lease_token', 64)]:
        if not isinstance(c.get(field), str) or not re.fullmatch('[a-f0-9]{%d}' % size, c[field]):
            raise ValueError('Invalid queue lease.')
    e = c['expected']
    package = e.get('package_name', '')
    match = re.fullmatch(r'com\.extreme\.brand\.b([a-f0-9]{32})(\.test)?', package)
    if not match or bool(match[2]) != (channel == 'testing'):
        raise ValueError('Generic/wrong-channel packages are forbidden in the brand builder.')
    if not re.fullmatch(r'[0-9]+\.[0-9]+\.[0-9]+\.[1-9][0-9]{0,4}' + ('-test' if channel == 'testing' else ''), e.get('version_name', '')):
        raise ValueError('Invalid immutable branded version.')
    tag = 'brand-' + match[1] + ('-testing' if channel == 'testing' else '')
    name = 'Brand-' + e['version_name'] + '.apk'
    url = f'https://github.com/{REPO}/releases/download/{tag}/{name}'
    if e.get('channel') != channel or e.get('release_tag') != tag or e.get('asset_name') != name or e.get('artifact_url') != url or e.get('trial_protocol') != 1:
        raise ValueError('Invalid brand-only publication destination.')
    if type(e.get('version_code')) is not int or not 1 <= e['version_code'] <= 2147483647 or not re.fullmatch('[a-f0-9]{64}', e.get('signer_sha256', '')):
        raise ValueError('Invalid central version/signing policy.')
    if not reconcile:
        m = c['bundle']['manifest']
        revision = m['brand_version']
        if m['brand_id'] != match[1] or m['package_name'] != 'com.extreme.brand.b' + match[1] or type(revision) is not int or not 1 <= revision <= 99999:
            raise ValueError('Bundle does not belong to the approved package.')
        version = m['app_version'] + '.' + str(revision) + ('-test' if channel == 'testing' else '')
        if e['version_name'] != version or e['version_code'] != m['app_version_code'] * 100000 + revision:
            raise ValueError('Bundle version allocation does not match.')
    return e


def manifest_identity(xml, expected):
    root = ET.fromstring(xml)
    app = root.find('application')
    if root.tag != 'manifest' or root.get('package') != expected['package_name'] or root.get(ANDROID+'versionName') != expected['version_name'] or root.get(ANDROID+'versionCode') != str(expected['version_code']):
        raise ValueError('Actual APK package/version does not match the approved job.')
    if app is None or app.get(ANDROID+'debuggable', 'false') not in ('false', '0'):
        raise ValueError('Debug APKs cannot be distributed by the brand builder.')
    protocols = [m.get(ANDROID+'value') for m in app.findall('meta-data') if m.get(ANDROID+'name') == 'com.extreme.public_trial_protocol']
    if protocols != [str(expected['trial_protocol'])]:
        raise ValueError('Actual APK does not advertise the expected trial protocol.')
    # Authorities and SDK-auth callbacks must use this package, not the generic ID.
    for p in app.findall('provider'):
        authority = p.get(ANDROID+'authorities', '')
        if authority and not authority.startswith(expected['package_name'] + '.'):
            raise ValueError('Provider authority is not isolated to this branded package.')


def verify_signer(text, expected):
    # Recent SDK tools use scheme-labelled records instead of numbered signers.
    # Count ALL certificate digest records first so an extra/unknown signer cannot
    # disappear merely because its label is not one of the supported formats.
    records = [line for line in text.splitlines() if 'certificate SHA-256 digest:' in line]
    match = re.fullmatch(r'(?:Signer #1|V(?:2|3|3\.1|4) Signer:) certificate SHA-256 digest: ([a-fA-F0-9]{64})', records[0]) if len(records) == 1 else None
    if match is None or match[1].lower() != expected:
        raise ValueError('Actual APK signer does not match the central certificate.')


def sdk_tool(kind):
    root = Path(os.environ['ANDROID_HOME'])
    if kind == 'apksigner':
        tools = list(root.glob('build-tools/*/apksigner'))
    else:
        tools = list(root.glob('cmdline-tools/*/bin/apkanalyzer'))
    if not tools:
        raise RuntimeError('Install Android SDK verification tools on the isolated runner.')
    return str(sorted(tools, key=lambda p: [int(n) for n in re.findall(r'\d+', str(p))])[-1])


def verify_apk(apk, expected, run=command):
    if not apk.is_file() or apk.is_symlink() or not 1024 <= apk.stat().st_size <= 536870912:
        raise ValueError('Invalid or oversized APK artifact.')
    print('Builder checkpoint: verifying central APK signature.', flush=True)
    verify_signer(run([sdk_tool('apksigner'), 'verify', '--verbose', '--print-certs', str(apk)]), expected['signer_sha256'])
    print('Builder checkpoint: verifying APK package and version.', flush=True)
    manifest_identity(run([sdk_tool('apkanalyzer'), 'manifest', 'print', str(apk)]), expected)
    return {'apk_sha256': digest(apk), 'apk_size_bytes': apk.stat().st_size}


def receipt(expected, source, measured, provenance):
    if not provenance.is_file() or provenance.is_symlink() or not 1 <= provenance.stat().st_size <= 65536:
        raise ValueError('Invalid bounded provenance document.')
    fields = ['channel', 'package_name', 'version_name', 'version_code', 'trial_protocol', 'signer_sha256', 'artifact_url']
    return dict({key: expected[key] for key in fields}, source_sha=source, **measured, provenance_sha256=digest(provenance))


def release_info(tag, run=command):
    # Enumerate every page so404/network/auth errors are never confused with
    # absence and an old brand channel is not missed behind newer releases.
    pages = json.loads(run(['gh', 'api', '--paginate', '--slurp', f'repos/{REPO}/releases?per_page=100']))
    if not isinstance(pages, list) or any(not isinstance(page, list) for page in pages):
        raise ValueError('Invalid GitHub release inventory.')
    found = [item for page in pages for item in page if item.get('tag_name') == tag]
    if not found:return None
    if len(found) != 1:raise ValueError('Ambiguous GitHub channel identity.')
    item = found[0]
    return {'tagName': item['tag_name'], 'name': item['name'], 'isDraft': item['draft'], 'isPrerelease': item['prerelease'], 'assets': item['assets']}


def check_release(info, expected, allow_new=False):
    if info is None:
        if allow_new:
            return
        raise ValueError('Brand channel has not been published.')
    if info['tagName'] != expected['release_tag'] or not info['isPrerelease']:
        raise ValueError('Never alter a generic/customer/latest release.')
    name = info.get('name', '')
    if name and name != expected['release_tag']:
        def version(v):
            if not re.fullmatch(r'\d+\.\d+\.\d+\.\d+(-test)?', v):
                raise ValueError('Unrecognized existing channel version; owner review required.')
            return tuple(int(x) for x in v.removesuffix('-test').split('.'))
        if version(name) > version(expected['version_name']):
            raise ValueError('Refusing to roll back a newer brand channel.')


def download_verified(work, context, run=command):
    e = context['expected']
    info = release_info(e['release_tag'], run)
    check_release(info, e)
    if info['isDraft'] or info['name'] != e['version_name']:
        raise ValueError('Channel metadata does not advertise this completed artifact.')
    directory = work/'downloaded'
    directory.mkdir(mode=0o700)
    for name in [e['asset_name'], e['asset_name']+'.provenance.json']:
        run(['gh', 'release', 'download', e['release_tag'], '--repo', REPO, '--pattern', name, '--dir', str(directory)])
    apk = directory/e['asset_name'];provenance = directory/(e['asset_name']+'.provenance.json')
    measured = verify_apk(apk, e, run)
    result = receipt(e, context['source_sha'], measured, provenance)
    if result != context['receipt']:
        raise ValueError('Published download/provenance differs from the frozen verified artifact.')
    return result


def retained_artifact(work, context, run=command, store=None):
    run_id=context.get('prepared_run_id')
    if not isinstance(run_id,str) or not re.fullmatch('[1-9][0-9]{0,19}',run_id):
        raise ValueError('Prepared artifact has no trusted retention identity. Do not rebuild this version.')
    directory=work/'retained';directory.mkdir(mode=0o700)
    if store is None:
        run(['gh','run','download',run_id,'--repo',REPO,'--name','central-branded-apk-'+run_id,'--dir',str(directory)])
    else:
        saved = Path(store)/run_id/context['build_id']
        for name in [context['expected']['asset_name'], context['expected']['asset_name']+'.provenance.json']:
            source = saved/name
            if source.is_symlink() or not source.is_file():
                raise ValueError('Retained artifact missing. Never rebuild this version.')
            shutil.copyfile(source, directory/name)
    e=context['expected'];apk=directory/e['asset_name'];provenance=directory/(e['asset_name']+'.provenance.json')
    result=receipt(e,context['source_sha'],verify_apk(apk,e,run),provenance)
    if result!=context['prepared_receipt']:
        raise ValueError('Retained bytes differ from the frozen prepared artifact; never rebuild or overwrite this version.')
    return apk,provenance,result


def retain_local(store, run_id, context, apk, provenance):
    """Durably retain exact verified bytes BEFORE freezing the server receipt."""
    if not re.fullmatch('[1-9][0-9]{0,19}', run_id):
        raise ValueError('Invalid local retention identity.')
    parent = Path(store)/run_id
    parent.mkdir(mode=0o700, parents=True, exist_ok=True)
    target = parent/context['build_id']
    # No existing-byte replacement, including after a lost prepared response.
    if target.exists():
        for source in [apk, provenance]:
            saved = target/source.name
            if saved.is_symlink() or not saved.is_file() or digest(saved) != digest(source):
                raise ValueError('Retained immutable artifact conflict.')
        return
    with tempfile.TemporaryDirectory(prefix='.retain-', dir=parent) as temporary:
        staging = Path(temporary)/'artifact';staging.mkdir(mode=0o700)
        for source in [apk, provenance]:
            with source.open('rb') as src, (staging/source.name).open('xb') as dst:
                os.chmod(staging/source.name, 0o600)
                shutil.copyfileobj(src, dst);dst.flush();os.fsync(dst.fileno())
        fd = os.open(staging, os.O_RDONLY | os.O_DIRECTORY)
        try:os.fsync(fd)
        finally:os.close(fd)
        # The standalone runner holds one host-wide lock throughout all writes.
        os.rename(staging, target)
        fd = os.open(parent, os.O_RDONLY | os.O_DIRECTORY)
        try:os.fsync(fd)
        finally:os.close(fd)


def publish(work, context, apk, provenance, run=command, guard=lambda: None):
    e = context['expected'];tag = e['release_tag']
    info = release_info(tag, run)
    check_release(info, e, allow_new=True)
    if info is None:
        guard()
        run(['gh', 'release', 'create', tag, '--repo', REPO, '--target', context['source_sha'], '--title', tag, '--draft', '--prerelease', '--latest=false'])
        info = {'assets': []}
    existing = {a['name'] for a in info['assets']}
    for file in [apk, provenance]:
        if file.name in existing:
            # Immutable names are NEVER clobbered. Reuse only identical bytes.
            scratch = work/('existing-'+file.suffix.replace('.', ''))
            scratch.mkdir(mode=0o700)
            run(['gh', 'release', 'download', tag, '--repo', REPO, '--pattern', file.name, '--dir', str(scratch)])
            if digest(scratch/file.name) != digest(file):
                raise ValueError('Immutable version already exists with different bytes; reconcile, do not overwrite.')
        else:
            guard()
            run(['gh', 'release', 'upload', tag, str(file), '--repo', REPO])
    notes = work/'release-notes.txt'
    notes.write_text('Centrally signed branded build.\nSource: '+context['source_sha']+'\nAPK SHA256: '+context['receipt']['apk_sha256']+'\n', encoding='utf-8')
    # Assets first, title last: old clients continue selecting the old immutable
    # version while upload is incomplete. All brand releases stay out of /latest.
    guard();check_release(release_info(tag, run), e)
    run(['gh', 'release', 'edit', tag, '--repo', REPO, '--title', e['version_name'], '--notes-file', str(notes), '--draft=false', '--prerelease', '--latest=false'])


def reviewed_source(run=command):
    root = Path(__file__).resolve().parent.parent
    source = run(['git', 'rev-parse', 'HEAD'], cwd=root).strip()
    if not re.fullmatch('[a-f0-9]{40}', source) or os.environ.get('GITHUB_SHA', source) != source:
        raise ValueError('Builder must run the exact checked-out workflow source commit.')
    if run(['git', 'diff', '--name-only', 'HEAD'], cwd=root).strip():
        raise ValueError('Build source is modified; commit reviewed code before signing.')
    if run(['git', 'ls-files', '--others', '--exclude-standard'], cwd=root).strip():
        raise ValueError('Untracked build source is forbidden on the central runner.')
    return root, source


def complete_staged(args, bridge=None, run=command):
    """Resume only after Actions retained the exact signed APK/provenance."""
    _, source = reviewed_source(run)
    work = Path(args.workdir).resolve(strict=True)
    context = json.loads((work/'staged.json').read_text())
    run_id = os.environ.get('GITHUB_RUN_ID', '')
    if not re.fullmatch('[1-9][0-9]{0,19}', run_id) or context.get('staged_run_id') != run_id:
        raise ValueError('Staged build does not belong to this Actions run.')
    validate_context(context, source, args.channel)
    bridge = bridge or Bridge()
    try:
        with Heartbeat(bridge, context) as heartbeat:
            bridge.call('heartbeat', lease(context))
            # For a resumed job its original retained run remains authoritative.
            retained = dict(context, prepared_run_id=context.get('prepared_run_id') or run_id,
                            prepared_receipt=context['receipt'])
            confirmation = work/'confirmation';confirmation.mkdir(mode=0o700)
            apk, provenance, result = retained_artifact(confirmation, retained, run)
            heartbeat.check();bridge.call('prepared', dict(lease(context), receipt=result, release_lease=not args.publish))
            if not args.publish:
                print('Signed APK retained and downloaded bytes verified. Build prepared; nothing published.')
                return
            heartbeat.check();bridge.call('delivery', lease(context))
            heartbeat.check();bridge.call('authorize', dict(lease(context), confirmed_publication=True))
            heartbeat.check();publish(work, context, apk, provenance, run, guard=lambda: bridge.call('heartbeat', lease(context)))
            downloaded = download_verified(work, context, run)
            heartbeat.check();bridge.upload(context, apk)
            heartbeat.check();bridge.call('finish', dict(lease(context), receipt=downloaded))
            print('Brand-only publication and website registry verified. Generic channels unchanged.')
    except Exception:
        try:bridge.call('fail', lease(context))
        except Exception:pass
        raise


def abort_job(args, bridge=None):
    path = Path(args.workdir)/'lease.json'
    if path.is_file():
        context = json.loads(path.read_text())
        # Published jobs reject fail; lost lease responses are harmless here.
        try:(bridge or Bridge()).call('fail', lease(context))
        except Exception:pass


def run_job(args, bridge=None, run=command):
    print('Builder checkpoint: checking trusted source.', flush=True)
    root, source = reviewed_source(run)
    print('Builder checkpoint: trusted source passed.', flush=True)
    work = Path(args.workdir).resolve();work.mkdir(mode=0o700, parents=False, exist_ok=False)
    bridge = bridge or Bridge()
    request={'build_id': args.build_id, 'channel': args.channel, 'source_sha': source}
    if args.mode=='run':
        run_id=getattr(args, 'retention_id', '') if getattr(args, 'artifact_store', None) else os.environ.get('GITHUB_RUN_ID','')
        if not re.fullmatch('[1-9][0-9]{0,19}',run_id):raise ValueError('Configure a trusted durable artifact retention identity.')
        request['runner_run_id']=run_id
    print('Builder checkpoint: requesting restricted queue hand-off.', flush=True)
    context = bridge.call('reconcile' if args.mode == 'reconcile' else 'claim', request)
    print('Builder checkpoint: queue response received.', flush=True)
    if context.get('state') == 'no_job':
        print('No eligible queued brand build.');return
    expected = validate_context(context, source, args.channel, args.mode == 'reconcile')
    print('Builder checkpoint: queue identity validated.', flush=True)
    private_json(work/'lease.json', context)
    try:
        with Heartbeat(bridge, context) as heartbeat:
            if args.mode == 'reconcile':
                result = download_verified(work, context, run)
                heartbeat.check();bridge.upload(context, work/'downloaded'/expected['asset_name'])
                heartbeat.check();bridge.call('finish', dict(lease(context), receipt=result))
                print('Previously published branded artifact verified and reconciled.');return
            if context.get('prepared_receipt'):
                apk,provenance,result=retained_artifact(work,context,run,getattr(args, 'artifact_store', None))
            else:
                bundle = work/'brand-input.json';private_json(bundle, context['bundle'])
                resources = work/'resources'
                print('Builder checkpoint: preparing approved artwork.', flush=True)
                run([sys.executable, str(root/'branding/prepare.py'), '--bundle', str(bundle), '--output', str(resources)], cwd=root)
                build_type = 'InternalTest' if args.channel == 'testing' else 'Release'
                started = time.monotonic()
                print('Builder checkpoint: compiling isolated APK.', flush=True)
                run([str(root/'gradlew'), f'-PbrandDirectory={resources}', ':app:assembleSideload'+build_type, '--no-daemon', '--console=plain'], cwd=root, timeout=5400)
                print('Builder checkpoint: APK compilation passed.', flush=True)
                elapsed = round(time.monotonic()-started, 2)
                output = root/'app/build/outputs/apk/sideload'/('internalTest' if args.channel == 'testing' else 'release')
                apks = list(output.glob('*.apk'))
                if len(apks) != 1:
                    raise ValueError('Expected exactly one universal centrally signed APK.')
                apk = work/expected['asset_name'];shutil.copyfile(apks[0], apk)
                measured = verify_apk(apk, expected, run)
                provenance = work/(expected['asset_name']+'.provenance.json')
                # No lease tokens, signing secrets, reseller contacts or panel identities.
                private_json(provenance, {'schema_version': 1, 'source_sha': source, 'build_id': context['build_id'],
                    'expected': expected, 'apk': measured, 'bundle_sha256': digest(bundle), 'build_seconds': elapsed})
                result = receipt(expected, source, measured, provenance)
            context['receipt'] = result
            if getattr(args, 'stage', False):
                export = work/'export';export.mkdir(mode=0o700)
                for file in [apk, provenance]:shutil.copyfile(file, export/file.name)
                context['staged_run_id'] = run_id
                private_json(work/'staged.json', context)
                heartbeat.check();bridge.call('heartbeat', lease(context))
                print('Signed APK staged. Retention must succeed before preparation/publication.')
                return
            if getattr(args, 'artifact_store', None) and not context.get('prepared_receipt'):
                retain_local(args.artifact_store, run_id, context, apk, provenance)
            heartbeat.check();bridge.call('prepared', dict(lease(context), receipt=result,release_lease=not args.publish))
            if not args.publish:
                print('Signed APK verified locally. Nothing published. Retain these exact bytes for later publication.');return
            heartbeat.check();bridge.call('delivery', lease(context))
            heartbeat.check();bridge.call('authorize', dict(lease(context), confirmed_publication=True))
            heartbeat.check();publish(work, context, apk, provenance, run, guard=lambda: bridge.call('heartbeat', lease(context)))
            downloaded = download_verified(work, context, run)
            heartbeat.check();bridge.upload(context, apk)
            heartbeat.check();bridge.call('finish', dict(lease(context), receipt=downloaded))
            print('Branded channel published and downloaded APK verified. Generic channels unchanged.')
    except Exception:
        try:bridge.call('fail', lease(context))
        except Exception:pass
        raise


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('mode', choices=['run', 'reconcile', 'complete', 'abort'])
    parser.add_argument('--build-id', default='')
    parser.add_argument('--channel', choices=['testing', 'production'], default='testing')
    parser.add_argument('--workdir', required=True)
    parser.add_argument('--publish', action='store_true')
    parser.add_argument('--stage', action='store_true', help='Actions only: defer preparation until upload and download verification.')
    parser.add_argument('--artifact-store', help='Private durable storage on the standalone trusted runner.')
    parser.add_argument('--retention-id', default='', help='Owner runner-generated numeric retention identity, not a GitHub run.')
    args = parser.parse_args()
    if args.build_id and not re.fullmatch('[a-f0-9]{32}', args.build_id):parser.error('Invalid build ID.')
    if args.mode == 'reconcile' and (not args.build_id or args.publish):parser.error('Reconcile requires a build ID and cannot upload/publish.')
    if args.retention_id and not args.artifact_store:parser.error('Local retention requires --artifact-store.')
    if args.stage and (args.mode != 'run' or args.publish or args.artifact_store):parser.error('Stage is build-only Actions mode.')
    if args.mode in ('complete', 'abort') and (args.artifact_store or args.stage):parser.error('Actions continuation cannot use local retention.')
    try:
        if args.mode == 'complete':complete_staged(args)
        elif args.mode == 'abort':abort_job(args)
        elif args.artifact_store:
            store = Path(args.artifact_store).resolve(strict=True)
            with (store/'.publisher.lock').open('a') as lock:
                fcntl.flock(lock, fcntl.LOCK_EX | fcntl.LOCK_NB)
                run_job(args)
        else:run_job(args)
    except ToolFailure as error:
        print(f'Central build tool category={error.category} exit={error.code}; private output withheld.', file=sys.stderr)
        return 1
    except Exception:
        print('Central build stopped. No automatic publication retry; review the private job state.', file=sys.stderr)
        return 1
    return 0


if __name__ == '__main__':
    sys.exit(main())
