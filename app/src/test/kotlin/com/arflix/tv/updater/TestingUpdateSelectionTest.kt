package com.arflix.tv.updater

import android.app.Application
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.ConscryptMode

@RunWith(RobolectricTestRunner::class)
@ConscryptMode(ConscryptMode.Mode.OFF)
@Config(sdk=[28],application=Application::class,manifest=Config.NONE)
class TestingUpdateSelectionTest {
    @Test fun actualMixedTestingReleaseOrderCannotOfferABrandedApkToTheGenericApp(){
        fun asset(name:String)=GitHubAssetDto(name,"https://github.com/example/repo/releases/download/staging-testing/$name",100)
        val oldBrand=asset("Extreme-IPTV-Branded-46ae4715-2.0.114.1-test.apk")
        val generic=asset("ExtremeTV-Test-latest.apk")
        val mixed=listOf(oldBrand,asset("Extreme-IPTV-Branded-46ae4715-2.0.114.2-test.apk"),generic)
        assertEquals("Reproduces the old in-app selection bug",oldBrand,AbiSelector.chooseBestApkAsset(mixed))
        assertEquals(generic,AbiSelector.chooseBestApkAsset(UpdateAssetPolicy.candidates(mixed,"staging-testing","2.0.120",false)))
    }
}
