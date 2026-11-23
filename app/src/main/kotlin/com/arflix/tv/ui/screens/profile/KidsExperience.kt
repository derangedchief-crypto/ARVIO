package com.arflix.tv.ui.screens.profile

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.ViewModel
import androidx.navigation.NavType
import androidx.navigation.compose.*
import androidx.navigation.navArgument
import coil.compose.AsyncImage
import com.arflix.tv.data.api.TmdbEpisode
import com.arflix.tv.data.api.TmdbTvSeason
import com.arflix.tv.data.model.MediaType
import com.arflix.tv.data.model.Profile
import com.arflix.tv.data.repository.KidsCatalogRepository
import com.arflix.tv.data.repository.KidsTitle
import com.arflix.tv.ui.screens.player.PlayerScreen
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.CancellationException
import javax.inject.Inject

@HiltViewModel
class KidsViewModel @Inject constructor(val catalog: KidsCatalogRepository) : ViewModel()

/** A separate graph prevents adult shelves, external routes and Live TV being
 * composed at all while a Kids profile is active. Player keeps its normal
 * entitlement checks and profile-specific history. */
@Composable
fun KidsExperience(profile: Profile, onSwitchProfile: () -> Unit, viewModel: KidsViewModel = hiltViewModel()) {
    val nav = rememberNavController()
    val catalog = viewModel.catalog
    NavHost(navController = nav, startDestination = "kids") {
        composable("kids") {
            KidsBrowse(profile, catalog, onSwitchProfile) { title ->
                val type = if (title.type == MediaType.MOVIE) "movie" else "tv"
                nav.navigate("kids/title/$type/${title.item.id}")
            }
        }
        composable("kids/title/{type}/{id}", arguments = listOf(navArgument("id") { type = NavType.IntType })) { entry ->
            val id = entry.arguments!!.getInt("id")
            val type = if (entry.arguments!!.getString("type") == "movie") MediaType.MOVIE else MediaType.TV
            KidsVerified(catalog, type, id) {
                KidsEpisodes(catalog, type, id, { nav.popBackStack() }) { season, episode ->
                    nav.navigate("kids/play/${if (type == MediaType.MOVIE) "movie" else "tv"}/$id/$season/$episode")
                }
            }
        }
        composable("kids/play/{type}/{id}/{season}/{episode}", arguments = listOf(
            navArgument("id") { type = NavType.IntType },
            navArgument("season") { type = NavType.IntType },
            navArgument("episode") { type = NavType.IntType }
        )) { entry ->
            val id = entry.arguments!!.getInt("id")
            val type = if (entry.arguments!!.getString("type") == "movie") MediaType.MOVIE else MediaType.TV
            val season = entry.arguments!!.getInt("season").takeIf { it > 0 }
            val episode = entry.arguments!!.getInt("episode").takeIf { it > 0 }
            KidsVerified(catalog, type, id) {
                PlayerScreen(mediaType = type, mediaId = id, seasonNumber = season, episodeNumber = episode,
                    onBack = { nav.popBackStack() },
                    onPlayNext = { next, _, _, _ ->
                        nav.navigate("kids/play/tv/$id/${next.displaySeason}/${next.displayEpisode}") {
                            popUpTo(entry.destination.id) { inclusive = true }
                        }
                    })
            }
        }
    }
}

@Composable
private fun KidsVerified(catalog: KidsCatalogRepository, type: MediaType, id: Int, content: @Composable () -> Unit) {
    var allowed by remember(type, id) { mutableStateOf<Boolean?>(null) }
    LaunchedEffect(type, id) { allowed = catalog.allowed(type, id) }
    when (allowed) {
        true -> content()
        false -> Box(Modifier.fillMaxSize().padding(32.dp)) { Text("This title is not available in Kids. Use Back to choose another title.") }
        null -> Box(Modifier.fillMaxSize().padding(32.dp)) { CircularProgressIndicator() }
    }
}

@Composable
private fun KidsBrowse(profile: Profile, catalog: KidsCatalogRepository, onSwitch: () -> Unit, onTitle: (KidsTitle) -> Unit) {
    var type by remember { mutableStateOf(MediaType.MOVIE) }
    var query by remember { mutableStateOf("") }
    var submitted by remember { mutableStateOf("") }
    var page by remember { mutableIntStateOf(1) }
    var titles by remember { mutableStateOf<List<KidsTitle>>(emptyList()) }
    var loading by remember { mutableStateOf(true) }
    var failed by remember { mutableStateOf(false) }
    BackHandler { onSwitch() }
    LaunchedEffect(type, submitted, page) {
        loading = true; failed = false; titles = emptyList()
        try { titles = catalog.browse(type, page, submitted) }
        catch (cancelled: CancellationException) { throw cancelled }
        catch (_: Exception) { failed = true }
        finally { loading = false }
    }
    Column(Modifier.fillMaxSize().background(Color(0xFF0B1424)).padding(24.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        Text("${profile.name} · Kids", style = MaterialTheme.typography.headlineMedium, color = Color.White)
        Text("Movies and shows for ages 12 and under", color = Color.White)
        Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            Button(onClick = { type = MediaType.MOVIE; page = 1 }) { Text("Movies") }
            Button(onClick = { type = MediaType.TV; page = 1 }) { Text("TV shows") }
            Button(onClick = onSwitch) { Text("Switch profile") }
        }
        Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            OutlinedTextField(value = query, onValueChange = { query = it }, label = { Text("Search Kids") }, singleLine = true, modifier = Modifier.weight(1f))
            Button(onClick = { submitted = query.trim(); page = 1 }) { Text("Search") }
        }
        if (loading) CircularProgressIndicator()
        if (!loading && titles.isEmpty()) Text(if (failed) "Could not load Kids titles. Please try again." else "No verified Kids titles on this page. Try another search or page.", color = Color.White)
        LazyVerticalGrid(columns = GridCells.Adaptive(150.dp), modifier = Modifier.weight(1f), horizontalArrangement = Arrangement.spacedBy(12.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            items(titles, key = { it.item.id }) { title ->
                Button(onClick = { onTitle(title) }, contentPadding = PaddingValues(8.dp)) {
                    Column {
                        AsyncImage(model = title.item.posterPath?.let { "https://image.tmdb.org/t/p/w342$it" }, contentDescription = null, modifier = Modifier.fillMaxWidth().aspectRatio(2f / 3f))
                        Text(title.item.title ?: title.item.name.orEmpty(), maxLines = 2)
                    }
                }
            }
        }
        Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            Button(enabled = page > 1 && !loading, onClick = { page-- }) { Text("Previous") }
            Text("Page $page", color = Color.White)
            Button(enabled = !loading && page < 500, onClick = { page++ }) { Text("Next") }
        }
    }
}

@Composable
private fun KidsEpisodes(catalog: KidsCatalogRepository, type: MediaType, id: Int, onBack: () -> Unit, onPlay: (Int, Int) -> Unit) {
    var seasons by remember(id) { mutableStateOf<List<TmdbTvSeason>>(emptyList()) }
    var selectedSeason by remember(id) { mutableStateOf<Int?>(null) }
    var episodes by remember(id) { mutableStateOf<List<TmdbEpisode>>(emptyList()) }
    var error by remember(id) { mutableStateOf(false) }
    LaunchedEffect(id, selectedSeason) {
        error = false; episodes = emptyList()
        try {
            if (type == MediaType.TV) {
                val season = selectedSeason
                if (season == null) seasons = catalog.seasons(id)
                else episodes = catalog.episodes(id, season)
            }
        } catch (cancelled: CancellationException) { throw cancelled }
        catch (_: Exception) { error = true }
    }
    BackHandler { if (selectedSeason != null) selectedSeason = null else onBack() }
    LazyColumn(Modifier.fillMaxSize().padding(24.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        item { Button(onClick = { if (selectedSeason != null) selectedSeason = null else onBack() }) { Text("Back") } }
        if (error) item { Text("Could not load episodes. Please go back and try again.") }
        if (type == MediaType.MOVIE) item { Button(onClick = { onPlay(0, 0) }) { Text("Play movie") } }
        else if (selectedSeason == null) items(seasons) { season ->
            Button(onClick = { selectedSeason = season.seasonNumber }) { Text("Season ${season.seasonNumber}") }
        } else items(episodes) { episode ->
            Button(onClick = { onPlay(episode.seasonNumber, episode.episodeNumber) }) { Text("${episode.episodeNumber}. ${episode.name}") }
        }
    }
}
