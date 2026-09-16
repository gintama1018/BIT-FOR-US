package com.meshwhisper.core.identity

/**
 * Peer trust states defined in NEXTGEN/01_VNEXT_PROTOCOL_FROZEN.md §5.2.
 */
enum class TrustState {
    SEEN,
    LINKED,
    IMPORTED,
    VERIFIED,
    CONFLICTED,
    BLOCKED,
    LEGACY_UNVERIFIED
}

/**
 * Cryptographic peer identity representation.
 * Specified in NEXTGEN/01_VNEXT_PROTOCOL_FROZEN.md §2.1–§2.7 and NEXTGEN/02_VNEXT_IMPLEMENTATION_PLAN.md §9.1.
 */
data class PeerIdentity(
    val identityHash: ByteArray,
    val ikPub: ByteArray,
    val ekPub: ByteArray,
    val keyVersion: Long = 1L,
    val lastAnnounceCounter: Long = 0L,
    val trustState: TrustState = TrustState.SEEN,
    val nodeId64: Long = 0L,
    val hasKeyChanged: Boolean = false,
    val warningCount: Int = 0
) {
    init {
        require(identityHash.size == 32) { "identityHash must be 32 bytes, got ${identityHash.size}" }
        require(ikPub.size == 32) { "ikPub must be 32 bytes, got ${ikPub.size}" }
        require(ekPub.size == 32) { "ekPub must be 32 bytes, got ${ekPub.size}" }
        require(keyVersion in 1L..0xFFFFFFFFL) { "keyVersion must be in 1..0xFFFFFFFF, got $keyVersion" }
        require(lastAnnounceCounter >= 0L) { "lastAnnounceCounter must be non-negative" }
    }

    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (javaClass != other?.javaClass) return false
        other as PeerIdentity
        return identityHash.contentEquals(other.identityHash) &&
                ikPub.contentEquals(other.ikPub) &&
                ekPub.contentEquals(other.ekPub) &&
                keyVersion == other.keyVersion &&
                lastAnnounceCounter == other.lastAnnounceCounter &&
                trustState == other.trustState &&
                nodeId64 == other.nodeId64 &&
                hasKeyChanged == other.hasKeyChanged &&
                warningCount == other.warningCount
    }

    override fun hashCode(): Int {
        var result = identityHash.contentHashCode()
        result = 31 * result + ikPub.contentHashCode()
        result = 31 * result + ekPub.contentHashCode()
        result = 31 * result + keyVersion.hashCode()
        result = 31 * result + lastAnnounceCounter.hashCode()
        result = 31 * result + trustState.hashCode()
        result = 31 * result + nodeId64.hashCode()
        result = 31 * result + hasKeyChanged.hashCode()
        result = 31 * result + warningCount
        return result
    }
}
