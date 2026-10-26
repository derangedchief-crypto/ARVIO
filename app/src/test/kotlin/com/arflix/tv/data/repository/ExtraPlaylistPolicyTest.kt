package com.arflix.tv.data.repository

import org.junit.Assert.*
import org.junit.Test

class ExtraPlaylistPolicyTest {
    private val a=IptvPlaylistEntry("list_1","Activation","https://a.test")
    private val b=IptvPlaylistEntry("list_2","Other","https://b.test")
    private val c=IptvPlaylistEntry("list_3","New","https://c.test")
    private fun config(vararg p:IptvPlaylistEntry)=IptvConfig(m3uUrl=p.firstOrNull()?.m3uUrl.orEmpty(),playlists=p.toList())
    @Test fun baseActivationAndAccountTransferAreNotPaidAdditions(){assertFalse(needsExtraPlaylistLicence(IptvConfig(),config(a)));assertFalse(needsExtraPlaylistLicence(config(a),config(c)))}
    @Test fun extraM3uOrXtreamIdentityRequiresLicence(){assertTrue(needsExtraPlaylistLicence(config(a),config(a,b)));assertTrue(needsExtraPlaylistLicence(config(a,b),config(a,b.copy(m3uUrl="https://changed.test"))))}
    @Test fun metadataDisablingReorderingAndRemovalKeepExistingAccess(){assertFalse(needsExtraPlaylistLicence(config(a,b),config(a,b.copy(name="Renamed",enabled=false))));assertFalse(needsExtraPlaylistLicence(config(a,b),config(b,a)));assertFalse(needsExtraPlaylistLicence(config(a,b),config(a)))}
    @Test fun retainingOldPrimaryCannotHideANewAddition(){assertTrue(needsExtraPlaylistLicence(config(a,b),config(c,a,b)))}
    @Test fun movingAnExtraSourceFirstCannotBypassCredentialReplacement(){assertTrue(needsExtraPlaylistLicence(config(b,a),config(b.copy(m3uUrl="https://replacement.test"),a)));assertTrue(needsExtraPlaylistLicence(config(a,b),config(a.copy(m3uUrl="https://replacement.test"),a)))}
    @Test fun newOrReplacedStalkerPortalRequiresLicence(){val p=StalkerPortalEntry("portal","Portal","https://p.test","00:1A:79:00:00:01");val base=config(a);val withPortal=base.copy(stalkerPortals=listOf(p));assertTrue(needsExtraPlaylistLicence(base,withPortal));assertTrue(needsExtraPlaylistLicence(withPortal,withPortal.copy(stalkerPortals=listOf(p.copy(macAddress="00:1A:79:00:00:02")))));assertFalse(needsExtraPlaylistLicence(withPortal,withPortal.copy(stalkerPortals=listOf(p.copy(name="Rename")))))}
    @Test fun baseActivationDoesNotDisableAnExistingLegacyPortal(){val p=StalkerPortalEntry("portal","Portal","https://p.test","00:1A:79:00:00:01");assertFalse(needsExtraPlaylistLicence(IptvConfig(stalkerPortals=listOf(p)),config(a).copy(stalkerPortals=listOf(p))))}
}
