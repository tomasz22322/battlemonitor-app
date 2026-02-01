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
    private val steamKeys = setOf(
        "steamid",
        "steamid64",
        "steam64",
        "steam"
    )

    val createdAt: String? = raw["createdAt"]?.toString()?.takeIf { it.isNotBlank() }
    val updatedAt: String? = raw["updatedAt"]?.toString()?.takeIf { it.isNotBlank() }
    val lastSeen: String? = raw["lastSeen"]?.toString()?.takeIf { it.isNotBlank() }
    val steamId: String? = extractSteamId()

    private fun extractSteamId(): String? {
        raw.entries.firstOrNull { (key, value) ->
            key.lowercase() in steamKeys && value != null
        }?.let { (_, value) ->
            return formatIdentifier(value)
        }

        val identifiers = raw["identifiers"]
        if (identifiers is List<*>) {
            identifiers.forEach { entry ->
                if (entry is String && entry.lowercase().startsWith("steam")) {
                    return formatIdentifier(entry)
                }
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

    private fun formatIdentifier(value: Any): String? {
        return when (value) {
            is Number -> value.toString()
            is String -> normalizeSteamString(value)
            else -> value.toString()
        }?.takeIf { it.isNotBlank() }
    }

    private fun normalizeSteamString(value: String): String? {
        val trimmed = value.trim()
        if (trimmed.isBlank()) return null
        if (":" in trimmed && trimmed.lowercase().startsWith("steam")) {
            return trimmed.substringAfter(":").trim().takeIf { it.isNotBlank() }
        }
        return trimmed
    }
}
