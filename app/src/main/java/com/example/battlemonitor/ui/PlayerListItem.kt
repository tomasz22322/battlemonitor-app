package com.example.battlemonitor.ui

import com.example.battlemonitor.model.WatchedPlayer

sealed class PlayerListItem {
    data class Header(val title: String, val notificationsEnabled: Boolean) : PlayerListItem()
    data class PlayerRow(val player: WatchedPlayer) : PlayerListItem()
}
