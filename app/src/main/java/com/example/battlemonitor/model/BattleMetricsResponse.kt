package com.example.battlemonitor.model

import com.squareup.moshi.Json

data class BattleMetricsResponse(
    val data: ServerData? = null,
    val included: List<IncludedPlayer>? = null
)

data class ServerData(
    val id: String? = null,
    val type: String? = null,
    @Json(name = "attributes")
    val attributes: Map<String, Any?>? = null
)

data class IncludedPlayer(
    val id: String? = null,
    val type: String? = null,
    @Json(name = "attributes")
    val attributes: Map<String, Any?>? = null
)

class ServerAttributes(private val raw: Map<String, Any?>) {
    val name: String? = raw["name"]?.toString()?.takeIf { it.isNotBlank() }
}

class PlayerAttributes(
    private val raw: Map<String, Any?>,
    val id: String? = null
) {

    private val steamKeys = setOf(
        "steamid",
        "steamid64",
        "steam64",
        "steam"
    )

    private val preferredDetails = listOf(
        "steamID",
        "steamId",
        "steamid",
        "steam64",
        "playerId",
        "country",
        "region",
        "score",
        "rank",
        "kills",
        "deaths",
        "kdr",
        "level"
    )

    private val excludedDetailKeys = setOf(
        "name",
        "online",
        "status",
        "lastSeen",
        "firstSeen",
        "createdAt",
        "updatedAt",
        "sessionTime",
        "onlineTime",
        "timePlayed",
        "timePlayedSeconds",
        "secondsPlayed",
        "playTime",
        "playtime"
    )

    val name: String? = raw["name"]?.toString()?.takeIf { it.isNotBlank() }

    fun extractSteamId(): String? {
        raw.entries.firstOrNull { (key, value) ->
            key.lowercase() in steamKeys && value != null
        }?.let { (_, value) ->
            return formatIdentifier(value)
        }

        val identifiers = raw["identifiers"]
        if (identifiers is List<*>) {
            identifiers.forEach { entry ->
                val map = entry as? Map<*, *> ?: return@forEach
                val type = map["type"]?.toString()?.lowercase()
                val identifier = map["identifier"] ?: map["id"]
                if (type?.contains("steam") == true && identifier != null) {
                    return formatIdentifier(identifier)
                }
            }
        }

        return null
    }

    fun bestSeconds(): Long? {
        val timeKeys = listOf(
            "sessionTime",
            "onlineTime",
            "timePlayed",
            "timePlayedSeconds",
            "secondsPlayed",
            "playTime",
            "playtime"
        )

        return timeKeys.asSequence()
            .mapNotNull { key -> raw[key]?.let { normalizeSeconds(it) } }
            .firstOrNull { it > 0 }
    }

    fun buildDetails(): List<String> {
        val details = mutableListOf<String>()
        val usedKeys = mutableSetOf<String>()

        preferredDetails.forEach { key ->
            val value = raw[key] ?: return@forEach
            val formatted = formatValue(value) ?: return@forEach
            details.add("${formatLabel(key)}: $formatted")
            usedKeys.add(key.lowercase())
        }

        raw.keys
            .filterNot { it.lowercase() in excludedDetailKeys || it.lowercase() in usedKeys }
            .sortedBy { it.lowercase() }
            .forEach { key ->
                val value = raw[key] ?: return@forEach
                val formatted = formatValue(value) ?: return@forEach
                details.add("${formatLabel(key)}: $formatted")
            }

        return details
    }

    private fun normalizeSeconds(value: Any): Long? {
        val number = when (value) {
            is Number -> value.toLong()
            is String -> value.toLongOrNull()
            else -> null
        } ?: return null

        return if (number > 1_000_000_000L) number / 1000L else number
    }

    private fun formatLabel(key: String): String {
        val spaced = key.replace(Regex("([a-z])([A-Z])"), "$1 $2")
        return spaced.replaceFirstChar { it.uppercase() }
    }

    private fun formatValue(value: Any?): String? {
        return when (value) {
            null -> null
            is Boolean -> if (value) "tak" else "nie"
            is Number -> value.toString()
            is String -> value.takeIf { it.isNotBlank() }
            is Map<*, *> -> value.entries.joinToString(", ") { entry ->
                "${entry.key}:${entry.value}"
            }
            is List<*> -> value.joinToString(", ") { it.toString() }
            else -> value.toString()
        }?.takeIf { it.isNotBlank() }
    }

    private fun formatIdentifier(value: Any): String? {
        return when (value) {
            is Number -> value.toString()
            is String -> value.takeIf { it.isNotBlank() }
            else -> value.toString()
        }?.takeIf { it.isNotBlank() }
    }
}
