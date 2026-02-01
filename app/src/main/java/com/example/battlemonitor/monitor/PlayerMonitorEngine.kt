package com.example.battlemonitor.monitor

import com.example.battlemonitor.data.PlayerRepository
import com.example.battlemonitor.model.PlayerAttributes
import com.example.battlemonitor.model.WatchedPlayer
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter

data class ScanResult(
    val changed: Boolean,
    val statusChanges: List<Pair<WatchedPlayer, Boolean>>
)

class PlayerMonitorEngine(
    private val repository: PlayerRepository
) {

    suspend fun scan(players: MutableList<WatchedPlayer>): ScanResult {
        val snapshot = repository.fetchOnlinePlayers()
        val onlineMap = snapshot.players
        val serverName = snapshot.serverName?.takeIf { it.isNotBlank() }
        val now = System.currentTimeMillis()

        var changed = false
        val statusChanges = mutableListOf<Pair<WatchedPlayer, Boolean>>()

        players.forEach { item ->
            val found = when {
                item.resolvedId != null && onlineMap.containsKey(item.resolvedId!!) ->
                    onlineMap[item.resolvedId!!]
                onlineMap.containsKey(item.key.lowercase()) ->
                    onlineMap[item.key.lowercase()]
                else -> null
            }

            val wasOnline = item.online
            val oldName = item.resolvedName
            val oldTime = item.playTime
            val oldDetails = item.details.orEmpty()
            val oldServer = item.currentServerName

            if (found != null) {
                updateOnlinePlayer(item, found, serverName, now, wasOnline)
            } else {
                updateOfflinePlayer(item, now, wasOnline)
            }

            if (wasOnline != item.online ||
                oldName != item.resolvedName ||
                oldTime != item.playTime ||
                oldDetails != item.details.orEmpty() ||
                oldServer != item.currentServerName
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
        wasOnline: Boolean
    ) {
        item.online = true

        val newName = found.name ?: item.resolvedName.ifBlank { item.key }
        item.resolvedName = newName
        if (serverName != null && serverName != item.currentServerName) {
            item.currentServerName = serverName
            item.sessionStartAt = null
        } else if (serverName != null) {
            item.currentServerName = serverName
        }

        if (item.resolvedId.isNullOrBlank()) {
            val key = item.key.trim()
            if (key.all { it.isDigit() }) item.resolvedId = key
        }

        item.lastSeenAt = now
        if (!wasOnline) {
            item.sessionStartAt = now
            item.joinHourCounts = incrementHourCount(item.joinHourCounts, now)
        }

        val apiSeconds = found.bestSeconds()
        if (item.sessionStartAt == null) {
            item.sessionStartAt = if (apiSeconds != null && apiSeconds > 0) {
                now - (apiSeconds * 1000L)
            } else {
                now
            }
        }

        val sessionSeconds = item.sessionStartAt?.let { ((now - it) / 1000L).coerceAtLeast(0) }
        val secondsToShow = sessionSeconds?.takeIf { it > 0 } ?: apiSeconds
        item.playTime = when {
            secondsToShow == null || secondsToShow <= 0 -> "??"
            else -> formatDuration(secondsToShow)
        }

        val derivedDetails = buildDerivedDetails(
            item = item,
            now = now,
            sessionSeconds = sessionSeconds
        )
        item.details = mergeDetails(derivedDetails + found.buildDetails())
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

        val derivedDetails = buildDerivedDetails(
            item = item,
            now = now,
            sessionSeconds = null
        )
        item.details = mergeDetails(derivedDetails)
    }

    private fun buildDerivedDetails(
        item: WatchedPlayer,
        now: Long,
        sessionSeconds: Long?
    ): List<String> {
        val details = mutableListOf<String>()

        if (item.online && !item.currentServerName.isNullOrBlank()) {
            details.add("Serwer: ${item.currentServerName}")
        }

        if (item.online && sessionSeconds != null) {
            details.add("Na serwerze: ${formatDuration(sessionSeconds)}")
        } else if (!item.online && item.lastSessionSeconds != null) {
            details.add("Ostatnia sesja: ${formatDuration(item.lastSessionSeconds!!)}")
        }

        val totalSeconds = (item.totalSessionSeconds ?: 0L) +
            if (item.online && sessionSeconds != null) sessionSeconds else 0L
        if (totalSeconds > 0) {
            details.add("Łącznie zmonitorowane: ${formatDuration(totalSeconds)}")
        }

        if (item.online && item.sessionStartAt != null) {
            details.add("Na serwerze od: ${formatTimeOfDay(item.sessionStartAt!!)}")
        } else if (!item.online && item.lastSeenAt != null) {
            details.add("Ostatnio widziany: ${formatRelativeTime(item.lastSeenAt!!, now)}")
        }

        val joinInfo = formatTypicalHour(item.joinHourCounts, "Najczęściej dołącza")
        if (joinInfo != null) details.add(joinInfo)

        val leaveInfo = formatTypicalHour(item.leaveHourCounts, "Najczęściej rozłącza")
        if (leaveInfo != null) details.add(leaveInfo)

        return details
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

    private fun formatTimeOfDay(millis: Long): String {
        val formatter = DateTimeFormatter.ofPattern("HH:mm")
        return Instant.ofEpochMilli(millis)
            .atZone(ZoneId.systemDefault())
            .format(formatter)
    }

    private fun formatRelativeTime(fromMillis: Long, nowMillis: Long): String {
        val diffSeconds = ((nowMillis - fromMillis) / 1000L).coerceAtLeast(0)
        val minutes = diffSeconds / 60
        val hours = minutes / 60
        val days = hours / 24

        return when {
            diffSeconds < 60 -> "przed chwilą"
            minutes < 60 -> "${minutes}m temu"
            hours < 24 -> "${hours}h temu"
            else -> "${days}d temu"
        }
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

    private fun formatTypicalHour(counts: List<Int>?, label: String): String? {
        val list = counts ?: return null
        if (list.isEmpty()) return null
        val max = list.maxOrNull() ?: return null
        if (max <= 0) return null
        val hour = list.indexOfFirst { it == max }.takeIf { it >= 0 } ?: return null
        val hourLabel = String.format("%02d:00", hour)
        return "$label: $hourLabel (${max}x)"
    }
}
