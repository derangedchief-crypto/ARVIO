package com.arflix.tv.data.repository

/** Paid fallback is automatic only after verified absence, or explicitly requested by the user. */
enum class SourceSearchMode { VodFirst, ManualScraperFallback }
