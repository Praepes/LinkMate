package io.linkmate.data.remote.hefeng

import com.google.gson.annotations.SerializedName

data class WeatherResponse(
    @SerializedName("code") val code: String,
    @SerializedName("now") val now: Now,
    @SerializedName("air_now") val airNow: AirNow? = null,
    @SerializedName("updateTime") val updateTime: String
)

data class Now(
    @SerializedName("temp") val temp: String,
    @SerializedName("text") val text: String,
    @SerializedName("icon") val icon: String
)

data class AirNow(
    @SerializedName("pubTime") val pubTime: String,
    @SerializedName("aqi") val aqi: String,
    @SerializedName("level") val level: String,
    @SerializedName("category") val category: String,
    @SerializedName("primary") val primary: String?,
    @SerializedName("station") val station: List<Station>?
)

data class Station(
    @SerializedName("name") val name: String,
    @SerializedName("id") val id: String,
    @SerializedName("aqi") val aqi: String,
    @SerializedName("level") val level: String,
    @SerializedName("category") val category: String,
    @SerializedName("primary") val primary: String?,
    @SerializedName("pm10") val pm10: String,
    @SerializedName("pm25") val pm25: String,
    @SerializedName("no2") val no2: String,
    @SerializedName("so2") val so2: String,
    @SerializedName("co") val co: String,
    @SerializedName("o3") val o3: String
)