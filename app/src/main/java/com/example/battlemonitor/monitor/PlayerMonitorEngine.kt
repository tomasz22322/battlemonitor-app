package com.example.battlemonitor.monitor

import com.example.battlemonitor.data.PlayerRepository
import com.example.battlemonitor.model.PlayerAttributes
import com.example.battlemonitor.model.WatchedPlayer
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.time.temporal.ChronoUnit

data class ScanResult(
    val changed: Boolean,
    val statusChanges: List<Pair<WatchedPlayer, Boolean>>
)

class PlayerMonitorEngine(
    private val repository: PlayerRepository
) {

    private companion object {
        private const val INFO_TTL_MS = 60_000L
    }

    suspend fun scan(players: MutableList<WatchedPlayer>): ScanResult {
        val watchedKeys = players.flatMap { player ->
            buildList {
                val normalizedKey = player.key.trim().lowercase()
                if (normalizedKey.isNotBlank()) {
                    add(normalizedKey)
                }
                val resolvedId = player.resolvedId?.trim()
                if (!resolvedId.isNullOrBlank()) {
                    add(resolvedId)
                }
            }
        }.toSet()
        val snapshot = repository.fetchOnlinePlayers(watchedKeys)
        if (!snapshot.isDataValid) {
            return ScanResult(changed = false, statusChanges = emptyList())
        }
        val onlineMap = snapshot.players
        val sessionStartTimes = snapshot.sessionStartTimes
        val serverName = snapshot.serverName?.takeIf { it.isNotBlank() }
        val now = System.currentTimeMillis()

        var changed = false
        val statusChanges = mutableListOf<Pair<WatchedPlayer, Boolean>>()

        players.forEach { item ->
            val resolvedId = item.resolvedId
            val normalizedKey = item.key.lowercase()
            val found = when {
                resolvedId != null && onlineMap.containsKey(resolvedId) ->
                    onlineMap[resolvedId]
                onlineMap.containsKey(normalizedKey) ->
                    onlineMap[normalizedKey]
                else -> null
            }
            val sessionStart = when {
                resolvedId != null && sessionStartTimes.containsKey(resolvedId) ->
                    sessionStartTimes[resolvedId]
                sessionStartTimes.containsKey(normalizedKey) ->
                    sessionStartTimes[normalizedKey]
                else -> null
            }

            val wasOnline = item.online
            val oldName = item.resolvedName
            val oldTime = item.playTime
            val oldDetails = item.details.orEmpty()
            val oldServer = item.currentServerName
            val oldCreatedAt = item.createdAt
            val oldUpdatedAt = item.updatedAt
            val oldLastSeenApiAt = item.lastSeenApiAt
            val oldBattleMetricsId = item.battleMetricsId
            val oldSteamId = item.steamId

            val apiDetails = found?.buildDetails().orEmpty()

            if (found != null) {
                updateOnlinePlayer(item, found, serverName, now, wasOnline, sessionStart)
            } else {
                updateOfflinePlayer(item, now, wasOnline)
            }

            refreshPlayerInfoIfNeeded(item, now)

            item.details = mergeDetails(
                buildDerivedDetails(
                    item = item,
                    now = now,
                    apiDetails = apiDetails
                )
            )

            if (wasOnline != item.online ||
                oldName != item.resolvedName ||
                oldTime != item.playTime ||
                oldDetails != item.details.orEmpty() ||
                oldServer != item.currentServerName ||
                oldCreatedAt != item.createdAt ||
                oldUpdatedAt != item.updatedAt ||
                oldLastSeenApiAt != item.lastSeenApiAt ||
                oldBattleMetricsId != item.battleMetricsId ||
                oldSteamId != item.steamId
            ) {
                changed = true
            }

            if (wasOnline != item.online) {
                statusChanges.add(item to item.online)
            }
        }

        return ScanResult(changed = changed, statusChanges = statusChanges)
    }

    private fun updateOnlinePlayer(
        item: WatchedPlayer,
        found: PlayerAttributes,
        serverName: String?,
        now: Long,
        wasOnline: Boolean,
        sessionStart: Long?
    ) {
        item.online = true

        val newName = found.name ?: item.resolvedName.ifBlank { item.key }
        item.resolvedName = newName
        if (!found.id.isNullOrBlank()) {
            item.resolvedId = found.id
            item.battleMetricsId = found.id
        }
        val steamId = found.extractSteamId()
        if (!steamId.isNullOrBlank()) {
            item.steamId = steamId
        }
        if (serverName != null && serverName != item.currentServerName) {
            item.currentServerName = serverName
        } else if (serverName != null) {
            item.currentServerName = serverName
        }

        if (item.resolvedId.isNullOrBlank()) {
            val key = item.key.trim()
            if (key.all { it.isDigit() }) item.resolvedId = key
        }

        item.lastSeenAt = now
        if (!wasOnline) {
            item.joinHourCounts = incrementHourCount(item.joinHourCounts, now)
        }

        if (sessionStart != null) {
            item.sessionStartAt = sessionStart
        } else if (!wasOnline) {
            item.sessionStartAt = null
        }

        val sessionSeconds = item.sessionStartAt?.let { ((now - it) / 1000L).coerceAtLeast(0) }
        val secondsToShow = sessionSeconds?.takeIf { it > 0 }
        item.playTime = when {
            secondsToShow == null || secondsToShow <= 0 -> "??"
            else -> formatDuration(secondsToShow)
        }

    }

    private fun updateOfflinePlayer(
        item: WatchedPlayer,
        now: Long,
        wasOnline: Boolean
    ) {
        if (wasOnline) {
            val sessionSeconds =
                item.sessionStartAt?.let { ((now - it) / 1000L).coerceAtLeast(0) }
            if (sessionSeconds != null && sessionSeconds > 0) {
                item.lastSessionSeconds = sessionSeconds
                val total = (item.totalSessionSeconds ?: 0L) + sessionSeconds
                item.totalSessionSeconds = total
            }
            item.sessionStartAt = null
            item.lastOfflineAt = now
            item.leaveHourCounts = incrementHourCount(item.leaveHourCounts, now)
        }
        item.online = false
        item.playTime = ""
        item.currentServerName = null

    }

    private suspend fun refreshPlayerInfoIfNeeded(item: WatchedPlayer, now: Long) {
        val resolvedId = item.resolvedId?.takeIf { it.isNotBlank() } ?: return
        val lastFetched = item.infoFetchedAt ?: 0L
        if (now - lastFetched < INFO_TTL_MS) return

        val info = repository.fetchPlayerInfo(resolvedId)
        if (info != null) {
            item.resolvedId = info.id
            item.battleMetricsId = info.id
            item.createdAt = info.createdAt
            item.updatedAt = info.updatedAt
            item.lastSeenApiAt = info.lastSeenAt
            if (!info.steamId.isNullOrBlank()) {
                item.steamId = info.steamId
            }
        }
        item.infoFetchedAt = now
    }

    private fun buildDerivedDetails(
        item: WatchedPlayer,
        now: Long,
        apiDetails: List<String>
    ): List<String> {
        val details = mutableListOf<String>()
        val battleMetricsId = item.battleMetricsId ?: item.resolvedId
        details.add("ID BM: ${battleMetricsId ?: "brak danych"}")
        val updatedAtForStay = parseUpdatedAtFromDetails(apiDetails) ?: item.updatedAt
        val staySeconds = updatedAtForStay?.let { ((now - it) / 1000L).coerceAtLeast(0) }
        details.add(
            "Czas przebywania: ${
                if (staySeconds != null) formatDuration(staySeconds)
                else "brak danych"
            }"
        )
        details.add(buildLastLoginDetails(item.lastSeenAt ?: item.lastSeenApiAt, now))

        return details
    }

    private fun parseUpdatedAtFromDetails(details: List<String>): Long? {
        return details.asSequence()
            .mapNotNull { entry ->
                val parts = entry.split(":", limit = 2)
                if (parts.size < 2) return@mapNotNull null
                if (!parts[0].trim().equals("Updated At", ignoreCase = true)) {
                    return@mapNotNull null
                }
                val value = parts[1].trim()
                runCatching { Instant.parse(value).toEpochMilli() }.getOrNull()
            }
            .firstOrNull()
    }

    private fun mergeDetails(details: List<String>): List<String> {
        val seen = LinkedHashSet<String>()
        val output = mutableListOf<String>()
        details.forEach { entry ->
            val normalized = entry.trim().lowercase()
            if (normalized.isBlank()) return@forEach
            if (seen.add(normalized)) {
                output.add(entry)
            }
        }
        return output
    }

    private fun formatDuration(seconds: Long): String {
        val safeSeconds = seconds.coerceAtLeast(0)
        val minutes = safeSeconds / 60
        val hours = minutes / 60
        val days = hours / 24

        return when {
            days > 0 -> "${days}d ${hours % 24}h"
            hours > 0 -> "${hours}h ${minutes % 60}m"
            else -> "${minutes}m"
        }
    }

    private fun buildLastLoginDetails(lastLoginAt: Long?, now: Long): String {
        if (lastLoginAt == null) {
            return "Ostatnio zalogował null"
        }
        val zone = ZoneId.systemDefault()
        val loginDateTime = Instant.ofEpochMilli(lastLoginAt).atZone(zone)
        val nowDate = Instant.ofEpochMilli(now).atZone(zone).toLocalDate()
        val loginDate = loginDateTime.toLocalDate()
        val daysAgo = ChronoUnit.DAYS.between(loginDate, nowDate).coerceAtLeast(0)
        val dayLabel = when (daysAgo) {
            0L -> "dzisiaj"
            1L -> "wczoraj"
            else -> "$daysAgo dni temu"
        }
        val timeText = loginDateTime.format(DateTimeFormatter.ofPattern("HH:mm"))
        return "Ostatnio zalogował $dayLabel o $timeText"
    }

    private fun incrementHourCount(counts: List<Int>?, timestamp: Long): List<Int> {
        val next = (counts ?: List(24) { 0 }).toMutableList()
        val hour = Instant.ofEpochMilli(timestamp)
            .atZone(ZoneId.systemDefault())
            .hour
        if (hour in 0..23) {
            next[hour] = next[hour] + 1
        }
        return next
    }

}
