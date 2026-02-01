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
    val onlineTime: Long? = null,

    @Json(name = "timePlayed")
    val timePlayed: Long? = null,

    @Json(name = "sessionTime")
    val sessionTime: Long? = null
) {
    fun bestSeconds(): Long? {
        // weź pierwsze sensowne, normalizując wartości w razie ms
        return listOf(sessionTime, onlineTime, timePlayed)
            .firstOrNull { it != null && it > 0 }
            ?.let { normalizeSeconds(it) }
    }

    private fun normalizeSeconds(value: Long): Long {
        // Jeśli API zwróci ms zamiast s, zbij do sekund.
        return if (value > 1_000_000_000L) value / 1000L else value
    }
}
