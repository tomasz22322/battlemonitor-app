package com.example.battlemonitor.model

data class WatchedPlayer(
    val key: String,

    var resolvedName: String = key,
    var originalName: String? = null,
    var resolvedId: String? = null,

    var online: Boolean = false,

    // tekst do pokazania w UI (np. "2h 10m")
    var playTime: String = "",

    // dodatkowe informacje z API
    var details: List<String>? = emptyList(),

    // metryki monitoringu sesji (lokalne)
    var sessionStartAt: Long? = null,
    var lastSeenAt: Long? = null,
    var lastOfflineAt: Long? = null,
    var lastSessionSeconds: Long? = null,
    var totalSessionSeconds: Long? = null,
    var joinHourCounts: List<Int>? = null,
    var leaveHourCounts: List<Int>? = null,

    // czy wysyłać powiadomienia o zmianie online/offline
    var notificationsEnabled: Boolean? = null,

    // ✅ ręczne grupowanie
    var group: String = "DEFAULT",

    // ręczna kolejność w obrębie grupy
    var sortOrder: Int = 0
)
