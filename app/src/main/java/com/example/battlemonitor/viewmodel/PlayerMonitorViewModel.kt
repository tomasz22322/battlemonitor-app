package com.example.battlemonitor.viewmodel

import android.app.Application
import androidx.lifecycle.*
import com.example.battlemonitor.data.PlayerRepository
import com.example.battlemonitor.data.PlayerStorage
import com.example.battlemonitor.model.WatchedPlayer
import com.example.battlemonitor.ui.PlayerListItem
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

class PlayerMonitorViewModel(app: Application) : AndroidViewModel(app) {

    private val repository = PlayerRepository()
    private val storage = PlayerStorage(app.applicationContext)

    private val watchedPlayers = mutableListOf<WatchedPlayer>()

    private val _items = MutableLiveData<List<PlayerListItem>>(emptyList())
    val items: LiveData<List<PlayerListItem>> = _items

    init {
        watchedPlayers.addAll(storage.load())
        publish()
        startMonitoring()
    }

    fun addPlayer(key: String, group: String) {
        val trimmed = key.trim()
        if (trimmed.isBlank()) return

        watchedPlayers.add(
            WatchedPlayer(
                key = trimmed,
                group = group.trim().ifBlank { "DEFAULT" }
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
        player.group = newGroup.trim().ifBlank { "DEFAULT" }
        storage.save(watchedPlayers)
        publish()
    }

    fun getGroups(): List<String> {
        val fromPlayers = watchedPlayers
            .map { it.group.ifBlank { "DEFAULT" } }
            .distinct()
            .sortedBy { it.lowercase() }

        return (listOf("DEFAULT") + fromPlayers).distinct()
    }

    private fun publish() {
        _items.value = buildListItems(watchedPlayers)
    }

    private fun buildListItems(players: List<WatchedPlayer>): List<PlayerListItem> {
        val grouped = players
            .groupBy { it.group.ifBlank { "DEFAULT" } }
            .toSortedMap(compareBy { it.lowercase() })

        val out = mutableListOf<PlayerListItem>()

        grouped.forEach { (group, list) ->
            out.add(PlayerListItem.Header(group))

            val sortedPlayers = list.sortedWith(
                compareByDescending<WatchedPlayer> { it.online }
                    .thenBy { (it.resolvedName.ifBlank { it.key }).lowercase() }
            )

            sortedPlayers.forEach { out.add(PlayerListItem.PlayerRow(it)) }
        }

        return out
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

            if (found != null) {
                item.online = true

                if (!wasOnline) {
                    item.sessionStartMs = now
                }

                val newName = found.name ?: item.resolvedName.ifBlank { item.key }
                item.resolvedName = newName

                // repo zwraca PlayerAttributes, nie ma tam "id" (u Ciebie był błąd unresolved reference id)
                // resolvedId ustawiamy TYLKO jeśli user wpisał ID (cyfry), wtedy traktujemy key jako ID.
                if (item.resolvedId.isNullOrBlank()) {
                    val k = item.key.trim()
                    if (k.all { it.isDigit() }) item.resolvedId = k
                }

                val apiSeconds = found.bestSeconds()
                val secondsToShow = when {
                    apiSeconds != null && apiSeconds > 0 -> apiSeconds
                    item.sessionStartMs != null -> ((now - item.sessionStartMs!!) / 1000L).toInt()
                    else -> null
                }

                item.playTime = when {
                    secondsToShow == null || secondsToShow <= 0 -> "??"
                    else -> formatTime(secondsToShow)
                }
            } else {
                item.online = false
                item.playTime = ""
                item.sessionStartMs = null
            }

            if (wasOnline != item.online || oldName != item.resolvedName || oldTime != item.playTime) {
                changed = true
            }
        }

        if (changed) {
            storage.save(watchedPlayers)
            publish()
        }
    }

    private fun formatTime(seconds: Int): String {
        val hours = seconds / 3600
        val minutes = (seconds % 3600) / 60
        return "${hours}h ${minutes}m"
    }
}
