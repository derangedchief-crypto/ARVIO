package com.arflix.tv.management

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.isActive

internal fun preserveObservedManagementRestriction(saved: ManagementSession, observedPolicy: String?): ManagementSession =
    if (observedPolicy in setOf("suspended", "blocked", "removed")) saved.copy(policy = observedPolicy) else saved

/** A transient keystore/file startup failure must not end the foreground runner.
 * Cancellation still belongs to lifecycle/account switching, never a retry. */
internal suspend fun runManagementSessionWithRetry(
    onFailure: () -> Unit,
    waitForRetry: suspend () -> Unit,
    runSession: suspend () -> Unit,
) {
    while (currentCoroutineContext().isActive) {
        try {
            runSession()
            return
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (_: Exception) {
            onFailure()
            waitForRetry()
        }
    }
}
