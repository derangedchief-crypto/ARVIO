package com.arflix.tv.ui.screens.tv.live

import com.arflix.tv.updater.VersionUtils
import org.junit.Assert.assertTrue
import org.junit.Test

class OwnTvUpdateVersionTest {
    @Test fun nextTestingReleaseIsNewerThanActivationLoginBuild() {
        assertTrue(VersionUtils.isRemoteNewer("2.0.075", "2.0.074-test"))
    }
}
