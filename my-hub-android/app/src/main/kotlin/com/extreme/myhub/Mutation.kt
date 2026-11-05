package com.extreme.myhub

import org.json.JSONObject
import java.util.UUID

/** Only routes with server-enforced idempotency may retain a replayable body. */
internal data class Mutation(val route: String, val refresh: String, val key: String?, val body: String?) {
    fun encode(): String = JSONObject().put("route", route).put("refresh", refresh).put("key", key ?: JSONObject.NULL).put("body", body ?: JSONObject.NULL).toString()
    companion object {
        fun supportsRetry(route: String): Boolean = Regex("devices/[a-f0-9]{32}/(commands|setup|favourites/backups/[a-f0-9]{32}/(preview|restore))").matches(route)
        fun create(route: String, refresh: String, payload: JSONObject): Mutation = if (supportsRetry(route)) Mutation(route, refresh, UUID.randomUUID().toString().replace("-", ""), payload.toString()) else Mutation(route, refresh, null, null)
        fun decode(value: String): Mutation = JSONObject(value).let { Mutation(it.getString("route"), it.getString("refresh"), if (it.isNull("key")) null else it.getString("key"), if (it.isNull("body")) null else it.getString("body")) }.also {
            require((it.key == null && it.body == null) || (supportsRetry(it.route) && it.key?.matches(Regex("[a-f0-9]{32}")) == true && it.body != null))
        }
    }
}
