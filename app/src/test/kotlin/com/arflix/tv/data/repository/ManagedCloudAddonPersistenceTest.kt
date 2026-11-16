package com.arflix.tv.data.repository

import com.arflix.tv.data.api.StreamApi
import com.arflix.tv.data.api.StremioManifestResponse
import io.mockk.*
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.async
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import okhttp3.OkHttpClient
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import org.robolectric.annotation.ConscryptMode
import java.io.IOException

@RunWith(RobolectricTestRunner::class)
@ConscryptMode(ConscryptMode.Mode.OFF)
@Config(sdk = [28], application = com.arflix.tv.SentryRegressionTestApplication::class, manifest = Config.NONE)
class ManagedCloudAddonPersistenceTest {
    private val oldUrl = "https://fixture.test/old/manifest.json"
    private val newUrl = "https://fixture.test/new/manifest.json"
    private val api = mockk<StreamApi>()
    private fun repository(): StreamRepository {
        val profile = mockk<ProfileManager>(relaxed = true)
        every { profile.getProfileIdSync() } returns "default"
        every { profile.activeProfileId } returns MutableStateFlow("default")
        val runtime = mockk<HttpLocalScraperRuntime>(relaxed = true)
        coEvery { runtime.fetchInstallCandidate(any(), any()) } returns null
        return StreamRepository(RuntimeEnvironment.getApplication(), api, OkHttpClient(), profile,
            mockk(relaxed = true), mockk(relaxed = true), runtime, mockk(relaxed = true),
            mockk(relaxed = true), mockk(relaxed = true))
    }
    private fun manifests() {
        coEvery { api.getAddonManifest(any()) } returns StremioManifestResponse(
            "fixture.scraper", "Fixture", "1", resources = listOf("stream"), types = listOf("movie"))
    }

    @Test fun rotationSurvivesRestartAndEveryCloudImportPath() = runBlocking {
        manifests()
        val repo = repository()
        val old = repo.addCustomAddon(oldUrl).getOrThrow()
        val personal = repo.addCustomAddon("https://fixture.test/personal/manifest.json").getOrThrow()
        repo.toggleAddon(old.id)
        val new = repo.ensureManagedCloudAddon(newUrl, old.id).getOrThrow()
        assertFalse(new.isEnabled)
        val recreated = repository()
        suspend fun check() {
            val installed = recreated.installedAddons.first()
            assertFalse(installed.any { it.url == oldUrl })
            assertFalse(installed.single { it.id == new.id }.isEnabled)
            assertTrue(installed.any { it.id == personal.id })
        }
        recreated.replaceSharedAddonsFromCloud(listOf(old, personal)); check()
        recreated.replaceAddonsForProfile("second", listOf(old, personal)); check()
        recreated.replaceAddonsFromCloud(listOf(old, personal)); check()
        recreated.revokeManagedCloudAddon(newUrl, new.id)
        recreated.replaceSharedAddonsFromCloud(listOf(old, new, personal))
        assertFalse(recreated.installedAddons.first().any { it.id == old.id || it.id == new.id })
        assertTrue(recreated.installedAddons.first().any { it.id == personal.id })
    }

    @Test fun failedAndCancelledFetchPreserveWorkingSource() = runBlocking {
        manifests()
        val repo = repository()
        val old = repo.ensureManagedCloudAddon(oldUrl, null).getOrThrow()
        coEvery { api.getAddonManifest(newUrl) } throws IOException("offline")
        assertTrue(repo.ensureManagedCloudAddon(newUrl, old.id).isFailure)
        assertTrue(repo.installedAddons.first().any { it.id == old.id })
        coEvery { api.getAddonManifest(newUrl) } throws CancellationException("cancelled")
        try { repo.ensureManagedCloudAddon(newUrl, old.id); fail("Cancellation must propagate") }
        catch (_: CancellationException) { }
        assertTrue(repo.installedAddons.first().any { it.id == old.id })
    }

    @Test fun cloudPullDuringDownloadCannotRemoveTheAcceptedReplacement() = runBlocking {
        manifests()
        val repo = repository()
        val old = repo.ensureManagedCloudAddon(oldUrl, null).getOrThrow()
        val started = CompletableDeferred<Unit>()
        val resume = CompletableDeferred<Unit>()
        coEvery { api.getAddonManifest(newUrl) } coAnswers {
            started.complete(Unit); resume.await()
            StremioManifestResponse("fixture.scraper", "Fixture", "2", resources = listOf("stream"))
        }
        val replacement = async { repo.ensureManagedCloudAddon(newUrl, old.id).getOrThrow() }
        started.await()
        repo.replaceSharedAddonsFromCloud(listOf(old))
        assertTrue(repo.installedAddons.first().any { it.id == old.id })
        resume.complete(Unit)
        val new = replacement.await()
        repo.replaceSharedAddonsFromCloud(listOf(old))
        assertTrue(repo.installedAddons.first().any { it.id == new.id })
        assertFalse(repo.installedAddons.first().any { it.id == old.id })
    }
}
