"""Keep previously reported main-thread/resource failure paths out of releases."""
from pathlib import Path
import unittest
from central_builder import verify_network_resources

ROOT=Path(__file__).resolve().parents[1]
K=ROOT/'app/src/main/kotlin/com/arflix/tv'

class SentryReleaseGuardTests(unittest.TestCase):
    def test_public_suffix_resources_are_mandatory_for_both_okhttp_layouts(self):
        for entry in ['assets/PublicSuffixDatabase.list','okhttp3/internal/publicsuffix/publicsuffixes.gz']:
            verify_network_resources([entry])
        with self.assertRaises(ValueError):verify_network_resources(['classes.dex'])
    def test_historical_epg_count_does_not_return_to_tv_main_path(self):
        text=(K/'ui/screens/tv/TvViewModel.kt').read_text()
        start=text.split('private fun startCompleteEpgBackfill(',1)[1].split('private fun requestVisibleCompleteEpgBackfill',1)[0]
        self.assertNotIn('indexedGuideChannelCount',start)
        self.assertNotIn('countChannelsWithPrograms',start)
    def test_apk_installer_streaming_remains_on_io(self):
        text=(K/'updater/ApkInstaller.kt').read_text()
        self.assertIn('suspend fun launchInstall(context: Context, apkFile: File): Boolean = withContext(Dispatchers.IO)',text)
    def test_old_card_report_has_bounded_remembered_image_requests(self):
        text=(K/'ui/components/MediaCard.kt').read_text()
        self.assertIn('val imageRequest = remember(',text)
        self.assertIn('.size(widthPx, heightPx)',text)
        self.assertIn('val logoRequest = remember(',text)
    def test_diagnostics_start_on_io_after_local_crash_fallback_is_installed(self):
        text=(K/'ArflixApplication.kt').read_text()
        self.assertIn('appScope.launch(Dispatchers.IO) {\n            DiagnosticsManager.initialize(this@ArflixApplication)',text)
        self.assertLess(text.index('Thread.setDefaultUncaughtExceptionHandler'),text.index('DiagnosticsManager.initialize'))
        reporter=(K/'util/SentryCrashReporter.kt').read_text()
        self.assertIn('if(!DiagnosticsManager.isReportingEnabled(context))return@setBeforeSend null',reporter)
    def test_native_editors_do_not_force_repeat_keyboard_binder_calls(self):
        for rel in ['ui/components/TextInputModal.kt','ui/screens/profile/ProfileDialogs.kt','ui/screens/settings/SettingsScreen.kt']:
            text=(K/rel).read_text()
            self.assertNotIn('InputMethodManager.SHOW_FORCED',text)
            self.assertIn('showEditorKeyboard(',text)

if __name__=='__main__':unittest.main()
