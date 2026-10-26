package com.arflix.tv.management

import android.content.Context
import com.arflix.tv.data.repository.IptvConfig
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.CancellationException
import org.json.JSONObject
import javax.inject.Inject
import javax.inject.Singleton

class ExtraPlaylistLicenceException(message: String) : IllegalStateException(message)

/** No cached unlock or device clock. A fresh server decision is needed only for additions. */
@Singleton
class ExtraPlaylistLicence @Inject constructor(@ApplicationContext private val context: Context) {
    suspend fun authorize(config: IptvConfig) {
        try {
            val api = ManagementApi()
            val policy = api.control("/api/device/addons/policy", JSONObject())
            check(policy.getInt("schema_version") == 1)
            if (!policy.getBoolean("enforced")) return
            // Only the activation source may determine the paid workspace. Never
            // borrow a licensed account hidden in a secondary playlist.
            val account = managementAccount(config.copy(playlists=config.playlists.take(1),stalkerPortals=emptyList())) ?: throw ExtraPlaylistLicenceException("Extra Playlists is locked. Contact your reseller about the monthly add-on.")
            val session = ManagementVault(context).load(account.scope)
            val credential = session?.credential ?: throw ExtraPlaylistLicenceException("Connect this activation account to the control center, then retry Extra Playlists.")
            check(session.scope == account.scope && session.deviceId != null)
            val decision = api.control("/api/device/addons/authorize", JSONObject(), credential)
            check(decision.getInt("schema_version") == 1 && decision.getString("device_id") == session.deviceId && decision.getString("module") == "extra_playlists")
            if (!decision.getBoolean("allowed")) throw ExtraPlaylistLicenceException("Extra Playlists is locked. Your reseller needs an active monthly add-on. Existing playlists still work.")
        } catch (e: CancellationException) { throw e }
        catch (e: ExtraPlaylistLicenceException) { throw e }
        catch (_: Exception) { throw ExtraPlaylistLicenceException("Cannot verify Extra Playlists. Connect to the internet and retry. Existing playlists still work.") }
    }
}
