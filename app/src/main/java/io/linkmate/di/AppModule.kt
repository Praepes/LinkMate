package io.linkmate.di

import android.app.Application
import androidx.room.Room
import io.linkmate.data.local.AppDatabase
import io.linkmate.data.local.dao.HaConfigDao
import io.linkmate.data.local.dao.LayoutConfigDao
import io.linkmate.data.local.dao.ReminderDao
import io.linkmate.data.local.dao.SettingsDao
import io.linkmate.data.local.dao.WeatherCacheDao
import io.linkmate.data.remote.homeassistant.HomeAssistantService
import io.linkmate.data.remote.homeassistant.HomeAssistantWebSocketClient
import io.linkmate.data.remote.hefeng.HeFengWeatherService
import io.linkmate.data.repository.HomeAssistantRepository
import io.linkmate.data.repository.LayoutRepository
import io.linkmate.data.repository.ReminderRepository
import io.linkmate.data.repository.SettingsRepository
import io.linkmate.data.repository.WeatherRepository
import com.google.gson.Gson
import com.google.gson.GsonBuilder
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent
import okhttp3.OkHttpClient
import okhttp3.logging.HttpLoggingInterceptor // Import HttpLoggingInterceptor
import retrofit2.Retrofit
import retrofit2.converter.gson.GsonConverterFactory
import java.util.concurrent.TimeUnit
import javax.inject.Singleton

@Module
@InstallIn(SingletonComponent::class)
object AppModule {

    @Provides
    @Singleton
    fun provideAppDatabase(app: Application): AppDatabase {
        return Room.databaseBuilder(
            app,
            AppDatabase::class.java,
            AppDatabase.DATABASE_NAME
        )
        .addMigrations(AppDatabase.MIGRATION_7_8, AppDatabase.MIGRATION_8_9) // Add migrations for color theme fields (version 9->10 is handled automatically)
        .fallbackToDestructiveMigration() // Fallback if migration is not found (开发阶�?
        .build()
    }

    @Provides
    @Singleton
    fun provideWeatherCacheDao(db: AppDatabase): WeatherCacheDao {
        return db.weatherCacheDao()
    }

    @Provides
    @Singleton
    fun provideHaConfigDao(db: AppDatabase): HaConfigDao {
        return db.haConfigDao()
    }

    @Provides
    @Singleton
    fun provideSettingsDao(db: AppDatabase): SettingsDao {
        return db.settingsDao()
    }

    @Provides
    @Singleton
    fun provideReminderDao(db: AppDatabase): ReminderDao {
        return db.reminderDao()
    }

    @Provides
    @Singleton
    fun provideLayoutConfigDao(db: AppDatabase): LayoutConfigDao {
        return db.layoutConfigDao()
    }

    @Provides
    @Singleton
    fun provideGson(): Gson {
        return GsonBuilder().setLenient().create()
    }

    @Provides
    @Singleton
    fun provideOkHttpClient(): OkHttpClient {
        val logging = HttpLoggingInterceptor()
        logging.setLevel(HttpLoggingInterceptor.Level.BODY) // Set log level to BODY for detailed logs

        return OkHttpClient.Builder()
            .addInterceptor(logging) // Add the logging interceptor
            .readTimeout(30, TimeUnit.SECONDS)
            .connectTimeout(30, TimeUnit.SECONDS)
            .build()
    }

    @Provides
    @Singleton
    fun provideHeFengWeatherService(okHttpClient: OkHttpClient, gson: Gson): HeFengWeatherService {
        return Retrofit.Builder()
            .baseUrl("https://devapi.qweather.com/") // Base URL for HeFeng Weather API
            .client(okHttpClient)
            .addConverterFactory(GsonConverterFactory.create(gson))
            .build()
            .create(HeFengWeatherService::class.java)
    }

    @Provides
    @Singleton
    fun provideHomeAssistantService(okHttpClient: OkHttpClient, gson: Gson): HomeAssistantService {
        // Base URL for Home Assistant REST API. This will be dynamic for each user.
        // We provide a dummy URL here, as the actual base URL comes from HaConfigEntity.
        return Retrofit.Builder()
            .baseUrl("http://localhost/") // Dummy URL, actual URL comes from HaConfigEntity
            .client(okHttpClient)
            .addConverterFactory(GsonConverterFactory.create(gson))
            .build()
            .create(HomeAssistantService::class.java)
    }

    @Provides
    @Singleton
    fun provideHomeAssistantWebSocketClient(okHttpClient: OkHttpClient, gson: Gson): HomeAssistantWebSocketClient {
        return HomeAssistantWebSocketClient(okHttpClient, gson)
    }

    @Provides
    @Singleton
    fun provideWeatherRepository(
        weatherCacheDao: WeatherCacheDao,
        heFengWeatherService: HeFengWeatherService
    ): WeatherRepository {
        return WeatherRepository(heFengWeatherService, weatherCacheDao)
    }

    @Provides
    @Singleton
    fun provideHomeAssistantRepository(
        haConfigDao: HaConfigDao,
        webSocketClient: HomeAssistantWebSocketClient,
        okHttpClient: OkHttpClient,
        gson: Gson
    ): HomeAssistantRepository {
        return HomeAssistantRepository(haConfigDao, webSocketClient, okHttpClient, gson)
    }

    @Provides
    @Singleton
    fun provideSettingsRepository(
        settingsDao: SettingsDao
    ): SettingsRepository {
        return SettingsRepository(settingsDao)
    }

    @Provides
    @Singleton
    fun provideReminderRepository(
        reminderDao: ReminderDao
    ): ReminderRepository {
        return ReminderRepository(reminderDao)
    }

    @Provides
    @Singleton
    fun provideLayoutRepository(
        layoutConfigDao: LayoutConfigDao
    ): LayoutRepository {
        return LayoutRepository(layoutConfigDao)
    }
}