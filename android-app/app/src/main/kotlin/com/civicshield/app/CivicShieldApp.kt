package com.civicshield.app

import android.app.Application
import android.app.NotificationChannel
import android.app.NotificationManager
import android.preference.PreferenceManager
import org.osmdroid.config.Configuration

class CivicShieldApp : Application() {

    override fun onCreate() {
        super.onCreate()
        createNotificationChannel()
        initOsmdroid()
    }

    private fun createNotificationChannel() {
        // minSdk is 26, so NotificationChannel is always available.
        val channel = NotificationChannel(
            CHANNEL_ID_CIVIC_UPDATES,
            "Civic Updates",
            NotificationManager.IMPORTANCE_DEFAULT,
        ).apply {
            description = "Status updates on your reported cases"
        }
        getSystemService(NotificationManager::class.java)
            .createNotificationChannel(channel)
    }

    /**
     * OSMDroid requires a one-time config load + a non-empty user agent before any MapView
     * is inflated. Running this in Application.onCreate guarantees every map screen sees a
     * configured engine and avoids the "403 user-agent blocked" tile failures.
     */
    @Suppress("DEPRECATION")
    private fun initOsmdroid() {
        val prefs = PreferenceManager.getDefaultSharedPreferences(this)
        Configuration.getInstance().apply {
            load(this@CivicShieldApp, prefs)
            userAgentValue = "$packageName/${BuildConfig.VERSION_NAME}"
        }
    }

    companion object {
        const val CHANNEL_ID_CIVIC_UPDATES = "CIVIC_UPDATES"
    }
}
