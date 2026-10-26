package com.arflix.tv.data.repository

import com.arflix.tv.access.PrimaryAccessRepository
import io.mockk.*

/** Existing catalogue/EPG tests isolate their subject from subscription transport.
 * Real access decisions have separate expiry, identity and persistence tests. */
internal fun primaryAccessFixture():PrimaryAccessRepository=mockk<PrimaryAccessRepository>(relaxed=true).also { access ->
    every{access.state}returns kotlinx.coroutines.flow.MutableStateFlow(com.arflix.tv.access.PrimaryAccessState(checking=false,bound=true,primaryAllowed=true,extrasAllowed=true))
    every{access.playbackConfig(any())}answers{firstArg()}
    every{access.editingConfig(any())}answers{firstArg()}
    every{access.protectPrimary(any(),any())}just Runs
    every{access.needsExtraGrant(any(),any())}answers{needsExtraPlaylistLicence(firstArg(),secondArg())}
    every{access.primaryAllowed()}returns true
    every{access.extrasAllowed()}returns true
    every{access.permitsIptvUrl(any())}returns true
    every{access.canonicalConfig()}returns IptvConfig(m3uUrl="https://provider.test/list.m3u")
}
