package com.saransk.alert

import android.app.*
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.IBinder
import android.os.PowerManager
import androidx.core.app.NotificationCompat
import kotlinx.coroutines.*
import okhttp3.OkHttpClient
import okhttp3.Request
import java.util.concurrent.TimeUnit

class MaxChannelService : Service() {

    private val serviceScope = CoroutineScope(Dispatchers.IO + SupervisorJob())
    private val client = OkHttpClient.Builder()
        .connectTimeout(10, TimeUnit.SECONDS)
        .readTimeout(10, TimeUnit.SECONDS)
        .build()

    companion object {
        private const val CHANNEL_URL = "https://max.ru/tyrbazaa"
        private const val MONITOR_CHANNEL_ID = "saransk_monitor_channel"
        private const val EMERGENCY_CHANNEL_ID = "saransk_emergency_channel"
        private const val PREFS_NAME = "saransk_alert_prefs"
        private const val KEY_LAST_ALERT_HASH = "last_alert_hash"

        fun isMissileAlertForMordovia(rawText: String): Boolean {
            val normalized = rawText.lowercase().replace(Regex("\\s+"), " ")

            val hasMordovia = normalized.contains("республике мордовия") ||
                    normalized.contains("мордовии") ||
                    normalized.contains("мордовия")
            val hasMissileAlert = normalized.contains("ракетная опасность")

            val isUavOnly = (normalized.contains("бпла") || normalized.contains("беспилотн")) &&
                    !hasMissileAlert
            val isCancel = normalized.contains("отбой ракетной опасности") ||
                    normalized.contains("отмена ракетной опасности")

            return hasMordovia && hasMissileAlert && !isUavOnly && !isCancel
        }
    }

    override fun onCreate() {
        super.onCreate()
        createNotificationChannels()
        val n = buildMonitorNotification()
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            startForeground(1, n, ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC)
        } else {
            startForeground(1, n)
        }
        startPollingChannel()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int = START_STICKY

    private fun startPollingChannel() {
        serviceScope.launch {
            while (isActive) {
                try {
                    val request = Request.Builder()
                        .url(CHANNEL_URL)
                        .header("User-Agent", "Mozilla/5.0 (Android 14; Mobile)")
                        .build()

                    client.newCall(request).execute().use { response ->
                        if (response.isSuccessful) {
                            val body = response.body?.string().orEmpty()
                            checkChannelContent(body)
                        }
                    }
                } catch (_: Exception) {
                }
                delay(15_000)
            }
        }
    }

    private fun checkChannelContent(htmlOrJson: String) {
        val textBlocks = htmlOrJson
            .replace(Regex("<[^>]*>"), "\n")
            .split("\n")
            .map { it.trim() }
            .filter { it.length > 15 }

        for (block in textBlocks.takeLast(15)) {
            if (isMissileAlertForMordovia(block)) {
                val blockHash = block.hashCode().toString()
                val prefs = getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
                val lastHash = prefs.getString(KEY_LAST_ALERT_HASH, "")

                if (blockHash != lastHash) {
                    prefs.edit().putString(KEY_LAST_ALERT_HASH, blockHash).apply()
                    withContextSafe {
                        launchEmergencyAlert()
                    }
                }
                break
            }
        }
    }

    private fun withContextSafe(action: () -> Unit) {
        CoroutineScope(Dispatchers.Main).launch {
            action()
        }
    }

    @Suppress("DEPRECATION")
    private fun launchEmergencyAlert() {
        val pm = getSystemService(Context.POWER_SERVICE) as PowerManager
        val wakeLock = pm.newWakeLock(
            PowerManager.FULL_WAKE_LOCK or
                    PowerManager.ACQUIRE_CAUSES_WAKEUP or
                    PowerManager.ON_AFTER_RELEASE,
            "SaranskAlert:EmergencyWakeLock"
        )
        wakeLock.acquire(30_000L)

        AlarmController.triggerAlarm(applicationContext)

        val fullScreenIntent = Intent(this, MainActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP
            putExtra("EMERGENCY_TRIGGERED", true)
        }

        val fullScreenPendingIntent = PendingIntent.getActivity(
            this, 0, fullScreenIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        val emergencyNotification = NotificationCompat.Builder(this, EMERGENCY_CHANNEL_ID)
            .setSmallIcon(android.R.drawable.ic_dialog_alert)
            .setContentTitle("РАКЕТНАЯ ОПАСНОСТЬ — МОРДОВИЯ!")
            .setContentText("Нажмите, чтобы открыть маршрут до ближайшего укрытия в Саранске")
            .setPriority(NotificationCompat.PRIORITY_MAX)
            .setCategory(NotificationCompat.CATEGORY_ALARM)
            .setContentIntent(fullScreenPendingIntent)
            .setFullScreenIntent(fullScreenPendingIntent, true)
            .setAutoCancel(true)
            .build()

        val nm = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        nm.notify(999, emergencyNotification)

        try {
            startActivity(fullScreenIntent)
        } catch (_: Exception) {
        }
    }

    private fun createNotificationChannels() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val nm = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager

            val monitorChannel = NotificationChannel(
                MONITOR_CHANNEL_ID,
                "Мониторинг канала оповещений",
                NotificationManager.IMPORTANCE_LOW
            )

            val emergencyChannel = NotificationChannel(
                EMERGENCY_CHANNEL_ID,
                "Экстренная ракетная тревога",
                NotificationManager.IMPORTANCE_HIGH
            ).apply {
                setBypassDnd(true)
                enableVibration(true)
                lockscreenVisibility = Notification.VISIBILITY_PUBLIC
            }

            nm.createNotificationChannel(monitorChannel)
            nm.createNotificationChannel(emergencyChannel)
        }
    }

    private fun buildMonitorNotification(): Notification {
        val pendingIntent = PendingIntent.getActivity(
            this, 0, Intent(this, MainActivity::class.java),
            PendingIntent.FLAG_IMMUTABLE
        )
        return NotificationCompat.Builder(this, MONITOR_CHANNEL_ID)
            .setSmallIcon(android.R.drawable.ic_lock_idle_alarm)
            .setContentTitle("Защита Саранска активна")
            .setContentText("Мониторинг канала max.ru/tyrbazaa (только ракетная опасность)")
            .setContentIntent(pendingIntent)
            .setOngoing(true)
            .build()
    }

    override fun onDestroy() {
        serviceScope.cancel()
        super.onDestroy()
    }

    override fun onBind(intent: Intent?): IBinder? = null
}
