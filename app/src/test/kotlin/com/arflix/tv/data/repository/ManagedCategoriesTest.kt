package com.arflix.tv.data.repository

import org.junit.Assert.*
import org.junit.Test

class ManagedCategoriesTest {
    private val source=ManagedCategorySource("list_1","Playlist 1","private identity",listOf(
        ManagedCategoryGroup("list_1|News","News",10),ManagedCategoryGroup("list_1|Sports","Sports",20),ManagedCategoryGroup("list_1|Movies","Movies",30)))
    private fun state()=ManagedCategoryState("profile",listOf(source),listOf("other|Private","list_1|Unavailable"),
        listOf("list_1|News","other|A","list_1|Sports","list_1|Unavailable","other|B"),"hidden raw","order raw","3")
    @Test fun visibilityPreservesOtherSourcesUnavailableEntriesAndOrder() {
        val state=state();val hidden=planManagedCategoryPatch(state,source,"hide","list_1|News")!!
        assertEquals(state.hidden+"list_1|News",hidden.hidden);assertEquals(state.order,hidden.order);assertFalse(hidden.writesOrder)
        val shown=planManagedCategoryPatch(state.copy(hidden=hidden.hidden),source,"show","list_1|News")!!
        assertEquals(state.hidden,shown.hidden)
        assertNull(planManagedCategoryPatch(state,source,"hide","other|Private"))
    }
    @Test fun movingAcrossPagesUsesFullSourceOrderAndPreservesOtherKeys() {
        val state=state();val moved=planManagedCategoryPatch(state,source,"move_top","list_1|Movies")!!
        assertEquals(listOf("list_1|Movies","list_1|News","list_1|Sports"),managedCategoryOrder(source,moved.order))
        assertEquals(listOf("other|A","list_1|Unavailable","other|B"),moved.order.filterNot { it in source.groups.map { g->g.key } })
        assertEquals(state.hidden,moved.hidden);assertFalse(moved.writesHidden)
        assertNull(planManagedCategoryPatch(state,source,"wipe",null))
    }
    @Test fun resetIsSourceOnlyAndDoesNotResetVisibility() {
        val state=state();val reset=planManagedCategoryPatch(state,source,"reset_order",null)!!
        assertEquals(listOf("other|A","other|B"),reset.order);assertEquals(state.hidden,reset.hidden)
    }
    @Test fun legacyOrderingIsNeverSilentlyMigratedByRemoteReorder() {
        val state=state().copy(schemaRaw="1")
        assertNull(planManagedCategoryPatch(state,source,"move_up","list_1|News"))
        assertNull(planManagedCategoryPatch(state,source,"reset_order",null))
        assertNotNull(planManagedCategoryPatch(state,source,"show","list_1|News"))
    }
    @Test fun profileSourceCatalogOrAnyPreferenceConflictRejectsThePatch() {
        assertTrue(validManagedCategorySourceIds(listOf("list_1","stalker1")))
        for(ids in listOf(listOf("a|b"),listOf("a:b"),listOf("list_1","list_1"),emptyList()))assertFalse(validManagedCategorySourceIds(ids))
        val expected=state()
        for(current in listOf(expected.copy(profileId="other"),expected.copy(hiddenRaw="changed"),expected.copy(orderRaw="changed"),
            expected.copy(sources=listOf(source.copy(privateIdentity="different source"))),expected.copy(sources=listOf(source.copy(groups=source.groups.drop(1)))))) {
            assertNull(guardedManagedCategoryPatch(expected,current,source.id,"hide","list_1|News"))
        }
        assertNotNull(guardedManagedCategoryPatch(expected,expected,source.id,"hide","list_1|News"))
    }
}
