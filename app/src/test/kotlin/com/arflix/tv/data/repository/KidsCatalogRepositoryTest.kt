package com.arflix.tv.data.repository

import com.arflix.tv.data.api.*
import com.arflix.tv.data.model.MediaType
import com.google.gson.JsonParser
import io.mockk.*
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test

class KidsCatalogRepositoryTest {
    private fun ratings(vararg values: String) = JsonParser.parseString(
        """{"results":[{"iso_3166_1":"US","release_dates":[${values.joinToString { """{"certification":"$it"}""" }}]}]}"""
    ).asJsonObject

    @Test fun unknownAdultAndConflictingMovieRatingsAreExcluded() {
        assertTrue(KidsRatingPolicy.allows(MediaType.MOVIE, ratings("G", "PG")))
        for (rating in listOf("PG-13", "R", "NC-17", "NR", "", "12")) {
            assertFalse(rating, KidsRatingPolicy.allows(MediaType.MOVIE, ratings(rating)))
        }
        assertFalse(KidsRatingPolicy.allows(MediaType.MOVIE, ratings("PG", "R")))
        assertFalse(KidsRatingPolicy.allows(MediaType.MOVIE, JsonParser.parseString("{}").asJsonObject))
    }

    @Test fun tvRatingsUseTheirOwnAllowlistAndRequireKnownRegion() {
        for (rating in listOf("TV-Y", "TV-Y7", "TV-Y7-FV", "TV-G", "TV-PG", "TV-14", "TV-MA", "")) {
            val json = JsonParser.parseString("""{"results":[{"iso_3166_1":"US","rating":"$rating"}]}""").asJsonObject
            assertEquals(rating, rating in setOf("TV-Y", "TV-Y7", "TV-Y7-FV", "TV-G", "TV-PG"), KidsRatingPolicy.allows(MediaType.TV, json))
        }
        val foreign = JsonParser.parseString("""{"results":[{"iso_3166_1":"XX","rating":"TV-G"}]}""").asJsonObject
        assertFalse(KidsRatingPolicy.allows(MediaType.TV, foreign))
    }

    @Test fun familyClassificationAndRatingAreBothRequired() = runBlocking {
        val api = mockk<TmdbApi>()
        val repo = KidsCatalogRepository(api)
        coEvery { api.getMovieDetails(1, any(), any()) } returns TmdbMovieDetails(id = 1, genres = listOf(TmdbGenre(10751)))
        coEvery { api.kidsMovieRatings(1, any()) } returns ratings("PG")
        assertTrue(repo.allowed(MediaType.MOVIE, 1))
        coEvery { api.getMovieDetails(1, any(), any()) } returns TmdbMovieDetails(id = 1, genres = listOf(TmdbGenre(16)))
        assertFalse(repo.allowed(MediaType.MOVIE, 1))
        coEvery { api.getMovieDetails(1, any(), any()) } returns TmdbMovieDetails(id = 1, adult = true, genres = listOf(TmdbGenre(10751)))
        assertFalse(repo.allowed(MediaType.MOVIE, 1))
    }

    @Test fun unavailableMetadataDeniesPlaybackAndCancellationPropagates() = runBlocking {
        val api = mockk<TmdbApi>()
        val repo = KidsCatalogRepository(api)
        coEvery { api.getMovieDetails(any(), any(), any()) } throws java.io.IOException("offline")
        assertFalse(repo.allowed(MediaType.MOVIE, 1))
        coEvery { api.getMovieDetails(any(), any(), any()) } throws CancellationException()
        try { repo.allowed(MediaType.MOVIE, 1); fail("Must cancel") } catch (_: CancellationException) { }
    }
}
