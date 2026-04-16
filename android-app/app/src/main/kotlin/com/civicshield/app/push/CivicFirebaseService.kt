package com.civicshield.app.push

import android.Manifest
import android.app.PendingIntent
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.util.Log
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.core.content.ContextCompat
import com.civicshield.app.CivicShieldApp
import com.civicshield.app.R
import com.civicshield.app.data.api.RetrofitClient
import com.civicshield.app.data.local.AuthStore
import com.civicshield.app.data.model.FcmTokenRequest
import com.civicshield.app.ui.login.LoginActivity
import com.google.firebase.messaging.FirebaseMessagingService
import com.google.firebase.messaging.RemoteMessage
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch

class CivicFirebaseService : FirebaseMessagingService() {

    private val ioScope by lazy { CoroutineScope(SupervisorJob() + Dispatchers.IO) }

    override fun onNewToken(token: String) {
        super.onNewToken(token)
        Log.i(TAG, "new FCM token received")

        ioScope.launch {
            try {
                val authStore = AuthStore(applicationContext)
                if (authStore.currentToken().isNullOrEmpty()) {
                    Log.i(TAG, "not logged in yet — deferring FCM token save")
                    return@launch
                }
                val api = RetrofitClient.create(applicationContext)
                api.saveFcmToken(FcmTokenRequest(token))
                Log.i(TAG, "FCM token saved to backend")
            } catch (e: Exception) {
                Log.w(TAG, "failed to save FCM token", e)
            }
        }
    }

    override fun onMessageReceived(message: RemoteMessage) {
        super.onMessageReceived(message)
        val data = message.data
        val caseId = data["case_id"]
        val statusUpdate = data["status"]
        val type = data["type"]

        val shortId = caseId?.take(8)?.let { "$it…" } ?: "update"
        val title = message.notification?.title ?: "Case $shortId"
        val body = message.notification?.body
            ?: buildString {
                append("Your ")
                append(type ?: "report")
                append(" is now ")
                append(statusUpdate ?: "updated")
                append('.')
            }
        postNotification(title, body, caseId)
    }

    private fun postNotification(title: String, body: String, caseId: String?) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            val granted = ContextCompat.checkSelfPermission(
                this, Manifest.permission.POST_NOTIFICATIONS
            ) == PackageManager.PERMISSION_GRANTED
            if (!granted) {
                Log.w(TAG, "POST_NOTIFICATIONS not granted — skipping notification")
                return
            }
        }

        val tapIntent = Intent(this, LoginActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_SINGLE_TOP
            caseId?.let { putExtra("case_id", it) }
        }
        val pendingIntent = PendingIntent.getActivity(
            this, 0, tapIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )

        val notification = NotificationCompat.Builder(this, CivicShieldApp.CHANNEL_ID_CIVIC_UPDATES)
            .setSmallIcon(R.drawable.ic_notification)
            .setContentTitle(title)
            .setContentText(body)
            .setStyle(NotificationCompat.BigTextStyle().bigText(body))
            .setAutoCancel(true)
            .setContentIntent(pendingIntent)
            .setPriority(NotificationCompat.PRIORITY_DEFAULT)
            .build()

        val notifId = caseId?.hashCode() ?: System.currentTimeMillis().toInt()
        NotificationManagerCompat.from(this).notify(notifId, notification)
    }

    override fun onDestroy() {
        super.onDestroy()
        (ioScope.coroutineContext[Job] as? Job)?.cancel()
    }

    companion object {
        private const val TAG = "CivicFcm"
    }
}
