import base64
from pathlib import Path
import subprocess
import sys
import tempfile
import unittest
import zipfile

ROOT=Path(__file__).resolve().parent


class WebsiteBridgeTests(unittest.TestCase):
    def test_setup_archive_contains_only_root_installer_docs_license_and_public_key(self):
        with tempfile.TemporaryDirectory() as temporary:
            root=Path(temporary);public=root/'key.pub';output=root/'setup.zip'
            encoded=base64.b64encode(b'\0\0\0\x0bssh-ed25519\0\0\0\x20'+b'x'*32).decode()
            public.write_text('ssh-ed25519 '+encoded+' synthetic-test\n')
            subprocess.run([sys.executable,str(ROOT/'package_website_bridge.py'),str(public),str(output)],check=True,capture_output=True)
            with zipfile.ZipFile(output) as archive:
                self.assertEqual({'install.sh','README.md','LICENSE','bridge-public-key.pub'},set(archive.namelist()))
                self.assertEqual(public.read_bytes(),archive.read('bridge-public-key.pub'))
                self.assertNotIn(b'PRIVATE KEY',archive.read('bridge-public-key.pub'))

    def test_packer_refuses_private_or_malformed_key_material(self):
        with tempfile.TemporaryDirectory() as temporary:
            root=Path(temporary);public=root/'key';output=root/'setup.zip'
            for value in ['-----BEGIN OPENSSH PRIVATE KEY-----\nSYNTHETIC','ssh-ed25519 AAAA','ssh-ed25519 !!!']:
                public.write_text(value)
                result=subprocess.run([sys.executable,str(ROOT/'package_website_bridge.py'),str(public),str(output)],capture_output=True)
                self.assertNotEqual(0,result.returncode);self.assertFalse(output.exists())

    def test_installer_syntax_and_no_automatic_enabling_or_broad_write_grants(self):
        path=ROOT/'deploy/install-website-bridge.sh'
        subprocess.run(['bash','-n',str(path)],check=True)
        script=path.read_text()
        self.assertIn('--check',script);self.assertIn('pre-setup.acl',script)
        self.assertIn('restrict,command=',script);self.assertIn('test ! -w',script)
        self.assertNotRegex(script,r'(chmod|chown)\s+-R\b')
        self.assertNotRegex(script,r'systemctl\s+(start|restart|enable)\b')
        self.assertNotIn('usermod -aG',script)


if __name__=='__main__':unittest.main()
