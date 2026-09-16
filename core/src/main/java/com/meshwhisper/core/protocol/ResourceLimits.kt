package com.meshwhisper.core.protocol

/**
 * Single canonical source of truth for all protocol and resource limits in MeshWhisper vNext.
 * Defined in NEXTGEN/01_VNEXT_PROTOCOL_FROZEN.md §8 and NEXTGEN/02_VNEXT_IMPLEMENTATION_PLAN.md §9.1.
 */
object ResourceLimits {
    // Protocol / Packet bounds
    const val PROTOCOL_VERSION = 1
    const val HEADER_SIZE = 40
    const val AUTH_TAG_SIZE = 16
    const val OVERHEAD_SIZE = HEADER_SIZE + AUTH_TAG_SIZE // 56 bytes
    const val MAX_PAYLOAD_SIZE = 2048
    const val MIN_PACKET_SIZE = OVERHEAD_SIZE // 56 bytes (payloadLen == 0)
    const val MAX_PACKET_SIZE = OVERHEAD_SIZE + MAX_PAYLOAD_SIZE // 2104 bytes
    const val CHUNK_PAYLOAD_SIZE = 320

    // Signatures & Transcripts
    const val HOP_SIGNATURE_SIZE = 64
    const val SIG_TRANSCRIPT_SIZE = 115
    const val IBC_TRANSCRIPT_SIZE = 83
    const val PURPOSE_CONTENT: Byte = 0x02
    const val PURPOSE_IBC: Byte = 0x03
    const val PURPOSE_LINK: Byte = 0x04

    // BLE bounds
    const val MAX_BLE_FRAME_SIZE = 512
    const val BLE_FRAMES_PER_SEC_PER_LINK = 50
    const val MAX_BLE_REASSEMBLY_SESSIONS_PER_LINK = 2
    const val MAX_BLE_REASSEMBLY_SESSIONS_GLOBAL = 16
    const val BLE_REASSEMBLY_TIMEOUT_SEC = 10L
    const val MAX_BLE_GATT_LINKS = 5

    // Wi-Fi bounds
    const val MAX_WIFI_TCP_FRAME_SIZE = 2104
    const val WIFI_TCP_FRAMES_PER_SEC_PER_LINK = 50
    const val WIFI_MAX_OVERSIZE_FRAMES_BEFORE_CLOSE = 3
    const val WIFI_HELLO_SIZE = 169
    const val WIFI_PENDING_HANDSHAKES_PER_LINK = 1
    const val WIFI_PENDING_HANDSHAKES_GLOBAL = 8
    const val WIFI_PENDING_HANDSHAKE_TIMEOUT_SEC = 3L
    const val MAX_WIFI_AUTHENTICATED_SESSIONS = 5
    const val WIFI_SESSION_IDLE_TIMEOUT_SEC = 120L

    // UDP bounds
    const val MAX_UDP_BEACON_SIZE = 128
    const val UDP_BEACONS_PER_SEC_PER_IP = 10
    const val UDP_BEACONS_PER_SEC_GLOBAL = 100
    const val UDP_MESH_PACKETS_ALLOWED = 0 // Forbidden

    // Cryptographic verification rate limits
    const val ED25519_VERIFY_BUDGET_PER_SEC_LINK = 32
    const val ED25519_VERIFY_BUDGET_PER_SEC_GLOBAL = 256

    // Media transfer bounds
    const val MAX_MEDIA_CHUNK_PAYLOAD = 320
    const val MAX_MEDIA_CHUNKS = 4096
    const val MAX_MEDIA_SIZE_BYTES = 20 * 1024 * 1024L // 20 MB
    const val MAX_MEDIA_SESSIONS_INBOUND_PER_IDENTITY = 2
    const val MAX_MEDIA_SESSIONS_INBOUND_GLOBAL = 8
    const val MEDIA_SESSION_IDLE_TIMEOUT_SEC = 300L
    const val MEDIA_BANDWIDTH_PER_IDENTITY_BYTES_PER_HOUR = 8 * 1024 * 1024L // 8 MB
    const val MEDIA_BANDWIDTH_GLOBAL_BYTES_PER_HOUR = 32 * 1024 * 1024L // 32 MB
    const val MEDIA_PREVIEW_MAX_BYTES = 512
    const val MEDIA_FILENAME_MAX_BYTES = 64
    const val MEDIA_CAPTION_MAX_BYTES = 200

    // Topology bounds
    const val MAX_NEIGHBORS_PER_ANNOUNCE = 16
    const val TOPOLOGY_NEIGHBOR_FRESH_SEC = 90L
    const val TOPOLOGY_NEIGHBOR_EVICT_SEC = 180L
    const val MAX_TOPOLOGY_ORIGINS = 512
    const val TOPOLOGY_PRUNE_INTERVAL_SEC = 30L

    // Storage capacity bounds
    const val MAX_IDENTITIES_PEERS = 1024
    const val MAX_PROFILES = 1024
    const val MIN_PROFILE_PAYLOAD_SIZE = 55
    const val MAX_PROFILE_PAYLOAD_SIZE = 207
    const val MAX_LAST_KNOWN_LOCATIONS = 512
    const val MAX_LOCATION_PAYLOAD_SIZE = 28
    const val MAX_MESSAGES_PER_CONVERSATION = 10000
    const val MAX_MESSAGES_GLOBAL = 100000
    const val MAX_STORE_FORWARD_PER_RECIPIENT = 50
    const val MAX_STORE_FORWARD_GLOBAL = 500
    const val MAX_STORE_FORWARD_OWN = 300
    const val MAX_STORE_FORWARD_RELAYED = 200
    const val STORE_FORWARD_RETENTION_HOURS = 24L
    const val MAX_PROCESSED_PACKETS_ROWS = 50000
    const val PROCESSED_PACKETS_PURGE_HOURS = 24L
    const val PACKET_LOGS_RING_CAPACITY = 500

    // Egress queue & QoS
    const val EGRESS_QUEUE_MAX_PER_TIER = 100
    const val EGRESS_QUEUE_RELAY_SLOTS_PER_IDENTITY = 25
    const val EGRESS_PACKET_MAX_LIFETIME_MS = 30000L

    // Rate limits for requests
    const val SOS_ACCEPTANCE_MAX_PER_10_MIN = 3
    const val MAX_SOS_PAYLOAD_SIZE = 675
    const val PROFILE_AVATAR_REQ_INTERVAL_SEC = 10L
    const val MAX_PROFILE_AVATAR_REQ_PAYLOAD_SIZE = 96

    // Timing & Skew
    const val FUTURE_SKEW_SEC = 120L
}
