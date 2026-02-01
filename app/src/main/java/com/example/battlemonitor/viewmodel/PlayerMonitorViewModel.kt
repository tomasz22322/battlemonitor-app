package com.example.battlemonitor.viewmodel

import android.app.Application
import androidx.lifecycle.*
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
    }

    private val repository = PlayerRepository()
    private val engine = PlayerMonitorEngine(repository)
    private val storage = PlayerStorage(app.applicationContext)

    private val watchedPlayers = mutableListOf<WatchedPlayer>()

    private val _items = MutableLiveData<List<PlayerListItem>>(emptyList())
    val items: LiveData<List<PlayerListItem>> = _items

    init {
        watchedPlayers.addAll(storage.load())
        ensureSortOrder()
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
        val result = engine.scan(watchedPlayers)
        if (result.changed) {
            storage.save(watchedPlayers)
            publish()
        }
    }
}
