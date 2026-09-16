package com.meshwhisper.core.identity

import com.meshwhisper.core.crypto.PureCryptoEngine

sealed class IdentityProcessResult {
    data class Accepted(
        val identity: PeerIdentity,
        val isNew: Boolean,
        val isRotation: Boolean
    ) : IdentityProcessResult()

    data class DroppedRollback(
        val storedVersion: Long,
        val incomingVersion: Long
    ) : IdentityProcessResult()

    data class DroppedEquivocation(
        val identityHash: ByteArray,
        val version: Long,
        val warningCount: Int
    ) : IdentityProcessResult() {
        override fun equals(other: Any?): Boolean {
            if (this === other) return true
            if (javaClass != other?.javaClass) return false
            other as DroppedEquivocation
            return identityHash.contentEquals(other.identityHash) &&
                    version == other.version &&
                    warningCount == other.warningCount
        }

        override fun hashCode(): Int {
            var result = identityHash.contentHashCode()
            result = 31 * result + version.hashCode()
            result = 31 * result + warningCount
            return result
        }
    }

    data class CollisionDetected(
        val collidingIdentities: List<PeerIdentity>
    ) : IdentityProcessResult()

    data class RejectedInvalidIbc(
        val reason: String
    ) : IdentityProcessResult()
}

object IdentityManager {

    /**
     * Processes an incoming peer identity presentation according to NEXTGEN/01_VNEXT_PROTOCOL_FROZEN.md:
     * - §2.4 & C-11: Validates IBC signature and notBefore <= packetTimestamp + 120
     * - §2.2: Computes canonical identityHash = SHA-256("MW/NODE/v2" || 0x00 || IK_pk)
     * - §2.3: Computes nodeId64 from identityHash[0..8]
     * - C-12: Handles EK rotation, rollback, and equivocation
     * - C-23: Handles nodeId64 collisions across distinct identities
     */
    fun processIncomingIdentity(
        identityStore: IdentityStore,
        ikPub: ByteArray,
        ekPub: ByteArray,
        keyVersion: Long,
        notBefore: Long,
        ibcSignature: ByteArray,
        packetTimestamp: Long,
        initialTrustState: TrustState = TrustState.SEEN
    ): IdentityProcessResult {
        // 1. Enforce u32 bounds and protocol IBC validation (C-11)
        if (!PureCryptoEngine.validateIbc(ikPub, ekPub, keyVersion, notBefore, ibcSignature, packetTimestamp)) {
            return IdentityProcessResult.RejectedInvalidIbc("IBC signature invalid or timestamp constraint violated (notBefore > packetTimestamp + 120)")
        }

        // 2. Derive canonical identityHash and nodeId64
        val identityHash = PureCryptoEngine.deriveIdentityHash(ikPub)
        val nodeId64 = PureCryptoEngine.deriveNodeId64(identityHash)

        // 3. Lookup existing identity by primary key (identityHash)
        val existing = identityStore.get(identityHash)

        if (existing == null) {
            // Check for nodeId64 collision (C-23)
            val colliding = identityStore.getAllByNodeId64(nodeId64)
            if (colliding.isNotEmpty()) {
                // Two distinct identityHash presenting the same nodeId64:
                // C-23: Both identities -> CONFLICTED
                val updatedColliding = colliding.map { peer ->
                    val conflicted = peer.copy(trustState = TrustState.CONFLICTED)
                    identityStore.upsert(conflicted)
                    conflicted
                }
                val newIdentity = PeerIdentity(
                    identityHash = identityHash,
                    ikPub = ikPub,
                    ekPub = ekPub,
                    keyVersion = keyVersion,
                    lastAnnounceCounter = 0L,
                    trustState = TrustState.CONFLICTED,
                    nodeId64 = nodeId64,
                    hasKeyChanged = false,
                    warningCount = 0
                )
                identityStore.upsert(newIdentity)
                return IdentityProcessResult.CollisionDetected(updatedColliding + newIdentity)
            }

            // Fresh valid identity
            val newIdentity = PeerIdentity(
                identityHash = identityHash,
                ikPub = ikPub,
                ekPub = ekPub,
                keyVersion = keyVersion,
                lastAnnounceCounter = 0L,
                trustState = initialTrustState,
                nodeId64 = nodeId64,
                hasKeyChanged = false,
                warningCount = 0
            )
            identityStore.upsert(newIdentity)
            return IdentityProcessResult.Accepted(newIdentity, isNew = true, isRotation = false)
        }

        // Existing identity found: Apply C-12 lifecycle rules
        return when {
            // Rollback attack: keyVersion < stored -> DROP packet, no state change
            keyVersion < existing.keyVersion -> {
                IdentityProcessResult.DroppedRollback(existing.keyVersion, keyVersion)
            }

            // Equivocation attack: keyVersion == stored && ekPub != stored -> DROP packet, raise warning, NO CONFLICTED transition
            keyVersion == existing.keyVersion && !ekPub.contentEquals(existing.ekPub) -> {
                val updated = existing.copy(warningCount = existing.warningCount + 1)
                identityStore.upsert(updated)
                IdentityProcessResult.DroppedEquivocation(identityHash, keyVersion, updated.warningCount)
            }

            // EK rotation: keyVersion > stored -> Accept, update ekPub, bump keyVersion, invalidate session keys, set hasKeyChanged = true, demote VERIFIED -> LINKED
            keyVersion > existing.keyVersion -> {
                PureCryptoEngine.invalidateSessionKey(nodeId64)
                val newTrust = if (existing.trustState == TrustState.VERIFIED) {
                    TrustState.LINKED
                } else {
                    existing.trustState
                }
                val updated = existing.copy(
                    ekPub = ekPub,
                    keyVersion = keyVersion,
                    trustState = newTrust,
                    hasKeyChanged = true
                )
                identityStore.upsert(updated)
                IdentityProcessResult.Accepted(updated, isNew = false, isRotation = true)
            }

            // Same keyVersion, same ekPub -> Unchanged active identity
            else -> {
                IdentityProcessResult.Accepted(existing, isNew = false, isRotation = false)
            }
        }
    }

    /**
     * Resolves a nodeId64 collision via an explicit out-of-band verification (e.g. QR scan) according to C-23:
     * Promotes verifiedIdentityHash to VERIFIED, and demotes all other colliding identities with that nodeId64 to BLOCKED.
     */
    fun resolveCollision(identityStore: IdentityStore, verifiedIdentityHash: ByteArray) {
        val verified = identityStore.get(verifiedIdentityHash) ?: return
        val colliding = identityStore.getAllByNodeId64(verified.nodeId64)
        for (peer in colliding) {
            val resolvedTrust = if (peer.identityHash.contentEquals(verifiedIdentityHash)) {
                TrustState.VERIFIED
            } else {
                TrustState.BLOCKED
            }
            identityStore.upsert(peer.copy(trustState = resolvedTrust))
        }
    }
}
