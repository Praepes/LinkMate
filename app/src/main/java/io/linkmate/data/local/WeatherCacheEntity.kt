package io.linkmate.data.local

import androidx.room.Entity
import androidx.room.PrimaryKey

@Entity(tableName = "weather_cache")
data class WeatherCacheEntity(
    @PrimaryKey(autoGenerate = true) val id: Int = 0,
    val latitude: Double,
    val longitude: Double,
    val temperature: String,
    val weatherDescription: String,
    val weatherIcon: String,
    val airQuality: String,
    val lastUpdated: Long
)