package com.arflix.tv.ui.screens.profile

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.Alignment
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.navigation.NavType
import androidx.navigation.compose.*
import androidx.navigation.navArgument
import com.arflix.tv.data.model.Category
import com.arflix.tv.data.model.MediaItem
import com.arflix.tv.data.model.MediaType
import com.arflix.tv.data.model.Profile
import com.arflix.tv.data.repository.KidsCatalogRepository
import com.arflix.tv.data.repository.KidsTitle
import com.arflix.tv.data.repository.WatchlistRepository
import com.arflix.tv.navigation.Screen
import com.arflix.tv.ui.components.AppBottomBar
import com.arflix.tv.ui.components.bottomBarItems
import com.arflix.tv.ui.screens.details.DetailsScreen
import com.arflix.tv.ui.screens.home.HomeScreen
import com.arflix.tv.ui.screens.home.HomeUiState
import com.arflix.tv.ui.screens.player.PlayerScreen
import com.arflix.tv.ui.theme.appBackgroundDark
import com.arflix.tv.ui.theme.TextPrimary
import com.arflix.tv.util.LocalDeviceType
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import javax.inject.Inject

internal fun KidsTitle.asHomeItem() = MediaItem(
    id = item.id,
    title = item.title ?: item.name.orEmpty(),
    subtitle = if (type == MediaType.MOVIE) "Movie" else "TV Series",
    overview = item.overview.orEmpty(),
    year = (item.releaseDate ?: item.firstAirDate).orEmpty().take(4),
    mediaType = type,
    image = item.posterPath?.let { "https://image.tmdb.org/t/p/w500$it" }.orEmpty(),
    backdrop = item.backdropPath?.let { "https://image.tmdb.org/t/p/w1280$it" },
    genreIds = item.genreIds
)

@HiltViewModel
class KidsViewModel @Inject constructor(
    val catalog: KidsCatalogRepository,
    private val watchlist: WatchlistRepository
) : ViewModel() {
    private val _home = MutableStateFlow(HomeUiState(isLoading = true))
    val home = _home.asStateFlow()
    private var job: Job? = null
    private var query = ""
    private var library = false
    private val pages = mutableMapOf<MediaType, Int>()
    private val loadingMore = mutableSetOf<MediaType>()
    private var generation = 0

    init { load() }

    fun load(search: String = "", showLibrary: Boolean = false) {
        job?.cancel()
        val request = ++generation
        query = search.trim(); library = showLibrary
        pages.clear(); loadingMore.clear()
        _home.value = HomeUiState(isLoading = true)
        job = viewModelScope.launch {
            try {
                val categories = if (showLibrary) {
                    val items = watchlist.getLocalWatchlistItems().filter { catalog.allowed(it.mediaType, it.id) }
                    listOf(Category("kids_library", "My library", items))
                } else coroutineScope {
                    val movies = async { catalog.browse(MediaType.MOVIE, 1, query).map { it.asHomeItem() } }
                    val shows = async { catalog.browse(MediaType.TV, 1, query).map { it.asHomeItem() } }
                    listOf(Category("kids_movie", "Movies", movies.await()), Category("kids_tv", "TV shows", shows.await()))
                }
                if (request != generation) return@launch
                pages[MediaType.MOVIE] = 1; pages[MediaType.TV] = 1
                _home.value = HomeUiState(
                    categories = categories.filter { it.items.isNotEmpty() },
                    heroItem = categories.firstNotNullOfOrNull { it.items.firstOrNull() },
                    isInitialLoad = false,
                    error = if (categories.all { it.items.isEmpty() }) "No kids titles found. Try another search." else null,
                    categoryHasMoreMap = if (showLibrary) emptyMap() else mapOf("kids_movie" to true, "kids_tv" to true)
                )
            } catch (cancelled: CancellationException) { throw cancelled }
            catch (_: Exception) {
                if (request == generation) _home.value = HomeUiState(isInitialLoad = false, error = "Could not load titles. Please try again.")
            }
        }
    }

    fun retry() = load(query, library)

    fun loadMore(categoryId: String) {
        val type = when (categoryId) { "kids_movie" -> MediaType.MOVIE; "kids_tv" -> MediaType.TV; else -> return }
        if (library || (pages[type] ?: 1) >= 500 || !loadingMore.add(type)) return
        val request = generation
        val page = (pages[type] ?: 1) + 1
        val search = query
        viewModelScope.launch {
            try {
                val items = catalog.browse(type, page, search).map { it.asHomeItem() }
                if (request != generation) return@launch
                pages[type] = page
                _home.value = _home.value.copy(categories = _home.value.categories.map {
                    if (it.id == categoryId) it.copy(items = (it.items + items).distinctBy { item -> item.id }) else it
                }, categoryHasMoreMap = _home.value.categoryHasMoreMap + (categoryId to (page < 500)))
            } catch (cancelled: CancellationException) { throw cancelled }
            catch (_: Exception) { /* Keep existing shelves; a later load can retry. */ }
            finally { if (request == generation) loadingMore.remove(type) }
        }
    }
}

/** Same home, title details, episode chooser and player as ordinary profiles.
 * The isolated graph only changes which content is allowed to enter them. */
@Composable
fun KidsExperience(profile: Profile, onSwitchProfile: () -> Unit, viewModel: KidsViewModel = hiltViewModel()) {
    val nav = rememberNavController()
    val catalog = viewModel.catalog
    val home by viewModel.home.collectAsStateWithLifecycle()
    val entry by nav.currentBackStackEntryAsState()
    val isPlayer = entry?.destination?.route == Screen.Player.route
    var selectedTab by remember { mutableStateOf("home") }
    var showSearch by remember { mutableStateOf(false) }
    var search by remember { mutableStateOf("") }
    val openHome: () -> Unit = {
        selectedTab = "home"; viewModel.load()
        nav.popBackStack("kids", false)
    }
    val openLibrary: () -> Unit = {
        selectedTab = "watchlist"; viewModel.load(showLibrary = true)
        nav.popBackStack("kids", false)
    }
    val openTitle: (MediaType, Int) -> Unit = { type, id ->
        nav.navigate("kids/title/${if (type == MediaType.MOVIE) "movie" else "tv"}/$id")
    }
    Column(Modifier.fillMaxSize().background(appBackgroundDark())) {
        Box(Modifier.weight(1f)) {
            NavHost(navController = nav, startDestination = "kids") {
                composable("kids") {
                    BackHandler { onSwitchProfile() }
                    HomeScreen(
                        currentProfile = profile, restrictedContent = home,
                        onRestrictedLoadMore = viewModel::loadMore, onRestrictedRetry = viewModel::retry,
                        onNavigateToDetails = { type, id, _, _ -> openTitle(type, id) },
                        onNavigateToSearch = { showSearch = true },
                        onNavigateToWatchlist = openLibrary,
                        onNavigateToSettings = onSwitchProfile,
                        onSwitchProfile = onSwitchProfile, onExitApp = onSwitchProfile
                    )
                }
                composable("kids/title/{type}/{id}", arguments = listOf(navArgument("id") { type = NavType.IntType })) { backStack ->
                    val id = backStack.arguments!!.getInt("id")
                    val type = if (backStack.arguments!!.getString("type") == "movie") MediaType.MOVIE else MediaType.TV
                    KidsVerified(catalog, type, id) {
                        DetailsScreen(
                            mediaType = type, mediaId = id, currentProfile = profile,
                            onNavigateToPlayer = { _, _, episode, imdbId, url, addon, source, position ->
                                nav.navigate(Screen.Player.createRoute(
                                    mediaType = type, mediaId = id,
                                    seasonNumber = episode?.displaySeason, episodeNumber = episode?.displayEpisode,
                                    tmdbSeasonNumber = episode?.tmdbSeason, tmdbEpisodeNumber = episode?.tmdbEpisode,
                                    kitsuId = episode?.kitsuId, kitsuEpisodeNumber = episode?.kitsuEpisode,
                                    imdbId = imdbId, streamUrl = url, preferredAddonId = addon,
                                    preferredSourceName = source, startPositionMs = position
                                ))
                            },
                            onNavigateToDetails = openTitle, onNavigateToHome = openHome,
                            onNavigateToSearch = { showSearch = true }, onNavigateToWatchlist = openLibrary,
                            onNavigateToSettings = onSwitchProfile, onSwitchProfile = onSwitchProfile,
                            onBack = { nav.popBackStack() }
                        )
                    }
                }
                composable(Screen.Player.route, arguments = listOf(
                    navArgument("mediaType") { type = NavType.StringType },
                    navArgument("mediaId") { type = NavType.IntType },
                    navArgument("startPositionMs") { type = NavType.LongType; defaultValue = -1L },
                    navArgument("isLiveStream") { type = NavType.BoolType; defaultValue = false }
                ) + listOf("seasonNumber", "episodeNumber", "tmdbSeasonNumber", "tmdbEpisodeNumber", "kitsuId", "kitsuEpisodeNumber").map { name ->
                    navArgument(name) { type = NavType.IntType; defaultValue = -1 }
                } + listOf("imdbId", "streamUrl", "preferredAddonId", "preferredSourceName", "preferredBingeGroup").map { name ->
                    navArgument(name) { type = NavType.StringType; defaultValue = "" }
                }) { backStack ->
                    val args = backStack.arguments!!
                    val id = args.getInt("mediaId")
                    val type = if (args.getString("mediaType") == "movie") MediaType.MOVIE else MediaType.TV
                    fun number(name: String) = args.getInt(name).takeIf { it >= 0 }
                    fun text(name: String) = args.getString(name)?.takeIf { it.isNotBlank() }
                    KidsVerified(catalog, type, id) {
                        PlayerScreen(mediaType = type, mediaId = id,
                            seasonNumber = number("seasonNumber"), episodeNumber = number("episodeNumber"),
                            tmdbSeasonNumber = number("tmdbSeasonNumber") ?: number("seasonNumber"),
                            tmdbEpisodeNumber = number("tmdbEpisodeNumber") ?: number("episodeNumber"),
                            kitsuId = number("kitsuId"), kitsuEpisodeNumber = number("kitsuEpisodeNumber"),
                            imdbId = text("imdbId"), streamUrl = text("streamUrl"),
                            preferredAddonId = text("preferredAddonId"), preferredSourceName = text("preferredSourceName"),
                            preferredBingeGroup = text("preferredBingeGroup"),
                            startPositionMs = args.getLong("startPositionMs").takeIf { it >= 0L },
                            onBack = { nav.popBackStack() },
                            onPlayNext = { next, addon, source, group ->
                                nav.navigate(Screen.Player.createRoute(
                                    mediaType = type, mediaId = id,
                                    seasonNumber = next.displaySeason, episodeNumber = next.displayEpisode,
                                    tmdbSeasonNumber = next.tmdbSeason, tmdbEpisodeNumber = next.tmdbEpisode,
                                    kitsuId = next.kitsuId, kitsuEpisodeNumber = next.kitsuEpisode,
                                    imdbId = text("imdbId"), preferredAddonId = addon,
                                    preferredSourceName = source, preferredBingeGroup = group
                                )) {
                                    popUpTo(backStack.destination.id) { inclusive = true }
                                }
                            })
                    }
                }
            }
        }
        if (LocalDeviceType.current.isTouchDevice() && !isPlayer) {
            AppBottomBar(currentRoute = selectedTab,
                items = bottomBarItems.filter { it.route in setOf("home", "search", "watchlist") },
                onNavigate = { route -> when (route) {
                    "home" -> openHome()
                    "search" -> showSearch = true
                    "watchlist" -> openLibrary()
                } })
        }
    }
    if (showSearch) AlertDialog(
        onDismissRequest = { showSearch = false }, containerColor = appBackgroundDark(),
        title = { Text("Search", color = TextPrimary) },
        text = { OutlinedTextField(value = search, onValueChange = { search = it },
            singleLine = true, label = { Text("Movies and TV shows") },
            colors = OutlinedTextFieldDefaults.colors(focusedTextColor = TextPrimary, unfocusedTextColor = TextPrimary)) },
        confirmButton = { TextButton(onClick = {
            showSearch = false; selectedTab = "search"; viewModel.load(search)
            nav.popBackStack("kids", false)
        }) { Text("Search", color = TextPrimary) } },
        dismissButton = { TextButton(onClick = { showSearch = false }) { Text("Cancel", color = TextPrimary) } }
    )
}

@Composable
private fun KidsVerified(catalog: KidsCatalogRepository, type: MediaType, id: Int, content: @Composable () -> Unit) {
    var allowed by remember(type, id) { mutableStateOf<Boolean?>(null) }
    LaunchedEffect(type, id) { allowed = catalog.allowed(type, id) }
    when (allowed) {
        true -> content()
        false -> Box(Modifier.fillMaxSize().padding(32.dp), contentAlignment = Alignment.Center) {
            Text("This title is not available in Kids. Use Back to choose another title.", color = TextPrimary)
        }
        null -> Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) { CircularProgressIndicator() }
    }
}
