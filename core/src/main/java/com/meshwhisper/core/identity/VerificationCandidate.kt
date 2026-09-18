package com.meshwhisper.core.identity

/**
 * Encapsulates a candidate identity decoded and cryptographically validated from a live camera QR scan,
 * prepared for out-of-band safety number comparison and explicit user confirmation (vNext §5.2 T3/T8).
 */
data class VerificationCandidate(
    val identityHash: ByteArray,
    val ikPub: ByteArray,
    val ekPub: ByteArray,
    val keyVersion: Long,
    val notBefore: Long,
    val ibcSignature: ByteArray,
    val nodeId64: Long,
    val alias: String,
    val safetyNumber: String,
    val peerFingerprint: String,
    val ourFingerprint: String,
    val isCollisionResolution: Boolean = false
) {
    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (javaClass != other?.javaClass) return false
        other as VerificationCandidate
        return identityHash.contentEquals(other.identityHash) &&
                ikPub.contentEquals(other.ikPub) &&
                ekPub.contentEquals(other.ekPub) &&
                keyVersion == other.keyVersion &&
                notBefore == other.notBefore &&
                ibcSignature.contentEquals(other.ibcSignature) &&
                nodeId64 == other.nodeId64 &&
                alias == other.alias &&
                safetyNumber == other.safetyNumber &&
                peerFingerprint == other.peerFingerprint &&
                ourFingerprint == other.ourFingerprint &&
                isCollisionResolution == other.isCollisionResolution
    }

    override fun hashCode(): Int {
        var result = identityHash.contentHashCode()
        result = 31 * result + ikPub.contentHashCode()
        result = 31 * result + ekPub.contentHashCode()
        result = 31 * result + keyVersion.hashCode()
        result = 31 * result + notBefore.hashCode()
        result = 31 * result + ibcSignature.contentHashCode()
        result = 31 * result + nodeId64.hashCode()
        result = 31 * result + alias.hashCode()
        result = 31 * result + safetyNumber.hashCode()
        result = 31 * result + peerFingerprint.hashCode()
        result = 31 * result + ourFingerprint.hashCode()
        result = 31 * result + isCollisionResolution.hashCode()
        return result
    }
}
