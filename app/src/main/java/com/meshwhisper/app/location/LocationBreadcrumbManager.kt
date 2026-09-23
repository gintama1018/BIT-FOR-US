package com.meshwhisper.app.location

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.location.Location
import android.os.BatteryManager
import android.util.Log
import com.meshwhisper.app.data.MeshDatabase
import com.meshwhisper.app.data.model.PeerEntity
import com.meshwhisper.app.router.MeshRouter
import com.meshwhisper.core.protocol.BreadcrumbTriggerType
import com.meshwhisper.core.protocol.LocationBreadcrumbPayload
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/**
 * Production authority for managing Emergency Location Beacons, Store-Carry-Forward Breadcrumbs,
 * multi-stage battery triggers (15%/10%/5%), and anti-stalking per-contact opt-in privacy.
 */
class LocationBreadcrumbManager(
    private val context: Context,
    private val meshRouter: MeshRouter,
    private val locationHelper: LocationHelper,
    private val database: MeshDatabase
) {
    private val tag = "BreadcrumbManager"
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val seqMutex = Mutex()

    private val prefs = context.getSharedPreferences("meshwhisper_breadcrumb_prefs", Context.MODE_PRIVATE)

    private val _isExpeditionModeActive = MutableStateFlow(false)
    val isExpeditionModeActive: StateFlow<Boolean> = _isExpeditionModeActive.asStateFlow()

    private var expeditionJob: Job? = null
    private var lastSentCoordinate: Pair<Double, Double>? = null
    private var lastSentTimestampMs: Long = 0L

    // Battery trigger hysteresis: tracks current stage so each threshold fires exactly once
    private var lastHandledBatteryStage: Int = 100

    private val batteryReceiver = object : BroadcastReceiver() {
        override fun onReceive(ctx: Context, intent: Intent) {
            if (intent.action != Intent.ACTION_BATTERY_CHANGED) return

            // Charging guard: NEVER trigger dying gasp while plugged in / charging
            val plugged = intent.getIntExtra(BatteryManager.EXTRA_PLUGGED, 0)
            if (plugged != 0) {
                lastHandledBatteryStage = 100 // Reset stage when plugged into charger
                return
            }

            val level = intent.getIntExtra(BatteryManager.EXTRA_LEVEL, -1)
            val scale = intent.getIntExtra(BatteryManager.EXTRA_SCALE, -1)
            val pct = if (scale > 0 && level >= 0) (level * 100) / scale else level
            if (pct !in 0..100) return

            // Stage 3: Critical Dying Gasp (<= 5%)
            if (pct <= 5 && lastHandledBatteryStage > 5) {
                lastHandledBatteryStage = 5
                Log.w(tag, "Battery critical ($pct%) - firing high-priority dying gasp burst with cached GPS")
                scope.launch {
                    fireDyingGasp(pct, BreadcrumbTriggerType.BATTERY_CRITICAL_5)
                }
            }
            // Stage 2: Alert (<= 10%)
            else if (pct <= 10 && lastHandledBatteryStage > 10) {
                lastHandledBatteryStage = 10
                Log.i(tag, "Battery low alert ($pct%) - dispatching stage 2 breadcrumb")
                scope.launch {
                    fireDyingGasp(pct, BreadcrumbTriggerType.BATTERY_10)
                }
            }
            // Stage 1: Warning (<= 15%) - only if moved > 50m
            else if (pct <= 15 && lastHandledBatteryStage > 15) {
                lastHandledBatteryStage = 15
                Log.i(tag, "Battery low warning ($pct%) - checking movement for stage 1 breadcrumb")
                scope.launch {
                    fireLowBatteryWarningIfMoved(pct)
                }
            }
        }
    }

    init {
        try {
            val filter = IntentFilter(Intent.ACTION_BATTERY_CHANGED)
            context.registerReceiver(batteryReceiver, filter)
        } catch (e: Exception) {
            Log.e(tag, "Failed to register battery receiver: ${e.message}", e)
        }
    }

    /**
     * Atomically increments and persists the monotonic 32-bit sequence counter.
     * Guaranteed to start at >= 1.
     */
    suspend fun getNextSequenceNumber(): Long = seqMutex.withLock {
        val current = prefs.getLong("seq_num", 1L)
        val next = if (current >= 0xFFFFFFFFL) 1L else current + 1L
        prefs.edit().putLong("seq_num", next).apply()
        return current
    }

    /**
     * Enables or disables location sharing with a specific contact (Default OFF).
     */
    suspend fun setContactLocationSharing(nodeId: Long, enabled: Boolean) {
        database.peerDao().setShareLocationWithContact(nodeId, enabled)
        if (!enabled) {
            // When revoking, send an encrypted REVOKE frame so receiver purges stored coordinates
            revokeLocationSharing(nodeId)
        }
    }

    /**
     * Sends an immediate encrypted REVOKE message to a peer to purge stored locations.
     */
    suspend fun revokeLocationSharing(peerNodeId: Long): Boolean {
        val seq = getNextSequenceNumber()
        val payload = LocationBreadcrumbPayload(
            triggerType = BreadcrumbTriggerType.REVOKE,
            sequenceNumber = seq,
            fixTimestampSec = System.currentTimeMillis() / 1000L,
            note = "Revoked"
        )
        return meshRouter.sendBreadcrumbDirect(peerNodeId, payload, isEmergency = false)
    }

    /**
     * Sends an immediate manual location beacon / SOS to a specific peer or all opted-in verified peers.
     */
    suspend fun sendManualLocationBeacon(targetPeerNodeId: Long? = null, note: String? = null): Boolean {
        val fix = locationHelper.getCurrentLocation(timeoutMs = 2500L) ?: locationHelper.getLastKnownLocation()
        if (fix == null) {
            Log.w(tag, "Cannot send manual beacon: No GPS fix available on device")
            return false
        }

        val seq = getNextSequenceNumber()
        val payload = LocationBreadcrumbPayload(
            triggerType = BreadcrumbTriggerType.MANUAL_SOS,
            batteryPercent = getBatteryPercent(),
            sequenceNumber = seq,
            latitude = fix.latitude,
            longitude = fix.longitude,
            altitude = fix.altitude,
            accuracyMeters = fix.accuracy,
            fixTimestampSec = fix.timestamp / 1000L,
            note = note
        )

        return if (targetPeerNodeId != null) {
            meshRouter.sendBreadcrumbDirect(targetPeerNodeId, payload, isEmergency = true)
        } else {
            val optedInPeers = database.peerDao().getOptedInPeers()
            var anySent = false
            for (p in optedInPeers) {
                val sent = meshRouter.sendBreadcrumbDirect(p.nodeId, payload, isEmergency = true)
                if (sent) anySent = true
            }
            anySent
        }
    }

    /**
     * Starts continuous Expedition / Live Tracking Mode.
     * Runs periodic background checks: only dispatches if moved > 25m and >= 5 minutes elapsed.
     */
    fun startExpeditionMode() {
        if (_isExpeditionModeActive.value) return
        _isExpeditionModeActive.value = true

        expeditionJob = scope.launch {
            Log.i(tag, "Expedition Mode started - periodic tracking active for opted-in contacts")
            while (isActive) {
                try {
                    checkAndDispatchPeriodicBreadcrumb()
                } catch (e: Exception) {
                    Log.e(tag, "Error in periodic breadcrumb loop: ${e.message}", e)
                }
                delay(5 * 60 * 1000L) // 5 minutes interval
            }
        }
    }

    /**
     * Stops Expedition Mode immediately.
     */
    fun stopExpeditionMode() {
        expeditionJob?.cancel()
        expeditionJob = null
        _isExpeditionModeActive.value = false
        Log.i(tag, "Expedition Mode stopped")
    }

    private suspend fun checkAndDispatchPeriodicBreadcrumb() {
        val optedInPeers = database.peerDao().getOptedInPeers()
        if (optedInPeers.isEmpty()) return

        val fix = locationHelper.getCurrentLocation(timeoutMs = 3000L) ?: locationHelper.getLastKnownLocation()
        if (fix == null) return

        val lastCoord = lastSentCoordinate
        if (lastCoord != null) {
            val dist = FloatArray(1)
            Location.distanceBetween(lastCoord.first, lastCoord.second, fix.latitude, fix.longitude, dist)
            if (dist[0] < 25.0f && (System.currentTimeMillis() - lastSentTimestampMs) < 15 * 60 * 1000L) {
                // Moved less than 25m and under 15 minutes since last fix
                return
            }
        }

        val seq = getNextSequenceNumber()
        val payload = LocationBreadcrumbPayload(
            triggerType = BreadcrumbTriggerType.PERIODIC,
            batteryPercent = getBatteryPercent(),
            sequenceNumber = seq,
            latitude = fix.latitude,
            longitude = fix.longitude,
            altitude = fix.altitude,
            accuracyMeters = fix.accuracy,
            fixTimestampSec = fix.timestamp / 1000L
        )

        for (peer in optedInPeers) {
            meshRouter.sendBreadcrumbDirect(peer.nodeId, payload, isEmergency = false)
        }

        lastSentCoordinate = Pair(fix.latitude, fix.longitude)
        lastSentTimestampMs = System.currentTimeMillis()
    }

    /**
     * Fires critical dying gasp burst using CACHED GPS fix with 0ms delay.
     * Never requests a fresh cold GPS fix at <= 5% battery to prevent shutdown race.
     */
    private suspend fun fireDyingGasp(batteryPct: Int, trigger: BreadcrumbTriggerType) {
        val optedInPeers = database.peerDao().getOptedInPeers()
        if (optedInPeers.isEmpty()) return

        // Instant 0ms cached fix
        val fix = locationHelper.getLastKnownLocation()
        if (fix == null) {
            Log.w(tag, "Dying gasp: No cached GPS fix available to send")
            return
        }

        val seq = getNextSequenceNumber()
        val payload = LocationBreadcrumbPayload(
            triggerType = trigger,
            batteryPercent = batteryPct,
            sequenceNumber = seq,
            latitude = fix.latitude,
            longitude = fix.longitude,
            altitude = fix.altitude,
            accuracyMeters = fix.accuracy,
            fixTimestampSec = fix.timestamp / 1000L
        )

        for (peer in optedInPeers) {
            meshRouter.sendBreadcrumbDirect(peer.nodeId, payload, isEmergency = true)
        }
    }

    private suspend fun fireLowBatteryWarningIfMoved(batteryPct: Int) {
        val optedInPeers = database.peerDao().getOptedInPeers()
        if (optedInPeers.isEmpty()) return

        val fix = locationHelper.getLastKnownLocation() ?: return
        val lastCoord = lastSentCoordinate
        if (lastCoord != null) {
            val dist = FloatArray(1)
            Location.distanceBetween(lastCoord.first, lastCoord.second, fix.latitude, fix.longitude, dist)
            if (dist[0] < 50.0f) return // Only fire stage 1 if moved > 50m
        }

        val seq = getNextSequenceNumber()
        val payload = LocationBreadcrumbPayload(
            triggerType = BreadcrumbTriggerType.BATTERY_15,
            batteryPercent = batteryPct,
            sequenceNumber = seq,
            latitude = fix.latitude,
            longitude = fix.longitude,
            altitude = fix.altitude,
            accuracyMeters = fix.accuracy,
            fixTimestampSec = fix.timestamp / 1000L
        )

        for (peer in optedInPeers) {
            meshRouter.sendBreadcrumbDirect(peer.nodeId, payload, isEmergency = false)
        }

        lastSentCoordinate = Pair(fix.latitude, fix.longitude)
        lastSentTimestampMs = System.currentTimeMillis()
    }

    private fun getBatteryPercent(): Int {
        return try {
            val bm = context.getSystemService(Context.BATTERY_SERVICE) as? BatteryManager
            bm?.getIntProperty(BatteryManager.BATTERY_PROPERTY_CAPACITY) ?: -1
        } catch (_: Exception) {
            -1
        }
    }

    fun destroy() {
        try {
            context.unregisterReceiver(batteryReceiver)
        } catch (_: Exception) {}
        stopExpeditionMode()
    }
}
