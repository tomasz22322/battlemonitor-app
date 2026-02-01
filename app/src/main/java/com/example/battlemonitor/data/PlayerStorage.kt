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

    fun saveGroupNotifications(settings: Map<String, Boolean>) {
        val json = gson.toJson(settings)
        prefs.edit().putString("group_notifications_json", json).apply()
    }

    fun saveGroupDisplayNames(names: Map<String, String>) {
        val json = gson.toJson(names)
        prefs.edit().putString("group_display_names_json", json).apply()
    }

    fun load(): List<WatchedPlayer> {
        val json = prefs.getString("players_json", null) ?: return emptyList()
        return try {
            val type = object : TypeToken<List<WatchedPlayer>>() {}.type
            val loaded = gson.fromJson<List<WatchedPlayer>>(json, type) ?: emptyList()
            loaded.map { player ->
                var updated = player
                if (updated.details == null) {
                    updated = updated.copy(details = emptyList())
                }
                if (updated.joinHourCounts.isNullOrEmpty() || updated.joinHourCounts?.size != 24) {
                    updated = updated.copy(joinHourCounts = List(24) { 0 })
                }
                if (updated.leaveHourCounts.isNullOrEmpty() || updated.leaveHourCounts?.size != 24) {
                    updated = updated.copy(leaveHourCounts = List(24) { 0 })
                }
                if (updated.notificationsEnabled == null) {
                    updated = updated.copy(notificationsEnabled = true)
                }
                updated
            }
        } catch (_: Exception) {
            emptyList()
        }
    }

    fun loadGroupNotifications(): MutableMap<String, Boolean> {
        val json = prefs.getString("group_notifications_json", null) ?: return mutableMapOf()
        return try {
            val type = object : TypeToken<Map<String, Boolean>>() {}.type
            val loaded = gson.fromJson<Map<String, Boolean>>(json, type).orEmpty()
            loaded.toMutableMap()
        } catch (_: Exception) {
            mutableMapOf()
        }
    }

    fun loadGroupDisplayNames(): MutableMap<String, String> {
        val json = prefs.getString("group_display_names_json", null) ?: return mutableMapOf()
        return try {
            val type = object : TypeToken<Map<String, String>>() {}.type
            val loaded = gson.fromJson<Map<String, String>>(json, type).orEmpty()
            loaded.toMutableMap()
        } catch (_: Exception) {
            mutableMapOf()
        }
    }
}
