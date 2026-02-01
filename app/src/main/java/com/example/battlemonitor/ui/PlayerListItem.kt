package com.example.battlemonitor.ui

import com.example.battlemonitor.model.WatchedPlayer

sealed class PlayerListItem {
    data class Header(
        val title: String,
        val groupName: String,
        val groupKey: String,
        val notificationsEnabled: Boolean,
        val isUngrouped: Boolean
    ) : PlayerListItem()
    data class PlayerRow(val player: WatchedPlayer) : PlayerListItem()
}
