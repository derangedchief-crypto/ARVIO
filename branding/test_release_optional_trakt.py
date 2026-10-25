"""Release policy only: optional Trakt stays in the APK, never a build prerequisite."""
from pathlib import Path
import unittest

ROOT = Path(__file__).resolve().parent.parent


class OptionalTraktReleaseTests(unittest.TestCase):
    def test_release_cloud_validation_keeps_required_cloud_checks_but_not_trakt(self):
        text = (ROOT/'app/build.gradle.kts').read_text()
        task = text.split('val validateReleaseCloudSecrets = tasks.register(', 1)[1].split('tasks.configureEach', 1)[0]
        self.assertNotIn('localSecretValue("TRAKT_', task)
        self.assertNotIn('require real TRAKT_', task)
        self.assertIn('require(hasValidSupabaseUrl)', task)
        self.assertIn('appAnonKey.length > 40', task)
        self.assertIn('dependsOn(validateReleaseCloudSecrets)', text)

    def test_trakt_build_values_and_apk_integration_are_retained(self):
        text = (ROOT/'app/build.gradle.kts').read_text()
        self.assertIn('defaultPropertiesFileName = "secrets.defaults.properties"', text)
        self.assertIn('propertiesFileName = "secrets.properties"', text)
        for key in ['TRAKT_CLIENT_ID', 'TRAKT_CLIENT_SECRET']:
            self.assertIn(key+'=', (ROOT/'secrets.defaults.properties').read_text())
            self.assertIn(key, (ROOT/'branding/configure_runner.py').read_text())
            self.assertIn(key, (ROOT/'app/src/main/kotlin/com/arflix/tv/util/Constants.kt').read_text())
        self.assertTrue((ROOT/'app/src/main/kotlin/com/arflix/tv/data/repository/TraktRepository.kt').is_file())
        self.assertTrue((ROOT/'app/src/main/kotlin/com/arflix/tv/data/repository/TraktSyncService.kt').is_file())


if __name__ == '__main__':
    unittest.main()
