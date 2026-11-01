import contextlib
import io
import sys
from pathlib import Path
from types import SimpleNamespace
import unittest
from unittest.mock import patch

sys.path.insert(0,str(Path(__file__).parent))
import prepare_customer_source as pin

SHA='a'*40

class CustomerSourcePinTests(unittest.TestCase):
    def test_current_main_is_pinned_before_customer_build(self):
        with patch.object(pin,'command',return_value=SHA),patch.object(pin,'metadata',return_value=('2.0.124',440)),patch.object(pin,'customer_tag_matches'),patch.object(pin,'gh_json',return_value={'object':{'sha':SHA}}),patch.object(pin,'immutable_tag') as create,patch.object(pin,'verify_immutable_tag') as verify,contextlib.redirect_stdout(io.StringIO()):
            pin.prepare(SHA,'refs/heads/main')
            create.assert_called_once_with('brand-source-2.0.124-'+SHA,SHA)
            verify.assert_called_once_with('brand-source-2.0.124-'+SHA,SHA)

    def test_moved_main_cannot_start_a_new_publication(self):
        with patch.object(pin,'command',return_value=SHA),patch.object(pin,'metadata',return_value=('2.0.124',440)),patch.object(pin,'customer_tag_matches'),patch.object(pin,'gh_json',return_value={'object':{'sha':'b'*40}}),patch.object(pin,'immutable_tag') as create:
            with self.assertRaises(ValueError):pin.prepare(SHA,'refs/heads/main')
            create.assert_not_called()

    def test_publication_recheck_uses_frozen_source_after_main_moves(self):
        with patch.object(pin,'command',return_value=SHA),patch.object(pin,'metadata',return_value=('2.0.124',440)),patch.object(pin,'customer_tag_matches'),patch.object(pin,'gh_json') as api,patch.object(pin,'immutable_tag') as create,patch.object(pin,'verify_immutable_tag') as verify,contextlib.redirect_stdout(io.StringIO()):
            pin.prepare(SHA,'refs/heads/main',True)
            api.assert_not_called();create.assert_not_called();verify.assert_called_once()

    def test_other_branches_and_wrong_checkout_cannot_publish(self):
        with self.assertRaises(ValueError):pin.prepare(SHA,'refs/heads/staging/testing')
        with patch.object(pin,'command',return_value='b'*40):
            with self.assertRaises(ValueError):pin.prepare(SHA,'refs/heads/main')

    def test_existing_customer_tag_cannot_be_retargeted(self):
        with patch.object(pin.subprocess,'run',return_value=SimpleNamespace(returncode=0)),patch.object(pin,'gh_json',return_value={'sha':'b'*40}):
            with self.assertRaises(ValueError):pin.customer_tag_matches(SHA,'2.0.124')

    def test_missing_tag_is_allowed_but_api_failure_is_not(self):
        with patch.object(pin.subprocess,'run',return_value=SimpleNamespace(returncode=1,stderr=b'gh: Not Found (HTTP 404)')):
            pin.customer_tag_matches(SHA,'2.0.124')
        with patch.object(pin.subprocess,'run',return_value=SimpleNamespace(returncode=1,stderr=b'gh: Unavailable (HTTP 503)')):
            with self.assertRaises(RuntimeError):pin.customer_tag_matches(SHA,'2.0.124')

    def test_workflow_cannot_publish_before_source_verification(self):
        text=(Path(__file__).parent.parent/'.github/workflows/publish-release.yml').read_text()
        self.assertLess(text.index('run: python3 branding/prepare_customer_source.py\n'),text.index('- name: Build staging APK'))
        self.assertLess(text.index('prepare_customer_source.py --verify'),text.index('- name: Publish GitHub Release'))
        self.assertIn('target_commitish: ${{ github.sha }}',text)

if __name__=='__main__':unittest.main()
