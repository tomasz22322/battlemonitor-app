package com.example.battlemonitor.model

import com.squareup.moshi.Json

data class PlayerDetailsResponse(
    val data: PlayerDetailsData? = null
)

data class PlayerDetailsData(
    val id: String? = null,
    val type: String? = null,
    @Json(name = "attributes")
    val attributes: Map<String, Any?>? = null
)

class PlayerDetailsAttributes(private val raw: Map<String, Any?>) {
    val createdAt: String? = raw["createdAt"]?.toString()?.takeIf { it.isNotBlank() }
    val updatedAt: String? = raw["updatedAt"]?.toString()?.takeIf { it.isNotBlank() }
    val lastSeen: String? = raw["lastSeen"]?.toString()?.takeIf { it.isNotBlank() }
}
