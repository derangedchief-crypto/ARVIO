package com.arflix.tv.management

import android.content.Context
import com.arflix.tv.data.repository.IptvConfig
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.CancellationException
import org.json.JSONObject
import javax.inject.Inject
import javax.inject.Singleton

class ExtraPlaylistLicenceException(message: String) : IllegalStateException(message)
data class ExtraPlaylistDecision(val allowed:Boolean,val seconds:Long=0,val primaryInvalid:Boolean=false,val graceEligible:Boolean=true)

/** No cached unlock or device clock. A fresh server decision is needed only for additions. */
@Singleton
class ExtraPlaylistLicence @Inject constructor(@ApplicationContext private val context: Context) {
    suspend fun authorize(config: IptvConfig) {
        if(!decision(config).allowed)throw ExtraPlaylistLicenceException("Extra Playlists is locked. Your reseller needs an active monthly add-on.")
    }
    suspend fun decision(config:IptvConfig):ExtraPlaylistDecision {
        try {
            val api = ManagementApi()
            val policy = api.control("/api/device/addons/policy", JSONObject())
            check(policy.getInt("schema_version") == 1)
            if (!policy.getBoolean("enforced")) return ExtraPlaylistDecision(true,900,graceEligible=false)
            // Only the activation source may determine the paid workspace. Never
            // borrow a licensed account hidden in a secondary playlist.
            val account = managementAccount(config.copy(playlists=config.playlists.take(1),stalkerPortals=emptyList())) ?: return ExtraPlaylistDecision(false)
            val session = ManagementVault(context).load(account.scope)
            val credential = session?.credential ?: return ExtraPlaylistDecision(false)
            check(session.scope == account.scope && session.deviceId != null)
            val decision = api.control("/api/device/addons/authorize", JSONObject(), credential)
            check(decision.getInt("schema_version") == 1 && decision.getString("device_id") == session.deviceId && decision.getString("module") == "extra_playlists")
            if (!decision.getBoolean("allowed")) return ExtraPlaylistDecision(false,primaryInvalid=decision.optString("reason")=="primary_invalid")
            return ExtraPlaylistDecision(true,decision.getLong("valid_for_seconds").also{check(it in 1..900)})
        } catch (e: CancellationException) { throw e }
        catch (e: ExtraPlaylistLicenceException) { throw e }
        catch (e: ManagementHttpException) {if(e.status in listOf(401,403))return ExtraPlaylistDecision(false);throw ExtraPlaylistLicenceException("Cannot verify Extra Playlists. Retry when connected.")}
        catch (_: Exception) { throw ExtraPlaylistLicenceException("Cannot verify Extra Playlists. Retry when connected.") }
    }
}
