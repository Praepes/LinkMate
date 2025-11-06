package io.linkmate.data.remote.homeassistant

import com.google.gson.annotations.SerializedName

data class HaEntityState(
    @SerializedName("entity_id") val entityId: String,
    @SerializedName("state") val state: String,
    @SerializedName("attributes") val attributes: Map<String, Any>,
    @SerializedName("last_changed") val lastChanged: String,
    @SerializedName("last_updated") val lastUpdated: String,
    @SerializedName("context") val context: Map<String, Any>
)

data class HaServiceCallRequest(
    @SerializedName("entity_id") val entityId: String
) // 恢复到只包含 entityId 的原始形�?
