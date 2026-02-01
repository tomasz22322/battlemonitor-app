package com.example.battlemonitor.model

import com.squareup.moshi.Json

data class BattleMetricsResponse(
    val included: List<IncludedPlayer>? = null
)

data class IncludedPlayer(
    val id: String? = null,
    val type: String? = null,
    val attributes: PlayerAttributes? = null
)

data class PlayerAttributes(
    val name: String? = null,

    // Najczęściej spotykane nazwy pól z czasem (sekundy)
    @Json(name = "onlineTime")
    val onlineTime: Int? = null,

    @Json(name = "timePlayed")
    val timePlayed: Int? = null,

    @Json(name = "sessionTime")
    val sessionTime: Int? = null
) {
    fun bestSeconds(): Int? {
        // weź pierwsze sensowne
        return listOf(sessionTime, onlineTime, timePlayed)
            .firstOrNull { it != null && it > 0 }
    }
}
