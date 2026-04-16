package com.civicshield.app

import android.app.Application
import android.app.NotificationChannel
import android.app.NotificationManager

class CivicShieldApp : Application() {

    override fun onCreate() {
        super.onCreate()
        createNotificationChannel()
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

    companion object {
        const val CHANNEL_ID_CIVIC_UPDATES = "CIVIC_UPDATES"
    }
}
