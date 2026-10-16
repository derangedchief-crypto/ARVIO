package com.arflix.tv.ui.screens.tv.live

/** In-session restoration memory for guide page positions, scoped by provider and category. */
internal class TvGuideViewportMemory {
    private val windows = LinkedHashMap<String, IntRange>()

    fun remember(scope: String, start: Int, endExclusive: Int) {
        if (scope.isBlank() || endExclusive <= start) return
        windows[scope] = start until endExclusive
    }

    fun restore(scope: String): IntRange? = windows[scope]
}
