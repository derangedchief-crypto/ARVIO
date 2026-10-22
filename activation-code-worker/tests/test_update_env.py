import contextlib
import io
import os
from pathlib import Path
import stat
import sys
import tempfile
import unittest


class EnvironmentMergeTests(unittest.TestCase):
    def test_installer_merges_only_bridge_fields_and_preserves_existing_settings(self):
        script = (Path(__file__).resolve().parents[1] / 'update-management-bridge.sh').read_text()
        code = script.split("<<'PY'\n", 1)[1].split('\nPY\n', 1)[0]
        with tempfile.TemporaryDirectory(prefix='control-env-merge-', dir='/tmp/opencode') as directory:
            root = Path(directory); target = root / '.env'; incoming = root / 'bridge.env'
            target.write_text('PANEL_DB_NAME=fixture\nPANEL_DB_PASSWORD=synthetic-private-value\nCUSTOM_SETTING=keep\nMANAGEMENT_ENROLLMENT_SECRET=old-value\n')
            target.chmod(0o600)
            incoming.write_text('MANAGEMENT_CONTROL_ORIGIN=https://control.example.test\nMANAGEMENT_ENROLLMENT_SECRET=' + 'ab' * 32 + '\n')
            original = sys.argv
            try:
                sys.argv = ['fixture', str(target), str(incoming)]
                output = io.StringIO()
                with contextlib.redirect_stdout(output):
                    exec(compile(code, '<installer-env-merge>', 'exec'), {'__name__': '__main__'})
                merged = target.read_text()
                self.assertIn('PANEL_DB_PASSWORD=synthetic-private-value', merged)
                self.assertIn('CUSTOM_SETTING=keep', merged)
                self.assertEqual(1, merged.count('MANAGEMENT_ENROLLMENT_SECRET='))
                self.assertIn('MANAGEMENT_CONTROL_ORIGIN=https://control.example.test', merged)
                self.assertEqual(0o600, stat.S_IMODE(target.stat().st_mode))
                self.assertEqual('', output.getvalue())
                before = target.read_bytes()
                incoming.write_text('PANEL_DB_PASSWORD=not-an-allowed-bridge-field\n')
                with self.assertRaises(SystemExit):
                    exec(compile(code, '<installer-env-merge>', 'exec'), {'__name__': '__main__'})
                self.assertEqual(before, target.read_bytes())
            finally:
                sys.argv = original
