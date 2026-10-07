package com.meshwhisper.app.ui.viewmodel

import android.app.Application
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.PowerManager
import android.provider.Settings
import android.util.Log
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.meshwhisper.app.MeshApplication
import com.meshwhisper.app.data.model.MessageEntity
import com.meshwhisper.app.data.model.PacketLogEntity
import com.meshwhisper.app.data.model.PeerEntity
import com.meshwhisper.app.telemetry.PeerLiveTelemetry
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import com.meshwhisper.core.crypto.PureCryptoEngine
import com.meshwhisper.core.identity.VerificationCandidate

class MeshViewModel(application: Application) : AndroidViewModel(application) {

    private val app = application as MeshApplication
    private val database = app.database
    private val cryptoEngine = app.cryptoEngine
    private val bleEngine = app.bleEngine
    private val router = app.router
    val identityRepository: com.meshwhisper.app.identity.IdentityRepository get() = router.identityRepository

    val identityVersion: StateFlow<Long> = cryptoEngine.identityVersion

    val myNodeId: Long
        get() = cryptoEngine.nodeId
    val myNodeIdHex: String
        get() = cryptoEngine.nodeIdHex
    val myFingerprint: String
        get() = cryptoEngine.publicFingerprint
    val myPublicKeyHex: String
        get() = com.meshwhisper.app.crypto.CryptoEngine.bytesToHex(cryptoEngine.publicKeyBytes)

    private val _myAlias = MutableStateFlow(cryptoEngine.alias)
    val myAlias: StateFlow<String> = _myAlias.asStateFlow()

    // Database Flows
    val peers: StateFlow<List<PeerEntity>> = database.peerDao().getAllPeers()
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())

    val totalPeersCount: StateFlow<Int> = peers.map { it.size }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), 0)

    val broadcastMessages: StateFlow<List<MessageEntity>> = database.messageDao().getBroadcastMessages()
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())

    val recentConversations: StateFlow<List<MessageEntity>> = database.messageDao().getRecentDirectConversations()
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())

    val packetLogs: StateFlow<List<PacketLogEntity>> = database.packetLogDao().getRecentLogs(100)
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())

    val topologyEdges: StateFlow<List<com.meshwhisper.app.data.model.TopologyEdgeEntity>> = database.topologyEdgeDao().getAllEdges()
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())

    val allLocations: StateFlow<List<com.meshwhisper.app.data.model.LastKnownLocationEntity>> = database.locationDao().getAllLocations()
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())

    val breadcrumbManager get() = app.breadcrumbManager
    val locationHelper get() = app.locationHelper

    fun getLocationForPeerFlow(nodeId: Long): Flow<com.meshwhisper.app.data.model.LastKnownLocationEntity?> {
        return database.locationDao().getLocationFlowForNode(nodeId)
    }

    fun getBreadcrumbHistoryForPeer(nodeId: Long, limit: Int = 5): Flow<List<com.meshwhisper.app.data.model.BreadcrumbHistoryEntity>> {
        return database.locationDao().getBreadcrumbHistory(nodeId, limit)
    }

    fun toggleLocationSharingWithContact(nodeId: Long, enabled: Boolean) {
        viewModelScope.launch {
            breadcrumbManager.setContactLocationSharing(nodeId, enabled)
        }
    }

    fun sendManualLocationBeacon(targetPeerNodeId: Long? = null, note: String? = null) {
        viewModelScope.launch {
            breadcrumbManager.sendManualLocationBeacon(targetPeerNodeId, note)
        }
    }

    fun revokeLocationSharing(nodeId: Long) {
        viewModelScope.launch {
            breadcrumbManager.revokeLocationSharing(nodeId)
        }
    }

    fun getMyLocation(): com.meshwhisper.app.location.LocationData? {
        return locationHelper.getLastKnownLocation()
    }

    val sosMessages: StateFlow<List<MessageEntity>> = database.messageDao().getSosMessages()
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())

    private val _activeSosAlert = MutableStateFlow<SosAlertEvent?>(null)
    val activeSosAlert: StateFlow<SosAlertEvent?> = _activeSosAlert.asStateFlow()

    private val _selectedHomingPeerId = MutableStateFlow<Long?>(null)
    val selectedHomingPeerId: StateFlow<Long?> = _selectedHomingPeerId.asStateFlow()

    // Engine & Router State
    val isBluetoothEnabled: StateFlow<Boolean> = bleEngine.isBluetoothEnabled
    val connectedPeersCount: StateFlow<Int> = bleEngine.connectedPeersCount
    val connectedNodeIds: StateFlow<Set<Long>> = combine(
        bleEngine.connectedNodeIds,
        app.wifiEngine.connectedWifiPeers
    ) { bleIds, wifiPeers ->
        bleIds + wifiPeers.keys
    }.stateIn(
        scope = viewModelScope,
        started = SharingStarted.Eagerly,
        initialValue = emptySet()
    )
    val isAdvertising: StateFlow<Boolean> = bleEngine.isAdvertising
    val isScanning: StateFlow<Boolean> = bleEngine.isScanning
    val supportsPeripheral: StateFlow<Boolean> = bleEngine.supportsPeripheral

    val relayedPacketsCount: StateFlow<Int> = router.relayedPacketsCount
    val totalPacketsReceived: StateFlow<Int> = router.totalPacketsReceived
    val peerTelemetry: StateFlow<List<PeerLiveTelemetry>> = router.peerTelemetry

    private val _isBatteryOptimizationIgnored = MutableStateFlow(checkBatteryOptimization())
    val isBatteryOptimizationIgnored: StateFlow<Boolean> = _isBatteryOptimizationIgnored.asStateFlow()

    fun refreshBatteryOptimizationStatus() {
        _isBatteryOptimizationIgnored.value = checkBatteryOptimization()
    }

    private fun checkBatteryOptimization(): Boolean {
        return try {
            val pm = app.getSystemService(Context.POWER_SERVICE) as? PowerManager
            pm?.isIgnoringBatteryOptimizations(app.packageName) ?: false
        } catch (e: Exception) {
            false
        }
    }

    // Wi-Fi Transport State
    val isWifiActive: StateFlow<Boolean> = app.wifiEngine.isWifiActive
    val wifiPeersCount: StateFlow<Int> = app.wifiEngine.connectedPeersCount
    val localIpAddress: StateFlow<String?> = app.wifiEngine.localIpAddress
    val connectedWifiPeers: StateFlow<Map<Long, String>> = app.wifiEngine.connectedWifiPeers

    // Voice Call State & Controls (Milestone 4)
    val callState: StateFlow<com.meshwhisper.app.voice.CallState> = router.voiceCallManager.callState
    val activeCallInfo: StateFlow<com.meshwhisper.app.voice.ActiveCallInfo?> = router.voiceCallManager.activeCallInfo
    val isCallMuted: StateFlow<Boolean> = router.voiceCallManager.isMuted
    val isCallSpeakerOn: StateFlow<Boolean> = router.voiceCallManager.isSpeakerOn
    val callDurationSeconds: StateFlow<Long> = router.voiceCallManager.callDurationSeconds

    fun startVoiceCall(peerNodeId: Long): Boolean {
        return router.voiceCallManager.startCall(peerNodeId)
    }

    fun acceptVoiceCall() {
        router.voiceCallManager.acceptCall()
    }

    fun declineVoiceCall() {
        router.voiceCallManager.declineCall()
    }

    fun endVoiceCall() {
        router.voiceCallManager.endCall()
    }

    fun toggleCallMute() {
        router.voiceCallManager.toggleMute()
    }

    fun toggleCallSpeaker() {
        router.voiceCallManager.toggleSpeaker()
    }

    fun dismissEndedCall() {
        router.voiceCallManager.dismissEndedCall()
    }

    fun getDirectMessagesForPeer(peerNodeId: Long): Flow<List<MessageEntity>> {
        return database.messageDao().getDirectMessagesForPeer(peerNodeId)
    }

    fun sendBroadcast(text: String) {
        if (text.isBlank()) return
        viewModelScope.launch {
            router.sendBroadcastMessage(text.trim())
        }
    }

    fun sendDirect(peerNodeId: Long, text: String) {
        if (text.isBlank()) return
        viewModelScope.launch {
            router.sendDirectMessage(peerNodeId, text.trim())
        }
    }

    fun updateAlias(newAlias: String) {
        if (newAlias.isBlank()) return
        cryptoEngine.alias = newAlias.trim()
        _myAlias.value = cryptoEngine.alias
        app.wifiEngine.updateAlias(cryptoEngine.alias)
        viewModelScope.launch {
            announcePresence()
        }
    }

    fun announcePresence(latitude: Double? = null, longitude: Double? = null, accuracyMeters: Float = 0f) {
        viewModelScope.launch {
            val loc = if (latitude != null && longitude != null) {
                com.meshwhisper.app.location.LocationData(latitude, longitude, accuracyMeters)
            } else {
                app.locationHelper.getLastKnownLocation()
            }
            router.announcePresence(loc?.latitude, loc?.longitude, loc?.accuracy ?: 0f)
        }
    }

    fun refreshLocationAndBroadcast() {
        viewModelScope.launch {
            val loc = app.locationHelper.getCurrentLocation(timeoutMs = 4000L) ?: app.locationHelper.getLastKnownLocation()
            if (loc != null) {
                router.announcePresence(loc.latitude, loc.longitude, loc.accuracy)
            } else {
                router.announcePresence()
            }
        }
    }

    fun sendSosBroadcast(text: String, latitude: Double? = null, longitude: Double? = null, accuracyMeters: Float = 0f) {
        if (text.isBlank()) return
        viewModelScope.launch {
            val loc = if (latitude != null && longitude != null) {
                com.meshwhisper.app.location.LocationData(latitude, longitude, accuracyMeters, timestamp = System.currentTimeMillis())
            } else {
                app.locationHelper.getCurrentLocation(timeoutMs = 3000L) ?: app.locationHelper.getLastKnownLocation()
            }
            router.sendSosBroadcast(
                text = text.trim(),
                latitude = loc?.latitude,
                longitude = loc?.longitude,
                accuracyMeters = loc?.accuracy ?: 0f,
                locationFixTimestamp = loc?.timestamp ?: System.currentTimeMillis()
            )
        }
    }

    fun dismissSosAlert() {
        _activeSosAlert.value = null
    }

    fun selectHomingPeer(peerId: Long?) {
        _selectedHomingPeerId.value = peerId
    }

    fun checkEmergencyKeywords(input: String): Boolean {
        if (input.isBlank()) return false
        val regex = Regex("""\b(help|trapped|sos|emergency|fire|medical|injured|bleeding|earthquake|collapse|bachao|madad|danger)\b""", RegexOption.IGNORE_CASE)
        return regex.containsMatchIn(input)
    }

    fun formatLastSeen(timestamp: Long): String {
        if (timestamp <= 0L) return "Never seen"
        val diff = System.currentTimeMillis() - timestamp
        val seconds = diff / 1000
        val minutes = seconds / 60
        val hours = minutes / 60
        val days = hours / 24
        return when {
            seconds < 15 -> "Active just now"
            seconds < 60 -> "Active ${seconds}s ago"
            minutes < 60 -> "Seen ${minutes}m ago"
            hours < 24 -> "Seen ${hours}h ago"
            else -> "Seen ${days}d ago"
        }
    }

    fun toggleBlockPeer(peerNodeId: Long, isBlocked: Boolean) {
        viewModelScope.launch {
            database.peerDao().setPeerBlocked(peerNodeId, isBlocked)
        }
    }

    fun acknowledgeSafetyWarning(peerNodeId: Long) {
        viewModelScope.launch {
            val peer = database.peerDao().getPeerById(peerNodeId)
            if (peer != null) {
                database.peerDao().markKeyChanged(peerNodeId, hasChanged = false, prevFp = null)
            }
        }
    }

    fun registerScannedPeer(nodeId: Long, alias: String, publicKeyHex: String) {
        viewModelScope.launch {
            // Frozen Protocol §2.1-§2.3, Phase P2:
            // The only canonical identity authority is IK_pk -> identityHash -> nodeId64.
            // Raw X25519 public key (EK) cannot establish canonical vNext identity or trust.
            // Legacy registration fails closed.
            android.util.Log.e("MeshViewModel",
                "QR peer registration REJECTED: Raw X25519 key cannot establish canonical vNext identity (vNext §2.1).")
            return@launch
        }
    }

    // Dynamic Mesh Channel Configuration & Key Derivation
    val activeChannelName: StateFlow<String> = cryptoEngine.activeChannelNameFlow
    val isChannelConfidential: StateFlow<Boolean> = cryptoEngine.isChannelConfidentialFlow

    fun setActiveChannel(name: String, passphrase: String?) {
        cryptoEngine.setActiveChannel(name, passphrase)
    }

    fun resetToPublicEmergencyChannel() {
        cryptoEngine.resetToPublicEmergencyChannel()
    }

    fun getChannelQrContent(): String {
        return cryptoEngine.generateChannelQr(cryptoEngine.activeChannelName, cryptoEngine.activeChannelPassphrase ?: "")
    }

    fun getNodeQrContent(alias: String): String {
        return cryptoEngine.generateNodeQr(alias)
    }

    suspend fun handleScannedQrContent(
        content: String,
        targetPeerNodeId: Long? = null
    ): QrScanResult {
        val trimmed = content.trim()

        // Handle vNext QR URI format: meshwhisper://node/v2?...
        if (trimmed.startsWith("meshwhisper://node/v2")) {
            val candidateResult = prepareCameraQrVerification(trimmed, targetPeerNodeId ?: 0L)
            return if (candidateResult.isSuccess) {
                QrScanResult.VerificationReady(candidateResult.getOrThrow())
            } else {
                val err = candidateResult.exceptionOrNull()?.message ?: "Invalid QR verification candidate"
                if (err.contains("claimed") || err.contains("expected") || err.contains("mismatch")) {
                    val qrData = com.meshwhisper.core.identity.NodeQrCodec.decode(trimmed)
                    QrScanResult.KeyMismatch(
                        claimedNodeId = qrData?.nodeId64 ?: 0L,
                        expectedNodeId = targetPeerNodeId ?: 0L,
                        alias = qrData?.alias ?: "Peer"
                    )
                } else {
                    QrScanResult.Invalid(err)
                }
            }
        }

        val uri = try { android.net.Uri.parse(trimmed) } catch (_: Exception) { null }
            ?: return QrScanResult.Invalid("Unparseable QR code format")

        if (uri.scheme != "meshwhisper") {
            return QrScanResult.Invalid("Not a valid MeshWhisper QR format")
        }

        return when (uri.host) {
            "node" -> {
                QrScanResult.Invalid("Security Warning: Legacy QR carries raw encryption key. Cannot establish canonical vNext identity (vNext §2.1).")
            }
            "channel" -> {
                val channelName = uri.getQueryParameter("name") ?: "Team Channel"
                val passphrase = uri.getQueryParameter("pass")
                if (!passphrase.isNullOrBlank()) {
                    setActiveChannel(channelName, passphrase)
                    QrScanResult.ChannelConfigured(channelName, isConfidential = true)
                } else {
                    setActiveChannel(channelName, null)
                    QrScanResult.ChannelConfigured(channelName, isConfidential = false)
                }
            }
            else -> QrScanResult.Invalid("Unknown MeshWhisper action: ${uri.host}")
        }
    }

    suspend fun getSafetyNumberForPeer(peerNodeId: Long): String? {
        val peer = database.peerDao().getPeerById(peerNodeId) ?: return null
        val hashHex = peer.identityHashHex ?: return null
        return try {
            val peerHash = PureCryptoEngine.hexToBytes(hashHex)
            PureCryptoEngine.computeSafetyNumber(cryptoEngine.identityHash, peerHash)
        } catch (_: Exception) {
            null
        }
    }

    fun getMyFingerprintHex(): String = PureCryptoEngine.bytesToHex(cryptoEngine.ikPublicKeyBytes)

    suspend fun getPeerFingerprintHex(peerNodeId: Long): String? {
        val peer = database.peerDao().getPeerById(peerNodeId) ?: return null
        val idEntity = peer.identityHashHex?.let { database.identityDao().getByIdentityHash(it) }
        return if (idEntity != null) {
            idEntity.ikPubHex
        } else {
            peer.fingerprint
        }
    }

    fun prepareCameraQrVerification(
        scannedContent: String,
        targetPeerNodeId: Long = 0L
    ): Result<VerificationCandidate> {
        return identityRepository.prepareCameraQrVerification(scannedContent, targetPeerNodeId)
    }

    suspend fun confirmSafetyNumber(candidate: VerificationCandidate): Result<Unit> {
        return identityRepository.confirmSafetyNumber(candidate)
    }

    suspend fun importPeerUri(uriString: String): Result<com.meshwhisper.app.data.model.IdentityEntity> {
        return identityRepository.importPeerUri(uriString)
    }

    fun blockPeer(identityHashHex: String) {
        viewModelScope.launch {
            identityRepository.blockPeer(identityHashHex)
        }
    }

    fun unblockPeer(identityHashHex: String) {
        viewModelScope.launch {
            identityRepository.unblockPeer(identityHashHex)
        }
    }

    fun acknowledgeKeyChange(nodeId: Long) {
        viewModelScope.launch {
            identityRepository.acknowledgeKeyChange(nodeId)
        }
    }

    fun isNodeConflicted(nodeId: Long): Boolean {
        return identityRepository.isNodeConflicted(nodeId)
    }

    private val secPrefs = application.getSharedPreferences("meshwhisper_security_settings", android.content.Context.MODE_PRIVATE)
    private val _isAppLockEnabled = MutableStateFlow(secPrefs.getBoolean("app_lock_enabled", false))
    val isAppLockEnabled: StateFlow<Boolean> = _isAppLockEnabled.asStateFlow()

    fun setAppLockEnabled(enabled: Boolean) {
        secPrefs.edit().putBoolean("app_lock_enabled", enabled).apply()
        _isAppLockEnabled.value = enabled
    }

    // Avatar & Profile Management
    private val _myAvatarUri = MutableStateFlow<String?>(
        java.io.File(application.filesDir, "avatars/my_avatar.jpg").let { if (it.exists()) it.absolutePath else null }
    )
    val myAvatarUri: StateFlow<String?> = _myAvatarUri.asStateFlow()

    val myProfileFlow: StateFlow<com.meshwhisper.app.data.model.ProfileEntity?> = database.profileDao().getProfileFlow(cryptoEngine.nodeId)
        .stateIn(viewModelScope, SharingStarted.Eagerly, null)

    val myBio: StateFlow<String> = myProfileFlow.map { it?.bio ?: "" }
        .stateIn(viewModelScope, SharingStarted.Eagerly, "")

    val myProfileVersion: StateFlow<Long> = myProfileFlow.map { it?.version ?: 1L }
        .stateIn(viewModelScope, SharingStarted.Eagerly, 1L)

    fun updateMyProfile(displayName: String, bio: String) {
        viewModelScope.launch {
            val cleanName = displayName.trim().ifEmpty { "Node-${myNodeIdHex.takeLast(4)}" }
            val cleanBio = bio.trim()
            cryptoEngine.alias = cleanName
            _myAlias.value = cleanName
            router.broadcastProfileUpdate(cleanName, cleanBio)
        }
    }

    fun getPeerProfileFlow(nodeId: Long): Flow<com.meshwhisper.app.data.model.ProfileEntity?> {
        return database.profileDao().getProfileFlow(nodeId)
    }

    fun updateMyAvatar(context: android.content.Context, uri: android.net.Uri) {
        viewModelScope.launch {
            val bytes = com.meshwhisper.app.media.MediaCompressor.compressAvatar(context, uri)
            if (bytes != null) {
                val avatarDir = java.io.File(app.filesDir, "avatars").also { if (!it.exists()) it.mkdirs() }
                val avatarFile = java.io.File(avatarDir, "my_avatar.jpg")
                avatarFile.writeBytes(bytes)
                _myAvatarUri.value = avatarFile.absolutePath
                val currentBio = myBio.value
                router.broadcastProfileUpdate(myAlias.value, currentBio, bytes)
                router.announcePresence()
            }
        }
    }

    fun removeMyAvatar() {
        viewModelScope.launch {
            val avatarFile = java.io.File(app.filesDir, "avatars/my_avatar.jpg")
            if (avatarFile.exists()) avatarFile.delete()
            _myAvatarUri.value = null
            val currentBio = myBio.value
            router.broadcastProfileUpdate(myAlias.value, currentBio, ByteArray(0))
            router.announcePresence()
        }
    }

    // Notification State & On-Chat / Off-Chat Tracking
    val currentOpenChatNodeId = MutableStateFlow<Long?>(null) // -1L = Public, >0 = Direct peer, null = None

    private val notifPrefs = application.getSharedPreferences("meshwhisper_notification_prefs", android.content.Context.MODE_PRIVATE)
    private val _isNotificationsEnabled = MutableStateFlow(notifPrefs.getBoolean("notifications_enabled", true))
    val isNotificationsEnabled: StateFlow<Boolean> = _isNotificationsEnabled.asStateFlow()

    private val _showNotificationPreviews = MutableStateFlow(notifPrefs.getBoolean("notification_previews", false))
    val showNotificationPreviews: StateFlow<Boolean> = _showNotificationPreviews.asStateFlow()

    fun setNotificationsEnabled(enabled: Boolean) {
        notifPrefs.edit().putBoolean("notifications_enabled", enabled).apply()
        _isNotificationsEnabled.value = enabled
    }

    fun setShowNotificationPreviews(enabled: Boolean) {
        notifPrefs.edit().putBoolean("notification_previews", enabled).apply()
        _showNotificationPreviews.value = enabled
    }

    fun setPeerMuted(peerNodeId: Long, isMuted: Boolean) {
        viewModelScope.launch {
            database.peerDao().setPeerMuted(peerNodeId, isMuted)
        }
    }

    fun setCurrentOpenChat(nodeId: Long?) {
        currentOpenChatNodeId.value = nodeId
        if (nodeId != null) {
            com.meshwhisper.app.service.MessageNotifier.clearNotification(app, nodeId)
        }
    }

    // Typing State
    private val _typingPeers = MutableStateFlow<Map<Long, Long>>(emptyMap()) // peerNodeId -> timestamp
    val typingPeers: StateFlow<Map<Long, Long>> = _typingPeers.asStateFlow()

    fun sendTyping(recipientNodeId: Long, isTyping: Boolean) {
        viewModelScope.launch {
            router.sendTypingIndicator(recipientNodeId, isTyping)
        }
    }

    val audioRecorder = com.meshwhisper.app.media.AudioRecorder(application)
    val audioPlayer = com.meshwhisper.app.media.AudioPlayer()

    val transferStates = router.mediaTransferManager.transferStates
    val tileUpdates = router.mediaTransferManager.tileUpdates

    fun sendMediaDirect(
        recipientNodeId: Long,
        mediaType: com.meshwhisper.app.data.model.MediaType,
        mediaBytes: ByteArray,
        caption: String = "",
        durationMs: Long = 0L,
        originalFileName: String = "",
        previewBytes: ByteArray = ByteArray(0),
        gridCols: Int = 1,
        gridRows: Int = 1,
        imageWidthPx: Int = 0,
        imageHeightPx: Int = 0,
        paddedTileByteLengths: List<Int> = emptyList()
    ) {
        viewModelScope.launch {
            router.sendMediaDirect(
                recipientNodeId,
                mediaType,
                mediaBytes,
                caption,
                durationMs,
                originalFileName,
                previewBytes,
                gridCols,
                gridRows,
                imageWidthPx,
                imageHeightPx,
                paddedTileByteLengths
            )
        }
    }

    fun sendMediaBroadcast(
        mediaType: com.meshwhisper.app.data.model.MediaType,
        mediaBytes: ByteArray,
        caption: String = "",
        durationMs: Long = 0L,
        originalFileName: String = "",
        previewBytes: ByteArray = ByteArray(0),
        gridCols: Int = 1,
        gridRows: Int = 1,
        imageWidthPx: Int = 0,
        imageHeightPx: Int = 0,
        paddedTileByteLengths: List<Int> = emptyList()
    ) {
        viewModelScope.launch {
            router.sendMediaBroadcast(
                mediaType,
                mediaBytes,
                caption,
                durationMs,
                originalFileName,
                previewBytes,
                gridCols,
                gridRows,
                imageWidthPx,
                imageHeightPx,
                paddedTileByteLengths
            )
        }
    }

    fun cancelTransfer(mediaId: java.util.UUID) {
        viewModelScope.launch {
            router.mediaTransferManager.cancelTransfer(mediaId)
        }
    }

    fun retryTransfer(mediaId: java.util.UUID) {
        viewModelScope.launch {
            router.mediaTransferManager.retryTransfer(mediaId)
        }
    }

    fun emergencyPanicWipe(killProcess: Boolean = true): kotlinx.coroutines.Job {
        android.util.Log.w("MeshViewModel", "EMERGENCY PANIC WIPE INITIATED — delegating to application-scoped coroutine per C-26")
        return app.triggerPanicWipe(killProcess = killProcess)
    }

    fun clearAllData() {
        viewModelScope.launch {
            audioPlayer.stop()
            database.messageDao().deleteAll()
            database.peerDao().deleteAll()
            database.packetLogDao().deleteAll()
            database.processedPacketDao().deleteAll()
            database.topologyEdgeDao().deleteAll()
            val mediaDir = java.io.File(app.filesDir, "media")
            mediaDir.deleteRecursively()
            val avatarDir = java.io.File(app.filesDir, "avatars")
            avatarDir.deleteRecursively()
            _myAvatarUri.value = null
        }
    }

    private val appPrefs = application.getSharedPreferences(com.meshwhisper.app.service.MeshForegroundService.PREFS_NAME, android.content.Context.MODE_PRIVATE)
    private val _isBackgroundRelayEnabled = MutableStateFlow(appPrefs.getBoolean(com.meshwhisper.app.service.MeshForegroundService.KEY_BACKGROUND_RELAY, true))
    val isBackgroundRelayEnabled: StateFlow<Boolean> = _isBackgroundRelayEnabled.asStateFlow()

    private val prefListener = android.content.SharedPreferences.OnSharedPreferenceChangeListener { _, key ->
        if (key == com.meshwhisper.app.service.MeshForegroundService.KEY_BACKGROUND_RELAY) {
            _isBackgroundRelayEnabled.value = appPrefs.getBoolean(com.meshwhisper.app.service.MeshForegroundService.KEY_BACKGROUND_RELAY, true)
        }
    }

    init {
        appPrefs.registerOnSharedPreferenceChangeListener(prefListener)

        // Incoming typing indicators with automatic 4-second expiry
        router.onTypingIndicatorListener = { senderId, isTyping ->
            val now = System.currentTimeMillis()
            val map = _typingPeers.value.toMutableMap()
            if (isTyping) {
                map[senderId] = now
                _typingPeers.value = map
                viewModelScope.launch {
                    kotlinx.coroutines.delay(4000L)
                    val current = _typingPeers.value
                    if (current[senderId] == now) {
                        val updated = current.toMutableMap()
                        updated.remove(senderId)
                        _typingPeers.value = updated
                    }
                }
            } else {
                map.remove(senderId)
                _typingPeers.value = map
            }
        }

        // WhatsApp-Style Smart Notifications (On-Chat vs Off-Chat)
        router.onIncomingMessageListener = { senderId, senderAlias, text, isBroadcast ->
            if (_isNotificationsEnabled.value) {
                viewModelScope.launch {
                    val peer = database.peerDao().getPeerById(senderId)
                    val isMuted = peer?.isMuted == true
                    if (!isMuted) {
                        val activeChat = currentOpenChatNodeId.value
                        val targetChat = if (isBroadcast) -1L else senderId
                        val isOnChat = (activeChat == targetChat)
                        if (!isOnChat) {
                            com.meshwhisper.app.service.MessageNotifier.showMessageNotification(
                                context = app,
                                senderId = senderId,
                                senderAlias = senderAlias,
                                text = text,
                                isBroadcast = isBroadcast,
                                showPreview = _showNotificationPreviews.value,
                                avatarUri = peer?.avatarUri
                            )
                        }
                    }
                }
            }
        }

        // Emergency SOS Broadcast Listener
        router.onSosAlertReceivedListener = { senderId, senderAlias, text, lat, lon, fixTimestamp ->
            _activeSosAlert.value = SosAlertEvent(
                senderId = senderId,
                senderAlias = senderAlias,
                text = text,
                latitude = lat,
                longitude = lon,
                fixTimestamp = fixTimestamp,
                timestamp = System.currentTimeMillis()
            )
        }
    }

    fun setBackgroundRelayEnabled(enabled: Boolean) {
        appPrefs.edit().putBoolean(com.meshwhisper.app.service.MeshForegroundService.KEY_BACKGROUND_RELAY, enabled).apply()
        _isBackgroundRelayEnabled.value = enabled
        val intent = android.content.Intent(app, com.meshwhisper.app.service.MeshForegroundService::class.java).apply {
            action = if (enabled) com.meshwhisper.app.service.MeshForegroundService.ACTION_RESUME_RELAY else com.meshwhisper.app.service.MeshForegroundService.ACTION_PAUSE_RELAY
        }
        try {
            if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.O) {
                app.startForegroundService(intent)
            } else {
                app.startService(intent)
            }
        } catch (e: Exception) {
            android.util.Log.e("MeshViewModel", "Failed to dispatch relay intent: ${e.message}")
        }
    }

    fun clearLogs() {
        viewModelScope.launch {
            database.packetLogDao().deleteAll()
        }
    }

    fun requestBatteryOptimizationExemption(context: Context) {
        try {
            val intent = Intent(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS).apply {
                data = Uri.parse("package:${context.packageName}")
                flags = Intent.FLAG_ACTIVITY_NEW_TASK
            }
            context.startActivity(intent)
        } catch (e: Exception) {
            try {
                val fallbackIntent = Intent(Settings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS).apply {
                    flags = Intent.FLAG_ACTIVITY_NEW_TASK
                }
                context.startActivity(fallbackIntent)
            } catch (e2: Exception) {
                Log.e("MeshViewModel", "Could not open battery optimization settings: ${e2.message}")
            }
        }
    }

    fun clearPacketJournal(context: Context) {
        viewModelScope.launch {
            router.clearJournal(context)
            clearLogs()
        }
    }

    fun sharePacketJournal(context: Context, onExportCompleted: ((String) -> Unit)? = null) {
        viewModelScope.launch {
            val exported = router.exportJournal(context)
            if (exported != null) {
                val (file, uri) = exported
                val sendIntent = Intent(Intent.ACTION_SEND).apply {
                    type = "text/csv"
                    putExtra(Intent.EXTRA_STREAM, uri)
                    putExtra(Intent.EXTRA_SUBJECT, "MeshWhisper Packet Journal (${file.name})")
                    putExtra(Intent.EXTRA_TEXT, "Attached is the field telemetry packet journal dump for MeshWhisper.")
                    addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                }
                val chooser = Intent.createChooser(sendIntent, "Export Packet Journal").apply {
                    flags = Intent.FLAG_ACTIVITY_NEW_TASK
                }
                context.startActivity(chooser)
                onExportCompleted?.invoke(file.absolutePath)
            }
        }
    }

    override fun onCleared() {
        super.onCleared()
        audioPlayer.stop()
        appPrefs.unregisterOnSharedPreferenceChangeListener(prefListener)
    }

    fun startService() {
        if (isBackgroundRelayEnabled.value) {
            app.startMeshService()
        }
    }

    fun stopService() {
        app.stopMeshService()
    }

    fun restartDiscovery() {
        viewModelScope.launch {
            app.bleEngine.setLowLatencyMode(true)
            app.bleEngine.restartDiscovery()
            app.wifiEngine.start(cryptoEngine.nodeId, cryptoEngine.alias)
        }
    }
}

data class SosAlertEvent(
    val senderId: Long,
    val senderAlias: String,
    val text: String,
    val latitude: Double? = null,
    val longitude: Double? = null,
    val fixTimestamp: Long? = null,
    val timestamp: Long = System.currentTimeMillis()
) {
    val isLocationStale: Boolean
        get() = fixTimestamp != null && (System.currentTimeMillis() - fixTimestamp) > 10 * 60 * 1000L
}

sealed class QrScanResult {
    data class VerificationReady(
        val candidate: VerificationCandidate
    ) : QrScanResult()

    data class ChannelConfigured(
        val channelName: String,
        val isConfidential: Boolean
    ) : QrScanResult()

    data class KeyMismatch(
        val claimedNodeId: Long,
        val expectedNodeId: Long,
        val alias: String
    ) : QrScanResult()

    data class Invalid(val reason: String) : QrScanResult()
}
