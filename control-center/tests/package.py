#!/usr/bin/env python3
"""Validate the actual delivery ZIP, including an in-place upgrade simulation."""
from pathlib import Path
import hashlib
import subprocess
import tempfile
import zipfile
import shutil

root = Path(__file__).resolve().parents[1]
with tempfile.TemporaryDirectory(prefix='control-package-', dir='/tmp/opencode') as directory:
    base = Path(directory)
    a, b = base / 'a.zip', base / 'b.zip'
    for target in [a, b]:
        subprocess.run(['python3', str(root / 'tools/package.py'), str(target)], check=True, capture_output=True)
    assert a.read_bytes() == b.read_bytes(), 'Package must be deterministic'
    # A new release must invalidate existing PHP opcodes even when the extraction
    # tool preserves ZIP timestamps. Same-version packaging stays deterministic.
    copy = base / 'next-release' / 'control-center'
    copy.mkdir(parents=True)
    for folder in ['src','public','database','deploy','cli','tools']:
        shutil.copytree(root / folder, copy / folder)
    for name in ['README.md','VALIDATION.md']:
        shutil.copyfile(root / name, copy / name)
    shutil.copyfile(root.parent / 'LICENSE', copy.parent / 'LICENSE')
    parts = list(map(int, (root / 'VERSION').read_text().strip().split('.')))
    parts[2] += 1
    if parts[2] == 1000: parts[1] += 1; parts[2] = 0
    if parts[1] == 1000: parts[0] += 1; parts[1] = 0
    (copy / 'VERSION').write_text('.'.join(map(str, parts)) + '\n')
    newer = base / 'next.zip'
    subprocess.run(['python3', str(copy / 'tools/package.py'), '--update', str(newer)], check=True, capture_output=True)
    with zipfile.ZipFile(a) as old, zipfile.ZipFile(newer) as new:
        for name in ['public/index.php','src/Application.php','src/ModuleLicences.php']:
            assert new.getinfo(name).date_time > old.getinfo(name).date_time, name
            assert old.getinfo(name).date_time != (2026, 1, 1, 0, 0, 0), 'Do not restore the stale shared timestamp'
            assert old.read(name) == new.read(name), 'Timestamp refresh must not modify PHP source'
    with zipfile.ZipFile(a) as archive:
        names = archive.namelist()
        assert 'public/index.php' in names and 'database/migrations/001_foundation.sql' in names
        assert 'deploy/INSTALL-AAPANEL.txt' in names and 'cli/console.php' in names
        assert 'database/migrations/013_device_setup_profiles.sql' in names
        assert 'src/DeviceProfiles.php' in names
        assert 'src/AppBrands.php' in names and 'database/migrations/014_reseller_app_brands.sql' in names
        assert 'src/TrialPolicies.php' in names and 'src/TrialContacts.php' in names and 'database/migrations/015_trial_policies_contacts.sql' in names
        assert 'src/TrialSignupBroker.php' in names and 'database/migrations/016_public_trial_signup.sql' in names
        assert 'src/BrandBuilder.php' in names and 'cli/brand-builder.php' in names and 'database/migrations/017_central_brand_builder.sql' in names
        for asset in ['device-workspace.js', 'workspace-library.js', 'workspace.css', 'app-branding.js', 'trial-workspace.js']:
            assert 'public/assets/' + asset in names
            assert ('/assets/' + asset).encode() in archive.read('public/index.html')
        assert not any(name.startswith(('tests/', 'dev/', 'test-results/', 'dist/')) for name in names)
        assert 'config.local.php' not in names
        assert 'config.example.php' not in names
        assert 'config.php' in names
        assert b"'password' => ''" in archive.read('config.php')
        assert (archive.getinfo('config.php').external_attr >> 16) & 0o777 == 0o600
        assert not any(name.startswith('tools/') for name in names)
        assert not any(name.endswith(('.key', '.sql.gz', '.zip', '.log', '.env')) for name in names)
        assert (archive.getinfo('storage/').external_attr >> 16) & 0o777 == 0o700
        for name in names:
            assert not name.startswith('/') and '..' not in Path(name).parts
        for line in archive.read('MANIFEST.sha256').decode().splitlines():
            checksum, name = line.split('  ', 1)
            assert hashlib.sha256(archive.read(name)).hexdigest() == checksum, name
        contents = b''.join(archive.read(name) for name in names)
        assert b'Synthetic-Test-Password' not in contents
    update = base / 'update.zip'
    subprocess.run(['python3', str(root / 'tools/package.py'), '--update', str(update)], check=True, capture_output=True)
    with zipfile.ZipFile(update) as archive:
        assert 'config.php' not in archive.namelist()
        assert not any(name.startswith('storage/') for name in archive.namelist()), 'Update ZIP must not reset initialized storage permissions or ACL masks'
        target = base / 'upgrade'
        (target / 'storage').mkdir(parents=True, mode=0o700)
        (target / 'config.php').write_text('PRIVATE CONFIG SENTINEL')
        (target / 'storage/application.key').write_text('ORIGINAL KEY SENTINEL')
        archive.extractall(target)
        assert (target / 'config.php').read_text() == 'PRIVATE CONFIG SENTINEL'
        assert (target / 'storage/application.key').read_text() == 'ORIGINAL KEY SENTINEL'
        for line in archive.read('MANIFEST.sha256').decode().splitlines():
            checksum, name = line.split('  ', 1)
            assert hashlib.sha256(archive.read(name)).hexdigest() == checksum
print('PASS reproducible ZIPs use advancing release timestamps; update preserves config/storage/keys and all manifests verify')
