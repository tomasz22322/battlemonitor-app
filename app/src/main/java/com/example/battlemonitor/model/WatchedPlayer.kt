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
    var details: List<String> = emptyList(),

    // ✅ ręczne grupowanie
    var group: String = "DEFAULT"
)
