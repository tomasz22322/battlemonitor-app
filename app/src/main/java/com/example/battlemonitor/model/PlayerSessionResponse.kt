package com.example.battlemonitor.model

import com.squareup.moshi.Json

data class PlayerSessionResponse(
    val data: List<PlayerSessionData>? = null
)

data class PlayerSessionData(
    val id: String? = null,
    val type: String? = null,
    @Json(name = "attributes")
    val attributes: PlayerSessionAttributes? = null
)

data class PlayerSessionAttributes(
    @Json(name = "start")
    val start: String? = null,
    @Json(name = "stop")
    val stop: String? = null
)
