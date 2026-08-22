package com.meshwhisper.app

import android.app.Application
import android.app.NotificationChannel
import android.app.NotificationManager
import android.content.Context
import android.content.Intent
import android.os.Build
import com.meshwhisper.app.ble.MeshBleEngine
import com.meshwhisper.app.crypto.CryptoEngine
import com.meshwhisper.app.data.MeshDatabase
import com.meshwhisper.app.router.MeshRouter
import com.meshwhisper.app.service.MeshForegroundService

class MeshApplication : Application() {

    lateinit var cryptoEngine: CryptoEngine private set
    lateinit var database: MeshDatabase private set
    lateinit var bleEngine: MeshBleEngine private set
    lateinit var router: MeshRouter private set

    override fun onCreate() {
        super.onCreate()
        instance = this

        try {
            System.loadLibrary("sqlcipher")
        } catch (t: Throwable) {
            android.util.Log.e("MeshApplication", "Failed to load sqlcipher native library: ${t.message}", t)
        }

        createNotificationChannel()

        cryptoEngine = CryptoEngine.getInstance(this)
        database = MeshDatabase.getInstance(this)
        bleEngine = MeshBleEngine(this)
        router = MeshRouter(this, bleEngine, cryptoEngine, database)
    }

    fun startMeshService() {
        try {
            val intent = Intent(this, MeshForegroundService::class.java)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                startForegroundService(intent)
            } else {
                startService(intent)
            }
        } catch (e: Exception) {
            android.util.Log.e("MeshApplication", "startForegroundService failed: ${e.message}", e)
            try {
                bleEngine.start(cryptoEngine.nodeId)
            } catch (ex: Exception) {
                android.util.Log.e("MeshApplication", "In-process bleEngine start failed: ${ex.message}", ex)
            }
        }
    }

    fun stopMeshService() {
        val intent = Intent(this, MeshForegroundService::class.java)
        stopService(intent)
    }

    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val manager = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager

            // 1. Persistent background mesh service channel (Low priority)
            val serviceChannel = NotificationChannel(
                CHANNEL_ID,
                getString(R.string.channel_name_mesh),
                NotificationManager.IMPORTANCE_LOW
            ).apply {
                description = getString(R.string.channel_desc_mesh)
            }
            manager.createNotificationChannel(serviceChannel)

            // 2. High priority chat message notification channel (Heads-up alert)
            val messagesChannel = NotificationChannel(
                MESSAGES_CHANNEL_ID,
                "Mesh Messages",
                NotificationManager.IMPORTANCE_HIGH
            ).apply {
                description = "Direct and broadcast mesh chat alerts"
                enableVibration(true)
                enableLights(true)
            }
            manager.createNotificationChannel(messagesChannel)
        }
    }

    companion object {
        const val CHANNEL_ID = "mesh_service_channel"
        const val MESSAGES_CHANNEL_ID = "mesh_messages_channel"
        lateinit var instance: MeshApplication private set
    }
}
