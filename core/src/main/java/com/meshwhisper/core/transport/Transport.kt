package com.meshwhisper.core.transport

import kotlinx.coroutines.flow.Flow

/**
 * Link event emitted across transports (BLE GATT, Wi-Fi Direct / Local socket, Virtual FakeTransport).
 */
sealed class LinkEvent {
    data class Connected(val linkId: String, val address: String? = null) : LinkEvent()
    data class Disconnected(val linkId: String) : LinkEvent()
    data class DataReceived(val linkId: String, val data: ByteArray) : LinkEvent() {
        override fun equals(other: Any?): Boolean {
            if (this === other) return true
            if (javaClass != other?.javaClass) return false
            other as DataReceived
            return linkId == other.linkId && data.contentEquals(other.data)
        }

        override fun hashCode(): Int {
            var result = linkId.hashCode()
            result = 31 * result + data.contentHashCode()
            return result
        }
    }
}

/**
 * Transport abstraction for mesh link communications.
 * Specified in NEXTGEN/03_VNEXT_TESTS_AND_AGENT_RULES.md §12.2 and 02_VNEXT_IMPLEMENTATION_PLAN.md §9.1.
 *
 * NOTE: attemptSend returning true means bytes were handed to a radio/link buffer.
 * It is not a delivery guarantee (Agent Rule 9).
 */
interface Transport {
    suspend fun attemptSend(linkId: String, bytes: ByteArray): Boolean
    suspend fun broadcast(bytes: ByteArray, excludeLinkId: String? = null)
    val events: Flow<LinkEvent>
}
