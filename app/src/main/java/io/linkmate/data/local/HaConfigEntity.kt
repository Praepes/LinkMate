package io.linkmate.data.local

import androidx.room.Entity
import androidx.room.PrimaryKey

@Entity(tableName = "ha_config")
data class HaConfigEntity(
    @PrimaryKey(autoGenerate = true) val id: Int = 0,
    val baseUrl: String = "",
    val token: String = "",
    val selectedDevices: String = "", // Stored as a comma-separated string or JSON
    val selectedSensors: String = "", // Stored as a comma-separated string or JSON
    val selectedEntities: String = "" // New field for selected entities
)