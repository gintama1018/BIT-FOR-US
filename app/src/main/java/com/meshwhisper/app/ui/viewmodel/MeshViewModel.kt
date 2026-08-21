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

    val myNodeId: Long = cryptoEngine.nodeId
    val myNodeIdHex: String = cryptoEngine.nodeIdHex
    val myFingerprint: String = cryptoEngine.publicFingerprint
    val myPublicKeyHex: String = com.meshwhisper.app.crypto.CryptoEngine.bytesToHex(cryptoEngine.publicKeyBytes)

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

    // Engine & Router State
    val isBluetoothEnabled: StateFlow<Boolean> = bleEngine.isBluetoothEnabled
    val connectedPeersCount: StateFlow<Int> = bleEngine.connectedPeersCount
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

    private val secPrefs = application.getSharedPreferences("meshwhisper_security_settings", android.content.Context.MODE_PRIVATE)
    private val _isAppLockEnabled = MutableStateFlow(secPrefs.getBoolean("app_lock_enabled", false))
    val isAppLockEnabled: StateFlow<Boolean> = _isAppLockEnabled.asStateFlow()

    fun setAppLockEnabled(enabled: Boolean) {
        secPrefs.edit().putBoolean("app_lock_enabled", enabled).apply()
        _isAppLockEnabled.value = enabled
    }

    fun emergencyPanicWipe() {
        viewModelScope.launch {
            database.messageDao().deleteAll()
            database.peerDao().deleteAll()
            database.packetLogDao().deleteAll()
            database.processedPacketDao().deleteAll()
            database.storeForwardDao().purgeExpired(Long.MAX_VALUE)
            cryptoEngine.resetIdentityKeys()
            setAppLockEnabled(false)
            _myAlias.value = "Node"
            router.announcePresence()
        }
    }

    fun clearAllData() {
        viewModelScope.launch {
            database.messageDao().deleteAll()
            database.peerDao().deleteAll()
            database.packetLogDao().deleteAll()
            database.processedPacketDao().deleteAll()
        }
    }

    fun startService() {
        app.startMeshService()
    }

    fun stopService() {
        app.stopMeshService()
    }
}
