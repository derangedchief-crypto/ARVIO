package com.arflix.tv.management

import com.arflix.tv.data.repository.*
import com.google.gson.Gson
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.mockk
import kotlinx.coroutines.test.runTest
import org.junit.Assert.*
import org.junit.Test

class ManagementFavouritesTest {
    private val account=ManagementAccount("fixture","password")
    private val a=ManagedFavouriteKey("channel","list_1:xtream:1")
    private val b=ManagedFavouriteKey("channel","list_1:xtream:2")
    private val g=ManagedFavouriteKey("group","News")
    private val original=ManagedFavouriteState("profile","private source",listOf(a.identity,b.identity),listOf(g.identity),"[a,b]","[g]",listOf(ManagedFavouriteItem(a,"News Channel",true),ManagedFavouriteItem(b,"Sports",true),ManagedFavouriteItem(g,"News",true)))
    private val protocol=ManagementFavouriteProtocol("ab".repeat(32),account.scope)
    private val backup=protocol.snapshot(original)
    @Test fun contentIdentityChangesOnWipeButSourceDoesNotAndNoRawConfigIsExported() {
        val empty=original.copy(channels=emptyList(),groups=emptyList(),channelsRaw="[]",groupsRaw="[]",items=emptyList())
        assertEquals(protocol.source(original),protocol.source(empty))
        assertNotEquals(protocol.content(original),protocol.content(empty))
        assertTrue(protocol.snapshot(empty).entries.isEmpty())
        assertFalse(Gson().toJson(backup).contains("private source"))
        assertNotEquals(backup.source_ref,ManagementFavouriteProtocol("cd".repeat(32),account.scope).source(original))
    }
    @Test fun previewAfterWipeDoesNotWriteAndRestoresAvailableFavouritesInOneCall() = runTest {
        val repository=mockk<IptvRepository>();val profiles=mockk<ProfileManager>();coEvery { profiles.getProfileId() } returns "profile"
        val wiped=original.copy(channels=emptyList(),groups=emptyList(),channelsRaw="[]",groupsRaw="[]")
        coEvery { repository.readManagedFavourites("profile",any(),any()) } returns wiped
        val actions=ManagementFavouriteActions(repository,profiles,"ab".repeat(32));val backupId="a".repeat(32)
        val result=actions.execute("preview","preview_favourites_restore",account.scope,mapOf("backup_id" to backupId),backup,null)
        val preview=result.favouritesPreview!!;assertEquals(2,preview.matched_channels);assertEquals(1,preview.matched_groups)
        coVerify(exactly=0) { repository.restoreManagedFavourites(any(),any(),any(),any()) }
        coEvery { repository.restoreManagedFavourites(wiped,listOf(a,b,g),"merge",any()) } returns true
        val restored=actions.execute("restore","restore_favourites",account.scope,mapOf("backup_id" to backupId,"mode" to "merge"),backup,preview)
        assertEquals("succeeded",restored.status)
        coVerify(exactly=1) { repository.restoreManagedFavourites(wiped,listOf(a,b,g),"merge",any()) }
    }
    @Test fun stalePreviewDifferentProfileAndChangedSourcesNeverWrite() = runTest {
        val repository=mockk<IptvRepository>();val profiles=mockk<ProfileManager>();coEvery { profiles.getProfileId() } returns "profile"
        coEvery { repository.readManagedFavourites("profile",any(),any()) } returns original
        val actions=ManagementFavouriteActions(repository,profiles,"ab".repeat(32));val id="b".repeat(32)
        val preview=actions.execute("p","preview_favourites_restore",account.scope,mapOf("backup_id" to id),backup,null).favouritesPreview!!
        coEvery { repository.readManagedFavourites("profile",any(),any()) } returns original.copy(channelsRaw="local edit")
        assertEquals("failed",actions.execute("r","restore_favourites",account.scope,mapOf("backup_id" to id,"mode" to "merge"),backup,preview).status)
        coEvery { repository.readManagedFavourites("profile",any(),any()) } returns original.copy(privateSourceIdentity="changed provider")
        assertEquals("failed",actions.execute("p","preview_favourites_restore",account.scope,mapOf("backup_id" to id),backup,null).status)
        coEvery { profiles.getProfileId() } returns "other"
        assertEquals("failed",actions.execute("p","preview_favourites_restore",account.scope,mapOf("backup_id" to id),backup,null).status)
        coVerify(exactly=0) { repository.restoreManagedFavourites(any(),any(),any(),any()) }
    }
    @Test fun missingChannelsAreReportedAndCannotClearCurrentFavouritesThroughReplace() = runTest {
        val repository=mockk<IptvRepository>();val profiles=mockk<ProfileManager>();coEvery { profiles.getProfileId() } returns "profile"
        val state=original.copy(items=original.items.map { if(it.key==b)it.copy(available=false)else it })
        coEvery { repository.readManagedFavourites("profile",any(),any()) } returns state
        val actions=ManagementFavouriteActions(repository,profiles,"ab".repeat(32));val id="c".repeat(32)
        val p=actions.execute("p","preview_favourites_restore",account.scope,mapOf("backup_id" to id),backup,null).favouritesPreview!!
        assertEquals(1,p.missing_refs.size)
        assertEquals("failed",actions.execute("r","restore_favourites",account.scope,mapOf("backup_id" to id,"mode" to "replace"),backup,p).status)
        coVerify(exactly=0) { repository.restoreManagedFavourites(any(),any(),any(),any()) }
        coEvery { repository.restoreManagedFavourites(state,listOf(a,g),"merge",any()) } returns true
        assertEquals("succeeded",actions.execute("r","restore_favourites",account.scope,mapOf("backup_id" to id,"mode" to "merge"),backup,p).status)
    }
}
