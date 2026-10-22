package com.arflix.tv.management

import com.arflix.tv.data.repository.IptvConfig
import com.arflix.tv.data.repository.IptvRepository
import com.arflix.tv.data.repository.ManagedCategoryGroup
import com.arflix.tv.data.repository.ManagedCategorySource
import com.arflix.tv.data.repository.ManagedCategoryState
import com.arflix.tv.data.repository.ProfileManager
import com.google.gson.Gson
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.test.runTest
import org.junit.Assert.*
import org.junit.Test

class ManagementCategoriesTest {
    private val account=ManagementAccount("fixture","private-password")
    private val source=ManagedCategorySource("list_1","Playlist 1","https://provider.test/get.php?password=private-password",(1..60).map {
        ManagedCategoryGroup("list_1|Category $it","Category $it",it)
    })
    private val state=ManagedCategoryState("profile",listOf(source),listOf("list_1|Category 1"),emptyList(),"private hidden", "", "3")
    private val protocol=ManagementCategoryProtocol("ab".repeat(32),account.scope)
    private val config=IptvConfig(m3uUrl="https://tv.extremeiptv.net fixture private-password")
    @Test fun sourceRefsArePrivateKeyedDeviceProfileAndSourceBound() {
        val ref=protocol.sourceRef(state,source)
        assertTrue(ref.matches(Regex("[a-f0-9]{64}")))
        assertNotEquals(ref,ManagementCategoryProtocol("cd".repeat(32),account.scope).sourceRef(state,source))
        assertNotEquals(ref,protocol.sourceRef(state.copy(profileId="other"),source))
        assertNotEquals(ref,protocol.sourceRef(state,source.copy(privateIdentity="new credentials")))
        assertNotEquals(protocol.revision(state,source),protocol.revision(state.copy(hiddenRaw="changed"),source))
    }
    @Test fun catalogIsPagedAndNeverContainsSourceCredentialsOrRawKeys() {
        val first=protocol.catalog(state,source,1);val second=protocol.catalog(state,source,2)
        assertEquals(50,first.items.size);assertEquals(10,second.items.size);assertEquals(51,second.items.first().position)
        assertTrue(first.items.first().hidden)
        val text=Gson().toJson(first)
        listOf("private-password","https://provider","list_1|").forEach { assertFalse(text.contains(it)) }
        assertTrue(runCatching { protocol.catalog(state,source,3) }.isFailure)
    }
    @Test fun labelRedactionPreservesUnicodeWithoutSplittingSurrogates() {
        listOf("http://private.test/?password=x","rtsp://private/user/pass","username=private","Bearer private","mac=private").forEach {
            assertEquals("[redacted category label]",categoryLabel(it))
        }
        val label=categoryLabel("😀".repeat(100));assertEquals(80,label.codePointCount(0,label.length))
        assertEquals("News",categoryLabel("\u0001News\n"))
    }
    @Test fun payloadsAreClosedAndCannotSelectArbitrarySourcesOrOperations() {
        val base=mapOf("profile_ref" to managementHash("profile"),"source_ref" to protocol.sourceRef(state,source),"expected_revision" to protocol.revision(state,source),"operation" to "hide","category_ref" to protocol.groupRef(state,source,source.groups.first().key))
        assertTrue(validCategoryPayload("set_categories",base))
        assertFalse(validCategoryPayload("set_categories",base+mapOf("url" to "https://forbidden.test")))
        assertFalse(validCategoryPayload("set_categories",base+mapOf("operation" to "delete_profile")))
        assertTrue(validCategoryPayload("get_categories",emptyMap()))
        assertFalse(validCategoryPayload("get_categories",mapOf("source_ref" to "a".repeat(64))))
        assertFalse(validCategoryPayload("get_categories",mapOf("page" to "41")))
    }
    @Test fun fullCatalogPagesNeverPersistInsideTheEncryptedReceiptJournal() {
        val catalog=protocol.catalog(state,source,1)
        val receipt=CommandReceipt("id","succeeded","read",catalog=catalog,categoryProfileRef=catalog.profileRef,categorySourceRef=catalog.sourceRef,categoryPage=1)
        val raw=Gson().toJson(receipt);assertFalse(raw.contains("Category 1"));assertFalse(raw.contains("\"catalog\""))
        val restored=Gson().fromJson(raw,CommandReceipt::class.java)
        assertEquals(catalog.sourceRef,categoryRetryPayload(restored)!!["source_ref"])
        assertEquals(catalog.profileRef,categoryRetryPayload(restored)!!["profile_ref"])
        assertNull(categoryRetryPayload(restored.copy(status="failed")))
    }
    @Test fun actionChecksRevisionAndKnownCategoryBeforeAnyWrite() = runTest {
        val repository=mockk<IptvRepository>();val profiles=mockk<ProfileManager>()
        coEvery { profiles.getProfileId() } returns "profile"
        coEvery { repository.readManagedCategories("profile",any()) } coAnswers {
            assertTrue(arg<(IptvConfig)->Boolean>(1)(config));state
        }
        val actions=ManagementCategoryActions(repository,profiles,"ab".repeat(32))
        val base=mapOf("profile_ref" to managementHash("profile"),"source_ref" to protocol.sourceRef(state,source),"expected_revision" to protocol.revision(state,source),"operation" to "hide","category_ref" to protocol.groupRef(state,source,source.groups.first().key))
        assertEquals("failed",actions.execute("id","set_categories",account.scope,base+mapOf("expected_revision" to "0".repeat(64))).status)
        assertEquals("failed",actions.execute("id","set_categories",account.scope,base+mapOf("category_ref" to "0".repeat(64))).status)
        coVerify(exactly=0) { repository.applyManagedCategoryPatch(any(),any(),any(),any(),any()) }
        coEvery { repository.applyManagedCategoryPatch(state,"list_1","hide",source.groups.first().key,any()) } returns true
        val result=actions.execute("id","set_categories",account.scope,base)
        assertEquals("succeeded",result.status);assertNull(result.catalog)
        coVerify(exactly=1) { repository.applyManagedCategoryPatch(state,"list_1","hide",source.groups.first().key,any()) }
    }
    @Test fun unavailableOrChangedSourcesFailWithoutMutating() = runTest {
        val repository=mockk<IptvRepository>();val profiles=mockk<ProfileManager>()
        coEvery { profiles.getProfileId() } returns "profile"
        coEvery { repository.readManagedCategories("profile",any()) } returns state
        val actions=ManagementCategoryActions(repository,profiles,"ab".repeat(32))
        assertEquals("failed",actions.execute("id","get_categories",account.scope,mapOf("profile_ref" to managementHash("other"),"source_ref" to "a".repeat(64))).status)
        assertEquals("failed",actions.execute("id","get_categories",account.scope,mapOf("profile_ref" to managementHash("profile"),"source_ref" to "a".repeat(64))).status)
        val page=actions.execute("id","get_categories",account.scope,emptyMap())
        assertEquals("succeeded",page.status);assertEquals(50,page.catalog!!.items.size)
        coVerify(exactly=0) { repository.applyManagedCategoryPatch(any(),any(),any(),any(),any()) }
    }
    @Test fun outerExecutorDiscardsAReadIfTheProfileChangesBeforePublishing() = runTest {
        val repository=mockk<IptvRepository>();val profiles=mockk<ProfileManager>();var current="profile"
        coEvery { profiles.getProfileId() } coAnswers { current }
        every { repository.observeConfig() } returns flowOf(config)
        coEvery { repository.readManagedCategories("profile",any()) } coAnswers { current="other";state }
        val result=ManagementActions(mockk(),repository,profiles,"ab".repeat(32)).execute("id","get_categories",account.scope)
        assertEquals("failed",result.status);assertNull(result.catalog)
    }
    @Test fun waitingForACatalogCannotHoldTheManagementLoopIndefinitely() = runTest {
        val repository=mockk<IptvRepository>();val profiles=mockk<ProfileManager>()
        coEvery { profiles.getProfileId() } returns "profile"
        every { repository.observeConfig() } returns flowOf(config)
        coEvery { repository.readManagedCategories("profile",any()) } coAnswers { awaitCancellation() }
        val result=ManagementActions(mockk(),repository,profiles,"ab".repeat(32)).execute("id","get_categories",account.scope)
        assertEquals("failed",result.status);assertTrue(result.summary.contains("timed out"));assertNull(result.catalog)
    }
}
