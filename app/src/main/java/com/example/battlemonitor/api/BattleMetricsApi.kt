package com.example.battlemonitor.api

import com.example.battlemonitor.model.BattleMetricsResponse
import retrofit2.Response
import retrofit2.http.GET
import retrofit2.http.Header
import retrofit2.http.Query

interface BattleMetricsApi {

    @GET("servers/14154299")
    suspend fun getServer(
        @Query("include") include: String = "player",
        @Header("Authorization") auth: String
    ): Response<BattleMetricsResponse>
}
