#!/usr/bin/env python3
import hashlib
import importlib.util
import json
from pathlib import Path
import shutil
import sys
import tempfile
import unittest
from zipfile import ZipFile

root = Path(__file__).resolve().parents[1]
spec = importlib.util.spec_from_file_location('installer', root / 'deploy/install-live-workspace.py')
installer = importlib.util.module_from_spec(spec)
spec.loader.exec_module(installer)
archive, baseline = map(Path, sys.argv[1:3])
sys.argv = sys.argv[:1]


class InstallerTests(unittest.TestCase):
    def setUp(self):
        self.tmp = tempfile.TemporaryDirectory(dir='/tmp/opencode')
        self.base = Path(self.tmp.name)
        self.site = self.base / 'site'
        for directory in ['src', 'public/assets', 'storage']:
            (self.site / directory).mkdir(parents=True, exist_ok=True)
        for name in ['src/Auth.php', 'src/Application.php', 'src/CategoryLayouts.php']:
            shutil.copyfile(baseline / name, self.site / name)
        for name, data in {'VERSION':'0.41.0\n','config.php':'local-config','storage/application.key':'local-key','public/index.html':'old-index'}.items():
            (self.site / name).write_text(data)

    def tearDown(self):
        self.tmp.cleanup()

    def install(self):
        installer.install(self.site, archive, self.base / 'backup', ['bash', str(root / 'dev/php.sh')])

    def test_install_preserves_identity_and_backs_up(self):
        self.install()
        self.assertEqual((self.site / 'VERSION').read_text(), '0.42.0\n')
        self.assertEqual((self.site / 'storage/application.key').read_text(), 'local-key')
        self.assertEqual((self.site / 'config.php').read_text(), 'local-config')
        self.assertIn('/assets/portal-0.42.0/studio.css', (self.site / 'public/index.html').read_text())
        self.assertTrue((self.site / 'src/DeviceInspection.php').is_file())
        with ZipFile(self.base / 'backup/website-before.zip') as z:
            self.assertEqual(z.read('public/index.html'), b'old-index')
        self.assertEqual(json.loads((self.base / 'backup/added-files.json').read_text()), ['src/DeviceInspection.php'])

    def test_custom_backend_is_rejected_before_writes(self):
        (self.site / 'src/Application.php').write_text('custom-backend')
        with self.assertRaisesRegex(RuntimeError, 'baseline'):
            self.install()
        self.assertEqual((self.site / 'public/index.html').read_text(), 'old-index')
        self.assertFalse((self.site / 'public/assets/portal-0.42.0').exists())

    def test_symlink_is_rejected(self):
        (self.site / 'config.php').unlink()
        (self.base / 'outside').write_text('untouched')
        (self.site / 'config.php').symlink_to(self.base / 'outside')
        with self.assertRaisesRegex(RuntimeError, 'symlink'):
            self.install()
        self.assertEqual((self.base / 'outside').read_text(), 'untouched')


unittest.main()
