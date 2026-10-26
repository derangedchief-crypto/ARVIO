package com.arflix.tv.access

import android.app.Application
import androidx.test.core.app.ApplicationProvider
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.ConscryptMode
import java.io.File
import javax.crypto.spec.SecretKeySpec

@RunWith(RobolectricTestRunner::class)
@ConscryptMode(ConscryptMode.Mode.OFF)
@Config(sdk=[28],application=Application::class,manifest=Config.NONE)
class ActivationVaultTest {
    @Test fun primaryBindingIsEncryptedPrivateAndAuthenticatedRatherThanCloudPreferences(){
        val context=ApplicationProvider.getApplicationContext<Application>()
        val key=SecretKeySpec(ByteArray(32){(it+1).toByte()},"AES")
        val vault=ActivationVault(context){key};val anchor=ActivationAnchor("synthetic-primary-user","synthetic-private-password")
        vault.save(anchor);val file=File(context.noBackupFilesDir,"verified-primary-v1.enc");val bytes=file.readBytes()
        assertFalse(String(bytes).contains(anchor.username));assertFalse(String(bytes).contains(anchor.password));assertEquals(anchor,vault.load())
        bytes[bytes.lastIndex]=(bytes.last().toInt() xor 1).toByte();file.writeBytes(bytes)
        try{vault.load();fail("Tampered activation must not be accepted")}catch(_:java.security.GeneralSecurityException){}
    }
}
