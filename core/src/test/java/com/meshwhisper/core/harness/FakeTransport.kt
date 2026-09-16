package com.meshwhisper.core.harness

import com.meshwhisper.core.transport.LinkEvent
import com.meshwhisper.core.transport.Transport
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.asSharedFlow
import java.util.concurrent.CopyOnWriteArrayList

/**
 * In-memory virtual transport for testing.
 * Configurable per-link loss ratio, dropped packet accounting, and virtual mesh connectivity.
 * Specified in NEXTGEN/03_VNEXT_TESTS_AND_AGENT_RULES.md §12.2.
 */
class FakeTransport(
    val nodeId: String,
    var mesh: InMemoryMesh? = null
) : Transport {

    data class SentRecord(val targetLinkId: String?, val bytes: ByteArray, val isBroadcast: Boolean) {
        override fun equals(other: Any?): Boolean {
            if (this === other) return true
            if (javaClass != other?.javaClass) return false
            other as SentRecord
            return targetLinkId == other.targetLinkId && isBroadcast == other.isBroadcast && bytes.contentEquals(other.bytes)
        }

        override fun hashCode(): Int {
            var result = targetLinkId?.hashCode() ?: 0
            result = 31 * result + bytes.contentHashCode()
            result = 31 * result + isBroadcast.hashCode()
            return result
        }
    }

    private val _events = MutableSharedFlow<LinkEvent>(replay = 64, extraBufferCapacity = 512)
    override val events: Flow<LinkEvent> = _events.asSharedFlow()

    val sentRecords = CopyOnWriteArrayList<SentRecord>()

    override suspend fun attemptSend(linkId: String, bytes: ByteArray): Boolean {
        sentRecords.add(SentRecord(linkId, bytes.clone(), isBroadcast = false))
        val currentMesh = mesh
        return if (currentMesh != null) {
            currentMesh.routeUnicast(fromNode = nodeId, toNode = linkId, bytes = bytes)
        } else {
            true
        }
    }

    override suspend fun broadcast(bytes: ByteArray, excludeLinkId: String?) {
        sentRecords.add(SentRecord(excludeLinkId, bytes.clone(), isBroadcast = true))
        val currentMesh = mesh
        currentMesh?.routeBroadcast(fromNode = nodeId, bytes = bytes, excludeNode = excludeLinkId)
    }

    suspend fun emitEvent(event: LinkEvent) {
        _events.emit(event)
    }

    suspend fun deliverIncoming(fromLinkId: String, data: ByteArray) {
        _events.emit(LinkEvent.DataReceived(linkId = fromLinkId, data = data.clone()))
    }

    suspend fun notifyConnected(peerId: String) {
        _events.emit(LinkEvent.Connected(linkId = peerId))
    }

    suspend fun notifyDisconnected(peerId: String) {
        _events.emit(LinkEvent.Disconnected(linkId = peerId))
    }

    fun clearHistory() {
        sentRecords.clear()
    }
}
