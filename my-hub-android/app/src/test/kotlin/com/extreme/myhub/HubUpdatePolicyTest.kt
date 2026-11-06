package com.extreme.myhub

import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test

class HubUpdatePolicyTest {
    private fun asset(v: String) = JSONObject().put("name", "My-Hub-$v-testing.apk").put("state", "uploaded")
        .put("size", 9000000L).put("digest", "sha256:" + "a".repeat(64))
        .put("browser_download_url", "https://github.com/derangedchief-crypto/ARVIO/releases/download/staging-testing/My-Hub-$v-testing.apk")

    @Test fun numericVersionSelectionIgnoresOtherAppsAndIncompleteUploads() {
        val assets = listOf(asset("0.1.9"), asset("0.1.10"), asset("0.1.8"), asset("0.2.0").put("state", "new"), asset("99.0.0").put("name", "My-TV-99.0.0-testing.apk"))
        assertEquals("0.1.10", HubUpdatePolicy.latest(assets, "0.1.9-dev")?.version)
        assertNull(HubUpdatePolicy.latest(assets, "0.1.10-dev"))
        assertNull(HubUpdatePolicy.latest(assets, "0.2.0-dev"))
    }
    @Test fun metadataMustHaveExactDownloadPathAndDigest() {
        for (bad in listOf(asset("0.2.0").put("browser_download_url", "https://example.test/wrong.apk"), asset("0.2.0").put("digest", ""), asset("0.2.0").put("size", 0))) {
            assertThrows(IllegalArgumentException::class.java) { HubUpdatePolicy.latest(listOf(bad), "0.1.9-dev") }
        }
    }
    @Test fun wrongPackageWrongVersionDowngradeAndDifferentSignerCannotInstall() {
        val release = HubUpdatePolicy.latest(listOf(asset("0.1.10")), "0.1.9-dev")!!
        fun verify(pkg: String = "com.extreme.myhub.dev", name: String = "0.1.10-dev", code: Long = 11, signers: Set<String> = setOf("known")) {
            HubUpdatePolicy.verifyIdentity(release, pkg, "com.extreme.myhub.dev", name, code, 10, signers, setOf("known"))
        }
        verify()
        assertThrows(IllegalArgumentException::class.java) { verify(pkg = "com.extreme.myhub") }
        assertThrows(IllegalArgumentException::class.java) { verify(name = "0.1.8-dev") }
        assertThrows(IllegalArgumentException::class.java) { verify(code = 10) }
        assertThrows(IllegalArgumentException::class.java) { verify(signers = setOf("different")) }
        assertThrows(IllegalArgumentException::class.java) { verify(signers = emptySet()) }
    }
}
