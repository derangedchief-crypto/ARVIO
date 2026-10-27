package com.arflix.tv.updater

import org.junit.Assert.*
import org.junit.Test

class UpdateAssetPolicyTest {
    private fun asset(name:String)=GitHubAssetDto(name,"https://github.com/example/repo/releases/download/staging-testing/$name",100)
    private val generic=asset("ExtremeTV-Test-latest.apk")
    // Actual ordering of the shared testing release at the time of the incident.
    private val mixed=listOf(asset("Extreme-IPTV-Branded-46ae4715-2.0.114.1-test.apk"),asset("Extreme-IPTV-Branded-46ae4715-2.0.114.2-test.apk"),generic)
    @Test fun genericTestingNeverPicksTheOlderBrandedAssetFirst(){
        assertEquals(listOf(generic),UpdateAssetPolicy.candidates(mixed,"staging-testing","2.0.120",false))
    }
    @Test fun missingCorrectTestingApkFailsClosedInsteadOfPickingAnotherApp(){
        assertTrue(UpdateAssetPolicy.candidates(mixed.dropLast(1),"staging-testing","2.0.120",false).isEmpty())
    }
    @Test fun customerChannelNeverOffersATestingOrBrandedApk(){
        val customer=asset("ExtremeTV-2.0.120.apk")
        assertEquals(listOf(customer),UpdateAssetPolicy.candidates(mixed+customer,"","v2.0.120",false))
        assertTrue(UpdateAssetPolicy.candidates(mixed,"","v2.0.120",false).isEmpty())
    }
    @Test fun brandedChannelsKeepTheirOwnExactImmutableVersion(){
        val brand=asset("Brand-2.0.120.4-test.apk")
        assertEquals(listOf(brand),UpdateAssetPolicy.candidates(mixed+brand+asset("Brand-2.0.120.3-test.apk"),"anything","2.0.120.4-test",true))
    }
    @Test fun abiNamedOrMisleadingReleaseAssetsCannotCrossTheVariantBoundary(){
        val extras=listOf(asset("Brand-arm64-v8a-release.apk"),asset("ExtremeTV-2.0.120.apk"),asset("other-universal.apk"))
        assertEquals(listOf(generic),UpdateAssetPolicy.candidates(extras+mixed,"staging-testing","2.0.120",false))
    }
    @Test fun invalidVersionsOrUnknownGenericChannelsNeverFallBackToAnyApk(){
        assertTrue(UpdateAssetPolicy.candidates(mixed,"unknown","2.0.120",false).isEmpty())
        assertTrue(UpdateAssetPolicy.candidates(mixed,"staging-testing","2.0.120.4-test",false).isEmpty())
        assertTrue(UpdateAssetPolicy.candidates(listOf(asset("ExtremeTV-2.0.120.apk")),"","2.0.120-test",false).isEmpty())
    }
}
