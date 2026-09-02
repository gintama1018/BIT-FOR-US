package com.meshwhisper.app.service

import android.app.Notification
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.IBinder
import android.util.Log
import androidx.core.app.NotificationCompat
import com.meshwhisper.app.MeshApplication
import com.meshwhisper.app.R
import com.meshwhisper.app.ui.MainActivity
import kotlinx.coroutines.CoroutineExceptionHandler
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch

class MeshForegroundService : Service() {

    private val tag = "MeshForegroundService"
    private val exceptionHandler = CoroutineExceptionHandler { _, throwable ->
        Log.e(tag, "Uncaught coroutine exception in MeshForegroundService: ${throwable.message}", throwable)
    }
    private val serviceScope = CoroutineScope(SupervisorJob() + Dispatchers.IO + exceptionHandler)
    private var heartbeatJob: Job? = null
    private var statsJob: Job? = null

    private var isRelayPaused: Boolean = false

    override fun onCreate() {
        super.onCreate()

        // Read initial persisted background relay preference
        val prefs = getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        val isEnabled = prefs.getBoolean(KEY_BACKGROUND_RELAY, true)
        isRelayPaused = !isEnabled

        startInForeground()

        if (!isRelayPaused) {
            val app = MeshApplication.instance
            try {
                app.bleEngine.setLowLatencyMode(false) // Balanced power mode for background
                app.bleEngine.start(app.cryptoEngine.nodeId)
                app.wifiEngine.start(app.cryptoEngine.nodeId, app.cryptoEngine.alias)
            } catch (e: Exception) {
                Log.e(tag, "Failed to start engines in service: ${e.message}", e)
            }
            startHeartbeatLoop()
        }

        // Live notification status updater
        statsJob = serviceScope.launch {
            while (isActive) {
                try {
                    updateNotification()
                } catch (e: Exception) {
                    Log.w(tag, "Failed to update notification: ${e.message}")
                }
                delay(5000L)
            }
        }
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_PAUSE_RELAY -> {
                pauseRelay()
            }
            ACTION_RESUME_RELAY -> {
                resumeRelay()
            }
            ACTION_STOP_SERVICE -> {
                stopSelf()
            }
        }
        return START_STICKY
    }

    private fun pauseRelay() {
        Log.i(tag, "Pausing Mesh Relay via service action")
        isRelayPaused = true
        getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            .edit()
            .putBoolean(KEY_BACKGROUND_RELAY, false)
            .apply()

        heartbeatJob?.cancel()
        heartbeatJob = null

        // Only stop the shared BLE engine singleton when the Activity is not in foreground.
        // If the user toggles the switch mid-chat, the live connection must stay up —
        // the toggle's own subtitle promises it only affects backgrounded behavior.
        if (!isActivityInForeground) {
            try {
                MeshApplication.instance.bleEngine.stop()
                MeshApplication.instance.wifiEngine.stop()
            } catch (e: Exception) {
                Log.e(tag, "Error stopping engines on pause: ${e.message}")
            }
        } else {
            Log.d(tag, "pauseRelay: Activity in foreground — skipping bleEngine.stop() to preserve live connection.")
        }
        updateNotification()
    }

    private fun resumeRelay() {
        Log.i(tag, "Resuming Mesh Relay via service action")
        isRelayPaused = false
        getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            .edit()
            .putBoolean(KEY_BACKGROUND_RELAY, true)
            .apply()

        val app = MeshApplication.instance
        try {
            app.bleEngine.setLowLatencyMode(false) // Balanced power mode
            app.bleEngine.start(app.cryptoEngine.nodeId)
            app.wifiEngine.start(app.cryptoEngine.nodeId, app.cryptoEngine.alias)
        } catch (e: Exception) {
            Log.e(tag, "Error starting engines on resume: ${e.message}")
        }
        startHeartbeatLoop()
        updateNotification()
    }

    private fun startHeartbeatLoop() {
        heartbeatJob?.cancel()
        heartbeatJob = serviceScope.launch {
            val app = MeshApplication.instance
            while (isActive) {
                try {
                    val loc = app.locationHelper.getLastKnownLocation()
                    app.router.announcePresence(loc?.latitude, loc?.longitude, loc?.accuracy ?: 0f)
                } catch (e: Exception) {
                    Log.e(tag, "Error during announcePresence heartbeat: ${e.message}")
                }

                // Adaptive heartbeat backoff:
                // 4 seconds when active direct peers are connected
                // 12 seconds when idle / 0 peers to conserve radio power
                val connectedPeers = app.bleEngine.connectedPeersCount.value
                val interval = if (connectedPeers > 0) 4000L else 12000L
                delay(interval)
            }
        }
    }

    private fun startInForeground() {
        try {
            val notification = buildNotification(
                peersCount = 0,
                relayedCount = 0,
                myAlias = MeshApplication.instance.cryptoEngine.alias,
                isPaused = isRelayPaused
            )

            val hasPerms = try {
                MeshApplication.instance.bleEngine.hasPermissions()
            } catch (e: Exception) {
                false
            }

            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE && hasPerms) {
                startForeground(
                    NOTIFICATION_ID,
                    notification,
                    ServiceInfo.FOREGROUND_SERVICE_TYPE_CONNECTED_DEVICE
                )
            } else {
                startForeground(NOTIFICATION_ID, notification)
            }
        } catch (e: Exception) {
            Log.e(tag, "startForeground error: ${e.message}", e)
        }
    }

    private fun updateNotification() {
        val app = MeshApplication.instance
        val peers = app.bleEngine.connectedPeersCount.value
        val relayed = app.router.relayedPacketsCount.value
        val alias = app.cryptoEngine.alias

        val notification = buildNotification(peers, relayed, alias, isRelayPaused)
        val manager = getSystemService(NOTIFICATION_SERVICE) as? android.app.NotificationManager
        manager?.notify(NOTIFICATION_ID, notification)
    }

    private fun buildNotification(
        peersCount: Int,
        relayedCount: Int,
        myAlias: String,
        isPaused: Boolean
    ): Notification {
        val mainIntent = Intent(this, MainActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_SINGLE_TOP
        }
        val mainPendingIntent = PendingIntent.getActivity(
            this,
            0,
            mainIntent,
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )

        val builder = NotificationCompat.Builder(this, MeshApplication.CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_mesh_notification)
            .setContentIntent(mainPendingIntent)
            .setOngoing(true)
            .setPriority(NotificationCompat.PRIORITY_LOW)

        if (isPaused) {
            val resumeIntent = Intent(this, MeshForegroundService::class.java).apply {
                action = ACTION_RESUME_RELAY
            }
            val resumePendingIntent = PendingIntent.getService(
                this,
                101,
                resumeIntent,
                PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
            )

            builder.setContentTitle("MeshWhisper: $myAlias (Relay Paused)")
                .setContentText("Background relaying is paused. Tap Resume to connect.")
                .addAction(
                    android.R.drawable.ic_media_play,
                    "Resume Relay",
                    resumePendingIntent
                )
        } else {
            val pauseIntent = Intent(this, MeshForegroundService::class.java).apply {
                action = ACTION_PAUSE_RELAY
            }
            val pausePendingIntent = PendingIntent.getService(
                this,
                102,
                pauseIntent,
                PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
            )

            val statusText = if (peersCount == 0) {
                "Scanning for mesh nodes (Balanced)..."
            } else {
                "$peersCount node${if (peersCount > 1) "s" else ""} connected • $relayedCount packets relayed"
            }

            builder.setContentTitle("MeshWhisper: $myAlias (Active)")
                .setContentText(statusText)
                .addAction(
                    android.R.drawable.ic_media_pause,
                    "Pause Relay",
                    pausePendingIntent
                )
        }

        return builder.build()
    }

    override fun onDestroy() {
        super.onDestroy()
        heartbeatJob?.cancel()
        statsJob?.cancel()
        try {
            MeshApplication.instance.bleEngine.stop()
        } catch (e: Exception) {
            Log.e(tag, "Error stopping bleEngine on service destroy: ${e.message}")
        }
    }

    override fun onBind(intent: Intent?): IBinder? = null

    companion object {
        const val NOTIFICATION_ID = 1001
        const val PREFS_NAME = "meshwhisper_prefs"
        const val KEY_BACKGROUND_RELAY = "pref_background_relay_enabled"

        const val ACTION_PAUSE_RELAY = "com.meshwhisper.app.action.PAUSE_RELAY"
        const val ACTION_RESUME_RELAY = "com.meshwhisper.app.action.RESUME_RELAY"
        const val ACTION_STOP_SERVICE = "com.meshwhisper.app.action.STOP_SERVICE"

        /**
         * Set to true by MainActivity.onStart() and false by onStop().
         * pauseRelay() checks this to avoid stopping the shared BLE engine singleton
         * while the Activity is still visible and actively using it.
         */
        @Volatile
        var isActivityInForeground: Boolean = false
    }
}
