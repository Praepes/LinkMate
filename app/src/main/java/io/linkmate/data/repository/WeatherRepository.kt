package io.linkmate.data.repository

import io.linkmate.data.local.dao.WeatherCacheDao
import io.linkmate.data.local.WeatherCacheEntity
import io.linkmate.data.remote.hefeng.HeFengWeatherService
import io.linkmate.data.remote.hefeng.AirQualityResponse // 导入新的数据�?
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.firstOrNull
import kotlinx.coroutines.flow.map
import javax.inject.Inject
import javax.inject.Singleton
import android.util.Log // 导入 Log

@Singleton
class WeatherRepository @Inject constructor(
    private val heFengWeatherService: HeFengWeatherService,
    private val weatherCacheDao: WeatherCacheDao
) {

    fun getCachedWeather(): Flow<WeatherCacheEntity?> {
        return weatherCacheDao.getWeatherCache()
    }

    suspend fun fetchAndCacheWeather(
        location: String,
        apiKey: String
    ): WeatherCacheEntity? {
        return try {
            val weatherResponse = heFengWeatherService.getCurrentWeather(location, apiKey)
            Log.d("WeatherRepo", "Weather API Response: code=${weatherResponse.code}, now.icon=${weatherResponse.now.icon}, now.text=${weatherResponse.now.text}")

            val airQualityResponse = heFengWeatherService.getCurrentAirQuality(location, apiKey)
            // Log for Air Quality API response, now using AirQualityResponse
            Log.d("WeatherRepo", "Air Quality API Response: code=${airQualityResponse.code}, now.aqi=${airQualityResponse.now.aqi}, now.category=${airQualityResponse.now.category}")


            if (weatherResponse.code == "200") {
                // 现在�?airQualityResponse.now.aqi 获取空气质量，并进行空安全检�?
                val airQuality = airQualityResponse.now?.aqi ?: "未知"
                val weatherEntity = WeatherCacheEntity(
                    latitude = 0.0,
                    longitude = 0.0,
                    temperature = weatherResponse.now.temp,
                    weatherDescription = weatherResponse.now.text,
                    weatherIcon = weatherResponse.now.icon,
                    airQuality = airQuality,
                    lastUpdated = System.currentTimeMillis()
                )
                weatherCacheDao.insertWeatherCache(weatherEntity)
                weatherEntity
            } else {
                Log.e("WeatherRepo", "Weather API returned error code: ${weatherResponse.code}")
                null
            }
        } catch (e: Exception) {
            Log.e("WeatherRepo", "Error fetching weather or air quality: ${e.message}", e)
            e.printStackTrace()
            null
        }
    }

    suspend fun shouldFetchNewWeather(lastUpdated: Long, refreshIntervalMillis: Long): Boolean {
        return System.currentTimeMillis() - lastUpdated > refreshIntervalMillis
    }
}