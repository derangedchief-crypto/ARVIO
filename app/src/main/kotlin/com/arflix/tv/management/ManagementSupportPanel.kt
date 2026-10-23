package com.arflix.tv.management

import android.app.Activity
import android.app.AlertDialog
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.os.Build
import android.view.View
import com.arflix.tv.BuildConfig
import kotlinx.coroutines.suspendCancellableCoroutine
import java.io.ByteArrayOutputStream
import kotlin.coroutines.resume

/** A dedicated safe app view. NEVER draws a window, account form, TV surface or other app. */
internal class ManagementSupportPanel(context: android.content.Context) : View(context) {
    override fun onMeasure(widthMeasureSpec: Int,heightMeasureSpec: Int) {
        val width=resolveSize(512,widthMeasureSpec)
        setMeasuredDimension(width,resolveSize(width*288/512,heightMeasureSpec))
    }
    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas);canvas.drawColor(Color.rgb(20,24,32))
        canvas.save();val scale=minOf(width/512f,height/288f);canvas.scale(scale,scale)
        val paint=Paint(Paint.ANTI_ALIAS_FLAG).apply { color=Color.WHITE;textSize=22f }
        val lines=listOf("${com.arflix.tv.branding.AppBrand.name} — private support panel","App: ${BuildConfig.VERSION_NAME.take(40)}","Android SDK: ${Build.VERSION.SDK_INT}","App-only capture with customer consent","No account details or stream image included")
        lines.forEachIndexed { index,line->canvas.drawText(line,20f,42f+index*44f,paint) }
        canvas.restore()
    }
    fun png(): ByteArray {
        val bitmap=Bitmap.createBitmap(512,288,Bitmap.Config.ARGB_8888)
        return try {measure(MeasureSpec.makeMeasureSpec(512,MeasureSpec.EXACTLY),MeasureSpec.makeMeasureSpec(288,MeasureSpec.EXACTLY));layout(0,0,512,288);draw(Canvas(bitmap));ByteArrayOutputStream().use { out->check(bitmap.compress(Bitmap.CompressFormat.PNG,100,out));out.toByteArray().also { check(it.size<=131072) } }}finally { bitmap.recycle() }
    }
}
internal suspend fun requestManagementSupportPanel(activity: Activity,expiresAtMs: Long,eligible: ()->Boolean): ByteArray? = suspendCancellableCoroutine { continuation ->
    if(!eligible()||activity.isFinishing||System.currentTimeMillis()>=expiresAtMs){continuation.resume(null);return@suspendCancellableCoroutine}
    val panel=ManagementSupportPanel(activity)
    val dialog=AlertDialog.Builder(activity).setTitle("Share this app support panel?")
        .setMessage("Your reseller requested an app-only support image. Only the safe panel below is shared—not your TV picture, current page, accounts or other apps. You can decline. Images expire from the private control center after seven days.")
        .setView(panel).setPositiveButton("Share panel once") { _,_->
            if(continuation.isActive)continuation.resume(if(eligible()&&System.currentTimeMillis()<expiresAtMs)runCatching { panel.png() }.getOrNull() else null)
        }.setNegativeButton("Decline") { _,_->if(continuation.isActive)continuation.resume(null) }.create()
    dialog.setOnCancelListener {if(continuation.isActive)continuation.resume(null)}
    continuation.invokeOnCancellation {activity.runOnUiThread {dialog.dismiss()}}
    dialog.show()
}
