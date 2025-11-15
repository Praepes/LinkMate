package io.linkmate.data.repository

import android.util.Log
import io.linkmate.data.local.LayoutConfigEntity
import io.linkmate.data.local.dao.LayoutConfigDao
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.firstOrNull
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class LayoutRepository @Inject constructor(
    private val layoutConfigDao: LayoutConfigDao
) {
    private val TAG = "LayoutRepository"

    /**
     * 获取布局配置
     */
    fun getLayoutConfig(): Flow<LayoutConfigEntity?> {
        return layoutConfigDao.getLayoutConfig()
    }

    /**
     * 保存布局配置
     */
    suspend fun saveLayoutConfig(widgetOrder: String, widgetPositions: String? = null) {
        try {
            val existingConfig = layoutConfigDao.getLayoutConfig().firstOrNull()
            if (existingConfig != null) {
                val updatedConfig = existingConfig.copy(
                    widgetOrder = widgetOrder,
                    widgetPositions = widgetPositions ?: existingConfig.widgetPositions
                )
                layoutConfigDao.updateLayoutConfig(updatedConfig)
                Log.d(TAG, "Layout config updated: order=$widgetOrder, positions=${widgetPositions?.take(100)}")
            } else {
                val newConfig = LayoutConfigEntity(
                    widgetOrder = widgetOrder,
                    widgetPositions = widgetPositions ?: ""
                )
                layoutConfigDao.insertLayoutConfig(newConfig)
                Log.d(TAG, "Layout config created: order=$widgetOrder, positions=${widgetPositions?.take(100)}")
            }
        } catch (e: Exception) {
            Log.e(TAG, "Error saving layout config: ${e.message}", e)
        }
    }
    
    /**
     * 保存位置信息
     */
    suspend fun saveWidgetPositions(widgetPositions: String) {
        try {
            val existingConfig = layoutConfigDao.getLayoutConfig().firstOrNull()
            if (existingConfig != null) {
                val updatedConfig = existingConfig.copy(widgetPositions = widgetPositions)
                layoutConfigDao.updateLayoutConfig(updatedConfig)
                Log.d(TAG, "Widget positions updated: ${widgetPositions.take(100)}")
            } else {
                // 如果没有配置，创建一个新的
                val newConfig = LayoutConfigEntity(
                    widgetOrder = getDefaultOrder(),
                    widgetPositions = widgetPositions
                )
                layoutConfigDao.insertLayoutConfig(newConfig)
                Log.d(TAG, "Layout config created with positions: ${widgetPositions.take(100)}")
            }
        } catch (e: Exception) {
            Log.e(TAG, "Error saving widget positions: ${e.message}", e)
        }
    }

    /**
     * 获取默认的布局顺序
     */
    fun getDefaultOrder(): String {
        return """["WEATHER","REMINDERS","HA_GRID"]"""
    }

    /**
     * 删除布局配置
     */
    suspend fun deleteLayoutConfig() {
        layoutConfigDao.deleteLayoutConfig()
    }
}

