package com.arflix.tv

import org.junit.Assert.assertNotNull
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import org.robolectric.annotation.ConscryptMode

/** Exercise ARVIO's real Hilt application, not only OwnTV's isolated graph. */
@RunWith(RobolectricTestRunner::class)
@ConscryptMode(ConscryptMode.Mode.OFF)
@Config(sdk = [28], application = ArflixApplication::class, manifest = Config.NONE)
class ActualApplicationStartupTest {
    @Test fun realApplicationInitializes() {
        assertNotNull((RuntimeEnvironment.getApplication() as ArflixApplication).workerFactory)
    }
}
