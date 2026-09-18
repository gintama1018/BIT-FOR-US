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

            // T11: LEGACY_UNVERIFIED transitions to SEEN when a valid vNext announce arrives
            existing.trustState == TrustState.LEGACY_UNVERIFIED -> {
                val updated = existing.copy(trustState = TrustState.SEEN)
                identityStore.upsert(updated)
                IdentityProcessResult.Accepted(updated, isNew = false, isRotation = false)
            }

            // Same keyVersion, same ekPub -> Unchanged active identity
            else -> {
                IdentityProcessResult.Accepted(existing, isNew = false, isRotation = false)
            }
        }
    }

    /**
     * T2: Deep link / NFC import.
     * Transitions (none) -> IMPORTED, or updates SEEN -> IMPORTED.
     * ABSOLUTE PROTOCOL INVARIANT (S-17): A deep link / import MUST NEVER produce VERIFIED.
     */
    fun importIdentity(
        identityStore: IdentityStore,
        ikPub: ByteArray,
        ekPub: ByteArray,
        keyVersion: Long,
        notBefore: Long,
        ibcSignature: ByteArray,
        packetTimestamp: Long = System.currentTimeMillis() / 1000L
    ): IdentityProcessResult {
        if (!PureCryptoEngine.validateIbc(ikPub, ekPub, keyVersion, notBefore, ibcSignature, packetTimestamp)) {
            return IdentityProcessResult.RejectedInvalidIbc("IBC signature invalid or timestamp constraint violated")
        }

        val identityHash = PureCryptoEngine.deriveIdentityHash(ikPub)
        val nodeId64 = PureCryptoEngine.deriveNodeId64(identityHash)
        val existing = identityStore.get(identityHash)

        if (existing == null) {
            val colliding = identityStore.getAllByNodeId64(nodeId64)
            if (colliding.isNotEmpty()) {
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
                    nodeId64 = nodeId64
                )
                identityStore.upsert(newIdentity)
                return IdentityProcessResult.CollisionDetected(updatedColliding + newIdentity)
            }

            val newIdentity = PeerIdentity(
                identityHash = identityHash,
                ikPub = ikPub,
                ekPub = ekPub,
                keyVersion = keyVersion,
                lastAnnounceCounter = 0L,
                trustState = TrustState.IMPORTED,
                nodeId64 = nodeId64
            )
            identityStore.upsert(newIdentity)
            return IdentityProcessResult.Accepted(newIdentity, isNew = true, isRotation = false)
        }

        // Frozen §5.2 T2: strictly (none) -> IMPORTED.
        // If identity is already stored, import does not mutate its trust state.
        val targetTrust = existing.trustState

        val updated = existing.copy(
            ekPub = ekPub,
            keyVersion = keyVersion,
            trustState = targetTrust
        )
        identityStore.upsert(updated)
        return IdentityProcessResult.Accepted(updated, isNew = false, isRotation = false)
    }

    /**
     * Staged Camera QR verification API (Step 1):
     * Decodes and validates the QR identity cryptographically, reproducing nodeId64 and computing
     * the 60-digit safety number. Does NOT mutate trust state to VERIFIED until explicitly confirmed.
     */
    fun prepareCameraQrCandidate(
        identityStore: IdentityStore,
        ourIdentityHash: ByteArray,
        qrData: NodeQrData,
        packetTimestamp: Long = System.currentTimeMillis() / 1000L,
        expectedNodeId64: Long? = null
    ): Result<VerificationCandidate> {
        if (!PureCryptoEngine.validateIbc(
                qrData.ikPub,
                qrData.ekPub,
                qrData.keyVersion,
                qrData.notBefore,
                qrData.ibcSignature,
                packetTimestamp
            )
        ) {
            return Result.failure(IllegalArgumentException("IBC signature invalid or timestamp constraint violated"))
        }

        val identityHash = PureCryptoEngine.deriveIdentityHash(qrData.ikPub)
        val nodeId64 = PureCryptoEngine.deriveNodeId64(identityHash)

        if (expectedNodeId64 != null && expectedNodeId64 != nodeId64) {
            return Result.failure(IllegalArgumentException("NodeId mismatch: expected $expectedNodeId64 != derived $nodeId64"))
        }

        val safetyNumber = PureCryptoEngine.computeSafetyNumber(ourIdentityHash, identityHash)
        val peerFingerprint = PureCryptoEngine.formatFullFingerprint(identityHash)
        val ourFingerprint = PureCryptoEngine.formatFullFingerprint(ourIdentityHash)

        val colliding = identityStore.getAllByNodeId64(nodeId64).filter { !it.identityHash.contentEquals(identityHash) }
        val isCollisionResolution = colliding.isNotEmpty()

        return Result.success(
            VerificationCandidate(
                identityHash = identityHash,
                ikPub = qrData.ikPub,
                ekPub = qrData.ekPub,
                keyVersion = qrData.keyVersion,
                notBefore = qrData.notBefore,
                ibcSignature = qrData.ibcSignature,
                nodeId64 = nodeId64,
                alias = qrData.alias,
                safetyNumber = safetyNumber,
                peerFingerprint = peerFingerprint,
                ourFingerprint = ourFingerprint,
                isCollisionResolution = isCollisionResolution
            )
        )
    }

    /**
     * Staged Camera QR verification API (Step 2):
     * Executes T3 / T8 ONLY after explicit user confirmation of the 60-digit safety number.
     * Transitions (none), SEEN, LINKED, IMPORTED, CONFLICTED -> VERIFIED.
     * If resolving a collision (T8): candidate -> VERIFIED, colliding peers -> BLOCKED.
     */
    fun applyConfirmedVerification(
        identityStore: IdentityStore,
        candidate: VerificationCandidate
    ): PeerIdentity {
        val existing = identityStore.get(candidate.identityHash)
        val colliding = identityStore.getAllByNodeId64(candidate.nodeId64).filter { !it.identityHash.contentEquals(candidate.identityHash) }

        if (colliding.isNotEmpty()) {
            for (peer in colliding) {
                identityStore.upsert(peer.copy(trustState = TrustState.BLOCKED))
            }
        }

        val verified = (existing ?: PeerIdentity(
            identityHash = candidate.identityHash,
            ikPub = candidate.ikPub,
            ekPub = candidate.ekPub,
            keyVersion = candidate.keyVersion,
            lastAnnounceCounter = 0L,
            trustState = TrustState.VERIFIED,
            nodeId64 = candidate.nodeId64
        )).copy(
            ekPub = candidate.ekPub,
            keyVersion = candidate.keyVersion,
            trustState = TrustState.VERIFIED,
            hasKeyChanged = false
        )

        identityStore.upsert(verified)
        return verified
    }

    /**
     * T3 / T8: In-app camera QR scan verification convenience method for programmatic / test use.
     * Transitions (none), SEEN, LINKED, IMPORTED, CONFLICTED -> VERIFIED.
     * If resolving a collision (T8): resolves collision, promoting this identity to VERIFIED and other colliding identities to BLOCKED.
     */
    fun verifyViaCameraQr(
        identityStore: IdentityStore,
        ikPub: ByteArray,
        ekPub: ByteArray,
        keyVersion: Long,
        notBefore: Long,
        ibcSignature: ByteArray,
        packetTimestamp: Long = System.currentTimeMillis() / 1000L,
        expectedNodeId64: Long? = null
    ): IdentityProcessResult {
        if (!PureCryptoEngine.validateIbc(ikPub, ekPub, keyVersion, notBefore, ibcSignature, packetTimestamp)) {
            return IdentityProcessResult.RejectedInvalidIbc("IBC signature invalid or timestamp constraint violated")
        }

        val identityHash = PureCryptoEngine.deriveIdentityHash(ikPub)
        val nodeId64 = PureCryptoEngine.deriveNodeId64(identityHash)

        if (expectedNodeId64 != null && expectedNodeId64 != nodeId64) {
            return IdentityProcessResult.RejectedInvalidIbc("NodeId mismatch: expected $expectedNodeId64 != derived $nodeId64")
        }

        val candidate = VerificationCandidate(
            identityHash = identityHash,
            ikPub = ikPub,
            ekPub = ekPub,
            keyVersion = keyVersion,
            notBefore = notBefore,
            ibcSignature = ibcSignature,
            nodeId64 = nodeId64,
            alias = "",
            safetyNumber = "",
            peerFingerprint = "",
            ourFingerprint = ""
        )

        val verifiedIdentity = applyConfirmedVerification(identityStore, candidate)
        val existing = identityStore.get(identityHash)
        return IdentityProcessResult.Accepted(verifiedIdentity, isNew = existing == null, isRotation = false)
    }

    /**
     * T4: LINK_AUTH CONFIRM verified on a live link (§3.4).
     * Transitions SEEN, IMPORTED -> LINKED.
     */
    fun onLinkEstablished(identityStore: IdentityStore, identityHash: ByteArray) {
        val existing = identityStore.get(identityHash) ?: return
        if (existing.trustState == TrustState.SEEN || existing.trustState == TrustState.IMPORTED) {
            identityStore.upsert(existing.copy(trustState = TrustState.LINKED))
        }
    }

    /**
     * T5: Link closed.
     * Transitions LINKED -> SEEN.
     */
    fun onLinkClosed(identityStore: IdentityStore, identityHash: ByteArray) {
        val existing = identityStore.get(identityHash) ?: return
        if (existing.trustState == TrustState.LINKED) {
            identityStore.upsert(existing.copy(trustState = TrustState.SEEN))
        }
    }

    /**
     * T9: Explicit user block.
     * Transitions any -> BLOCKED.
     */
    fun blockIdentity(identityStore: IdentityStore, identityHash: ByteArray) {
        val existing = identityStore.get(identityHash) ?: return
        PureCryptoEngine.invalidateSessionKey(existing.nodeId64)
        identityStore.upsert(existing.copy(trustState = TrustState.BLOCKED))
    }

    /**
     * T10: Explicit user unblock.
     * Transitions BLOCKED -> SEEN.
     */
    fun unblockIdentity(identityStore: IdentityStore, identityHash: ByteArray) {
        val existing = identityStore.get(identityHash) ?: return
        if (existing.trustState == TrustState.BLOCKED) {
            identityStore.upsert(existing.copy(trustState = TrustState.SEEN))
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
