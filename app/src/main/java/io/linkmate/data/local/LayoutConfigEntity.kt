package io.linkmate.data.local

import androidx.room.Entity
import androidx.room.PrimaryKey

@Entity(tableName = "layout_config")
data class LayoutConfigEntity(
    @PrimaryKey(autoGenerate = true) val id: Int = 0,
    val widgetOrder: String = "", // JSON string storing widget order
    val widgetPositions: String = "" // JSON string storing widget positions: {"itemId": {"x": 0, "y": 0}, ...}
)

