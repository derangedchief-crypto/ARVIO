package com.arflix.tv.ui.screens.details

/** A recovered permission refreshes only a source search the user already asked
 * for, not browsing/idle screens, initial grants or every recomposition. */
internal fun shouldRetryRestoredCloudSources(previous:Boolean?,current:Boolean,searchRequested:Boolean):Boolean =
    previous==false && current && searchRequested
