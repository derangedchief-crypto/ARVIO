package com.arflix.tv.ui.screens.tv.live

import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.stringResource
import com.arflix.tv.R
import com.arflix.tv.data.model.IptvChannel
import com.arflix.tv.data.repository.StalkerPortalSupport

/** Broad channel genre derived from M3U group name. */
enum class Genre {
    Sports, Movies, Series, News, Kids, Music, Docs, General;
}

/** Picture quality tier derived from channel name / group. */
enum class Quality(val label: String) {
    SD("SD"), HD("HD"), FHD("FHD"), K4("4K");
}

/**
 * Enriched channel — wraps [IptvChannel] with fields parsed from its
 * M3U metadata so the spec-level sidebar / EPG / badges can render.
 *
 * We never mutate [IptvChannel]; all core IPTV code keeps working.
 */
data class EnrichedChannel(
    val source: IptvChannel,
    val number: Int,
    val country: String?, // ISO-ish 2-letter, uppercase, or null if unresolved
    val genre: Genre,
    val quality: Quality,
    val lang: String,     // 2-letter uppercase language hint
    val brandBg: Color,
    val brandFg: Color,
    val isAdult: Boolean,
) {
    val id: String get() = source.id
    val name: String get() = source.name
    val streamUrl: String get() = source.streamUrl
    val logo: String? get() = source.logo
    val catchupDays: Int get() = source.catchupDays
}

data class LiveCategoryIndex(
    val byCategory: Map<String, List<EnrichedChannel>>,
    val byId: Map<String, EnrichedChannel>,
    val hiddenIds: Set<String> = emptySet(),
) {
    fun channelsFor(
        categoryId: String,
        favorites: Collection<String>,
        recents: Collection<String>,
    ): List<EnrichedChannel> {
        return when (categoryId) {
            "fav" -> favorites.mapNotNull(byId::get).filterNot { it.isAdult || it.id in hiddenIds }
            "recent" -> recents.toList().asReversed().mapNotNull(byId::get).filterNot { it.isAdult || it.id in hiddenIds }
            else -> byCategory[categoryId].orEmpty()
        }
    }

    companion object {
        val Empty = LiveCategoryIndex(emptyMap(), emptyMap())
    }
}

fun LiveCategoryIndex.isVisibleNonAdultChannel(channelId: String): Boolean {
    val channel = byId[channelId] ?: return false
    return !channel.isAdult && channelId !in hiddenIds
}

private data class ChannelTraits(
    val country: String?,
    val genre: Genre,
    val quality: Quality,
    val lang: String,
    val brandBg: Color,
    val brandFg: Color,
    val isAdult: Boolean,
)

private val ADULT_KEYWORDS = listOf("adult", "xxx", "18+", "erot", "nsfw")
private val TAG_RE = Regex("""[|\-:/,]+""")
private val TRIM_PUNCT = Regex("""^[\s\-|:•\u2022]+|[\s\-|:•\u2022]+$""")

// Bucketing codes. Real ISO countries plus language prefixes commonly used in
// IPTV playlists (EN, JA, KO, ZH, AR, SV, DA, EL, CS, HI, HE, FA) so groups
// like "EN | CHRISTMAS 1 4K" resolve instead of falling into the null bucket.
private val KNOWN_COUNTRIES = setOf(
    // ISO 3166-1 alpha-2 plus the UK/GB, USA aliases
    "NL", "UK", "GB", "US", "USA", "DE", "FR", "IT", "ES", "PT",
    "BE", "TR", "AR", "IN", "BR", "PL", "EX", "SE", "DK", "NO",
    "FI", "RU", "GR", "RO", "HU", "CZ", "AT", "CH", "IE", "JP",
    "KR", "CN", "TW", "HK", "MX", "CA", "AU", "NZ", "ZA", "AE",
    "SA", "EG", "MA", "UA", "BG", "HR", "RS", "SK", "SI", "LT",
    "LV", "EE", "IL",
    // Language codes used as playlist buckets
    "EN", "JA", "KO", "ZH", "SV", "DA", "EL", "CS", "HI", "HE",
    "FA", "AF",
)

private val COUNTRY_ALIASES = mapOf("GB" to "UK", "USA" to "US")

// When rendering a flag for a language/bucket code that isn't itself a valid
// regional-indicator country, substitute the most-representative country flag.
// "EN" → UK flag, "JA" → JP, etc. Keeps sidebar labels honest ("EN") while
// producing a recognisable flag glyph.
private val FLAG_SUBSTITUTES = mapOf(
    "EN" to "GB",
    "JA" to "JP",
    "KO" to "KR",
    "ZH" to "CN",
    "SV" to "SE",
    "DA" to "DK",
    "EL" to "GR",
    "CS" to "CZ",
    "HI" to "IN",
    "HE" to "IL",
    "FA" to "IR",
    "AR" to "SA",
    "AF" to "ZA",
)

fun countryFlag(code: String?): String {
    if (code.isNullOrBlank() || code.length != 2) return "🌐"
    val effective = FLAG_SUBSTITUTES[code.uppercase()] ?: code.uppercase()
    return effective.map { ch ->
        val off = 0x1F1E6 + (ch.code - 'A'.code)
        String(Character.toChars(off))
    }.joinToString("")
}

/** Human-readable country / language-bucket name. Matches the mockup
 *  ("NL Netherlands", "US United States", …). */
private val COUNTRY_NAMES = mapOf(
    "NL" to "Netherlands", "UK" to "UK", "GB" to "UK",
    "US" to "USA", "DE" to "Germany", "FR" to "France",
    "IT" to "Italy", "ES" to "Spain", "PT" to "Portugal", "BE" to "Belgium",
    "TR" to "Turkey", "IN" to "India", "BR" to "Brazil", "PL" to "Poland",
    "SE" to "Sweden", "DK" to "Denmark", "NO" to "Norway", "FI" to "Finland",
    "RU" to "Russia", "GR" to "Greece", "RO" to "Romania", "HU" to "Hungary",
    "CZ" to "Czechia", "AT" to "Austria", "CH" to "Switzerland",
    "IE" to "Ireland", "JP" to "Japan", "KR" to "South Korea", "CN" to "China",
    "TW" to "Taiwan", "HK" to "Hong Kong", "MX" to "Mexico", "CA" to "Canada",
    "AU" to "Australia", "NZ" to "New Zealand", "ZA" to "South Africa",
    "AE" to "UAE", "SA" to "Saudi Arabia", "EG" to "Egypt", "MA" to "Morocco",
    "UA" to "Ukraine", "BG" to "Bulgaria", "HR" to "Croatia", "RS" to "Serbia",
    "SK" to "Slovakia", "SI" to "Slovenia", "LT" to "Lithuania",
    "LV" to "Latvia", "EE" to "Estonia", "IL" to "Israel",
    // Language buckets
    "EN" to "English", "JA" to "Japanese", "KO" to "Korean", "ZH" to "Chinese",
    "AR" to "Arabic", "SV" to "Swedish", "DA" to "Danish", "EL" to "Greek",
    "CS" to "Czech", "HI" to "Hindi", "HE" to "Hebrew", "FA" to "Persian",
    "AF" to "South Africa",
)

fun countryName(code: String): String = COUNTRY_NAMES[code.uppercase()] ?: code

/** Parse a genre out of any slice of text (group name, channel name, etc). */
fun genreFromText(text: String): Genre {
    val t = text.lowercase()
    return when {
        "sport" in t || "espn" in t || "uefa" in t -> Genre.Sports
        "movie" in t || "cinema" in t || "film" in t -> Genre.Movies
        "series" in t || "show" in t -> Genre.Series
        "news" in t || "cnn" in t || "bbc news" in t -> Genre.News
        "kids" in t || "cartoon" in t || "child" in t || "family" in t -> Genre.Kids
        "music" in t || "mtv" in t || "hits" in t -> Genre.Music
        "doc" in t || "history" in t || "discovery" in t || "nat geo" in t -> Genre.Docs
        else -> Genre.General
    }
}

/** Parse a quality tier out of channel/group name. */
fun qualityFromText(text: String): Quality {
    val t = text.uppercase()
    return when {
        "4K" in t || "UHD" in t || "2160" in t -> Quality.K4
        "FHD" in t || "1080" in t -> Quality.FHD
        "HD" in t || "720" in t -> Quality.HD
        else -> Quality.SD
    }
}

private fun qualityFromLabel(label: String?): Quality? {
    val normalized = label?.trim()?.takeIf { it.isNotBlank() } ?: return null
    val quality = qualityFromText(normalized)
    return if (quality != Quality.SD || normalized.equals("SD", ignoreCase = true)) quality else null
}

/** Extract a 2-letter bucket code from a group/channel name. Accepts ISO
 *  country codes or language prefixes (EN, JA, …) commonly used in IPTV. */
fun countryFromText(text: String): String? {
    val tokens = text.split(TAG_RE).map { it.trim().trim('[', ']', '(', ')') }
    for (tok in tokens) {
        if (tok.length in 2..3) {
            val up = tok.uppercase()
            val canonical = COUNTRY_ALIASES[up] ?: up
            if (canonical.length == 2 && canonical in KNOWN_COUNTRIES) return canonical
        }
    }
    // Fallback: first two/three chars of the string — handles "UK|Sports"
    // and similar no-delimiter prefixes.
    val head = text.trim().take(3).uppercase()
    val two = head.take(2)
    if (two in KNOWN_COUNTRIES) return COUNTRY_ALIASES[two] ?: two
    if (head in COUNTRY_ALIASES) return COUNTRY_ALIASES[head]
    return null
}

fun isAdultGroup(group: String, name: String): Boolean {
    val t = (group + " " + name).lowercase()
    return ADULT_KEYWORDS.any { it in t }
}

fun brandForGenre(genre: Genre): LiveColors.Brand = when (genre) {
    Genre.News    -> LiveColors.BrandNews
    Genre.Sports  -> LiveColors.BrandSport
    Genre.Movies  -> LiveColors.BrandMovies
    Genre.Series  -> LiveColors.BrandSeries
    Genre.Kids    -> LiveColors.BrandKids
    Genre.Music   -> LiveColors.BrandMusic
    Genre.Docs    -> LiveColors.BrandDocs
    Genre.General -> LiveColors.BrandGeneral
}

private fun IptvChannel.traits(): ChannelTraits {
    val combined = "$group | $name"
    val country = this.country
        ?.trim()
        ?.uppercase()
        ?.let { COUNTRY_ALIASES[it] ?: it }
        ?.takeIf { it in KNOWN_COUNTRIES }
        ?: countryFromText(group)
        ?: countryFromText(name)
    val genre = genreFromText(combined)
    val quality = qualityFromLabel(qualityLabel)
        ?: qualityFromText(name).takeUnless { it == Quality.SD }
        ?: qualityFromText(group)
    val lang = this.language
        ?.trim()
        ?.uppercase()
        ?.take(2)
        ?.takeIf { it.length == 2 }
        ?: country
        ?: "EN"
    val brand = brandForGenre(genre)
    return ChannelTraits(
        country = country,
        genre = genre,
        quality = quality,
        lang = lang,
        brandBg = brand.bg,
        brandFg = brand.fg,
        isAdult = isAdultGroup(group, name),
    )
}

fun IptvChannel.enrich(number: Int): EnrichedChannel {
    val traits = traits()
    return EnrichedChannel(
        source = this,
        number = number,
        country = traits.country,
        genre = traits.genre,
        quality = traits.quality,
        lang = traits.lang,
        brandBg = traits.brandBg,
        brandFg = traits.brandFg,
        isAdult = traits.isAdult,
    )
}

// ─────────────────────────────────────────────────────────────────────────
// Category tree (spec §5)
// ─────────────────────────────────────────────────────────────────────────

fun IptvChannel.enrichForFastStartup(number: Int): EnrichedChannel {
    val rawCountry = country
        ?.trim()
        ?.uppercase()
        ?.let { COUNTRY_ALIASES[it] ?: it }
        ?.takeIf { it in KNOWN_COUNTRIES }
    val lang = language
        ?.trim()
        ?.uppercase()
        ?.take(2)
        ?.takeIf { it.length == 2 }
        ?: rawCountry
        ?: "EN"
    val quality = qualityFromLabel(qualityLabel) ?: qualityFromText(name)
    val brand = LiveColors.BrandGeneral
    return EnrichedChannel(
        source = this,
        number = number,
        country = rawCountry,
        genre = Genre.General,
        quality = quality,
        lang = lang,
        brandBg = brand.bg,
        brandFg = brand.fg,
        isAdult = isAdultGroup(group, name),
    )
}

data class LiveCategory(
    val id: String,
    val label: String,
    val count: Int,
    val iconToken: CategoryIcon,
    val flagEmoji: String? = null,
    val children: List<LiveCategory> = emptyList(),
    val playlistGroupName: String? = null,
    val playlistId: String? = null,
) {
    val isGroup: Boolean get() = children.isNotEmpty()
}

internal fun sortChannelsByConfiguredOrder(
    channels: List<EnrichedChannel>,
    sortOrder: String,
): List<EnrichedChannel> = when (sortOrder) {
    "number" -> channels.sortedWith { first, second ->
        val firstNumber = first.source.providerChannelNumber?.trim()?.toBigDecimalOrNull()
        val secondNumber = second.source.providerChannelNumber?.trim()?.toBigDecimalOrNull()
        when {
            firstNumber != null && secondNumber != null -> firstNumber.compareTo(secondNumber)
            firstNumber != null -> -1
            secondNumber != null -> 1
            else -> 0
        }
    }
    "name" -> channels.sortedWith { first, second ->
        first.name.compareTo(second.name, ignoreCase = true)
    }
    else -> channels
}

enum class CategoryIcon { Favorite, Recent, All, Grid, Sport, Movie, News, Kids, Docs, Music, Lock, Country, SubEntry }

/**
 * Display-localization for category labels. The stored [LiveCategory.label] stays in English
 * so grouping/comparison logic keeps working; only the rendered text is localized here.
 * Dynamic labels (country names, "4K | Ultra HD", playlist group names, …) pass through.
 */
@Composable
fun liveCategoryLabel(raw: String): String = when (raw) {
    "Favorites" -> stringResource(R.string.live_cat_favorites)
    "Recently Watched" -> stringResource(R.string.live_cat_recently_watched)
    "All Channels" -> stringResource(R.string.live_label_all_channels)
    "Adult" -> stringResource(R.string.live_cat_adult)
    "Ungrouped" -> stringResource(R.string.live_cat_ungrouped)
    else -> raw
}

/** Display-localization for [LiveSection.label] section headers. Unknown headers pass through. */
@Composable
fun liveSectionLabel(raw: String): String = when (raw) {
    "PLAYLIST" -> stringResource(R.string.live_section_playlist)
    "ADULT" -> stringResource(R.string.live_section_adult)
    "HIDDEN" -> stringResource(R.string.live_section_hidden)
    else -> raw
}

data class LiveSection(val id: String, val label: String, val categories: List<LiveCategory>)

data class LiveCategoryTree(
    val top: List<LiveCategory>,
    val global: LiveSection,
    val countries: LiveSection,
    val adult: LiveSection,
    val hidden: LiveSection = LiveSection("hidden", "HIDDEN", emptyList()),
) {
    val allSections: List<LiveSection> = listOf(global, countries, adult)
    fun byId(id: String): LiveCategory? {
        fun findIn(category: LiveCategory): LiveCategory? {
            if (category.id == id) return category
            category.children.forEach { child ->
                findIn(child)?.let { return it }
            }
            return null
        }
        top.forEach { category ->
            findIn(category)?.let { return it }
        }
        for (section in allSections) {
            for (cat in section.categories) {
                findIn(cat)?.let { return it }
            }
        }
        return null
    }
}

private fun playlistGroupLabel(group: String): String {
    return group.trim().ifBlank { "Ungrouped" }
}

/**
 * Resolves the playlist id used to build a hidden-group key (`playlistId|group`)
 * for a given channel id. M3U/Xtream ids are shaped `<playlistId>:<origId>`, so
 * the first segment is the playlist id. Stalker ids are shaped
 * `stalker:<portalId>:<origId>`; hidden groups for Stalker portals are stored
 * under the *portal* id (e.g. `stalker1|group`), not the literal `stalker`
 * prefix, so the portal segment must be returned for the key to match.
 */
internal fun channelPlaylistId(channelId: String): String =
    StalkerPortalSupport.playlistIdFromChannelId(channelId)

private fun playlistGroupKey(playlistId: String, group: String): String {
    return com.arflix.tv.data.model.PlaylistGroupKey.build(playlistId, playlistGroupLabel(group))
}

fun isHiddenPlaylistGroup(channel: EnrichedChannel, hiddenGroups: Set<String>): Boolean {
    if (hiddenGroups.isEmpty()) return false
    val playlistId = channelPlaylistId(channel.id)
    return playlistGroupKey(playlistId, channel.source.group) in hiddenGroups
}

private fun isHiddenPlaylistGroup(channel: IptvChannel, hiddenGroups: Set<String>): Boolean {
    if (hiddenGroups.isEmpty()) return false
    val playlistId = channelPlaylistId(channel.id)
    return playlistGroupKey(playlistId, channel.group) in hiddenGroups
}

fun playlistGroupCategoryId(playlistId: String, group: String): String {
    val normalized = playlistGroupLabel(group).lowercase()
    return "grp:$playlistId:${normalized.hashCode().toUInt().toString(16)}"
}

/**
 * Build the category tree from a list of enriched channels. Counts are
 * computed up front so the sidebar can render without filtering again.
 * Never synthesize country, genre or quality categories: provider groups remain in
 * PLAYLIST in their configured order, and hidden groups stay under HIDDEN.
 */
fun buildCategoryTree(
    channels: List<EnrichedChannel>,
    favoritesCount: Int,
    recentCount: Int,
    hiddenGroups: Set<String> = emptySet(),
    groupOrder: List<String> = emptyList(),
): LiveCategoryTree {
    var allCount = 0
    val playlistGroupCounts = LinkedHashMap<String, Pair<String, Int>>()
    val hiddenPlaylistGroupCounts = LinkedHashMap<String, Pair<String, Int>>()
    val hiddenPlaylistGroups = hiddenGroups.toHashSet()

    channels.forEach { channel ->
        val playlistId = channelPlaylistId(channel.id)
        val hiddenPlaylistId = playlistId
        val groupLabel = playlistGroupLabel(channel.source.group)
        val groupKey = playlistGroupKey(hiddenPlaylistId, groupLabel)
        val groupId = playlistGroupCategoryId(playlistId, channel.source.group)
        val targetCounts = if (groupKey in hiddenPlaylistGroups) hiddenPlaylistGroupCounts else playlistGroupCounts
        val groupCount = targetCounts[groupId]?.second ?: 0
        targetCounts[groupId] = groupLabel to (groupCount + 1)
        if (groupKey in hiddenPlaylistGroups) {
            return@forEach
        }

        if (channel.isAdult) {
            return@forEach
        }

        allCount += 1
    }

    val top = listOf(
        LiveCategory("fav", "Favorites", favoritesCount, CategoryIcon.Favorite),
        LiveCategory("recent", "Recently Watched", recentCount, CategoryIcon.Recent),
        LiveCategory(
            id = "all",
            label = "All Channels",
            count = allCount,
            iconToken = CategoryIcon.All,
        ),
    )
    val playlistGroups = orderPlaylistGroups(playlistGroupCounts, groupOrder).map { (id, value) ->
        LiveCategory(
            id = id,
            label = value.first,
            count = value.second,
            iconToken = CategoryIcon.Grid,
            playlistGroupName = value.first,
            playlistId = playlistIdFromGroupCategoryId(id),
        )
    }
    val hiddenGroups = orderPlaylistGroups(hiddenPlaylistGroupCounts, groupOrder).map { (id, value) ->
        LiveCategory(
            id = id,
            label = value.first,
            count = value.second,
            iconToken = CategoryIcon.Grid,
            playlistGroupName = value.first,
            playlistId = playlistIdFromGroupCategoryId(id),
        )
    }
    val global = LiveSection("playlist", "PLAYLIST", playlistGroups)
    val countries = LiveSection("matched", "MATCHED", emptyList())
    val adult = LiveSection("adult", "ADULT", emptyList())
    val hidden = LiveSection("hidden", "HIDDEN", hiddenGroups)

    return LiveCategoryTree(top = top, global = global, countries = countries, adult = adult, hidden = hidden)
}

fun buildCategoryTree(
    channels: List<IptvChannel>,
    favorites: Set<String>,
    recents: Set<String>,
    hiddenGroups: Set<String> = emptySet(),
    groupOrder: List<String> = emptyList(),
): LiveCategoryTree {
    var allCount = 0
    val playlistGroupCounts = LinkedHashMap<String, Pair<String, Int>>()
    val hiddenPlaylistGroupCounts = LinkedHashMap<String, Pair<String, Int>>()
    val hiddenPlaylistGroups = hiddenGroups.toHashSet()

    channels.forEach { channel ->
        val playlistId = channelPlaylistId(channel.id)
        val hiddenPlaylistId = playlistId
        val traits = channel.traits()
        val groupLabel = playlistGroupLabel(channel.group)
        val groupKey = playlistGroupKey(hiddenPlaylistId, groupLabel)
        val groupId = playlistGroupCategoryId(playlistId, channel.group)
        val targetCounts = if (groupKey in hiddenPlaylistGroups) hiddenPlaylistGroupCounts else playlistGroupCounts
        val groupCount = targetCounts[groupId]?.second ?: 0
        targetCounts[groupId] = groupLabel to (groupCount + 1)
        if (groupKey in hiddenPlaylistGroups) {
            return@forEach
        }

        if (traits.isAdult) {
            return@forEach
        }

        allCount += 1
    }

    val channelIds = channels.asSequence()
        .filterNot { channel -> isAdultGroup(channel.group, channel.name) || isHiddenPlaylistGroup(channel, hiddenGroups) }
        .map { it.id }
        .toHashSet()
    val top = listOf(
        LiveCategory("fav", "Favorites", favorites.count { it in channelIds }, CategoryIcon.Favorite),
        LiveCategory("recent", "Recently Watched", recents.count { it in channelIds }, CategoryIcon.Recent),
        LiveCategory(
            id = "all",
            label = "All Channels",
            count = allCount,
            iconToken = CategoryIcon.All,
        ),
    )
    val playlistGroups = orderPlaylistGroups(playlistGroupCounts, groupOrder).map { (id, value) ->
        LiveCategory(
            id = id,
            label = value.first,
            count = value.second,
            iconToken = CategoryIcon.Grid,
            playlistGroupName = value.first,
            playlistId = playlistIdFromGroupCategoryId(id),
        )
    }
    val hiddenGroups = orderPlaylistGroups(hiddenPlaylistGroupCounts, groupOrder).map { (id, value) ->
        LiveCategory(
            id = id,
            label = value.first,
            count = value.second,
            iconToken = CategoryIcon.Grid,
            playlistGroupName = value.first,
            playlistId = playlistIdFromGroupCategoryId(id),
        )
    }
    val global = LiveSection("playlist", "PLAYLIST", playlistGroups)
    val countries = LiveSection("matched", "MATCHED", emptyList())
    val adult = LiveSection("adult", "ADULT", emptyList())
    val hidden = LiveSection("hidden", "HIDDEN", hiddenGroups)

    return LiveCategoryTree(top = top, global = global, countries = countries, adult = adult, hidden = hidden)
}

internal fun playlistIdFromGroupCategoryId(id: String): String? {
    if (!id.startsWith("grp:")) return null
    return id.removePrefix("grp:")
        .substringBefore(':', missingDelimiterValue = "")
        .takeIf { it.isNotBlank() }
}

private fun orderPlaylistGroups(
    groups: LinkedHashMap<String, Pair<String, Int>>,
    groupOrder: List<String>,
): List<Map.Entry<String, Pair<String, Int>>> {
    if (groups.isEmpty()) return emptyList()
    if (groupOrder.isEmpty()) return groups.entries.toList()
    val orderMap = groupOrder.asSequence()
        .flatMap { rawOrder ->
            val trimmed = rawOrder.trim()
            val groupName = com.arflix.tv.data.model.PlaylistGroupKey(trimmed).groupName
            sequenceOf(trimmed, playlistGroupLabel(groupName))
        }
        .distinct()
        .withIndex()
        .associate { (index, key) -> key to index }
    return groups.entries.sortedWith(
        compareBy<Map.Entry<String, Pair<String, Int>>> { entry ->
            val playlistId = playlistIdFromGroupCategoryId(entry.key).orEmpty()
            val label = playlistGroupLabel(entry.value.first)
            orderMap[playlistGroupKey(playlistId, label)]
                ?: orderMap[label]
                ?: Int.MAX_VALUE
        }
            .thenBy { entry -> groups.keys.indexOf(entry.key) }
    )
}

private fun rawCategoryMatcher(
    categoryId: String,
    favorites: Set<String>,
    recents: Set<String>,
): (IptvChannel) -> Boolean {
    if (categoryId == "all") return { ch -> !ch.traits().isAdult }
    if (categoryId == "fav") return { ch -> ch.id in favorites && !ch.traits().isAdult }
    if (categoryId == "recent") return { ch -> ch.id in recents && !ch.traits().isAdult }
    if (categoryId == "adult") return { ch -> ch.traits().isAdult }
    if (categoryId.startsWith("grp:")) return { ch -> playlistGroupCategoryId(channelPlaylistId(ch.id), ch.group) == categoryId }
    // Old saved synthetic selections must not reconstruct inferred categories.
    return { false }
}

fun buildFastStartupChannelState(
    channels: List<IptvChannel>,
    favorites: Set<String>,
    recents: Set<String>,
    hiddenGroups: Set<String> = emptySet(),
    groupOrder: List<String> = emptyList(),
): EnrichedChannels {
    if (channels.isEmpty()) return EnrichedChannels.Empty

    val allChannels = ArrayList<EnrichedChannel>(channels.size)
    val byId = LinkedHashMap<String, EnrichedChannel>(channels.size)
    val buckets = LinkedHashMap<String, MutableList<EnrichedChannel>>()
    val hiddenIds = LinkedHashSet<String>()
    val playlistGroupCounts = LinkedHashMap<String, Pair<String, Int>>()
    val hiddenPlaylistGroupCounts = LinkedHashMap<String, Pair<String, Int>>()
    val hiddenPlaylistGroups = hiddenGroups.toHashSet()
    val favoritesSet = favorites.toHashSet()
    val recentsSet = recents.toHashSet()

    var allCount = 0
    var favoriteCount = 0
    var recentCount = 0

    fun add(categoryId: String, channel: EnrichedChannel) {
        buckets.getOrPut(categoryId) { ArrayList() }.add(channel)
    }

    channels.forEachIndexed { index, rawChannel ->
        val channel = rawChannel.enrichForFastStartup(index + 1)
        val playlistId = channelPlaylistId(rawChannel.id)
        val hiddenPlaylistId = playlistId
        val groupLabel = playlistGroupLabel(rawChannel.group)
        val groupKey = playlistGroupKey(hiddenPlaylistId, groupLabel)
        val groupId = playlistGroupCategoryId(playlistId, rawChannel.group)
        val hidden = groupKey in hiddenPlaylistGroups

        allChannels += channel
        byId[channel.id] = channel
        add(groupId, channel)

        val targetCounts = if (hidden) hiddenPlaylistGroupCounts else playlistGroupCounts
        val groupCount = targetCounts[groupId]?.second ?: 0
        targetCounts[groupId] = groupLabel to (groupCount + 1)

        if (hidden) {
            hiddenIds += channel.id
            return@forEachIndexed
        }
        if (channel.isAdult) {
            add("adult", channel)
            return@forEachIndexed
        }

        allCount += 1
        add("all", channel)
        if (channel.id in favoritesSet) favoriteCount += 1
        if (channel.id in recentsSet) recentCount += 1
    }

    val top = listOf(
        LiveCategory("fav", "Favorites", favoriteCount, CategoryIcon.Favorite),
        LiveCategory("recent", "Recently Watched", recentCount, CategoryIcon.Recent),
        LiveCategory(
            id = "all",
            label = "All Channels",
            count = allCount,
            iconToken = CategoryIcon.All,
        ),
    )
    val playlistGroups = orderPlaylistGroups(playlistGroupCounts, groupOrder).map { (id, value) ->
        LiveCategory(
            id = id,
            label = value.first,
            count = value.second,
            iconToken = CategoryIcon.Grid,
            playlistGroupName = value.first,
            playlistId = playlistIdFromGroupCategoryId(id),
        )
    }
    val hidden = orderPlaylistGroups(hiddenPlaylistGroupCounts, groupOrder).map { (id, value) ->
        LiveCategory(
            id = id,
            label = value.first,
            count = value.second,
            iconToken = CategoryIcon.Grid,
            playlistGroupName = value.first,
            playlistId = playlistIdFromGroupCategoryId(id),
        )
    }
    return EnrichedChannels(
        all = allChannels,
        tree = LiveCategoryTree(
            top = top,
            global = LiveSection("playlist", "PLAYLIST", playlistGroups),
            countries = LiveSection("matched", "MATCHED", emptyList()),
            adult = LiveSection("adult", "ADULT", emptyList()),
            hidden = LiveSection("hidden", "HIDDEN", hidden),
        ),
        index = LiveCategoryIndex(
            byCategory = buckets.mapValues { (_, value) -> value.toList() },
            byId = byId,
            hiddenIds = hiddenIds,
        ),
    )
}

fun buildPagedStartupChannelState(
    channels: List<IptvChannel>,
    totalChannelCount: Int,
    playlistGroupCounts: List<Triple<String, String, Int>>,
    favorites: Set<String>,
    recents: Set<String>,
    hiddenGroups: Set<String> = emptySet(),
    groupOrder: List<String> = emptyList(),
    windowOffset: Int = 0,
): EnrichedChannels {
    val visibleWindow = channels.mapIndexed { index, rawChannel ->
        rawChannel.enrichForFastStartup(windowOffset + index + 1)
    }
    val byId = LinkedHashMap<String, EnrichedChannel>(visibleWindow.size)
    val byCategory = LinkedHashMap<String, MutableList<EnrichedChannel>>()
    val hiddenIds = LinkedHashSet<String>()
    val hiddenPlaylistGroups = hiddenGroups.toHashSet()

    fun add(categoryId: String, channel: EnrichedChannel) {
        byCategory.getOrPut(categoryId) { ArrayList() }.add(channel)
    }

    visibleWindow.forEach { channel ->
        byId[channel.id] = channel
        if (channel.id in favorites) add("fav", channel)
        if (channel.id in recents) add("recent", channel)
        val playlistId = channelPlaylistId(channel.source.id)
        val groupKey = playlistGroupKey(playlistId, channel.source.group)
        val groupId = playlistGroupCategoryId(playlistId, channel.source.group)
        add(groupId, channel)
        if (groupKey in hiddenPlaylistGroups) {
            hiddenIds += channel.id
        } else if (channel.isAdult) {
            add("adult", channel)
        } else {
            add("all", channel)
        }
    }

    val visibleGroupCounts = LinkedHashMap<String, Pair<String, Int>>()
    val hiddenGroupCounts = LinkedHashMap<String, Pair<String, Int>>()
    var visibleTotal = 0
    playlistGroupCounts.forEach { (playlistId, rawGroup, count) ->
        val label = playlistGroupLabel(rawGroup)
        val key = playlistGroupKey(playlistId, label)
        val id = playlistGroupCategoryId(playlistId, label)
        if (key in hiddenPlaylistGroups) {
            hiddenGroupCounts[id] = label to count
        } else {
            visibleTotal += count
            visibleGroupCounts[id] = label to count
        }
    }
    val totalVisible = totalChannelCount.takeIf { it > 0 } ?: visibleTotal
    val top = listOf(
        LiveCategory("fav", "Favorites", favorites.size, CategoryIcon.Favorite),
        LiveCategory("recent", "Recently Watched", recents.size, CategoryIcon.Recent),
        LiveCategory(
            id = "all",
            label = "All Channels",
            count = totalVisible,
            iconToken = CategoryIcon.All,
        ),
    )
    val playlistGroups = orderPlaylistGroups(visibleGroupCounts, groupOrder).map { (id, value) ->
        LiveCategory(
            id = id,
            label = value.first,
            count = value.second,
            iconToken = CategoryIcon.Grid,
            playlistGroupName = value.first,
            playlistId = playlistIdFromGroupCategoryId(id),
        )
    }
    val hidden = orderPlaylistGroups(hiddenGroupCounts, groupOrder).map { (id, value) ->
        LiveCategory(
            id = id,
            label = value.first,
            count = value.second,
            iconToken = CategoryIcon.Grid,
            playlistGroupName = value.first,
            playlistId = playlistIdFromGroupCategoryId(id),
        )
    }
    return EnrichedChannels(
        all = visibleWindow,
        tree = LiveCategoryTree(
            top = top,
            global = LiveSection("playlist", "PLAYLIST", playlistGroups),
            countries = LiveSection("matched", "MATCHED", emptyList()),
            adult = LiveSection("adult", "ADULT", emptyList()),
            hidden = LiveSection("hidden", "HIDDEN", hidden),
        ),
        index = LiveCategoryIndex(
            byCategory = byCategory.mapValues { (_, value) -> value.toList() },
            byId = byId,
            hiddenIds = hiddenIds,
        ),
    )
}

fun buildInitialCategoryChannels(
    channels: List<IptvChannel>,
    categoryId: String,
    favorites: Set<String>,
    recents: Set<String>,
    hiddenGroups: Set<String> = emptySet(),
    limit: Int,
): List<EnrichedChannel> {
    if (channels.isEmpty() || limit <= 0) return emptyList()
    if (categoryId == "all") {
        return buildList(limit.coerceAtMost(channels.size)) {
            channels.forEachIndexed { index, channel ->
                if (!isAdultGroup(channel.group, channel.name) && !isHiddenPlaylistGroup(channel, hiddenGroups)) {
                    add(channel.enrichForFastStartup(index + 1))
                    if (size >= limit) return@buildList
                }
            }
        }
    }
    if (categoryId == "fav") {
        return buildList(limit.coerceAtMost(favorites.size)) {
            channels.forEachIndexed { index, channel ->
                if (channel.id in favorites && !isAdultGroup(channel.group, channel.name) && !isHiddenPlaylistGroup(channel, hiddenGroups)) {
                    add(channel.enrichForFastStartup(index + 1))
                    if (size >= limit) return@buildList
                }
            }
        }
    }
    if (categoryId == "recent") {
        val indexById = channels.withIndex().associate { (index, channel) -> channel.id to (index to channel) }
        return buildList(limit.coerceAtMost(recents.size)) {
            recents.toList().asReversed().forEach { id ->
                val (index, channel) = indexById[id] ?: return@forEach
                if (!isAdultGroup(channel.group, channel.name) && !isHiddenPlaylistGroup(channel, hiddenGroups)) {
                    add(channel.enrichForFastStartup(index + 1))
                    if (size >= limit) return@buildList
                }
            }
        }
    }
    val matcher = rawCategoryMatcher(categoryId, favorites, recents)
    return buildList(limit.coerceAtMost(channels.size)) {
        channels.forEachIndexed { index, channel ->
            val isSelectedHiddenGroup = categoryId.startsWith("grp:") &&
                playlistGroupCategoryId(channelPlaylistId(channel.id), channel.group) == categoryId
            if (matcher(channel) && (isSelectedHiddenGroup || !isHiddenPlaylistGroup(channel, hiddenGroups))) {
                add(channel.enrich(index + 1))
                if (size >= limit) return@buildList
            }
        }
    }
}

fun buildCategoryIndex(
    channels: List<EnrichedChannel>,
    hiddenGroups: Set<String> = emptySet(),
): LiveCategoryIndex {
    if (channels.isEmpty()) return LiveCategoryIndex.Empty

    val byId = LinkedHashMap<String, EnrichedChannel>(channels.size)
    val buckets = LinkedHashMap<String, MutableList<EnrichedChannel>>()
    val hiddenIds = LinkedHashSet<String>()

    fun add(categoryId: String, channel: EnrichedChannel) {
        buckets.getOrPut(categoryId) { ArrayList() }.add(channel)
    }

    channels.forEach { channel ->
        byId[channel.id] = channel
        add(playlistGroupCategoryId(channelPlaylistId(channel.source.id), channel.source.group), channel)
        if (isHiddenPlaylistGroup(channel, hiddenGroups)) {
            hiddenIds += channel.id
            return@forEach
        }
        if (channel.isAdult) {
            add("adult", channel)
            return@forEach
        }

        add("all", channel)

    }

    return LiveCategoryIndex(
        byCategory = buckets.mapValues { (_, value) -> value.toList() },
        byId = byId,
        hiddenIds = hiddenIds,
    )
}

fun bestCategoryIdForChannel(
    channel: EnrichedChannel,
    tree: LiveCategoryTree,
): String {
    val playlistGroupId = playlistGroupCategoryId(channelPlaylistId(channel.source.id), channel.source.group)
    if (tree.byId(playlistGroupId) != null) return playlistGroupId
    return "all"
}

/** Returns a predicate matching channels for [categoryId]. Implements spec §5. */
fun categoryMatcher(
    categoryId: String,
    favorites: Set<String>,
    recents: Set<String>,
): (EnrichedChannel) -> Boolean {
    return when {
        categoryId == "all"    -> { ch -> !ch.isAdult }
        categoryId == "fav"    -> { ch -> ch.id in favorites && !ch.isAdult }
        categoryId == "recent" -> { ch -> ch.id in recents && !ch.isAdult }
        categoryId == "adult"  -> { ch -> ch.isAdult }
        categoryId.startsWith("grp:") -> { ch -> playlistGroupCategoryId(channelPlaylistId(ch.source.id), ch.source.group) == categoryId }
        else -> { _ -> false }
    }
}
