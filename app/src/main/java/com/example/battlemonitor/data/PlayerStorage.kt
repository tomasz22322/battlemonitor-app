package com.example.battlemonitor.data

import android.content.Context
import com.example.battlemonitor.model.WatchedPlayer
import com.google.gson.Gson
import com.google.gson.reflect.TypeToken

class PlayerStorage(context: Context) {

    private val prefs = context.getSharedPreferences("battle_monitor_prefs", Context.MODE_PRIVATE)
    private val gson = Gson()

    fun save(players: List<WatchedPlayer>) {
        val json = gson.toJson(players)
        prefs.edit().putString("players_json", json).apply()
    }

    fun load(): List<WatchedPlayer> {
        val json = prefs.getString("players_json", null) ?: return emptyList()
        return try {
            val type = object : TypeToken<List<WatchedPlayer>>() {}.type
            val loaded = gson.fromJson<List<WatchedPlayer>>(json, type) ?: emptyList()
            loaded.map { player ->
                if (player.details == null) {
                    player.copy(details = emptyList())
                } else {
                    player
                }
            }
        } catch (_: Exception) {
            emptyList()
        }
    }
}
