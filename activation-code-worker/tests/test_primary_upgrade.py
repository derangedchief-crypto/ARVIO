import importlib.util
from pathlib import Path
import tempfile
import unittest

ROOT=Path(__file__).resolve().parents[1]
spec=importlib.util.spec_from_file_location('primary_upgrade',ROOT/'patch-primary-status.py')
module=importlib.util.module_from_spec(spec);spec.loader.exec_module(module)


class PrimaryUpgradeTests(unittest.TestCase):
    def test_targeted_update_is_idempotent_and_preserves_private_files(self):
        with tempfile.TemporaryDirectory(prefix='primary-worker-',dir='/tmp/opencode') as directory:
            app=Path(directory)
            original=b'# unrelated original routes must stay\ndef panel_cursor(): pass\napp = object()\nsettings = object()\n'
            (app/'main.py').write_bytes(original)
            (app/'management_enrollment.py').write_bytes((ROOT/'management_enrollment.py').read_bytes())
            (app/'.env').write_bytes(b'PRIVATE ENV SENTINEL')
            (app/'config.py').write_bytes(b'# PRIVATE CONFIG SENTINEL')
            (app/'trials.sqlite').write_bytes(b'EXISTING TRIAL HISTORY')
            backup=module.patch(app,ROOT)
            self.assertTrue((app/'main.py').read_bytes().startswith(original))
            self.assertIn(b'install_primary_status_routes(app, settings, panel_cursor)',(app/'main.py').read_bytes())
            self.assertIsNone(module.patch(app,ROOT))
            self.assertEqual(b'PRIVATE ENV SENTINEL',(app/'.env').read_bytes())
            self.assertEqual(b'# PRIVATE CONFIG SENTINEL',(app/'config.py').read_bytes())
            self.assertEqual(b'EXISTING TRIAL HISTORY',(app/'trials.sqlite').read_bytes())
            module.restore(backup)
            self.assertEqual(original,(app/'main.py').read_bytes())
            self.assertFalse((app/'primary_line_status.py').exists())

    def test_unknown_layout_is_not_overwritten(self):
        with tempfile.TemporaryDirectory(prefix='primary-worker-',dir='/tmp/opencode') as directory:
            app=Path(directory);(app/'main.py').write_text('# user-owned unknown worker\n')
            with self.assertRaises(RuntimeError):module.patch(app,ROOT)
            self.assertEqual('# user-owned unknown worker\n',(app/'main.py').read_text())


if __name__=='__main__':unittest.main()
