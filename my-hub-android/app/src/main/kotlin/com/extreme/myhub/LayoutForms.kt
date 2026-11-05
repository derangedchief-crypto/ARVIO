package com.extreme.myhub

import org.json.JSONArray
import org.json.JSONObject

internal object LayoutForms {
    fun template(catalog: JSONObject, entries: List<JSONObject>): JSONObject {
        val rows = catalog.rows()
        require(catalog.optInt("pages") == 1 && rows.size == catalog.optInt("total")) { "Read a complete category layout first." }
        val named = rows.filter { !it.isNull("match_name") }
        require(named.map { it.getString("match_name") }.distinct().size == named.size) { "Ambiguous category names; edit manually." }
        require(entries.map { it.getString("name") }.distinct().size == entries.size) { "Duplicate template names." }
        val byName = named.associateBy { it.getString("match_name") }
        val matched = entries.mapNotNull { entry -> byName[entry.getString("name")]?.let { JSONObject(it.toString()).put("hidden", entry.getBoolean("hidden")) } }
        require(matched.isNotEmpty()) { "No exact category names match this source." }
        val selected = matched.map { it.getString("ref") }.toSet()
        return JSONObject().put("items", JSONArray(matched + rows.filter { it.getString("ref") !in selected })).put("missing", JSONArray(entries.filter { it.getString("name") !in byName }.map { it.getString("name") })).put("matched", matched.size)
    }
}
