package com.meshwhisper.app.ui.viewmodel

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.meshwhisper.app.MeshApplication
import com.meshwhisper.app.data.model.MessageEntity
import com.meshwhisper.app.data.model.PacketLogEntity
import com.meshwhisper.app.data.model.PeerEntity
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

class MeshViewModel(application: Application) : AndroidViewModel(application) {

    private val app = application as MeshApplication
    private val database = app.database
    private val cryptoEngine = app.cryptoEngine
    private val bleEngine = app.bleEngine
    private val router = app.router

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

    val broadcastMessages: StateFlow<List<MessageEntity>> = database.messageDao().getBroadcastMessages()
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())

    val recentConversations: StateFlow<List<MessageEntity>> = database.messageDao().getRecentDirectConversations()
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())

    val packetLogs: StateFlow<List<PacketLogEntity>> = database.packetLogDao().getRecentLogs(100)
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())

    val topologyEdges: StateFlow<List<com.meshwhisper.app.data.model.TopologyEdgeEntity>> = database.topologyEdgeDao().getAllEdges()
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())

    // Engine & Router State
    val isBluetoothEnabled: StateFlow<Boolean> = bleEngine.isBluetoothEnabled
    val connectedPeersCount: StateFlow<Int> = bleEngine.connectedPeersCount
    val connectedNodeIds: StateFlow<Set<Long>> = bleEngine.connectedNodeIds
    val isAdvertising: StateFlow<Boolean> = bleEngine.isAdvertising
    val isScanning: StateFlow<Boolean> = bleEngine.isScanning
    val supportsPeripheral: StateFlow<Boolean> = bleEngine.supportsPeripheral

    val relayedPacketsCount: StateFlow<Int> = router.relayedPacketsCount
    val totalPacketsReceived: StateFlow<Int> = router.totalPacketsReceived

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
        viewModelScope.launch {
            router.announcePresence()
        }
    }

    fun announcePresence() {
        viewModelScope.launch {
            router.announcePresence()
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
            val existing = database.peerDao().getPeerById(nodeId)
            val pubBytes = com.meshwhisper.app.crypto.CryptoEngine.hexToBytes(publicKeyHex)
            val fp = com.meshwhisper.app.crypto.CryptoEngine.generateFingerprint(pubBytes)

            // Mirror MeshRouter.handlePeerAnnounce: detect key rotation before writing to DB.
            // A crafted deep link or NFC tag must not silently overwrite a previously trusted key.
            val hasKeyChanged = (existing != null && existing.publicKeyHex != publicKeyHex)
            val prevFp = if (hasKeyChanged) existing?.fingerprint else existing?.previousFingerprint

            if (hasKeyChanged) {
                android.util.Log.w("MeshViewModel",
                    "TOFU ALERT via QR: Key changed for peer $nodeId! (Old: ${existing?.fingerprint}, New: $fp)")
                // Invalidate any cached session keys derived from the old peer public key.
                cryptoEngine.invalidateSessionKey(nodeId)
            }

            val entity = com.meshwhisper.app.data.model.PeerEntity(
                nodeId = nodeId,
                alias = alias,
                publicKeyHex = publicKeyHex,
                fingerprint = fp,
                lastSeen = System.currentTimeMillis(),
                isDirect = false,
                rssi = existing?.rssi ?: 0,
                hopCount = existing?.hopCount ?: 1,
                isBlocked = existing?.isBlocked ?: false,
                // Carry forward any previously set hasKeyChanged flag OR set it now if the QR
                // presents a new key. This surfaces the safety-number banner on next open.
                hasKeyChanged = hasKeyChanged || (existing?.hasKeyChanged ?: false),
                previousFingerprint = prevFp
            )
            database.peerDao().insertOrUpdate(entity)
        }
    }

    private val secPrefs = application.getSharedPreferences("meshwhisper_security_settings", android.content.Context.MODE_PRIVATE)
    private val _isAppLockEnabled = MutableStateFlow(secPrefs.getBoolean("app_lock_enabled", false))
    val isAppLockEnabled: StateFlow<Boolean> = _isAppLockEnabled.asStateFlow()

    fun setAppLockEnabled(enabled: Boolean) {
        secPrefs.edit().putBoolean("app_lock_enabled", enabled).apply()
        _isAppLockEnabled.value = enabled
    }

    // Avatar Management
    private val _myAvatarUri = MutableStateFlow<String?>(
        java.io.File(application.filesDir, "avatars/my_avatar.jpg").let { if (it.exists()) it.absolutePath else null }
    )
    val myAvatarUri: StateFlow<String?> = _myAvatarUri.asStateFlow()

    fun updateMyAvatar(context: android.content.Context, uri: android.net.Uri) {
        viewModelScope.launch {
            val bytes = com.meshwhisper.app.media.MediaCompressor.compressAvatar(context, uri)
            if (bytes != null) {
                val avatarDir = java.io.File(app.filesDir, "avatars").also { if (!it.exists()) it.mkdirs() }
                val avatarFile = java.io.File(avatarDir, "my_avatar.jpg")
                avatarFile.writeBytes(bytes)
                _myAvatarUri.value = avatarFile.absolutePath
                router.announcePresence()
            }
        }
    }

    fun removeMyAvatar() {
        viewModelScope.launch {
            val avatarFile = java.io.File(app.filesDir, "avatars/my_avatar.jpg")
            if (avatarFile.exists()) avatarFile.delete()
            _myAvatarUri.value = null
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

    fun sendMediaDirect(
        recipientNodeId: Long,
        mediaType: com.meshwhisper.app.data.model.MediaType,
        mediaBytes: ByteArray,
        caption: String = "",
        durationMs: Long = 0L
    ) {
        viewModelScope.launch {
            router.sendMediaDirect(recipientNodeId, mediaType, mediaBytes, caption, durationMs)
        }
    }

    fun sendMediaBroadcast(
        mediaType: com.meshwhisper.app.data.model.MediaType,
        mediaBytes: ByteArray,
        caption: String = "",
        durationMs: Long = 0L
    ) {
        viewModelScope.launch {
            router.sendMediaBroadcast(mediaType, mediaBytes, caption, durationMs)
        }
    }

    fun emergencyPanicWipe() {
        viewModelScope.launch {
            audioPlayer.stop()
            com.meshwhisper.app.data.MeshDatabase.executeSecureWipe(app, database)
            // Clean local media and avatar directories
            val mediaDir = java.io.File(app.filesDir, "media")
            mediaDir.deleteRecursively()
            val avatarDir = java.io.File(app.filesDir, "avatars")
            avatarDir.deleteRecursively()
            _myAvatarUri.value = null
            cryptoEngine.regenerateIdentity()
            setAppLockEnabled(false)
            _myAlias.value = "Node-${cryptoEngine.nodeIdHex.takeLast(4)}"
            router.announcePresence()
        }
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

        // Incoming typing indicators
        router.onTypingIndicatorListener = { senderId, isTyping ->
            val now = System.currentTimeMillis()
            val map = _typingPeers.value.toMutableMap()
            if (isTyping) {
                map[senderId] = now
            } else {
                map.remove(senderId)
            }
            _typingPeers.value = map
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
}
