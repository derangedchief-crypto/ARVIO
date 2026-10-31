"""Code promotion is not publication; preserve official/reseller update identities."""
from pathlib import Path
import re
import unittest

ROOT=Path(__file__).resolve().parent.parent

class PromotionSafetyTests(unittest.TestCase):
    def test_customer_publication_is_manual_main_only_and_explicitly_confirmed(self):
        s=(ROOT/'.github/workflows/publish-release.yml').read_text()
        self.assertIn('name: Publish Customer Release',s)
        self.assertIn('workflow_dispatch:',s);self.assertNotRegex(s,re.compile(r'^\s+push:',re.M))
        self.assertIn("github.ref == 'refs/heads/main' && inputs.confirmed_publication == true",s)
        self.assertIn('default: false',s);self.assertIn('ref: ${{ github.sha }}',s)
        self.assertNotIn('875bb03c7c2f376295252a235d578fe2b4355a89',s)
        self.assertIn('branding/prepare_customer_source.py --verify',s)
        self.assertNotIn('-x :app:validateReleaseCloudSecrets',s)
        self.assertIn('3273d0b0294a689565ab4b0c8ef7e1887e2ca680240718f5521628b5c46328b7',s)
    def test_candidate_has_no_release_tag_feed_or_rollout_write(self):
        s=(ROOT/'.github/workflows/build-customer-candidate.yml').read_text()
        self.assertIn('contents: read',s);self.assertIn('actions/upload-artifact@',s)
        for forbidden in ['contents: write','action-gh-release','gh release','rollout_main.py','prepare_customer_source.py']:
            self.assertNotIn(forbidden,s)
        self.assertIn('ref: ${{ github.sha }}',s)
    def test_future_platform_advances_past_both_installed_customer_and_testing_brands(self):
        s=(ROOT/'app/build.gradle.kts').read_text();v=re.search(r'val platformVersionName = "([^"]+)"',s)[1];code=int(re.search(r'val platformVersionCode = (\d+)',s)[1])
        self.assertGreater(tuple(map(int,v.split('.'))),(2,0,129));self.assertGreater(code,445)
        self.assertRegex(s,r'versionCode = '+str(code)+r'\b');self.assertIn('versionName = "'+v+'"',s)
        php=(ROOT/'control-center/src/AppBrands.php').read_text();self.assertIn("APP_VERSION='"+v+"'",php);self.assertIn('APP_VERSION_CODE='+str(code),php)
        self.assertLessEqual(code*100000+99999,2147483647)
    def test_standalone_and_reseller_app_identities_remain_separate(self):
        s=(ROOT/'app/build.gradle.kts').read_text()
        self.assertIn('applicationId = if (brandDirectory == null) "com.arvio.tv" else "com.extreme.brand.b$brandId"',s)
        self.assertIn('applicationIdSuffix = ".beta"',s);self.assertIn('applicationIdSuffix = ".test"',s)
        gate=(ROOT/'app/src/main/kotlin/com/arflix/tv/ui/components/PrimaryActivationGate.kt').read_text();self.assertIn('if(BuildConfig.BRAND_ID.isBlank())AccountAccessNotice(inactive=true)',gate)
        client=(ROOT/'app/src/main/kotlin/com/arflix/tv/trials/TrialClient.kt').read_text();self.assertIn('if(brandId.isBlank())return TrialOffer(false)',client)
        updates=(ROOT/'app/src/main/kotlin/com/arflix/tv/updater/AppUpdateRepository.kt').read_text();self.assertIn('BrandUpdatePolicy.metadataUrl',updates);self.assertIn('api.github.com/repos/',updates)

if __name__=='__main__':unittest.main()
