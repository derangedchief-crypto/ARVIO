#!/usr/bin/env python3
"""Create only owner-controlled private runner inputs; never accepts brand input."""
import base64
import os
from pathlib import Path


def write(path, value):
    with open(path, 'xb', opener=lambda p, flags: os.open(p, flags, 0o600)) as stream:
        stream.write(value)


def prop(value):
    # Java Properties.load(InputStream) is ISO-8859-1; encode Unicode explicitly.
    result = ''
    for c in value:
        if c in '\r\n\0':raise ValueError('Multiline owner properties are unsupported.')
        if c in '\\:=#! ':
            result += '\\' + c
        elif ord(c) > 126 or ord(c) < 32:
            result += '\\u%04x' % ord(c) if ord(c) <= 65535 else ''.join('\\u%04x' % int.from_bytes(c.encode('utf-16-be')[i:i+2], 'big') for i in (0,2))
        else:result += c
    return result


def main():
    root = Path(__file__).resolve().parent.parent
    temp = Path(os.environ['RUNNER_TEMP'])
    required = ['BRAND_BUILDER_SSH_PRIVATE_KEY','BRAND_BUILDER_SSH_KNOWN_HOSTS','RELEASE_KEYSTORE_BASE64','RELEASE_KEYSTORE_PASSWORD','RELEASE_KEY_ALIAS','RELEASE_KEY_PASSWORD']
    if any(not os.environ.get(k) for k in required):raise ValueError('Missing owner-managed builder inputs.')
    write(temp/'brand-builder-ssh-key', os.environ['BRAND_BUILDER_SSH_PRIVATE_KEY'].encode())
    write(temp/'brand-builder-known-hosts', os.environ['BRAND_BUILDER_SSH_KNOWN_HOSTS'].encode())
    write(root/'app/release.keystore', base64.b64decode(''.join(os.environ['RELEASE_KEYSTORE_BASE64'].split()), validate=True))
    keys = {'storeFile':'release.keystore', 'storePassword':os.environ['RELEASE_KEYSTORE_PASSWORD'], 'keyAlias':os.environ['RELEASE_KEY_ALIAS'], 'keyPassword':os.environ['RELEASE_KEY_PASSWORD']}
    write(root/'keystore.properties', ''.join(k+'='+prop(v)+'\n' for k,v in keys.items()).encode('ascii'))
    values = (root/'secrets.defaults.properties').read_bytes() + b'\n'
    for k in ['TMDB_API_KEY','ENTITLEMENT_CLOUD_STREAM_MANIFEST_URL','SENTRY_DSN','SUPABASE_URL','SUPABASE_ANON_KEY','APP_ANON_KEY','TRAKT_CLIENT_ID','TRAKT_CLIENT_SECRET']:
        if os.environ.get(k):values += (k+'='+prop(os.environ[k])+'\n').encode('ascii')
    write(root/'secrets.properties', values)


if __name__ == '__main__':
    try:main()
    except Exception:raise SystemExit('Could not prepare private owner-managed runner inputs.')
