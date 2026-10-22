package com.arflix.tv.management

import com.arflix.tv.data.repository.IptvConfig
import com.arflix.tv.data.repository.IptvPlaylistEntry
import org.junit.Assert.*
import org.junit.Test
import java.security.KeyPairGenerator
import java.security.Signature
import java.security.spec.ECGenParameterSpec

class ManagementProtocolTest {
    @Test fun savingFirstActivatedLineDoesNotRestartTheColdStartGate() {
        val initial = DeviceManagementState()
        assertTrue(preparingManagementAccount(initial, false).checkingLocalState)
        val noAccountYet = DeviceManagementState(false, status = "No eligible activation account")
        val activated = preparingManagementAccount(noAccountYet, false)
        assertFalse(activated.checkingLocalState)
        assertFalse(activated.restricted)
        val blocked = DeviceManagementState(false, "saved-id", policy = "blocked")
        assertTrue(preparingManagementAccount(blocked, true).restricted)
        assertFalse(preparingManagementAccount(blocked, true).checkingLocalState)
    }
    @Test fun existingEncodedActivationNeedsNoReactivation() {
        val account = managementAccount(IptvConfig(m3uUrl = "https://tv.extremeiptv.net/get.php?username=fixture&password=pa%26ss"))!!
        assertEquals("fixture", account.username); assertEquals("pa&ss", account.password)
        assertFalse(account.toString().contains("pa&ss"))
    }
    @Test fun legacyTripletStillResolvesFixedProviderOnly() {
        assertEquals("fixture", managementAccount(IptvConfig(m3uUrl = "https://tv.extremeiptv.net fixture password"))?.username)
        assertNull(managementAccount(IptvConfig(m3uUrl = "https://foreign.example/get.php?username=fixture&password=password")))
        assertNull(managementAccount(IptvConfig(m3uUrl = "http://tv.extremeiptv.net/get.php?username=fixture&password=password")))
        assertNull(managementAccount(IptvConfig(m3uUrl = "https://tv.extremeiptv.net:8443/get.php?username=fixture&password=password")))
    }
    @Test fun disabledPlaylistNeverEnrollsEvenIfItIsTheLegacyPrimary() {
        val url = "https://tv.extremeiptv.net/get.php?username=fixture&password=password"
        assertNull(managementAccount(IptvConfig(m3uUrl = url, playlists = listOf(IptvPlaylistEntry("list_1", "Main", url, "", enabled = false)))))
    }
    @Test fun credentialAndProfileChangesHaveExplicitAccountIsolation() {
        val a = ManagementAccount("Account", "old")
        val rotated = ManagementAccount("account", "new")
        assertEquals(a.scope, rotated.scope)
        assertNotEquals(a.scope, ManagementAccount("other", "old").scope)
        assertNotEquals(a, rotated)
    }
    @Test fun deviceSignatureIsBoundToChallengeProofInstallationAndPackage() {
        val pair = KeyPairGenerator.getInstance("EC").apply { initialize(ECGenParameterSpec("secp256r1")) }.generateKeyPair()
        val nonce = "a".repeat(64); val install = "b".repeat(32); val fingerprint = managementHash(pair.public.encoded)
        val text = enrollmentSigningText(nonce, "fixture-proof", install, "com.arvio.tv.test", fingerprint)
        assertEquals("ECENROLL1\n$nonce\n${managementHash("fixture-proof")}\n$install\ncom.arvio.tv.test\n$fingerprint", text)
        val signed = Signature.getInstance("SHA256withECDSA").apply { initSign(pair.private); update(text.toByteArray()) }.sign()
        assertTrue(Signature.getInstance("SHA256withECDSA").run { initVerify(pair.public); update(text.toByteArray()); verify(signed) })
        assertFalse(Signature.getInstance("SHA256withECDSA").run { initVerify(pair.public); update((text + "changed").toByteArray()); verify(signed) })
    }
    @Test fun onlyExplicitKnownPoliciesCanRestrictTheApp() {
        assertFalse(DeviceManagementState(false, status = "Offline").restricted)
        assertFalse(DeviceManagementState(false, policy = "active").restricted)
        listOf("blocked", "suspended", "removed").forEach { assertTrue(DeviceManagementState(false, policy = it).restricted) }
    }
    @Test fun commandAndMessageJournalCommitTogetherAndDoNotRedisplayAfterDismissal() {
        val session = ManagementSession("a".repeat(64), "b".repeat(32))
        val command = ManagementCommand("c".repeat(32), "show_message", 5000, "Hello", 30)
        val plan = planManagementCommand(session, command, 1000)
        assertEquals("succeeded", plan.receipt.status)
        assertEquals(command.id, plan.session.message?.id)
        assertEquals(plan.receipt, plan.session.receipts.single())
        val dismissed = plan.session.copy(message = null)
        val repeated = planManagementCommand(dismissed, command, 2000)
        assertNull(repeated.session.message)
        assertSame(dismissed, repeated.session)
    }
    @Test fun expiredUnsupportedAndInvalidMessagesHaveNoSideEffects() {
        val session = ManagementSession("a".repeat(64), "b".repeat(32))
        listOf(ManagementCommand("1", "restart_app", 9000), ManagementCommand("2", "show_message", 1, "hello", 30),
            ManagementCommand("3", "show_message", 9000, "hello", 99999)).forEach {
            val plan = planManagementCommand(session, it, 1000)
            assertEquals("failed", plan.receipt.status); assertNull(plan.session.message)
        }
    }
    @Test fun journalIsBoundedAndRefreshIsIdempotent() {
        var session = ManagementSession("a".repeat(64), "b".repeat(32))
        repeat(700) { session = planManagementCommand(session, ManagementCommand(it.toString(), "request_checkin", 9000), 1000).session }
        assertEquals(512, session.receipts.size)
        assertNull(session.message)
    }
    @Test fun timestampsAreStrictAndUtc() {
        assertEquals(0L, parseManagementTime("1970-01-01 00:00:00"))
        assertTrue(runCatching { parseManagementTime("2026-02-30 00:00:00") }.isFailure)
        assertTrue(runCatching { parseManagementTime("2026-10-22 00:00:00 trailing") }.isFailure)
    }
}
