package com.arflix.tv.data.repository

import org.junit.Assert.*
import org.junit.Test

class ManagedFavouritesTest {
    private val a=ManagedFavouriteKey("channel","list_1:xtream:1")
    private val b=ManagedFavouriteKey("channel","list_1:xtream:2")
    private val g=ManagedFavouriteKey("group","News")
    private fun state()=ManagedFavouriteState("profile","source",listOf(a.identity),emptyList(),"raw","[]",listOf(ManagedFavouriteItem(a,"A",true),ManagedFavouriteItem(b,"B",true),ManagedFavouriteItem(g,"News",true)))
    @Test fun mergePreservesCurrentFavouritesAndAppendsMissingBackupEntriesInOrder() {
        val patch=planManagedFavouriteRestore(state(),listOf(b,a,g),"merge")!!
        assertEquals(listOf(a.identity,b.identity),patch.channels);assertEquals(listOf("News"),patch.groups)
    }
    @Test fun emptyCurrentListRecoversTheSavedOrderAndReplaceIsExplicit() {
        val empty=state().copy(channels=emptyList())
        assertEquals(listOf(b.identity,a.identity),planManagedFavouriteRestore(empty,listOf(b,a),"merge")!!.channels)
        assertEquals(listOf(b.identity),planManagedFavouriteRestore(state(),listOf(b),"replace")!!.channels)
    }
    @Test fun missingDuplicateAndUnknownKindsCannotBecomeBlindWrites() {
        assertNull(planManagedFavouriteRestore(state(),listOf(a,a),"merge"))
        assertNull(planManagedFavouriteRestore(state(),listOf(ManagedFavouriteKey("channel","unknown")),"replace"))
        assertNull(planManagedFavouriteRestore(state(),listOf(ManagedFavouriteKey("shell","wipe")),"merge"))
        assertNull(planManagedFavouriteRestore(state(),emptyList(),"replace"))
    }
}
