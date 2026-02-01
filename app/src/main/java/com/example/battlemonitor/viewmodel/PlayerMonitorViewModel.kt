package com.example.battlemonitor.viewmodel

import android.app.Application
import android.app.NotificationChannel
import android.app.NotificationManager
import android.content.pm.PackageManager
import android.os.Build
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.core.content.ContextCompat
import androidx.lifecycle.*
import com.example.battlemonitor.data.PlayerRepository
import com.example.battlemonitor.data.PlayerStorage
import com.example.battlemonitor.model.WatchedPlayer
import com.example.battlemonitor.ui.PlayerListItem
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter

class PlayerMonitorViewModel(app: Application) : AndroidViewModel(app) {

    companion object {
        private const val DEFAULT_GROUP = "DEFAULT"
        private const val STATUS_CHANNEL_ID = "player_status"
    }

    private val repository = PlayerRepository()
    private val storage = PlayerStorage(app.applicationContext)
    private val notificationManager = NotificationManagerCompat.from(app.applicationContext)

    private val watchedPlayers = mutableListOf<WatchedPlayer>()

    private val _items = MutableLiveData<List<PlayerListItem>>(emptyList())
    val items: LiveData<List<PlayerListItem>> = _items

    init {
        watchedPlayers.addAll(storage.load())
        ensureSortOrder()
        createNotificationChannel()
        publish()
        startMonitoring()
    }

    fun addPlayer(key: String, group: String) {
        val trimmed = key.trim()
        if (trimmed.isBlank()) return

        val resolvedGroup = resolveGroupName(group)
        watchedPlayers.add(
            WatchedPlayer(
                key = trimmed,
                group = resolvedGroup,
                sortOrder = nextSortOrder(resolvedGroup)
            )
        )
        storage.save(watchedPlayers)
        publish()
    }

    fun removePlayer(player: WatchedPlayer) {
        watchedPlayers.remove(player)
        storage.save(watchedPlayers)
        publish()
    }

    fun movePlayerToGroup(player: WatchedPlayer, newGroup: String) {
        val resolvedGroup = resolveGroupName(newGroup)
        if (player.group != resolvedGroup) {
            player.group = resolvedGroup
            player.sortOrder = nextSortOrder(resolvedGroup)
            storage.save(watchedPlayers)
            publish()
        }
    }

    fun renameGroup(oldGroup: String, newGroup: String) {
        val normalizedOld = normalizeGroupName(oldGroup)
        val normalizedNew = normalizeGroupName(newGroup)
        val oldKey = groupKey(normalizedOld)
        val newKey = groupKey(normalizedNew)

        if (oldKey == newKey && normalizedOld == normalizedNew) {
            return
        }

        val keysToUpdate = if (oldKey == newKey) {
            setOf(oldKey)
        } else {
            setOf(oldKey, newKey)
        }

        var changed = false
        watchedPlayers.forEach { player ->
            if (groupKey(player.group) in keysToUpdate && player.group != normalizedNew) {
                player.group = normalizedNew
                changed = true
            }
        }

        if (changed) {
            storage.save(watchedPlayers)
            publish()
        }
    }

    fun toggleNotifications(player: WatchedPlayer) {
        val enabledNow = player.notificationsEnabled != false
        player.notificationsEnabled = !enabledNow
        storage.save(watchedPlayers)
        publish()
    }

    fun reorderPlayers(items: List<PlayerListItem>) {
        var currentGroup = DEFAULT_GROUP
        val groupOrder = mutableMapOf<String, Int>()
        var changed = false

        items.forEach { item ->
            when (item) {
                is PlayerListItem.Header -> {
                    currentGroup = resolveGroupName(item.title)
                }

                is PlayerListItem.PlayerRow -> {
                    val key = groupKey(currentGroup)
                    val nextIndex = groupOrder[key] ?: 0
                    if (item.player.group != currentGroup) {
                        item.player.group = currentGroup
                        changed = true
                    }
                    if (item.player.sortOrder != nextIndex) {
                        item.player.sortOrder = nextIndex
                        changed = true
                    }
                    groupOrder[key] = nextIndex + 1
                }
            }
        }

        if (changed) {
            storage.save(watchedPlayers)
            publish()
        }
    }

    fun getGroups(): List<String> {
        val displayNames = buildGroupDisplayNames(watchedPlayers)
        val sortedKeys = sortGroupKeys(displayNames)
        return sortedKeys.map { key -> displayNames[key] ?: DEFAULT_GROUP }
    }

    private fun publish() {
        _items.value = buildListItems(watchedPlayers)
    }

    private fun buildListItems(players: List<WatchedPlayer>): List<PlayerListItem> {
        val displayNames = buildGroupDisplayNames(players)
        val grouped = players.groupBy { groupKey(it.group) }
        val sortedKeys = sortGroupKeys(displayNames).filter { grouped.containsKey(it) }

        val out = mutableListOf<PlayerListItem>()

        sortedKeys.forEach { key ->
            val list = grouped[key] ?: emptyList()
            val displayName = displayNames[key] ?: DEFAULT_GROUP
            out.add(PlayerListItem.Header(displayName))

            val sortedPlayers = list.sortedWith(
                compareBy<WatchedPlayer> { it.sortOrder }
                    .thenBy { (it.resolvedName.ifBlank { it.key }).lowercase() }
            )

            sortedPlayers.forEach { out.add(PlayerListItem.PlayerRow(it)) }
        }

        return out
    }

    private fun normalizeGroupName(name: String?): String {
        val trimmed = name?.trim().orEmpty()
        return if (trimmed.isBlank()) DEFAULT_GROUP else trimmed
    }

    private fun groupKey(name: String?): String = normalizeGroupName(name).lowercase()

    private fun resolveGroupName(input: String): String {
        val normalized = normalizeGroupName(input)
        val key = groupKey(normalized)
        val existing = watchedPlayers.firstOrNull { groupKey(it.group) == key }?.group
        return normalizeGroupName(existing ?: normalized)
    }

    private fun nextSortOrder(group: String): Int {
        val key = groupKey(group)
        val max = watchedPlayers
            .filter { groupKey(it.group) == key }
            .maxOfOrNull { it.sortOrder } ?: -1
        return max + 1
    }

    private fun ensureSortOrder() {
        val grouped = watchedPlayers.groupBy { groupKey(it.group) }
        var changed = false
        grouped.forEach { (_, players) ->
            val hasDuplicates = players.groupBy { it.sortOrder }.any { it.value.size > 1 }
            if (hasDuplicates) {
                players.sortedBy { (it.resolvedName.ifBlank { it.key }).lowercase() }
                    .forEachIndexed { index, player ->
                        if (player.sortOrder != index) {
                            player.sortOrder = index
                            changed = true
                        }
                    }
            }
        }
        if (changed) {
            storage.save(watchedPlayers)
        }
    }

    private fun buildGroupDisplayNames(players: List<WatchedPlayer>): Map<String, String> {
        val displayNames = linkedMapOf(groupKey(DEFAULT_GROUP) to DEFAULT_GROUP)
        players.forEach { player ->
            val normalized = normalizeGroupName(player.group)
            displayNames[groupKey(normalized)] = normalized
        }
        return displayNames
    }

    private fun sortGroupKeys(displayNames: Map<String, String>): List<String> {
        val defaultKey = groupKey(DEFAULT_GROUP)
        return displayNames.keys.sortedWith(
            compareBy<String> { it != defaultKey }
                .thenBy { displayNames[it]?.lowercase() ?: it }
        )
    }

    private fun startMonitoring() {
        viewModelScope.launch {
            while (true) {
                scanServer()
                delay(3000)
            }
        }
    }

    private suspend fun scanServer() {
        val onlineMap = repository.fetchOnlinePlayers()
        val now = System.currentTimeMillis()

        var changed = false

        watchedPlayers.forEach { item ->

            // Dopasowanie:
            // 1) jeśli mamy resolvedId i onlineMap ma wpis pod ID
            // 2) jeśli nie, próbujemy po key wpisanym przez usera (nick/ID)
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

            if (found != null) {
                item.online = true

                val newName = found.name ?: item.resolvedName.ifBlank { item.key }
                item.resolvedName = newName

                // repo zwraca PlayerAttributes, nie ma tam "id" (u Ciebie był błąd unresolved reference id)
                // resolvedId ustawiamy TYLKO jeśli user wpisał ID (cyfry), wtedy traktujemy key jako ID.
                if (item.resolvedId.isNullOrBlank()) {
                    val k = item.key.trim()
                    if (k.all { it.isDigit() }) item.resolvedId = k
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
                item.details = (derivedDetails + found.buildDetails()).filter { it.isNotBlank() }
            } else {
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

                val derivedDetails = buildDerivedDetails(
                    item = item,
                    now = now,
                    sessionSeconds = null
                )
                item.details = derivedDetails
            }

            if (wasOnline != item.online ||
                oldName != item.resolvedName ||
                oldTime != item.playTime ||
                oldDetails != item.details.orEmpty()
            ) {
                changed = true
            }

            if (wasOnline != item.online && item.notificationsEnabled != false) {
                if (canPostNotifications()) {
                    sendStatusNotification(item, item.online)
                }
            }
        }

        if (changed) {
            storage.save(watchedPlayers)
            publish()
        }
    }

    private fun buildDerivedDetails(
        item: WatchedPlayer,
        now: Long,
        sessionSeconds: Long?
    ): List<String> {
        val details = mutableListOf<String>()

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
            details.add("Online od: ${formatTimeOfDay(item.sessionStartAt!!)}")
        } else if (!item.online && item.lastSeenAt != null) {
            details.add("Ostatnio widziany: ${formatRelativeTime(item.lastSeenAt!!, now)}")
        }

        val joinInfo = formatTypicalHour(item.joinHourCounts, "Najczęściej dołącza")
        if (joinInfo != null) details.add(joinInfo)

        val leaveInfo = formatTypicalHour(item.leaveHourCounts, "Najczęściej rozłącza")
        if (leaveInfo != null) details.add(leaveInfo)

        return details
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

    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                STATUS_CHANNEL_ID,
                "Zmiany online/offline",
                NotificationManager.IMPORTANCE_DEFAULT
            ).apply {
                description = "Powiadomienia o zmianach statusu graczy"
            }
            val manager =
                getApplication<Application>().getSystemService(NotificationManager::class.java)
            manager.createNotificationChannel(channel)
        }
    }

    private fun sendStatusNotification(player: WatchedPlayer, isOnline: Boolean) {
        val displayName = player.resolvedName.ifBlank { player.key }
        val title = if (isOnline) "Gracz online" else "Gracz offline"
        val message = if (isOnline) {
            "$displayName jest online"
        } else {
            "$displayName jest offline"
        }

        val notification = NotificationCompat.Builder(getApplication(), STATUS_CHANNEL_ID)
            .setSmallIcon(com.example.battlemonitor.R.mipmap.ic_launcher)
            .setContentTitle(title)
            .setContentText(message)
            .setAutoCancel(true)
            .build()

        notificationManager.notify(displayName.hashCode(), notification)
    }

    private fun canPostNotifications(): Boolean {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU) {
            return true
        }
        return ContextCompat.checkSelfPermission(
            getApplication(),
            android.Manifest.permission.POST_NOTIFICATIONS
        ) == PackageManager.PERMISSION_GRANTED
    }
}
