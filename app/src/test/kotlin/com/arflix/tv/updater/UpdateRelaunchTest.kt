package com.arflix.tv.updater

import android.app.Activity
import android.app.Application
import android.content.Intent
import android.content.pm.PackageInstaller
import com.arflix.tv.MainActivity
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import org.robolectric.annotation.ConscryptMode

@RunWith(RobolectricTestRunner::class)
@ConscryptMode(ConscryptMode.Mode.OFF)
@Config(sdk = [28], application = Application::class, manifest = Config.NONE)
class UpdateRelaunchTest {
    @Test fun sessionCallbackIsExplicitActivityNotBroadcastAndSessionScoped() {
        val context = RuntimeEnvironment.getApplication()
        val callback = UpdateRelaunch.createStatusCallback(context, 41)
        val shadow = shadowOf(callback)
        assertTrue(shadow.isActivity)
        assertEquals(UpdateInstallActivity::class.java.name, shadow.savedIntent.component?.className)
        assertEquals(context.packageName, shadow.savedIntent.component?.packageName)
        assertEquals(ApkInstallReceiver.actionFor(context), shadow.savedIntent.action)
        assertTrue(shadow.savedIntent.dataString.orEmpty().endsWith("/session/41"))
        assertNotEquals(callback, UpdateRelaunch.createStatusCallback(context, 42))
    }

    @Test fun foregroundRelaunchReusesTaskAndDoesNotClearIt() {
        val activity = Robolectric.buildActivity(Activity::class.java).setup()
        try {
            UpdateRelaunch.openApp(activity.get())
            val launched = shadowOf(activity.get()).nextStartedActivity
            assertEquals(MainActivity::class.java.name, launched.component?.className)
            assertEquals(0, launched.flags and Intent.FLAG_ACTIVITY_CLEAR_TASK)
            assertEquals(0, launched.flags and Intent.FLAG_ACTIVITY_NEW_TASK)
            assertNotEquals(0, launched.flags and Intent.FLAG_ACTIVITY_SINGLE_TOP)
            assertNotEquals(0, launched.flags and Intent.FLAG_ACTIVITY_CLEAR_TOP)
        } finally { activity.pause().stop().destroy() }
    }

    @Test fun backgroundCompatibilityTokenTargetsMainActivityDirectly() {
        val context = RuntimeEnvironment.getApplication()
        UpdateRelaunch.openApp(context)
        val launched = shadowOf(context).nextStartedActivity
        assertEquals(MainActivity::class.java.name, launched.component?.className)
        assertNotEquals(0, launched.flags and Intent.FLAG_ACTIVITY_NEW_TASK)
        assertEquals(0, launched.flags and Intent.FLAG_ACTIVITY_CLEAR_TASK)
    }

    @Test fun successfulInstallCallbackOpensMainFromForegroundActivity() {
        val activity = Robolectric.buildActivity(Activity::class.java).setup()
        val statuses = UpdateStatusManager()
        try {
            ApkInstallResultHandler.handle(activity.get(), Intent().putExtra(
                PackageInstaller.EXTRA_STATUS, PackageInstaller.STATUS_SUCCESS), statuses)
            assertTrue(statuses.status.value is UpdateStatus.Success)
            assertEquals(MainActivity::class.java.name, shadowOf(activity.get()).nextStartedActivity.component?.className)
        } finally { activity.pause().stop().destroy() }
    }

    @Test fun pendingUserActionStillLaunchesAndroidConfirmationNotAppHome() {
        val activity = Robolectric.buildActivity(Activity::class.java).setup()
        val statuses = UpdateStatusManager()
        try {
            ApkInstallResultHandler.handle(activity.get(), Intent().putExtra(
                PackageInstaller.EXTRA_STATUS, PackageInstaller.STATUS_PENDING_USER_ACTION)
                .putExtra(Intent.EXTRA_INTENT, Intent("fixture.android.CONFIRM_INSTALL")), statuses)
            assertEquals("fixture.android.CONFIRM_INSTALL", shadowOf(activity.get()).nextStartedActivity.action)
            assertTrue(statuses.status.value is UpdateStatus.Installing)
        } finally { activity.pause().stop().destroy() }
    }

    @Test fun missingConfirmationIsRetryableFailureNotSuccess() {
        val statuses = UpdateStatusManager()
        ApkInstallResultHandler.handle(RuntimeEnvironment.getApplication(), Intent().putExtra(
            PackageInstaller.EXTRA_STATUS, PackageInstaller.STATUS_PENDING_USER_ACTION), statuses)
        assertTrue(statuses.status.value is UpdateStatus.Failure)
    }

    @Test fun cancelledInstallerNeverPublishesSuccess() {
        val statuses = UpdateStatusManager()
        ApkInstallResultHandler.handle(RuntimeEnvironment.getApplication(), Intent().putExtra(
            PackageInstaller.EXTRA_STATUS, PackageInstaller.STATUS_FAILURE_ABORTED), statuses)
        assertTrue(statuses.status.value is UpdateStatus.Failure)
    }

    @Test fun oldAndroidDoesNotCallUnavailableCreatorOptInApi() {
        assertNull(UpdateRelaunch.creatorOptions())
    }

    @Test @Config(sdk = [34]) fun modernInstallerCallbackAllowsSystemToFillInResultExtras() {
        val callback = UpdateRelaunch.createStatusCallback(RuntimeEnvironment.getApplication(), 51)
        assertTrue(callback.isActivity)
        assertFalse(callback.isImmutable)
    }
}
