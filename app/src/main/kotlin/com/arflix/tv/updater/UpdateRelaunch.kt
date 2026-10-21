package com.arflix.tv.updater

import android.app.Activity
import android.app.ActivityOptions
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.util.Log
import com.arflix.tv.MainActivity

/** Only updater-owned, explicit activity tokens receive launch privileges. These
 * options opt in to Android's policy; they do not override OEM/BAL restrictions. */
internal object UpdateRelaunch {
    fun statusIntent(context: Context, sessionId: Int) =
        Intent(context, UpdateInstallActivity::class.java)
            .setAction(ApkInstallReceiver.actionFor(context))
            .setData(Uri.parse("extreme-update://${context.packageName}/session/$sessionId"))
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_SINGLE_TOP)

    fun createStatusCallback(context: Context, sessionId: Int): PendingIntent = PendingIntent.getActivity(
        context, sessionId, statusIntent(context, sessionId),
        PendingIntent.FLAG_UPDATE_CURRENT or
            if (Build.VERSION.SDK_INT >= 31) PendingIntent.FLAG_MUTABLE else 0,
        creatorOptions(),
    )

    @Suppress("DEPRECATION")
    internal fun creatorOptions(): Bundle? = if (Build.VERSION.SDK_INT >= 35) {
        ActivityOptions.makeBasic().apply {
            setPendingIntentCreatorBackgroundActivityStartMode(
                if (Build.VERSION.SDK_INT >= 36) ActivityOptions.MODE_BACKGROUND_ACTIVITY_START_ALLOW_ALWAYS
                else ActivityOptions.MODE_BACKGROUND_ACTIVITY_START_ALLOWED)
        }.toBundle()
    } else null

    @Suppress("DEPRECATION")
    private fun senderOptions(): Bundle? = if (Build.VERSION.SDK_INT >= 34) {
        ActivityOptions.makeBasic().apply {
            setPendingIntentBackgroundActivityStartMode(
                if (Build.VERSION.SDK_INT >= 36) ActivityOptions.MODE_BACKGROUND_ACTIVITY_START_ALLOW_ALWAYS
                else ActivityOptions.MODE_BACKGROUND_ACTIVITY_START_ALLOWED)
        }.toBundle()
    } else null

    internal fun launchIntent(context: Context) = Intent(context, MainActivity::class.java)
        .setAction(Intent.ACTION_MAIN)
        .addCategory(Intent.CATEGORY_LAUNCHER)
        .addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_SINGLE_TOP)
        .apply { if (context !is Activity) addFlags(Intent.FLAG_ACTIVITY_NEW_TASK) }

    fun openApp(context: Context) {
        runCatching {
            val intent = launchIntent(context)
            if (context is Activity) {
                // The new status callback is already an activity: no receiver
                // trampoline, launcher resolution or destructive CLEAR_TASK.
                context.startActivity(intent)
            } else {
                // Compatibility for old installer sessions and MY_PACKAGE_REPLACED.
                // Android may silently block this background attempt. Do not
                // claim success merely because PendingIntent.send did not throw.
                val pending = PendingIntent.getActivity(context, 7301, intent,
                    PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE, creatorOptions())
                pending.send(context, 0, null, null, null, null, senderOptions())
            }
        }.onFailure { Log.e("UpdateRelaunch", "Unable to reopen updated app; use the installer Open button or app icon.", it) }
    }
}
