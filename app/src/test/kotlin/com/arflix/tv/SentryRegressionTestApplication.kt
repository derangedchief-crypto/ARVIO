package com.arflix.tv

/** Explicit non-Hilt application: never start production services/network work
 * while testing installer, image decoder and entitlement recovery regressions. */
class SentryRegressionTestApplication : android.app.Application()
