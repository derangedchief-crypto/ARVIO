package com.arflix.tv.data.repository

import android.content.Context
import com.arflix.tv.data.api.TraktApi
import com.arflix.tv.data.model.CatalogConfig
import com.arflix.tv.data.model.CatalogKind
import io.mockk.mockk
import okhttp3.OkHttpClient
import org.junit.Assert.assertEquals
import org.junit.Test

class CatalogRepositoryLegacyCompatibilityTest {
    @Test fun `saved service catalogs repair dead intro URLs and retain user settings`() {
        val repository = CatalogRepository(mockk(relaxed = true), mockk(relaxed = true), mockk(relaxed = true),
            mockk(relaxed = true), mockk(relaxed = true))
        val old = "https://raw.githubusercontent.com/mrtxiv/networks-video-collection/3486fc9a3d0efe59d1929e75f66021dc4e15bcb7/"
        val json = """[{"id":"collection_service_netflix","title":"My Netflix","sourceType":"PREINSTALLED",
            "kind":"COLLECTION","collectionHideTitle":true,"collectionHeroVideoUrl":"${old}networks%20videos/netflix.mp4",
            "collectionCoverImageUrl":"${old}networks%20collection/netflix.png"}]"""
        val parse = CatalogRepository::class.java.getDeclaredMethod("parseCatalogsJson", String::class.java).apply { isAccessible = true }
        @Suppress("UNCHECKED_CAST") val cfg = (parse.invoke(repository, json) as List<CatalogConfig>).single()
        assertEquals(ServiceIntroAssets.BASE_URL + "networks%20videos/netflix.mp4", cfg.collectionHeroVideoUrl)
        assertEquals(ServiceIntroAssets.BASE_URL + "networks%20collection/netflix.png", cfg.collectionCoverImageUrl)
        assertEquals("My Netflix", cfg.title)
        org.junit.Assert.assertTrue(cfg.collectionHideTitle)
    }

    @Test
    fun `catalog without kind defaults to standard`() {
        val repository = CatalogRepository(
            context = mockk<Context>(relaxed = true),
            profileManager = mockk<ProfileManager>(relaxed = true),
            traktApi = mockk<TraktApi>(relaxed = true),
            okHttpClient = mockk<OkHttpClient>(relaxed = true),
            invalidationBus = mockk<CloudSyncInvalidationBus>(relaxed = true)
        )
        val legacyJson = """
            [
              {
                "id": "legacy_catalog",
                "title": "Legacy Catalog",
                "sourceType": "PREINSTALLED"
              }
            ]
        """.trimIndent()

        val parseMethod = CatalogRepository::class.java.getDeclaredMethod(
            "parseCatalogsJson",
            String::class.java
        ).apply {
            isAccessible = true
        }

        @Suppress("UNCHECKED_CAST")
        val parsed = parseMethod.invoke(repository, legacyJson) as List<CatalogConfig>

        assertEquals(1, parsed.size)
        assertEquals(CatalogKind.STANDARD, parsed.single().kind)
    }
}
