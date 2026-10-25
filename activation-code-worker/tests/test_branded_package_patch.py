import importlib.util
from pathlib import Path
import re
import tempfile
import unittest

spec=importlib.util.spec_from_file_location('package_patch',Path(__file__).resolve().parents[1]/'patch-branded-package.py');module=importlib.util.module_from_spec(spec);spec.loader.exec_module(module)


class BrandedPackagePatchTests(unittest.TestCase):
    def test_only_the_known_model_literal_changes_and_repeating_is_safe(self):
        with tempfile.TemporaryDirectory(dir='/tmp/opencode') as directory:
            p=Path(directory)/'main.py';original='class ManagementProofRequest(BaseModel):\n    app_package: str = Field(pattern=r"'+module.OLD+'")\n\ndef activate():\n    return "UNCHANGED_PORTAL"\n';p.write_text(original);p.chmod(0o640)
            self.assertTrue(module.patch(p));self.assertEqual(original.replace(module.OLD,module.NEW),p.read_text());self.assertEqual(0o640,p.stat().st_mode&0o777);self.assertFalse(module.patch(p))
            p.write_text(original.replace(module.OLD,'anything-unknown'));before=p.read_bytes()
            with self.assertRaises(ValueError):module.patch(p)
            self.assertEqual(before,p.read_bytes())
    def test_legacy_issuer_is_additively_updated_without_changing_ownership_or_secret_logic(self):
        with tempfile.TemporaryDirectory(dir='/tmp/opencode') as directory:
            p=Path(directory)/'management_enrollment.py';original='import re\nPACKAGES = frozenset({"com.arvio.tv", "com.arvio.tv.beta", "com.arvio.tv.test"})\n\ndef issue_proof(app_package,canonical_username):\n    if app_package not in PACKAGES or not canonical_username:\n        raise ValueError("invalid")\n    return "UNCHANGED_OWNERSHIP_AND_HMAC"\n';p.write_text(original);self.assertTrue(module.patch_module(p));self.assertFalse(module.patch_module(p));self.assertIn('UNCHANGED_OWNERSHIP_AND_HMAC',p.read_text());self.assertIn('if not package_format(app_package) or not canonical_username:',p.read_text())
    def test_closed_package_family_accepts_both_channels_but_not_arbitrary_apps(self):
        for value in ['com.arvio.tv','com.arvio.tv.test','com.extreme.brand.b'+'a'*32,'com.extreme.brand.b'+'a'*32+'.test']:self.assertIsNotNone(re.fullmatch(module.NEW,value))
        for value in ['com.extreme.brand.arbitrary','com.extreme.brand.b'+'a'*32+'.evil','com.extreme.brand.b'+'a'*31,'com.other.tv']:self.assertIsNone(re.fullmatch(module.NEW,value))


if __name__=='__main__':unittest.main()
