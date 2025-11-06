package io.linkmate.data.remote.hefeng

import retrofit2.http.GET
import retrofit2.http.Query

interface HeFengWeatherService {
    @GET("v7/weather/now")
    suspend fun getCurrentWeather(
        @Query("location") location: String,
        @Query("key") key: String
    ): WeatherResponse

    @GET("v7/air/now")
    suspend fun getCurrentAirQuality(
        @Query("location") location: String,
        @Query("key") key: String
    ): AirQualityResponse // 修改返回类型�?AirQualityResponse
}