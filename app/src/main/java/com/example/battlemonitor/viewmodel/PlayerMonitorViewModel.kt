package com.example.battlemonitor.viewmodel

import android.Manifest
import android.app.Application
import android.app.PendingIntent
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.core.content.ContextCompat
import androidx.lifecycle.*
import com.example.battlemonitor.MainActivity
import com.example.battlemonitor.R
import com.example.battlemonitor.data.PlayerRepository
import com.example.battlemonitor.data.PlayerStorage
import com.example.battlemonitor.model.WatchedPlayer
import com.example.battlemonitor.monitor.PlayerMonitorEngine
import com.example.battlemonitor.ui.PlayerListItem
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

class PlayerMonitorViewModel(app: Application) : AndroidViewModel(app) {

    companion object {
        private const val DEFAULT_GROUP = "DEFAULT"
        private const val NO_GROUP = ""
        private const val STATUS_CHANNEL_ID = "player_status"
    }

    private val repository = PlayerRepository()
    private val engine = PlayerMonitorEngine(repository)
    private val storage = PlayerStorage(app.applicationContext)
    private val notificationManager = NotificationManagerCompat.from(app.applicationContext)
    private val appContext = app.applicationContext

    private val watchedPlayers = mutableListOf<WatchedPlayer>()
    private val groupNotifications = storage.loadGroupNotifications().toMutableMap()
    private val groupDisplayNames = storage.loadGroupDisplayNames().toMutableMap()
    private val groupOrder = storage.loadGroupOrder().toMutableList()
    private val groupOfflineState = mutableMapOf<String, Boolean>()
    private val groupMembershipSignatures = mutableMapOf<String, String>()

    private val _items = MutableLiveData<List<PlayerListItem>>(emptyList())
    val items: LiveData<List<PlayerListItem>> = _items

    init {
        watchedPlayers.addAll(storage.load())
        var requiresSave = false
        watchedPlayers.forEach { player ->
            if (player.originalName.isNullOrBlank()) {
                player.originalName = player.key
                requiresSave = true
            }
        }
        if (requiresSave) {
            storage.save(watchedPlayers)
        }
        ensureSortOrder()
        syncGroupDisplayNames()
        publish()
        startMonitoring()
    }

    fun addPlayer(key: String, group: String) {
        val trimmed = key.trim()
        if (trimmed.isBlank()) return

        val resolvedGroup = resolveGroupName(group)
        rememberGroupName(resolvedGroup)
        watchedPlayers.add(
            WatchedPlayer(
                key = trimmed,
                originalName = trimmed,
                group = resolvedGroup,
                sortOrder = nextSortOrder(resolvedGroup)
            )
        )
        storage.save(watchedPlayers)
        publish()
        viewModelScope.launch {
            scanServer()
        }
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
            rememberGroupName(resolvedGroup)
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

        if (oldKey != newKey && newKey.isNotBlank()) {
            val existingSetting = groupNotifications[oldKey]
            if (existingSetting != null && !groupNotifications.containsKey(newKey)) {
                groupNotifications[newKey] = existingSetting
            }
            if (groupNotifications.containsKey(oldKey)) {
                groupNotifications.remove(oldKey)
            }
            storage.saveGroupNotifications(groupNotifications)
        } else if (oldKey != newKey && newKey.isBlank()) {
            if (groupNotifications.containsKey(oldKey)) {
                groupNotifications.remove(oldKey)
                storage.saveGroupNotifications(groupNotifications)
            }
        }

        if (normalizedOld.isNotBlank()) {
            groupDisplayNames.remove(oldKey)
        }
        if (normalizedNew.isNotBlank()) {
            groupDisplayNames[newKey] = normalizedNew
        }
        storage.saveGroupDisplayNames(groupDisplayNames)

        if (oldKey.isNotBlank()) {
            val index = groupOrder.indexOf(oldKey)
            if (index != -1) {
                if (newKey.isBlank()) {
                    groupOrder.removeAt(index)
                } else {
                    val existingIndex = groupOrder.indexOf(newKey)
                    if (existingIndex != -1 && existingIndex != index) {
                        groupOrder.removeAt(existingIndex)
                    }
                    groupOrder[index] = newKey
                }
                storage.saveGroupOrder(groupOrder)
            }
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

    fun deleteGroup(group: String) {
        val normalized = normalizeGroupName(group)
        val key = groupKey(normalized)
        if (key.isBlank()) return

        val iterator = watchedPlayers.iterator()
        var changed = false
        while (iterator.hasNext()) {
            val player = iterator.next()
            if (groupKey(player.group) == key) {
                iterator.remove()
                changed = true
            }
        }

        groupNotifications.remove(key)
        groupDisplayNames.remove(key)
        if (groupOrder.remove(key)) {
            storage.saveGroupOrder(groupOrder)
        }
        storage.saveGroupNotifications(groupNotifications)
        storage.saveGroupDisplayNames(groupDisplayNames)

        if (changed) {
            storage.save(watchedPlayers)
        }
        publish()
    }

    fun toggleGroupNotifications(group: String) {
        val key = groupKey(group)
        val enabledNow = groupNotifications[key] ?: true
        groupNotifications[key] = !enabledNow
        storage.saveGroupNotifications(groupNotifications)
        publish()
    }

    fun toggleNotifications(player: WatchedPlayer) {
        val enabledNow = player.notificationsEnabled != false
        player.notificationsEnabled = !enabledNow
        storage.save(watchedPlayers)
        publish()
    }

    fun reorderPlayers(items: List<PlayerListItem>) {
        var currentGroup = NO_GROUP
        val groupIndex = mutableMapOf<String, Int>()
        var changed = false
        val newGroupOrder = mutableListOf<String>()

        items.forEach { item ->
            when (item) {
                is PlayerListItem.Header -> {
                    currentGroup = resolveGroupName(item.groupName)
                    if (item.groupKey.isNotBlank()) {
                        newGroupOrder.add(item.groupKey)
                    }
                }

                is PlayerListItem.PlayerRow -> {
                    val key = groupKey(currentGroup)
                    val nextIndex = groupIndex[key] ?: 0
                    if (item.player.group != currentGroup) {
                        item.player.group = currentGroup
                        changed = true
                    }
                    if (item.player.sortOrder != nextIndex) {
                        item.player.sortOrder = nextIndex
                        changed = true
                    }
                    groupIndex[key] = nextIndex + 1
                }
            }
        }

        var shouldPublish = changed
        if (newGroupOrder.isNotEmpty() && newGroupOrder != this.groupOrder) {
            this.groupOrder.clear()
            this.groupOrder.addAll(newGroupOrder.distinct())
            storage.saveGroupOrder(this.groupOrder)
            shouldPublish = true
        }

        if (changed) {
            storage.save(watchedPlayers)
        }
        if (shouldPublish) {
            publish()
        }
    }

    fun getGroups(): List<String> {
        val displayNames = buildGroupDisplayNames(watchedPlayers)
        val sortedKeys = sortGroupKeys(displayNames)
        return sortedKeys.map { key -> displayNames[key] ?: DEFAULT_GROUP }
    }

    private fun publish() {
        syncGroupNotifications()
        _items.value = buildListItems(watchedPlayers)
    }

    private fun buildListItems(players: List<WatchedPlayer>): List<PlayerListItem> {
        val displayNames = buildGroupDisplayNames(players)
        val grouped = players.groupBy { groupKey(it.group) }
        val sortedKeys = sortGroupKeys(displayNames)

        val out = mutableListOf<PlayerListItem>()

        val ungroupedPlayers = grouped[groupKey(NO_GROUP)].orEmpty()
        val sortedUngrouped = ungroupedPlayers.sortedWith(
            compareBy<WatchedPlayer> { it.sortOrder }
                .thenBy { (it.resolvedName.ifBlank { it.key }).lowercase() }
        )
        sortedUngrouped.forEach { player ->
            out.add(
                PlayerListItem.Header(
                    title = "",
                    groupName = NO_GROUP,
                    groupKey = groupKey(NO_GROUP),
                    notificationsEnabled = true,
                    isUngrouped = true
                )
            )
            out.add(PlayerListItem.PlayerRow(player))
        }

        sortedKeys.forEach { key ->
            val list = grouped[key] ?: emptyList()
            val displayName = displayNames[key] ?: DEFAULT_GROUP
            val notificationsEnabled = groupNotifications[key] ?: true
            out.add(
                PlayerListItem.Header(
                    title = displayName,
                    groupName = displayName,
                    groupKey = key,
                    notificationsEnabled = notificationsEnabled,
                    isUngrouped = false
                )
            )

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
        return if (trimmed.isBlank()) NO_GROUP else trimmed
    }

    private fun groupKey(name: String?): String = normalizeGroupName(name).lowercase()

    private fun resolveGroupName(input: String): String {
        val normalized = normalizeGroupName(input)
        if (normalized.isBlank()) {
            return NO_GROUP
        }
        val key = groupKey(normalized)
        val existing = groupDisplayNames[key]
            ?: watchedPlayers.firstOrNull { groupKey(it.group) == key }?.group
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
        val displayNames = linkedMapOf<String, String>()
        displayNames.putAll(groupDisplayNames)
        players.forEach { player ->
            val normalized = normalizeGroupName(player.group)
            if (normalized.isNotBlank()) {
                displayNames[groupKey(normalized)] = normalized
            }
        }
        return displayNames
    }

    private fun sortGroupKeys(displayNames: Map<String, String>): List<String> {
        val activeKeys = displayNames.keys.filter { it.isNotBlank() }
        val ordered = groupOrder.filter { it in activeKeys }
        val remaining = activeKeys
            .filterNot { it in ordered }
            .sortedWith(compareBy<String> { displayNames[it]?.lowercase() ?: it })
        val result = ordered + remaining
        if (result != groupOrder) {
            groupOrder.clear()
            groupOrder.addAll(result)
            storage.saveGroupOrder(groupOrder)
        }
        return result
    }

    private fun syncGroupNotifications() {
        val keys = watchedPlayers.map { groupKey(it.group) }.filter { it.isNotBlank() }.toMutableSet()
        keys.addAll(groupDisplayNames.keys)
        var changed = false
        keys.forEach { key ->
            if (!groupNotifications.containsKey(key)) {
                groupNotifications[key] = true
                changed = true
            }
        }
        val removed = groupNotifications.keys - keys
        if (removed.isNotEmpty()) {
            removed.forEach { groupNotifications.remove(it) }
            changed = true
        }
        if (changed) {
            storage.saveGroupNotifications(groupNotifications)
        }
    }

    private fun syncGroupDisplayNames() {
        var changed = false
        watchedPlayers.forEach { player ->
            val normalized = normalizeGroupName(player.group)
            if (normalized.isNotBlank()) {
                val key = groupKey(normalized)
                if (!groupDisplayNames.containsKey(key)) {
                    groupDisplayNames[key] = normalized
                    changed = true
                }
            }
        }
        if (changed) {
            storage.saveGroupDisplayNames(groupDisplayNames)
        }
    }

    private fun rememberGroupName(group: String) {
        val normalized = normalizeGroupName(group)
        if (normalized.isBlank()) return
        val key = groupKey(normalized)
        if (groupDisplayNames[key] != normalized) {
            groupDisplayNames[key] = normalized
            storage.saveGroupDisplayNames(groupDisplayNames)
        }
    }

    private fun startMonitoring() {
        viewModelScope.launch {
            while (true) {
                scanServer()
                delay(1000)
            }
        }
    }

    private suspend fun scanServer() {
        val result = engine.scan(watchedPlayers)
        sendStatusNotifications(result.statusChanges)
        updateGroupOfflineNotifications(watchedPlayers)
        if (result.changed) {
            storage.save(watchedPlayers)
            publish()
        }
    }

    private fun sendStatusNotifications(statusChanges: List<Pair<WatchedPlayer, Boolean>>) {
        if (statusChanges.isEmpty()) return
        if (!canPostNotifications()) return
        statusChanges
            .filter { (player, _) ->
                val key = groupKey(player.group)
                val groupEnabled = groupNotifications[key] ?: true
                groupEnabled && player.notificationsEnabled != false
            }
            .forEach { (player, isOnline) ->
                sendStatusNotification(player, isOnline)
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

        val notification = NotificationCompat.Builder(appContext, STATUS_CHANNEL_ID)
            .setSmallIcon(R.mipmap.ic_launcher)
            .setContentTitle(title)
            .setContentText(message)
            .setContentIntent(buildContentIntent())
            .setAutoCancel(true)
            .setPriority(NotificationCompat.PRIORITY_HIGH)
            .build()

        notificationManager.notify(displayName.hashCode(), notification)
    }

    private fun updateGroupOfflineNotifications(players: List<WatchedPlayer>) {
        val grouped = players.groupBy { groupKey(it.group) }
        val activeKeys = grouped.keys.filter { it.isNotBlank() }.toSet()
        groupOfflineState.keys.retainAll(activeKeys)
        groupMembershipSignatures.keys.retainAll(activeKeys)

        grouped.forEach { (key, members) ->
            if (key.isBlank()) {
                return@forEach
            }
            val signature = buildGroupSignature(members)
            val previousSignature = groupMembershipSignatures[key]
            val membershipChanged = previousSignature != null && previousSignature != signature
            val isFirstSeen = previousSignature == null
            groupMembershipSignatures[key] = signature
            val enabled = groupNotifications[key] ?: true
            if (!enabled) {
                groupOfflineState[key] = false
                return@forEach
            }
            val allOffline = members.all { !it.online }
            val wasOffline = groupOfflineState[key] ?: false
            if ((membershipChanged || isFirstSeen)) {
                groupOfflineState[key] = allOffline
                return@forEach
            }
            if (!wasOffline && allOffline && canPostNotifications()) {
                val groupName = members.firstOrNull()?.group ?: DEFAULT_GROUP
                sendGroupOfflineNotification(groupName)
            }
            groupOfflineState[key] = allOffline
        }
    }

    private fun sendGroupOfflineNotification(groupName: String) {
        val title = "Grupa offline"
        val message = "Wszyscy gracze w grupie $groupName są offline"
        val notification = NotificationCompat.Builder(appContext, STATUS_CHANNEL_ID)
            .setSmallIcon(R.mipmap.ic_launcher)
            .setContentTitle(title)
            .setContentText(message)
            .setContentIntent(buildContentIntent())
            .setAutoCancel(true)
            .build()
        notificationManager.notify("group_$groupName".hashCode(), notification)
    }

    private fun canPostNotifications(): Boolean {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU) {
            return true
        }
        return ContextCompat.checkSelfPermission(
            appContext,
            Manifest.permission.POST_NOTIFICATIONS
        ) == PackageManager.PERMISSION_GRANTED
    }

    private fun buildContentIntent(): PendingIntent {
        val intent = Intent(appContext, MainActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP
        }
        val flags = PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        return PendingIntent.getActivity(appContext, 0, intent, flags)
    }

    private fun buildGroupSignature(members: List<WatchedPlayer>): String {
        return members
            .map { it.resolvedId?.trim().orEmpty().ifBlank { it.key } }
            .map { it.trim().lowercase() }
            .sorted()
            .joinToString(separator = "|")
    }
}
