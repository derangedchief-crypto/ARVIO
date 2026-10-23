package com.arflix.tv.management

import com.arflix.tv.data.repository.*
import org.junit.Assert.*
import org.junit.Test

class ManagementSourcesTest {
    private val primary = IptvPlaylistEntry("list_1","Main","https://provider.test/get.php?username=fixture&password=private")
    private val original = IptvConfig(m3uUrl=primary.m3uUrl,playlists=listOf(primary))
    @Test fun supplementarySourcesNamesVisibilityAndGuidesAreEditableWithoutChangingActivation() {
        val next = original.copy(playlists=listOf(primary.copy(name="Renamed",enabled=false),IptvPlaylistEntry("extra","Extra","https://second.test/list.m3u")),stalkerPortals=listOf(StalkerPortalEntry("portal","Portal","https://portal.test","00:11:22:33:44:55")))
        assertTrue(validManagedSourceConfig(original,next))
    }
    @Test fun canonicalCredentialsRemovalReorderingAndUnsafeUrlsAreRejected() {
        val extra = IptvPlaylistEntry("extra","Extra","https://second.test/list.m3u")
        val invalid = listOf(original.copy(m3uUrl="https://other.test",playlists=listOf(primary.copy(m3uUrl="https://other.test"))),
            original.copy(playlists=emptyList(),m3uUrl=""),original.copy(playlists=listOf(extra,primary)),original.copy(playlists=listOf(primary,extra.copy(m3uUrl="file:///private"))),original.copy(playlists=listOf(primary,extra.copy(id=primary.id))))
        invalid.forEach { assertFalse(validManagedSourceConfig(original,it)) }
    }
    @Test fun sourceRevisionBindsProfileAndSourceContentButIgnoresSorting() {
        val secret="a".repeat(64)
        val revision=managedSourcesRevision(secret,"profile",original)
        assertEquals(revision,managedSourcesRevision(secret,"profile",original.copy(sortOrder="name")))
        assertNotEquals(revision,managedSourcesRevision(secret,"other-profile",original))
        assertNotEquals(revision,managedSourcesRevision(secret,"profile",original.copy(playlists=listOf(primary.copy(enabled=false)))))
        assertFalse(ManagedSourceSnapshot(profile_ref="b".repeat(64),revision=revision,config=original).toString().contains("private"))
    }
    @Test fun legacyConfigurationCanBeCheckpointedAndRolledBackWithoutForcingMigration() {
        val legacy=IptvConfig(m3uUrl=primary.m3uUrl)
        val converted=legacy.copy(playlists=listOf(primary.copy(name="List 1")))
        assertTrue(validManagedSourceConfig(legacy,converted));assertTrue(validManagedSourceConfig(converted,legacy))
        assertNotEquals(managedSourcesRevision("a".repeat(64),"profile",legacy),managedSourcesRevision("a".repeat(64),"profile",converted))
    }
    @Test fun replacementCredentialsCannotRebindOldFavouriteIdentitiesButExplicitCheckpointRestoreCan() {
        val secondary=IptvPlaylistEntry("secondary","Extra","https://old.test/list.m3u")
        val before=original.copy(playlists=listOf(primary,secondary))
        val changed=before.copy(playlists=listOf(primary,secondary.copy(m3uUrl="https://new.test/list.m3u")))
        assertFalse(validManagedSourceConfig(before,changed))
        assertTrue(validManagedSourceConfig(before,changed.copy(playlists=listOf(primary,changed.playlists[1].copy(id="new_identity")))))
        assertTrue(validManagedSourceConfig(changed,before,restoreCheckpoint=true))
        assertFalse(validManagedSourceConfig(original,original.copy(playlists=listOf(primary.copy(enabled=false)))))
    }
}
