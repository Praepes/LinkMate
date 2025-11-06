package io.linkmate.data.local.dao

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Update
import io.linkmate.data.local.HaConfigEntity
import kotlinx.coroutines.flow.Flow

@Dao
interface HaConfigDao {
    @Query("SELECT * FROM ha_config LIMIT 1")
    fun getHaConfig(): Flow<HaConfigEntity?>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertHaConfig(haConfig: HaConfigEntity)

    @Update
    suspend fun updateHaConfig(haConfig: HaConfigEntity)

    @Query("DELETE FROM ha_config")
    suspend fun deleteAll()
}