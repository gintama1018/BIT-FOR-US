package com.meshwhisper.app.service

import android.annotation.SuppressLint
import android.app.Notification
import android.app.PendingIntent
import android.app.Service
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.IBinder
import android.os.PowerManager
import androidx.core.app.NotificationCompat
import com.meshwhisper.app.MeshApplication
import com.meshwhisper.app.R
import com.meshwhisper.app.ui.MainActivity
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch

class MeshForegroundService : Service() {

    private val serviceScope = CoroutineScope(Dispatchers.IO)
    private var heartbeatJob: Job? = null
    private var statsJob: Job? = null
    private var wakeLock: PowerManager.WakeLock? = null

    override fun onCreate() {
        super.onCreate()

        val powerManager = getSystemService(POWER_SERVICE) as? PowerManager
        wakeLock = powerManager?.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "MeshWhisper:ServiceWakeLock")
        wakeLock?.acquire(10 * 60 * 1000L) // 10-minute safe timeout

        startInForeground()

        val app = MeshApplication.instance
        app.bleEngine.start(app.cryptoEngine.nodeId)

        // Periodic heartbeat & presence announcement every 4 seconds (fast discovery & recovery)
        heartbeatJob = serviceScope.launch {
            var lastWakeLockRenew = System.currentTimeMillis()
            while (isActive) {
                app.router.announcePresence()

                // Periodically refresh 10-minute wakelock every 8 minutes
                val now = System.currentTimeMillis()
                if (now - lastWakeLockRenew > 8 * 60 * 1000L) {
                    wakeLock?.let {
                        if (it.isHeld) it.release()
                        it.acquire(10 * 60 * 1000L)
                    }
                    lastWakeLockRenew = now
                }

                delay(4000L)
            }
        }

        // Live notification status updater
        statsJob = serviceScope.launch {
            while (isActive) {
                updateNotification()
                delay(5000L)
            }
        }
    }

    private fun startInForeground() {
        val notification = buildNotification(
            peersCount = 0,
            relayedCount = 0,
            myAlias = MeshApplication.instance.cryptoEngine.alias
        )

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
            startForeground(
                NOTIFICATION_ID,
                notification,
                ServiceInfo.FOREGROUND_SERVICE_TYPE_CONNECTED_DEVICE
            )
        } else {
            startForeground(NOTIFICATION_ID, notification)
        }
    }

    private fun updateNotification() {
        val app = MeshApplication.instance
        val peers = app.bleEngine.connectedPeersCount.value
        val relayed = app.router.relayedPacketsCount.value
        val alias = app.cryptoEngine.alias

        val notification = buildNotification(peers, relayed, alias)
        val manager = getSystemService(NOTIFICATION_SERVICE) as android.app.NotificationManager
        manager.notify(NOTIFICATION_ID, notification)
    }

    private fun buildNotification(peersCount: Int, relayedCount: Int, myAlias: String): Notification {
        val intent = Intent(this, MainActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_SINGLE_TOP
        }
        val pendingIntent = PendingIntent.getActivity(
            this,
            0,
            intent,
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )

        val statusText = if (peersCount == 0) {
            "Scanning for mesh nodes..."
        } else {
            "$peersCount node${if (peersCount > 1) "s" else ""} connected • $relayedCount packets relayed"
        }

        return NotificationCompat.Builder(this, MeshApplication.CHANNEL_ID)
            .setContentTitle("MeshWhisper: $myAlias (Active)")
            .setContentText(statusText)
            .setSmallIcon(R.drawable.ic_launcher_foreground)
            .setContentIntent(pendingIntent)
            .setOngoing(true)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .build()
    }

    override fun onDestroy() {
        super.onDestroy()
        heartbeatJob?.cancel()
        statsJob?.cancel()
        MeshApplication.instance.bleEngine.stop()

        try {
            if (wakeLock?.isHeld == true) {
                wakeLock?.release()
            }
        } catch (e: Exception) {
            // Ignored
        }
    }

    override fun onBind(intent: Intent?): IBinder? = null

    companion object {
        private const val NOTIFICATION_ID = 1001
    }
}
