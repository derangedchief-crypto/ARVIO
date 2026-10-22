package com.arflix.tv.management

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File
import java.security.KeyFactory
import java.security.KeyStore
import java.security.Signature
import java.security.spec.X509EncodedKeySpec
import java.util.UUID

/** Compile-checked here; execute on a real supported Android device for Keystore evidence. */
@RunWith(AndroidJUnit4::class)
class ManagementVaultInstrumentedTest {
    @Test fun encryptedPolicyAndSigningIdentitySurviveVaultRecreation() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val scope = managementHash("instrumentation-only-" + UUID.randomUUID())
        val file = File(context.noBackupFilesDir, "device-management-v1/$scope.enc")
        check(!file.exists())
        try {
            val first = ManagementVault(context)
            val publicKey = first.publicKey(scope)
            val session = ManagementSession(scope, "a".repeat(32), managementHash(publicKey),
                deviceId = "b".repeat(32), credential = "c".repeat(64), policy = "suspended")
            first.save(session)
            assertFalse(String(file.readBytes(), Charsets.ISO_8859_1).contains(session.credential!!))
            val second = ManagementVault(context)
            assertEquals(session, second.load(scope))
            assertArrayEquals(publicKey, second.publicKey(scope))
            val text = enrollmentSigningText("d".repeat(64), "synthetic-proof", session.installationId, context.packageName, session.keyFingerprint)
            val signature = android.util.Base64.decode(second.sign(scope, text), android.util.Base64.NO_WRAP)
            val decoded = KeyFactory.getInstance("EC").generatePublic(X509EncodedKeySpec(publicKey))
            assertTrue(Signature.getInstance("SHA256withECDSA").run { initVerify(decoded); update(text.toByteArray()); verify(signature) })
        } finally {
            // Only this random test identity is removed. Never delete the shared
            // encryption key, real account files, profiles or customer credentials.
            KeyStore.getInstance("AndroidKeyStore").apply { load(null); deleteEntry("control-management-signing-$scope") }
            listOf(file, File(file.path + ".bak"), File(file.path + ".new")).forEach { it.delete() }
        }
    }
}
