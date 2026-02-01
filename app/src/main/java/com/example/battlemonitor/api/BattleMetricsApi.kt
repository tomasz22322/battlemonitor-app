package com.example.battlemonitor.api

import com.example.battlemonitor.model.BattleMetricsResponse
import com.example.battlemonitor.model.PlayerSessionResponse
import retrofit2.Response
import retrofit2.http.GET
import retrofit2.http.Header
import retrofit2.http.Path
import retrofit2.http.Query

interface BattleMetricsApi {

    @GET("servers/14154299")
    suspend fun getServer(
        @Query("include") include: String = "player",
        @Header("Authorization") auth: String
    ): Response<BattleMetricsResponse>

    @GET("players/{playerId}/sessions")
    suspend fun getPlayerSessions(
        @Path("playerId") playerId: String,
        @Query("filter[server]") serverId: String,
        @Query("sort") sort: String = "-start",
        @Query("page[size]") pageSize: Int = 1,
        @Header("Authorization") auth: String
    ): Response<PlayerSessionResponse>
}
