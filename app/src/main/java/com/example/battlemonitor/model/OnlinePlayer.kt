package com.example.battlemonitor.net

data class OnlinePlayer(
    val id: String? = null,
    val name: String? = null,

    /**
     * Jeżeli API daje czas "od dołączenia" albo "czas gry w sesji" (sekundy),
     * wpisz to tu.
     */
    val sessionSeconds: Int? = null
) {
    fun bestSeconds(): Int? = sessionSeconds
}
