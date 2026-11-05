package com.extreme.myhub

import org.json.JSONObject

internal data class CustomerIdentity(val name: String, val subtitle: String) {
    companion object {
        fun from(row: JSONObject): CustomerIdentity {
            fun value(key: String) = if (row.isNull(key)) "" else row.optString(key).trim()
            val username = value("line_username")
            val storedName = value("name")
            val placeholder = storedName.matches(Regex("^Panel line [0-9]+$"))
            if (value("external_reference") == "management:unassigned") {
                return CustomerIdentity("Unassigned devices", "Devices without a customer assignment")
            }
            val realName = storedName.takeUnless { placeholder }.orEmpty()
            return CustomerIdentity(
                username.ifBlank { realName.ifBlank { "Username awaiting app check-in" } },
                if (username.isNotBlank() && realName.isNotBlank() && realName != username) realName else "Customer"
            )
        }
    }
}
