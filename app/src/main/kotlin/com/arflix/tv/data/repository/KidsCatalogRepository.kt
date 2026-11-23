package com.arflix.tv.data.repository

import com.arflix.tv.data.api.TmdbApi
import com.arflix.tv.data.api.TmdbMediaItem
import com.arflix.tv.data.model.MediaType
import com.arflix.tv.util.Constants
import com.google.gson.JsonObject
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import javax.inject.Inject
import javax.inject.Singleton

/** Ratings, not animation or a provider category name, authorize Kids content. */
internal object KidsRatingPolicy {
    fun allows(type: MediaType, ratings: JsonObject): Boolean {
        val us = ratings.getAsJsonArray("results")?.filter {
            it.asJsonObject.get("iso_3166_1")?.asString == "US"
        }.orEmpty()
        val values = if (type == MediaType.MOVIE) us.flatMap {
            it.asJsonObject.getAsJsonArray("release_dates")?.mapNotNull { date ->
                date.asJsonObject.get("certification")?.asString?.trim()?.takeIf(String::isNotEmpty)
            }.orEmpty()
        } else us.mapNotNull { it.asJsonObject.get("rating")?.asString?.trim()?.takeIf(String::isNotEmpty) }
        val allowed = if (type == MediaType.MOVIE) setOf("G", "PG") else setOf("TV-Y", "TV-Y7", "TV-Y7-FV", "TV-G", "TV-PG")
        // Conflicting, unknown and absent ratings fail closed.
        return values.isNotEmpty() && values.all { it in allowed }
    }
}

data class KidsTitle(val item: TmdbMediaItem, val type: MediaType)

@Singleton
class KidsCatalogRepository @Inject constructor(private val api: TmdbApi) {
    private val requests = Semaphore(4)

    suspend fun allowed(type: MediaType, id: Int): Boolean = requests.withPermit {
        if (id <= 0) return@withPermit false
        try {
            val key = Constants.TMDB_API_KEY
            val genres = if (type == MediaType.MOVIE) {
                val details = api.getMovieDetails(id, key)
                if (details.adult) return@withPermit false
                details.genres.map { it.id }
            } else api.getTvDetails(id, key).genres.map { it.id }
            // Family/kids plus a known child-appropriate rating. Animation alone
            // is insufficient (adult animation must never qualify).
            if (genres.none { it == 10751 || it == 10762 }) return@withPermit false
            KidsRatingPolicy.allows(type, if (type == MediaType.MOVIE) api.kidsMovieRatings(id, key) else api.kidsTvRatings(id, key))
        } catch (cancelled: CancellationException) { throw cancelled }
        catch (_: Exception) { false }
    }

    suspend fun browse(type: MediaType, page: Int, query: String = ""): List<KidsTitle> = coroutineScope {
        val response = if (query.isNotBlank()) {
            if (type == MediaType.MOVIE) api.searchMovies(Constants.TMDB_API_KEY, query, page = page)
            else api.searchTv(Constants.TMDB_API_KEY, query, page = page)
        } else if (type == MediaType.MOVIE) api.discoverMovies(Constants.TMDB_API_KEY, genres = "10751", page = page)
            else api.discoverTv(Constants.TMDB_API_KEY, genres = "10751|10762", page = page)
        response.results.map { item -> async {
            if (!item.adult && allowed(type, item.id)) KidsTitle(item, type) else null
        } }.awaitAll().filterNotNull()
    }

    suspend fun seasons(id: Int) = api.getTvDetails(id, Constants.TMDB_API_KEY).seasons.filter { it.seasonNumber > 0 }
    suspend fun episodes(id: Int, season: Int) = api.getTvSeason(id, season, Constants.TMDB_API_KEY).episodes
}
