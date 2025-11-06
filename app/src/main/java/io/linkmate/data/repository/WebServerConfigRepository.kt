package io.linkmate.data.repository

import android.content.Context
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.intPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import javax.inject.Inject
import javax.inject.Singleton

private const val DATA_STORE_NAME = "web_server_prefs"

private val Context.dataStore by preferencesDataStore(name = DATA_STORE_NAME)

@Singleton
class WebServerConfigRepository @Inject constructor(
    @ApplicationContext private val context: Context
) {
    private object Keys {
        val PORT: Preferences.Key<Int> = intPreferencesKey("port")
        val PASSWORD: Preferences.Key<String> = stringPreferencesKey("web_password")
    }

    fun portFlow(defaultPort: Int = 8080): Flow<Int> =
        context.dataStore.data.map { prefs -> prefs[Keys.PORT] ?: defaultPort }

    suspend fun setPort(port: Int) {
        android.util.Log.d("WebServerConfigRepo", "setPort: $port")
        context.dataStore.edit { prefs ->
            prefs[Keys.PORT] = port
        }
    }

    fun passwordFlow(): Flow<String> =
        context.dataStore.data.map { prefs -> prefs[Keys.PASSWORD] ?: "" }

    suspend fun setPassword(password: String) {
        android.util.Log.d("WebServerConfigRepo", "setPassword: (len) ${password.length}")
        context.dataStore.edit { prefs ->
            prefs[Keys.PASSWORD] = password
        }
    }
}


