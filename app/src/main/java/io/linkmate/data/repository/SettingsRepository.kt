package io.linkmate.data.repository

import io.linkmate.data.local.dao.SettingsDao
import io.linkmate.data.local.SettingsEntity
import kotlinx.coroutines.flow.Flow
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class SettingsRepository @Inject constructor(
    private val settingsDao: SettingsDao
) {

    fun getSettings(): Flow<SettingsEntity?> {
        return settingsDao.getSettings()
    }

    suspend fun saveSettings(settings: SettingsEntity) {
        settingsDao.insertSettings(settings)
    }
}