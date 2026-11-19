package com.arflix.tv.ui.screens.player

import java.io.IOException

/** A failed atomic preference write must not terminate playback. Leave the last
 * persisted state intact and let the next progress tick retry; never publish a
 * failed local save as a successful cloud snapshot. Cancellation still escapes. */
internal suspend fun persistPlaybackProgress(
    onStorageFailure: suspend (IOException) -> Unit,
    save: suspend () -> Unit,
) {
    try {
        save()
    } catch (error: IOException) {
        onStorageFailure(error)
    }
}
