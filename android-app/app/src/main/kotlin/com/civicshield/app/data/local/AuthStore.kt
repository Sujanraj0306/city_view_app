package com.civicshield.app.data.local

import android.content.Context
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.longPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map

private val Context.dataStore by preferencesDataStore(name = "auth")

class AuthStore(context: Context) {

    private val dataStore = context.applicationContext.dataStore

    val token: Flow<String?> = dataStore.data.map { it[TOKEN_KEY] }
    val role: Flow<String?> = dataStore.data.map { it[ROLE_KEY] }
    val userId: Flow<Long?> = dataStore.data.map { it[USER_ID_KEY] }

    suspend fun save(token: String, role: String, userId: Long) {
        dataStore.edit { prefs ->
            prefs[TOKEN_KEY] = token
            prefs[ROLE_KEY] = role
            prefs[USER_ID_KEY] = userId
        }
    }

    suspend fun clear() {
        dataStore.edit { it.clear() }
    }

    suspend fun currentToken(): String? = token.first()
    suspend fun currentRole(): String? = role.first()

    companion object {
        private val TOKEN_KEY = stringPreferencesKey("token")
        private val ROLE_KEY = stringPreferencesKey("role")
        private val USER_ID_KEY = longPreferencesKey("user_id")
    }
}
