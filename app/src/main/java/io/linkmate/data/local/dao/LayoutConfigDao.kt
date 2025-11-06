package io.linkmate.data.local.dao

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Update
import io.linkmate.data.local.LayoutConfigEntity
import kotlinx.coroutines.flow.Flow

@Dao
interface LayoutConfigDao {
    @Query("SELECT * FROM layout_config LIMIT 1")
    fun getLayoutConfig(): Flow<LayoutConfigEntity?>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertLayoutConfig(config: LayoutConfigEntity)

    @Update
    suspend fun updateLayoutConfig(config: LayoutConfigEntity)

    @Query("DELETE FROM layout_config")
    suspend fun deleteLayoutConfig()
}

