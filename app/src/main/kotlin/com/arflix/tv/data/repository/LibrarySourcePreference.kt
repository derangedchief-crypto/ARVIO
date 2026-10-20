package com.arflix.tv.data.repository

import com.arflix.tv.data.model.StreamSource

internal fun isLibrarySource(source: StreamSource): Boolean =
    source.addonId == "iptv_xtream_vod" || source.addonId == HomeServerRepository.ADDON_ID

/** Listing Cloud backups must not automatically resolve/open them when a library link exists. */
internal fun automaticPrewarmSources(sources: List<StreamSource>): List<StreamSource> =
    if (sources.any(::isLibrarySource)) sources.filter(::isLibrarySource) else sources

internal fun mergeVodAndBackupSources(vod: List<StreamSource>, backups: List<StreamSource>): List<StreamSource> =
    (vod + backups).distinctBy(::providerScopedStreamIdentity)
