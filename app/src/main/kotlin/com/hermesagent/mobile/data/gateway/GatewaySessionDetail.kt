package com.hermesagent.mobile.data.gateway

import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.doubleOrNull

/** Read-only detail contract, sessions.py::get_session_detail @ e05b16348b1d06a3311237423b0a4fc30d9c5aa1. */
data class GatewaySessionDetail(
    val id: String,
    val profile: String,
    val source: String?,
    val ended: Boolean,
    val schedulerOwned: Boolean?,
    val isActive: Boolean?,
    val lastActiveSeconds: Double?,
) {
    companion object {
        internal fun parse(row: JsonObject, id: String, profile: String): GatewaySessionDetail? {
            fun string(key: String) = (row[key] as? JsonPrimitive)?.takeIf { it.isString }?.content
            fun boolean(key: String) = (row[key] as? JsonPrimitive)?.takeUnless { it.isString }?.booleanOrNull
            if (string("id") != id || string("profile") != profile) return null
            return GatewaySessionDetail(
                id, profile, string("source"),
                row["ended_at"] != null && row["ended_at"] != JsonNull,
                boolean("scheduler_owned"), boolean("is_active"),
                (row["last_active"] as? JsonPrimitive)?.takeUnless { it.isString }
                    ?.doubleOrNull?.takeIf { it.isFinite() },
            )
        }
    }
}
