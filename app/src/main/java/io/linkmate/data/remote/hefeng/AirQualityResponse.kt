package io.linkmate.data.remote.hefeng

import com.google.gson.annotations.SerializedName

data class AirQualityResponse(
    @SerializedName("code") val code: String,
    @SerializedName("updateTime") val updateTime: String,
    @SerializedName("fxLink") val fxLink: String?,
    @SerializedName("now") val now: AirNowData, // 这里�?'now' 是空气质量数�?
    @SerializedName("refer") val refer: Refer?
)

data class AirNowData( // 这是空气质量 API 响应�?'now' 字段的具体内�?
    @SerializedName("pubTime") val pubTime: String,
    @SerializedName("aqi") val aqi: String,
    @SerializedName("level") val level: String,
    @SerializedName("category") val category: String,
    @SerializedName("primary") val primary: String?,
    @SerializedName("pm10") val pm10: String,
    @SerializedName("pm2p5") val pm2p5: String,
    @SerializedName("no2") val no2: String,
    @SerializedName("so2") val so2: String,
    @SerializedName("co") val co: String,
    @SerializedName("o3") val o3: String
)

data class Refer(
    @SerializedName("sources") val sources: List<String>?,
    @SerializedName("license") val license: List<String>?
)
