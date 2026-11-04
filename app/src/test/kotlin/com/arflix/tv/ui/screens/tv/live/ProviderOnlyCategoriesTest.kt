package com.arflix.tv.ui.screens.tv.live

import com.arflix.tv.data.model.IptvChannel
import org.junit.Assert.*
import org.junit.Test

class ProviderOnlyCategoriesTest {
    private val channels = listOf(
        channel("1", "US Sports 4K", "Provider Sports"),
        channel("2", "PL News HD", "Provider News"),
        channel("3", "US Movies", "Provider Cinema"),
    )

    private fun channel(id: String, name: String, group: String) = IptvChannel(
        id = "list_1:$id", name = name, group = group, streamUrl = "https://provider.test/live/$id",
    )

    private fun assertProviderOnly(tree: LiveCategoryTree, labels: List<String>) {
        // These are navigation controls, not inferred content categories.
        assertEquals(listOf("fav", "recent", "all"), tree.top.map { it.id })
        assertTrue(tree.top.all { it.children.isEmpty() })
        assertEquals(labels, tree.global.categories.map { it.label })
        assertTrue(tree.global.categories.all { it.id.startsWith("grp:list_1:") && it.children.isEmpty() })
        assertTrue(tree.countries.categories.isEmpty())
        assertTrue(tree.adult.categories.isEmpty())
        val folders = tvStageFolders(tree, emptyList())
        assertEquals(tree.top.map { it.id } + tree.global.categories.map { it.id }, folders.map { it.category.id })
    }

    @Test fun everyTreePathShowsOnlyActualProviderGroups() {
        val enriched = channels.mapIndexed { index, ch -> ch.enrich(index + 1) }
        val fast = buildFastStartupChannelState(channels, emptySet(), emptySet())
        val paged = buildPagedStartupChannelState(
            channels.take(1), channels.size,
            channels.map { Triple("list_1", it.group, 1) }, emptySet(), emptySet(),
        )
        listOf(
            buildCategoryTree(channels, emptySet(), emptySet()),
            buildCategoryTree(enriched, 0, 0), fast.tree, paged.tree,
        ).forEach { assertProviderOnly(it, channels.map { ch -> ch.group }) }
        listOf(buildCategoryIndex(enriched), fast.index, paged.index).forEach { index ->
            assertTrue(index.byCategory.keys.all { it == "all" || it.startsWith("grp:") })
        }
    }

    @Test fun realProviderCountryAndGenreNamesAreKeptVerbatim() {
        val actual = listOf("Poland", "PL | General", "US | Sports").mapIndexed { index, group ->
            channel(index.toString(), "Channel $index", group)
        }
        assertProviderOnly(buildCategoryTree(actual, emptySet(), emptySet()), actual.map { it.group })
    }

    @Test fun hiddenGroupsAndExplicitOrderArePreservedWithoutSyntheticFallbacks() {
        val order = listOf("list_1|Provider Cinema", "list_1|Provider Sports")
        val hidden = setOf("list_1|Provider News")
        val tree = buildCategoryTree(channels, emptySet(), emptySet(), hidden, order)
        assertProviderOnly(tree, listOf("Provider Cinema", "Provider Sports"))
        assertEquals(listOf("Provider News"), tree.hidden.categories.map { it.label })
        val enriched = channels.mapIndexed { index, ch -> ch.enrich(index + 1) }
        assertEquals("all", bestCategoryIdForChannel(enriched[1], tree))
    }

    @Test fun staleGeneratedSelectionsCannotRebuildSyntheticBuckets() {
        val enriched = channels.mapIndexed { index, ch -> ch.enrich(index + 1) }
        listOf("US", "PL", "PL-general", "US-sports", "g-sports", "g-4k").forEach { id ->
            assertTrue(enriched.none(categoryMatcher(id, emptySet(), emptySet())))
            assertTrue(buildInitialCategoryChannels(channels, id, emptySet(), emptySet(), limit = 10).isEmpty())
        }
    }
}
