package io.linkmate.data.remote.homeassistant

import okhttp3.RequestBody // 导入 RequestBody
import retrofit2.Response
import retrofit2.http.GET
import retrofit2.http.Header
import retrofit2.http.POST
import retrofit2.http.Path
import retrofit2.http.Body

interface HomeAssistantService {

    @GET("api/states")
    suspend fun getAllEntityStates(
        @Header("Authorization") bearerToken: String
    ): List<HaEntityState>
    
    @GET("api/states/{entity_id}")
    suspend fun getEntityState(
        @Header("Authorization") bearerToken: String,
        @Path("entity_id") entityId: String
    ): HaEntityState

    @POST("api/services/{domain}/{service}")
    suspend fun callService(
        @Header("Authorization") bearerToken: String,
        @Path("domain") domain: String,
        @Path("service") service: String,
        @Body requestBody: RequestBody // 更改此处：现在接�?RequestBody
    ): Response<List<HaEntityState>>
    
    @POST("api/states/{entity_id}")
    suspend fun updateEntityState(
        @Header("Authorization") bearerToken: String,
        @Path("entity_id") entityId: String,
        @Body requestBody: RequestBody
    ): Response<HaEntityState>
}