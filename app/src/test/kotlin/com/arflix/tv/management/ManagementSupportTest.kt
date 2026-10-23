package com.arflix.tv.management

import android.app.Activity
import android.app.Application
import android.content.DialogInterface
import android.graphics.BitmapFactory
import android.os.Looper
import androidx.test.core.app.ApplicationProvider
import kotlinx.coroutines.async
import kotlinx.coroutines.test.*
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows
import org.robolectric.annotation.Config
import org.robolectric.annotation.ConscryptMode
import org.robolectric.annotation.GraphicsMode
import org.robolectric.shadows.ShadowAlertDialog

@RunWith(RobolectricTestRunner::class)
@ConscryptMode(ConscryptMode.Mode.OFF)
@Config(sdk=[28],application=Application::class,manifest=Config.NONE)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)
class ManagementSupportTest {
    @Test fun historyIsBoundedFixedCodeAndCannotExportAnotherAccountScope() {
        val context=ApplicationProvider.getApplicationContext<Application>()
        ManagementEvents.bind(context,"first")
        repeat(150){ManagementEvents.record(ManagementEventCode.LIVE_TUNE_REQUEST)}
        assertEquals(128,ManagementEvents.export("first").length())
        assertEquals(0,ManagementEvents.export("different").length())
        ManagementEvents.bind(context,"different")
        assertEquals(0,ManagementEvents.export("different").length())
        ManagementEvents.unbind();ManagementEvents.record(ManagementEventCode.LIVE_STOP)
        assertEquals(0,ManagementEvents.export("different").length())
    }
    @Test fun supportPanelHasStrictPixelBoundsAndNeverDrawsAnActivityWindow() {
        val panel=ManagementSupportPanel(ApplicationProvider.getApplicationContext())
        val image=panel.png();assertTrue(image.size<=131072)
        var offset=8
        val allowed=setOf("IHDR","IDAT","IEND","sBIT","sRGB","gAMA","cHRM","pHYs")
        while(offset<image.size){val length=java.nio.ByteBuffer.wrap(image,offset,4).int;val type=String(image,offset+4,4,Charsets.US_ASCII);assertTrue("Unsupported native PNG chunk: $type",type in allowed);offset+=length+12}
        assertEquals(image.size,offset)
        val bitmap=BitmapFactory.decodeByteArray(image,0,image.size)
        assertEquals(512,bitmap.width);assertEquals(288,bitmap.height);bitmap.recycle()
    }
    @Test fun decliningConsentOrChangedEligibilityProducesNoImage() = runTest {
        val controller=Robolectric.buildActivity(Activity::class.java).setup().resume()
        try {
            val activity=controller.get();var eligible=true
            val declined=async(UnconfinedTestDispatcher(testScheduler)){requestManagementSupportPanel(activity,System.currentTimeMillis()+20000){eligible}}
            ShadowAlertDialog.getLatestAlertDialog().getButton(DialogInterface.BUTTON_NEGATIVE).performClick()
            Shadows.shadowOf(Looper.getMainLooper()).idle()
            assertNull(declined.await())
            val changed=async(UnconfinedTestDispatcher(testScheduler)){requestManagementSupportPanel(activity,System.currentTimeMillis()+20000){eligible}}
            eligible=false
            ShadowAlertDialog.getLatestAlertDialog().getButton(DialogInterface.BUTTON_POSITIVE).performClick()
            Shadows.shadowOf(Looper.getMainLooper()).idle()
            assertNull(changed.await())
            eligible=true
            val accepted=async(UnconfinedTestDispatcher(testScheduler)){requestManagementSupportPanel(activity,System.currentTimeMillis()+20000){eligible}}
            ShadowAlertDialog.getLatestAlertDialog().getButton(DialogInterface.BUTTON_POSITIVE).performClick()
            Shadows.shadowOf(Looper.getMainLooper()).idle()
            assertTrue(accepted.await()!!.size<=131072)
        } finally {controller.pause().stop().destroy()}
    }
    @Test fun expiredRequestCannotOpenConsentOrCreateImage() = runTest {
        val controller=Robolectric.buildActivity(Activity::class.java).setup().resume()
        try {assertNull(requestManagementSupportPanel(controller.get(),System.currentTimeMillis()-1){true})}
        finally {controller.pause().stop().destroy()}
    }
}
