package io.linkmate.data.local.dao

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Update
import io.linkmate.data.local.WeatherCacheEntity
import kotlinx.coroutines.flow.Flow

@Dao
interface WeatherCacheDao {
    @Query("SELECT * FROM weather_cache ORDER BY lastUpdated DESC LIMIT 1")
    fun getWeatherCache(): Flow<WeatherCacheEntity?>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertWeatherCache(weatherCache: WeatherCacheEntity)

    @Update
    suspend fun updateWeatherCache(weatherCache: WeatherCacheEntity)

    @Query("DELETE FROM weather_cache")
    suspend fun deleteAll()
}