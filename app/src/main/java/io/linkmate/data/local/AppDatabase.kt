package io.linkmate.data.local

import androidx.room.Database
import androidx.room.RoomDatabase
import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase
import io.linkmate.data.local.dao.HaConfigDao
import io.linkmate.data.local.dao.LayoutConfigDao
import io.linkmate.data.local.dao.ReminderDao
import io.linkmate.data.local.dao.SettingsDao
import io.linkmate.data.local.dao.WeatherCacheDao

@Database(
    entities = [
        HaConfigEntity::class,
        SettingsEntity::class,
        WeatherCacheEntity::class,
        ReminderEntity::class,
        LayoutConfigEntity::class
    ],
    version = 11,
    exportSchema = false
)
abstract class AppDatabase : RoomDatabase() {
    abstract fun haConfigDao(): HaConfigDao
    abstract fun settingsDao(): SettingsDao
    abstract fun weatherCacheDao(): WeatherCacheDao
    abstract fun reminderDao(): ReminderDao
    abstract fun layoutConfigDao(): LayoutConfigDao

    companion object {
        const val DATABASE_NAME = "hp_app_database"
        
        // Migration from version 7 to 8: Add color theme fields
        val MIGRATION_7_8 = object : Migration(7, 8) {
            override fun migrate(database: SupportSQLiteDatabase) {
                // Add new columns with default values
                database.execSQL("""
                    ALTER TABLE app_settings 
                    ADD COLUMN colorThemeMode INTEGER NOT NULL DEFAULT 1
                """.trimIndent())
                
                database.execSQL("""
                    ALTER TABLE app_settings 
                    ADD COLUMN customLightPrimaryColor INTEGER NOT NULL DEFAULT ${0xFF6650a4L}
                """.trimIndent())
                
                database.execSQL("""
                    ALTER TABLE app_settings 
                    ADD COLUMN customDarkPrimaryColor INTEGER NOT NULL DEFAULT ${0xFFD0BCFFL}
                """.trimIndent())
                
                database.execSQL("""
                    ALTER TABLE app_settings 
                    ADD COLUMN semiAutoPrimaryColor INTEGER NOT NULL DEFAULT ${0xFF6650a4L}
                """.trimIndent())
            }
        }
        
        // Migration from version 8 to 9: Simplify custom color to single base color
        val MIGRATION_8_9 = object : Migration(8, 9) {
            override fun migrate(database: SupportSQLiteDatabase) {
                // Add new customPrimaryColor column
                database.execSQL("""
                    ALTER TABLE app_settings 
                    ADD COLUMN customPrimaryColor INTEGER NOT NULL DEFAULT ${0xFF6650a4L}
                """.trimIndent())
                
                // Migrate existing data: use customDarkPrimaryColor as the base color
                // (or customLightPrimaryColor if customDarkPrimaryColor is default)
                database.execSQL("""
                    UPDATE app_settings 
                    SET customPrimaryColor = CASE 
                        WHEN customDarkPrimaryColor != ${0xFFD0BCFFL} THEN customDarkPrimaryColor
                        ELSE customLightPrimaryColor
                    END
                """.trimIndent())
                
                // Note: We don't remove the old columns to avoid data loss
                // They will be ignored by the new entity definition
            }
        }
        
        // Migration from version 9 to 10: Remove semiAutoPrimaryColor field (no longer needed)
        // Note: We don't actually remove the column from database, just ignore it in the entity
        // This is safe because the entity no longer references it
        
        // Migration from version 10 to 11: Add widgetPositions field
        val MIGRATION_10_11 = object : Migration(10, 11) {
            override fun migrate(database: SupportSQLiteDatabase) {
                // Add new widgetPositions column with default empty string
                database.execSQL("""
                    ALTER TABLE layout_config 
                    ADD COLUMN widgetPositions TEXT NOT NULL DEFAULT ''
                """.trimIndent())
            }
        }
    }
}