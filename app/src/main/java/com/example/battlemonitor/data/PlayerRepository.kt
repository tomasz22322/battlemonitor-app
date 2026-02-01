package com.example.battlemonitor.data

import android.util.Log
import com.example.battlemonitor.api.RetrofitInstance
import com.example.battlemonitor.model.PlayerAttributes

class PlayerRepository {

    /**
     * 🚨 NIE TRZYMAJ TOKENA W KODZIE.
     * - Przenieś do BuildConfig (gradle) albo local.properties
     * - Ten który wrzuciłeś publicznie: NATYCHMIAST WYMIENIĆ.
     */
    private val token =
        "Bearer eyJhbGciOiJIUzI1NiIsInR5cCI6IkpXVCJ9.eyJ0b2tlbiI6ImFiYmY1ZGFhNTg1YTQ2M2IiLCJpYXQiOjE3Njk3Nzg4NDAsIm5iZiI6MTc2OTc3ODg0MCwiaXNzIjoiaHR0cHM6Ly93d3cuYmF0dGxlbWV0cmljcy5jb20iLCJzdWIiOiJ1cm46dXNlcjoxMTQ1MTU0In0.uv0ly-B3hXsdfIHIewS_Pp5byKRLxwZ_SxvLxPAh4WU"


    /**
     * Zwraca mapę klucz -> PlayerAttributes:
     * - klucz = playerId (np. battlemetrics player id)
     * - klucz = name.lowercase() (opcjonalnie)
     */
    suspend fun fetchOnlinePlayers(): Map<String, PlayerAttributes> {
        return try {
            val response = RetrofitInstance.api.getServer(auth = token)

            if (!response.isSuccessful) {
                Log.e("BM", "HTTP ${response.code()} ${response.message()}")
                Log.e("BM", "errorBody: ${response.errorBody()?.string()}")
                return emptyMap()
            }

            val body = response.body()
            if (body == null) {
                Log.e("BM", "Response body is null")
                return emptyMap()
            }

            val result = HashMap<String, PlayerAttributes>(256)

            val included = body.included.orEmpty()
            for (item in included) {
                if (item.type != "player") continue

                val id = item.id ?: continue
                val attr = item.attributes ?: continue

                // ✅ mapowanie po ID
                result[id] = attr

                // ✅ mapowanie po nicku (lowercase)
                val name = attr.name
                if (!name.isNullOrBlank()) {
                    result[name.lowercase()] = attr
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

            result
        } catch (e: Exception) {
            Log.e("BM", "fetchOnlinePlayers exception: ${e.message}", e)
            emptyMap()
        }
    }
}
