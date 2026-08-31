package com.carl.glucobro

import android.content.Context
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.core.doublePreferencesKey
import androidx.datastore.preferences.core.intPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import kotlinx.coroutines.flow.first

private val Context.dataStore by preferencesDataStore(
    name = "glucobro_preferences"
)

class Preferences(
    private val context: Context
) {

    private val loggedInKey =
        booleanPreferencesKey("logged_in")

    private val usernameKey =
        stringPreferencesKey("username")

    private val authTokenKey =
        stringPreferencesKey("auth_token")

    private val userIdKey =
        stringPreferencesKey("user_id")

    private val targetLowKey =
        doublePreferencesKey("target_low_mmol")

    private val targetHighKey =
        doublePreferencesKey("target_high_mmol")

    private val urgentLowAlarmEnabledKey =
        booleanPreferencesKey("urgent_low_alarm_enabled")

    private val urgentLowEpisodeActiveKey =
        booleanPreferencesKey("urgent_low_episode_active")

    private val lowAlarmEnabledKey =
        booleanPreferencesKey("low_alarm_enabled")

    private val highAlarmEnabledKey =
        booleanPreferencesKey("high_alarm_enabled")

    private val urgentLowAlarmLevelKey =
        doublePreferencesKey("urgent_low_alarm_level_mmol")

    private val lowAlarmLevelKey =
        doublePreferencesKey("low_alarm_level_mmol")

    private val highAlarmLevelKey =
        doublePreferencesKey("high_alarm_level_mmol")

    private val urgentLowAlarmVolumeKey =
        intPreferencesKey("urgent_low_alarm_volume_percent")

    private val lowAlarmVolumeKey =
        intPreferencesKey("low_alarm_volume_percent")

    private val highAlarmVolumeKey =
        intPreferencesKey("high_alarm_volume_percent")

    suspend fun saveSession(
        username: String,
        authToken: String,
        userId: String
    ) {
        context.dataStore.edit { prefs ->

            prefs[loggedInKey] = true
            prefs[usernameKey] = username
            prefs[authTokenKey] = authToken
            prefs[userIdKey] = userId
        }
    }

    suspend fun isLoggedIn(): Boolean {

        val prefs = context.dataStore.data.first()

        return prefs[loggedInKey] ?: false
    }

    suspend fun getAuthToken(): String? {

        val prefs = context.dataStore.data.first()

        return prefs[authTokenKey]
    }

    suspend fun getUserId(): String? {

        val prefs = context.dataStore.data.first()

        return prefs[userIdKey]
    }


    suspend fun getTargetLow(): Double {
        val prefs = context.dataStore.data.first()
        return prefs[targetLowKey] ?: 4.0
    }

    suspend fun getTargetHigh(): Double {
        val prefs = context.dataStore.data.first()
        return prefs[targetHighKey] ?: 10.0
    }

    suspend fun saveTargetRange(low: Double, high: Double) {
        context.dataStore.edit { prefs ->
            prefs[targetLowKey] = low
            prefs[targetHighKey] = high
        }
    }

    suspend fun getUrgentLowAlarmEnabled(): Boolean {
        val prefs = context.dataStore.data.first()
        return prefs[urgentLowAlarmEnabledKey] ?: true
    }

    suspend fun getUrgentLowEpisodeActive(): Boolean {
        val prefs = context.dataStore.data.first()
        return prefs[urgentLowEpisodeActiveKey] ?: false
    }

    suspend fun setUrgentLowEpisodeActive(active: Boolean) {
        context.dataStore.edit { prefs ->
            prefs[urgentLowEpisodeActiveKey] = active
        }
    }

    suspend fun disableUrgentLowForSensorFault() {
        context.dataStore.edit { prefs ->
            prefs[urgentLowAlarmEnabledKey] = false
            prefs[urgentLowEpisodeActiveKey] = false
        }
    }

    suspend fun getLowAlarmEnabled(): Boolean {
        val prefs = context.dataStore.data.first()
        return prefs[lowAlarmEnabledKey] ?: true
    }

    suspend fun getHighAlarmEnabled(): Boolean {
        val prefs = context.dataStore.data.first()
        return prefs[highAlarmEnabledKey] ?: true
    }

    suspend fun getUrgentLowAlarmLevel(): Double {
        val prefs = context.dataStore.data.first()
        return (prefs[urgentLowAlarmLevelKey] ?: 2.9).coerceIn(2.5, 3.9)
    }

    suspend fun getLowAlarmLevel(): Double {
        val prefs = context.dataStore.data.first()
        return (prefs[lowAlarmLevelKey] ?: 4.0).coerceAtLeast(3.5)
    }

    suspend fun getHighAlarmLevel(): Double {
        val prefs = context.dataStore.data.first()
        return (prefs[highAlarmLevelKey] ?: 10.0).coerceAtMost(18.0)
    }

    suspend fun saveAlarmSettings(
        urgentLowEnabled: Boolean,
        urgentLowLevel: Double,
        lowEnabled: Boolean,
        lowLevel: Double,
        highEnabled: Boolean,
        highLevel: Double
    ) {
        context.dataStore.edit { prefs ->
            prefs[urgentLowAlarmEnabledKey] = urgentLowEnabled
            prefs[urgentLowAlarmLevelKey] = urgentLowLevel.coerceIn(2.5, 3.9)
            prefs[lowAlarmEnabledKey] = lowEnabled
            prefs[lowAlarmLevelKey] = lowLevel.coerceIn(3.5, 17.9)
            prefs[highAlarmEnabledKey] = highEnabled
            prefs[highAlarmLevelKey] = highLevel.coerceAtMost(18.0)
        }
    }

    suspend fun getUrgentLowAlarmVolume(): Int {
        val prefs = context.dataStore.data.first()
        return (prefs[urgentLowAlarmVolumeKey] ?: 100).coerceIn(20, 100)
    }

    suspend fun getLowAlarmVolume(): Int {
        val prefs = context.dataStore.data.first()
        return (prefs[lowAlarmVolumeKey] ?: 100).coerceIn(20, 100)
    }

    suspend fun getHighAlarmVolume(): Int {
        val prefs = context.dataStore.data.first()
        return (prefs[highAlarmVolumeKey] ?: 100).coerceIn(20, 100)
    }

    suspend fun saveAlarmVolume(alarm: String, volumePercent: Int) {
        val volume = volumePercent.coerceIn(20, 100)
        context.dataStore.edit { prefs ->
            when (alarm) {
                "urgent" -> prefs[urgentLowAlarmVolumeKey] = volume
                "low" -> prefs[lowAlarmVolumeKey] = volume
                "high" -> prefs[highAlarmVolumeKey] = volume
            }
        }
    }

    suspend fun logout() {

        context.dataStore.edit { prefs ->
            prefs.clear()
        }
    }
}