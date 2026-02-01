package com.example.battlemonitor.data

import android.util.Log
import com.example.battlemonitor.api.RetrofitInstance
import com.example.battlemonitor.model.PlayerAttributes
import com.example.battlemonitor.model.PlayerSessionAttributes
import com.example.battlemonitor.model.ServerAttributes
import java.time.Instant

data class OnlinePlayersSnapshot(
    val serverName: String?,
    val players: Map<String, PlayerAttributes>,
    val sessionStartTimes: Map<String, Long>
)

class PlayerRepository {

    /**
     * 🚨 NIE TRZYMAJ TOKENA W KODZIE.
     * - Przenieś do BuildConfig (gradle) albo local.properties
     * - Ten który wrzuciłeś publicznie: NATYCHMIAST WYMIENIĆ.
     */
    private val token =
        "Bearer eyJhbGciOiJIUzI1NiIsInR5cCI6IkpXVCJ9.eyJ0b2tlbiI6ImFiYmY1ZGFhNTg1YTQ2M2IiLCJpYXQiOjE3Njk3Nzg4NDAsIm5iZiI6MTc2OTc3ODg0MCwiaXNzIjoiaHR0cHM6Ly93d3cuYmF0dGxlbWV0cmljcy5jb20iLCJzdWIiOiJ1cm46dXNlcjoxMTQ1MTU0In0.uv0ly-B3hXsdfIHIewS_Pp5byKRLxwZ_SxvLxPAh4WU"

    private companion object {
        private const val SERVER_ID = "14154299"
    }

    /**
     * Zwraca mapę klucz -> PlayerAttributes:
     * - klucz = playerId (np. battlemetrics player id)
     * - klucz = name.lowercase() (opcjonalnie)
     */
    suspend fun fetchOnlinePlayers(): OnlinePlayersSnapshot {
        return try {
            val response = RetrofitInstance.api.getServer(auth = token)

            if (!response.isSuccessful) {
                Log.e("BM", "HTTP ${response.code()} ${response.message()}")
                Log.e("BM", "errorBody: ${response.errorBody()?.string()}")
                return OnlinePlayersSnapshot(
                    serverName = null,
                    players = emptyMap(),
                    sessionStartTimes = emptyMap()
                )
            }

            val body = response.body()
            if (body == null) {
                Log.e("BM", "Response body is null")
                return OnlinePlayersSnapshot(
                    serverName = null,
                    players = emptyMap(),
                    sessionStartTimes = emptyMap()
                )
            }

            val result = HashMap<String, PlayerAttributes>(256)
            val sessionStartTimes = HashMap<String, Long>(128)
            val serverName = body.data?.attributes
                ?.let { ServerAttributes(it).name }

            val included = body.included.orEmpty()
            for (item in included) {
                if (item.type != "player") continue

                val id = item.id ?: continue
                val attrMap = item.attributes ?: continue
                val attr = PlayerAttributes(attrMap)
                val sessionStartAt = fetchSessionStartAt(id)

                // ✅ mapowanie po ID
                result[id] = attr
                if (sessionStartAt != null) {
                    sessionStartTimes[id] = sessionStartAt
                }

                // ✅ mapowanie po nicku (lowercase)
                val name = attr.name
                if (!name.isNullOrBlank()) {
                    result[name.lowercase()] = attr
                    if (sessionStartAt != null) {
                        sessionStartTimes[name.lowercase()] = sessionStartAt
                    }
                }

                /**
                 * 🔥 Jeśli chcesz "czas od kiedy gracz dołączył":
                 * - musisz mieć w PlayerAttributes jakieś pole czasu sesji albo timestamp
                 *   np.:
                 *   attr.timePlayed
                 *   attr.onlineSince
                 *   attr.lastSeen
                 *   attr.createdAt
                 *   attr.updatedAt
                 *
                 * Samo BattleMetrics w endpointach servera często NIE daje "join time"
                 * per player wprost — czasem trzeba go wyciągać inaczej (np. endpoint player sessions / events).
                 *
                 * Jak pokażesz mi definicję PlayerAttributes i 1 przykładowy JSON "player"
                 * z included, to dopasuję to 1:1.
                 */
            }

            OnlinePlayersSnapshot(
                serverName = serverName,
                players = result,
                sessionStartTimes = sessionStartTimes
            )
        } catch (e: Exception) {
            Log.e("BM", "fetchOnlinePlayers exception: ${e.message}", e)
            OnlinePlayersSnapshot(
                serverName = null,
                players = emptyMap(),
                sessionStartTimes = emptyMap()
            )
        }
    }

    private suspend fun fetchSessionStartAt(playerId: String): Long? {
        return try {
            val response = RetrofitInstance.api.getPlayerSessions(
                playerId = playerId,
                serverId = SERVER_ID,
                auth = token
            )
            if (!response.isSuccessful) {
                Log.e("BM", "Sessions HTTP ${response.code()} ${response.message()}")
                return null
            }

            val session = response.body()
                ?.data
                ?.firstOrNull()
                ?.attributes

            parseSessionStart(session)
        } catch (e: Exception) {
            Log.e("BM", "fetchSessionStartAt exception: ${e.message}", e)
            null
        }
    }

    private fun parseSessionStart(attributes: PlayerSessionAttributes?): Long? {
        if (attributes == null) return null
        if (!attributes.stop.isNullOrBlank()) return null
        val start = attributes.start ?: return null
        return runCatching { Instant.parse(start).toEpochMilli() }.getOrNull()
    }
}
