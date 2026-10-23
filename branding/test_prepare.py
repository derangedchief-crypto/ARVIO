import base64
import hashlib
import importlib.util
import io
import json
import re
from pathlib import Path
import tempfile
import unittest
import xml.etree.ElementTree as ET
from PIL import Image

spec = importlib.util.spec_from_file_location('prepare', Path(__file__).with_name('prepare.py'))
module = importlib.util.module_from_spec(spec)
spec.loader.exec_module(module)


class BrandPreparationTest(unittest.TestCase):
    def bundle(self):
        image = io.BytesIO()
        Image.new('RGBA', (64, 48), (20, 100, 220, 255)).save(image, format='PNG')
        raw = image.getvalue()
        meta = {'id': 'a'*32, 'sha256': hashlib.sha256(raw).hexdigest()}
        return {'manifest': {'schema_version': 1, 'brand_id': 'b'*32, 'brand_version': 1,'app_version':'2.0.113','app_version_code':429,
            'app_name': "North & O'Neil TV", 'package_name': 'com.extreme.brand.b'+'b'*32,
            'assets': {role: dict(meta) for role in module.ROLES}},
            'images': {role: base64.b64encode(raw).decode() for role in module.ROLES}}

    def test_generated_resources_cover_all_locales_and_density_artwork(self):
        with tempfile.TemporaryDirectory(dir='/tmp/opencode') as tmp:
            output = module.prepare(self.bundle(), Path(tmp)/'brand')
            strings = list((output/'res').glob('values*/brand_strings.xml'))
            self.assertGreaterEqual(len(strings), 3)
            for resource in strings:
                text = ''.join(ET.parse(resource).getroot().itertext())
                self.assertNotRegex(text, r'Extreme\s+(TV|Hub)')
                self.assertIn('North & O', text)
                self.assertNotIn("\\\\'", text)
            self.assertTrue((output/'res/mipmap-xxxhdpi/ic_launcher_foreground.png').is_file())
            self.assertTrue((output/'res/drawable/ic_logo_text.png').is_file())
            self.assertEqual({'brand_id', 'brand_name', 'package_name','app_version','app_version_code'}, {line.split('=')[0] for line in (output/'brand.properties').read_text().splitlines()})
            for original in (module.ROOT/'app/src/main/res').glob('drawable*/app_banner.webp'):
                self.assertTrue((output/'res'/original.relative_to(module.ROOT/'app/src/main/res')).is_file())

    def test_rejects_package_tampering_and_update_or_signing_settings(self):
        with tempfile.TemporaryDirectory(dir='/tmp/opencode') as tmp:
            for field, value in [('package_name', 'com.other.app'), ('update_url', 'https://bad.test'), ('app_name', 'Injected\nvalue')]:
                bundle = self.bundle(); bundle['manifest'][field] = value
                with self.assertRaises(ValueError): module.prepare(bundle, Path(tmp)/'rejected')
                self.assertFalse((Path(tmp)/'rejected').exists())

    def test_image_hash_mismatch_and_executable_uploads_are_rejected(self):
        with tempfile.TemporaryDirectory(dir='/tmp/opencode') as tmp:
            bundle = self.bundle(); bundle['images']['icon_asset'] = base64.b64encode(b'<svg/>').decode()
            with self.assertRaises(ValueError): module.prepare(bundle, Path(tmp)/'rejected')
            self.assertFalse((Path(tmp)/'rejected').exists())

    def test_existing_output_is_never_overwritten(self):
        with tempfile.TemporaryDirectory(dir='/tmp/opencode') as tmp:
            path = Path(tmp)/'output';path.mkdir();sentinel=path/'private';sentinel.write_text('KEEP')
            with self.assertRaises(FileExistsError): module.prepare(self.bundle(), path)
            self.assertEqual('KEEP',sentinel.read_text())

    def test_preparation_is_deterministic_without_changing_base_resources(self):
        with tempfile.TemporaryDirectory(dir='/tmp/opencode') as tmp:
            a = module.prepare(self.bundle(), Path(tmp)/'a');b = module.prepare(self.bundle(), Path(tmp)/'b')
            self.assertEqual({str(p.relative_to(a)):p.read_bytes() for p in a.rglob('*') if p.is_file()},
                {str(p.relative_to(b)):p.read_bytes() for p in b.rglob('*') if p.is_file()})
    def test_platform_release_is_consistent_between_queue_and_android_source(self):
        native=(module.ROOT/'app/build.gradle.kts').read_text()
        backend=(module.ROOT/'control-center/src/AppBrands.php').read_text()
        self.assertEqual(re.search(r'platformVersionName = "([^"]+)"',native)[1],re.search(r"APP_VERSION='([^']+)'",backend)[1])
        self.assertEqual(re.search(r'platformVersionCode = (\d+)',native)[1],re.search(r'APP_VERSION_CODE=(\d+)',backend)[1])
        self.assertEqual(re.search(r'platformVersionName = "([^"]+)"',native)[1],re.search(r'\bversionName\s*=\s*"([^"]+)"',native)[1])
        self.assertEqual(re.search(r'platformVersionCode = (\d+)',native)[1],re.search(r'\bversionCode\s*=\s*(\d+)',native)[1])

if __name__ == '__main__': unittest.main()
