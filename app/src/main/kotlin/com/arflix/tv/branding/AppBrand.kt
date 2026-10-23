package com.arflix.tv.branding

import com.arflix.tv.BuildConfig

/** Cosmetic public build identity. Never an authorization secret or entitlement. */
object AppBrand {
    val id: String get() = BuildConfig.BRAND_ID
    val name: String get() = BuildConfig.BRAND_NAME
    private val originalName = Regex("Extreme\\s+(?:TV|Hub)", RegexOption.IGNORE_CASE)
    fun text(value: String): String = if (id.isEmpty()) value else originalName.replace(value) { name }
}
