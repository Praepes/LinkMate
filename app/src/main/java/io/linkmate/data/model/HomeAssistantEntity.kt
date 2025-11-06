package io.linkmate.data.model

data class HomeAssistantEntity(
    val id: String,
    val name: String,
    val type: String, // e.g., "light", "sensor", "switch"
    val area: String? = null // Area might not be directly available in entity state, will be null for now
)
