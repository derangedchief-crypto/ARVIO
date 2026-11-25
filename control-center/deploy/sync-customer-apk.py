#!/usr/bin/env python3
"""Mirror only the latest main customer release without public upstream redirects."""
import fcntl
import hashlib
import json
import os
from pathlib import Path
import tempfile
import urllib.request

API = 'https://api.github.com/repos/derangedchief-crypto/ARVIO/releases/latest'

def request(url):
    return urllib.request.urlopen(urllib.request.Request(url, headers={'User-Agent': 'Customer-APK-Mirror'}), timeout=120)

def sync(site):
    state = site / 'storage/customer-download'
    state.mkdir(parents=True, exist_ok=True, mode=0o700)
    with (state / 'sync.lock').open('w') as lock:
        try:
            fcntl.flock(lock, fcntl.LOCK_EX | fcntl.LOCK_NB)
        except BlockingIOError:
            return
        with request(API) as response:
            release = json.load(response)
        if release.get('draft') or release.get('prerelease') or not release['tag_name'].startswith('v2.'):
            raise RuntimeError('Not a customer release')
        assets = [a for a in release['assets'] if a['name'] == 'ExtremeTV.apk']
        if len(assets) != 1:
            raise RuntimeError('Customer APK alias unavailable')
        asset = assets[0]
        digest = asset.get('digest') or ''
        if not digest.startswith('sha256:') or len(digest) != 71:
            raise RuntimeError('Verified upstream digest unavailable')
        size = asset['size']
        if not 1024 <= size <= 536870912:
            raise RuntimeError('Invalid APK size')
        directory = site / 'public/downloads/customer'
        directory.mkdir(parents=True, exist_ok=True, mode=0o755)
        target = directory / 'ExtremeTV.apk'
        receipt = state / 'receipt.json'
        identity = {'id': asset['id'], 'digest': digest, 'size': size}
        if receipt.exists() and target.is_file() and target.stat().st_size == size and json.loads(receipt.read_text()) == identity:
            return
        fd, temporary = tempfile.mkstemp(prefix='.customer-', dir=directory)
        try:
            with os.fdopen(fd, 'wb') as out, request(asset['browser_download_url']) as source:
                checksum = hashlib.sha256()
                received = 0
                magic = None
                while chunk := source.read(1048576):
                    if magic is None:
                        magic = chunk[:4]
                    received += len(chunk)
                    if received > size:
                        raise RuntimeError('APK exceeds expected size')
                    checksum.update(chunk)
                    out.write(chunk)
                if received != size or checksum.hexdigest() != digest[7:] or magic != b'PK\x03\x04':
                    raise RuntimeError('APK integrity check failed')
                out.flush()
                os.fsync(out.fileno())
                os.fchmod(out.fileno(), 0o644)
            os.replace(temporary, target)
            receipt.write_text(json.dumps(identity))
            print('Customer APK mirror updated: ' + release['tag_name'])
        finally:
            if os.path.exists(temporary):
                os.unlink(temporary)

if __name__ == '__main__':
    sync(Path('/www/wwwroot/control.fecxbrfzku.net'))
