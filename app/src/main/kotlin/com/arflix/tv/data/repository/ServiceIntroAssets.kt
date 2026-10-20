package com.arflix.tv.data.repository

/** Surviving copy of the original service artwork/intros, pinned to verified files. */
internal object ServiceIntroAssets {
    const val BASE_URL = "https://raw.githubusercontent.com/PacerguyDon/networks-video-collection/c5802b081ccc945bbac8d74621c687e711627ce2/"
    private const val LEGACY_BASE = "https://raw.githubusercontent.com/mrtxiv/networks-video-collection/3486fc9a3d0efe59d1929e75f66021dc4e15bcb7/"

    /** Repair existing saved/cloud-restored catalogs without touching custom URLs. */
    fun resolve(url: String?): String? = url?.trim()?.takeIf { it.isNotEmpty() }?.let {
        if (it.startsWith(LEGACY_BASE)) BASE_URL + it.removePrefix(LEGACY_BASE) else it
    }
}
