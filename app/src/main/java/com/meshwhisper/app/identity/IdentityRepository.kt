package com.meshwhisper.app.identity

import android.util.Log
import androidx.room.withTransaction
import com.meshwhisper.app.crypto.CryptoEngine
import com.meshwhisper.app.data.MeshDatabase
import com.meshwhisper.app.data.model.IdentityEntity
import com.meshwhisper.app.data.model.PeerEntity
import com.meshwhisper.core.crypto.PureCryptoEngine
import com.meshwhisper.core.identity.IdentityManager
import com.meshwhisper.core.identity.IdentityProcessResult
import com.meshwhisper.core.identity.IdentityStore
import com.meshwhisper.core.identity.InMemoryIdentityStore
import com.meshwhisper.core.identity.NodeQrCodec
import com.meshwhisper.core.identity.PeerIdentity
import com.meshwhisper.core.identity.TrustState
import com.meshwhisper.core.identity.VerificationCandidate
import com.meshwhisper.core.protocol.AuthenticatedPacket
import com.meshwhisper.core.protocol.MeshPacket
import com.meshwhisper.core.protocol.PeerAnnouncePayload
import com.meshwhisper.core.util.Clock
import com.meshwhisper.core.util.SystemClock
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

sealed class AnnounceTrustResult {
    data class Success(val trustState: TrustState, val isNew: Boolean, val isRotation: Boolean) : AnnounceTrustResult()
    data class Collision(val nodeId64: Long, val collidingIdentities: List<String>) : AnnounceTrustResult()
    data class Dropped(val reason: String) : AnnounceTrustResult()
}

/**
 * Authoritative persistence-backed repository for peer identities and trust states in :app.
 * Complies with NEXTGEN/01_VNEXT_PROTOCOL_FROZEN.md §2.7, §5, §6, C-12, C-23, and T-ARCH-01.
 */
class IdentityRepository(
    val database: MeshDatabase,
    val cryptoEngine: CryptoEngine,
    val clock: Clock = SystemClock(),
    val identityStore: IdentityStore = InMemoryIdentityStore()
) {
    private val tag = "IdentityRepository"
    private val transactionMutex = Mutex()

    /**
     * Initializes identityStore on app startup from Room identities table and seeds own identity.
     */
    suspend fun initialize() = transactionMutex.withLock {
        // 1. Seed own identity in identityStore
        identityStore.upsert(
            PeerIdentity(
                identityHash = cryptoEngine.identityHash,
                ikPub = cryptoEngine.ikPublicKeyBytes,
                ekPub = cryptoEngine.ekPublicKeyBytes,
                keyVersion = cryptoEngine.keyVersion,
                lastAnnounceCounter = cryptoEngine.announceCounter,
                trustState = TrustState.VERIFIED,
                nodeId64 = cryptoEngine.nodeId64
            )
        )

        // 2. Reconstruct in-memory identityStore from persistent Room identities, prioritized by trust state and recency
        val identities = database.identityDao().getPrioritizedIdentities(com.meshwhisper.core.protocol.ResourceLimits.MAX_IDENTITIES_PEERS)
        for (entity in identities) {
            try {
                val idHash = PureCryptoEngine.hexToBytes(entity.identityHashHex)
                val ikPub = PureCryptoEngine.hexToBytes(entity.ikPubHex)
                val ekPub = PureCryptoEngine.hexToBytes(entity.ekPubHex)
                val trust = try {
                    TrustState.valueOf(entity.trustState)
                } catch (_: Exception) {
                    TrustState.SEEN
                }
                identityStore.upsert(
                    PeerIdentity(
                        identityHash = idHash,
                        ikPub = ikPub,
                        ekPub = ekPub,
                        keyVersion = entity.keyVersion,
                        lastAnnounceCounter = entity.lastAnnounceCounter,
                        trustState = trust,
                        nodeId64 = entity.nodeId64
                    )
                )
            } catch (e: Exception) {
                Log.w(tag, "Failed to restore identity ${entity.identityHashHex}: ${e.message}")
            }
        }
    }

    /**
     * Handles an authenticated PEER_ANNOUNCE from PacketPipeline.
     * Consults Room in a serialized transaction for nodeId64 collisions (C-23).
     * Applies T7 on collision, or T1 / T6 / T11 otherwise, updating Room and identityStore.
     */
    suspend fun onAuthenticatedAnnounce(
        authPacket: AuthenticatedPacket,
        announce: PeerAnnouncePayload
    ): AnnounceTrustResult = transactionMutex.withLock {
        val senderIdentity = authPacket.senderIdentity
        val senderNodeId64 = authPacket.packet.senderId
        val senderHashHex = PureCryptoEngine.bytesToHex(senderIdentity.identityHash)
        val ikPubHex = PureCryptoEngine.bytesToHex(announce.ikPub)
        val ekPubHex = PureCryptoEngine.bytesToHex(announce.ekPub)

        database.withTransaction {
            // Check Room for nodeId64 collision (C-23)
            val existingWithNodeId = database.identityDao().getAllByNodeId64(senderNodeId64)
            val colliding = existingWithNodeId.filter { it.identityHashHex != senderHashHex }

            if (colliding.isNotEmpty()) {
                Log.w(tag, "NodeId64 collision detected for $senderNodeId64! Colliding identities: ${colliding.map { it.identityHashHex }} vs $senderHashHex")
                // T7: Apply collision -> all colliding identities become CONFLICTED
                for (col in colliding) {
                    database.identityDao().insertOrUpdate(
                        col.copy(
                            trustState = TrustState.CONFLICTED.name,
                            lastSeenAt = System.currentTimeMillis()
                        )
                    )
                    database.peerDao().updateTrustStateByIdentityHash(col.identityHashHex, TrustState.CONFLICTED.name)
                }

                val incomingEntity = IdentityEntity(
                    identityHashHex = senderHashHex,
                    ikPubHex = ikPubHex,
                    ekPubHex = ekPubHex,
                    keyVersion = announce.keyVersion,
                    lastAnnounceCounter = announce.announceCounter,
                    trustState = TrustState.CONFLICTED.name,
                    nodeId64 = senderNodeId64,
                    alias = announce.alias,
                    lastSeenAt = System.currentTimeMillis()
                )
                database.identityDao().insertOrUpdate(incomingEntity)
                database.peerDao().updateTrustStateByNodeId(senderNodeId64, TrustState.CONFLICTED.name)

                // Update runtime identityStore
                for (col in colliding) {
                    val colHash = PureCryptoEngine.hexToBytes(col.identityHashHex)
                    val mem = identityStore.get(colHash)
                    if (mem != null) {
                        identityStore.upsert(mem.copy(trustState = TrustState.CONFLICTED))
                    }
                }
                identityStore.upsert(
                    senderIdentity.copy(trustState = TrustState.CONFLICTED)
                )

                return@withTransaction AnnounceTrustResult.Collision(
                    nodeId64 = senderNodeId64,
                    collidingIdentities = colliding.map { it.identityHashHex } + senderHashHex
                )
            }

            // No collision. Check if identity exists in Room.
            val existing = database.identityDao().getByIdentityHash(senderHashHex)
            var isNew = false
            var isRotation = false
            val targetTrustState: TrustState
            var hasKeyChanged = false
            var prevFingerprint: String? = null

            if (existing == null) {
                isNew = true
                // Check if there is an existing peer row with LEGACY_UNVERIFIED
                val existingPeer = database.peerDao().getPeerById(senderNodeId64)
                if (existingPeer != null && existingPeer.trustState == TrustState.LEGACY_UNVERIFIED.name) {
                    // T11: LEGACY_UNVERIFIED -> SEEN
                    targetTrustState = TrustState.SEEN
                } else {
                    // T1: (none) -> SEEN
                    targetTrustState = TrustState.SEEN
                }
            } else {
                val currentTrust = try {
                    TrustState.valueOf(existing.trustState)
                } catch (_: Exception) {
                    TrustState.SEEN
                }

                if (currentTrust == TrustState.BLOCKED) {
                    return@withTransaction AnnounceTrustResult.Dropped("Identity is BLOCKED")
                }

                // Check key rotation
                if (announce.keyVersion > existing.keyVersion) {
                    isRotation = true
                    // T6: Key rotation demotes VERIFIED to LINKED! (C-12)
                    targetTrustState = if (currentTrust == TrustState.VERIFIED) TrustState.LINKED else currentTrust
                    hasKeyChanged = (currentTrust == TrustState.VERIFIED || currentTrust == TrustState.LINKED)
                    prevFingerprint = CryptoEngine.generateFingerprint(PureCryptoEngine.hexToBytes(existing.ikPubHex))
                } else {
                    // Preserve existing trust state (VERIFIED, LINKED, IMPORTED, SEEN)
                    targetTrustState = currentTrust
                }
            }

            val identityEntity = IdentityEntity(
                identityHashHex = senderHashHex,
                ikPubHex = ikPubHex,
                ekPubHex = ekPubHex,
                keyVersion = announce.keyVersion,
                lastAnnounceCounter = announce.announceCounter,
                trustState = targetTrustState.name,
                nodeId64 = senderNodeId64,
                alias = announce.alias,
                lastSeenAt = System.currentTimeMillis()
            )
            database.identityDao().insertOrUpdate(identityEntity)
            if (isNew && database.identityDao().getIdentityCount() > com.meshwhisper.core.protocol.ResourceLimits.MAX_IDENTITIES_PEERS) {
                database.identityDao().pruneExcessUnverified(com.meshwhisper.core.protocol.ResourceLimits.MAX_IDENTITIES_PEERS)
            }

            val existingPeer = database.peerDao().getPeerById(senderNodeId64)
            val peerEntity = PeerEntity(
                nodeId = senderNodeId64,
                alias = announce.alias,
                publicKeyHex = ekPubHex,
                fingerprint = CryptoEngine.generateFingerprint(announce.ikPub),
                lastSeen = authPacket.packet.timestamp * 1000L,
                isDirect = (authPacket.packet.ttl == MeshPacket.DEFAULT_TTL),
                rssi = -50,
                hopCount = MeshPacket.DEFAULT_TTL - authPacket.packet.ttl,
                identityHashHex = senderHashHex,
                trustState = targetTrustState.name,
                isVerified = (targetTrustState == TrustState.VERIFIED),
                hasKeyChanged = hasKeyChanged || (existingPeer?.hasKeyChanged ?: false),
                previousFingerprint = prevFingerprint ?: existingPeer?.previousFingerprint
            )
            database.peerDao().insertOrUpdate(peerEntity)

            // Update runtime identityStore
            identityStore.upsert(
                senderIdentity.copy(
                    trustState = targetTrustState,
                    hasKeyChanged = peerEntity.hasKeyChanged
                )
            )

            AnnounceTrustResult.Success(
                trustState = targetTrustState,
                isNew = isNew,
                isRotation = isRotation
            )
        }
    }

    /**
     * Imports an identity from a v2 URI (meshwhisper://node/v2?...).
     * Enforces T2: strictly (none) -> IMPORTED. Existing trust states are not mutated.
     * Invariant I-8: Deep links NEVER produce VERIFIED.
     */
    suspend fun importPeerUri(uriString: String): Result<IdentityEntity> = transactionMutex.withLock {
        try {
            val qrData = NodeQrCodec.decode(uriString.trim())
                ?: return@withLock Result.failure(IllegalArgumentException("Invalid v2 QR URI format"))
            val nowSec = clock.nowSeconds()
            val importResult = IdentityManager.importIdentity(
                identityStore = identityStore,
                ikPub = qrData.ikPub,
                ekPub = qrData.ekPub,
                keyVersion = qrData.keyVersion,
                notBefore = qrData.notBefore,
                ibcSignature = qrData.ibcSignature,
                packetTimestamp = nowSec
            )
            val peerIdentity = when (importResult) {
                is IdentityProcessResult.Accepted -> importResult.identity
                is IdentityProcessResult.CollisionDetected -> importResult.collidingIdentities.first { it.ikPub.contentEquals(qrData.ikPub) }
                is IdentityProcessResult.RejectedInvalidIbc -> return@withLock Result.failure(IllegalArgumentException(importResult.reason))
                is IdentityProcessResult.DroppedRollback -> return@withLock Result.failure(IllegalArgumentException("Dropped rollback"))
                is IdentityProcessResult.DroppedEquivocation -> return@withLock Result.failure(IllegalArgumentException("Dropped equivocation"))
            }
            val hashHex = PureCryptoEngine.bytesToHex(peerIdentity.identityHash)
            val ikHex = PureCryptoEngine.bytesToHex(peerIdentity.ikPub)
            val ekHex = PureCryptoEngine.bytesToHex(peerIdentity.ekPub)
            val alias = qrData.alias.ifBlank { null } ?: "Peer-${String.format("%04X", (peerIdentity.nodeId64 and 0xFFFF))}"

            database.withTransaction {
                val existing = database.identityDao().getByIdentityHash(hashHex)
                val targetTrustState = if (existing == null) {
                    peerIdentity.trustState
                } else {
                    try { TrustState.valueOf(existing.trustState) } catch (_: Exception) { TrustState.IMPORTED }
                }

                val entity = IdentityEntity(
                    identityHashHex = hashHex,
                    ikPubHex = ikHex,
                    ekPubHex = ekHex,
                    keyVersion = peerIdentity.keyVersion,
                    lastAnnounceCounter = peerIdentity.lastAnnounceCounter,
                    trustState = targetTrustState.name,
                    nodeId64 = peerIdentity.nodeId64,
                    alias = alias,
                    lastSeenAt = System.currentTimeMillis()
                )
                database.identityDao().insertOrUpdate(entity)

                val existingPeer = database.peerDao().getPeerById(peerIdentity.nodeId64)
                val peerEntity = PeerEntity(
                    nodeId = peerIdentity.nodeId64,
                    alias = alias,
                    publicKeyHex = ekHex,
                    fingerprint = CryptoEngine.generateFingerprint(peerIdentity.ikPub),
                    lastSeen = System.currentTimeMillis(),
                    isDirect = true,
                    identityHashHex = hashHex,
                    trustState = targetTrustState.name,
                    isVerified = (targetTrustState == TrustState.VERIFIED),
                    hasKeyChanged = existingPeer?.hasKeyChanged ?: false,
                    previousFingerprint = existingPeer?.previousFingerprint
                )
                database.peerDao().insertOrUpdate(peerEntity)

                identityStore.upsert(peerIdentity.copy(trustState = targetTrustState))

                Result.success(entity)
            }
        } catch (e: Exception) {
            Result.failure(e)
        }
    }

    /**
     * Staged 2-step verification Step 1: Validates QR content, reproduces nodeId64,
     * computes canonical 60-digit safety numbers and fingerprints. Zero state mutation.
     */
    fun prepareCameraQrVerification(
        scannedContent: String,
        targetPeerNodeId: Long? = null
    ): Result<VerificationCandidate> {
        val qrData = NodeQrCodec.decode(scannedContent.trim())
            ?: return Result.failure(IllegalArgumentException("Invalid v2 QR URI format"))
        val nowSec = clock.nowSeconds()
        return IdentityManager.prepareCameraQrCandidate(
            identityStore = identityStore,
            ourIdentityHash = cryptoEngine.identityHash,
            qrData = qrData,
            packetTimestamp = nowSec,
            expectedNodeId64 = targetPeerNodeId
        )
    }

    /**
     * Staged 2-step verification Step 2: User explicitly confirmed safety numbers.
     * Applies T3 (promotion to VERIFIED) or T8 (collision resolution: winner VERIFIED, losers BLOCKED).
     */
    suspend fun confirmSafetyNumber(candidate: VerificationCandidate): Result<Unit> = transactionMutex.withLock {
        try {
            // 1. Apply to in-memory identityStore
            val updatedIdentity = IdentityManager.applyConfirmedVerification(identityStore, candidate)
            val winnerHashHex = PureCryptoEngine.bytesToHex(updatedIdentity.identityHash)
            val winnerNodeId = updatedIdentity.nodeId64

            // 2. Commit to Room inside serialized transaction
            database.withTransaction {
                val existingEntity = database.identityDao().getByIdentityHash(winnerHashHex)
                if (existingEntity != null) {
                    database.identityDao().insertOrUpdate(
                        existingEntity.copy(
                            trustState = TrustState.VERIFIED.name,
                            lastSeenAt = System.currentTimeMillis()
                        )
                    )
                } else {
                    database.identityDao().insertOrUpdate(
                        IdentityEntity(
                            identityHashHex = winnerHashHex,
                            ikPubHex = PureCryptoEngine.bytesToHex(updatedIdentity.ikPub),
                            ekPubHex = PureCryptoEngine.bytesToHex(updatedIdentity.ekPub),
                            keyVersion = updatedIdentity.keyVersion,
                            lastAnnounceCounter = updatedIdentity.lastAnnounceCounter,
                            trustState = TrustState.VERIFIED.name,
                            nodeId64 = winnerNodeId,
                            lastSeenAt = System.currentTimeMillis()
                        )
                    )
                }

                database.peerDao().updateTrustStateByIdentityHash(winnerHashHex, TrustState.VERIFIED.name)
                database.peerDao().updateHasKeyChangedByIdentityHash(winnerHashHex, false)

                // If collision resolution (T8), mark losers as BLOCKED
                if (candidate.isCollisionResolution) {
                    val colliding = database.identityDao().getAllByNodeId64(winnerNodeId)
                        .filter { it.identityHashHex != winnerHashHex }
                    for (loser in colliding) {
                        database.identityDao().insertOrUpdate(
                            loser.copy(trustState = TrustState.BLOCKED.name)
                        )
                        database.peerDao().updateTrustStateByIdentityHash(loser.identityHashHex, TrustState.BLOCKED.name)
                    }
                    database.peerDao().setPeerBlocked(winnerNodeId, false)
                }
            }

            Result.success(Unit)
        } catch (e: Exception) {
            Result.failure(e)
        }
    }

    /**
     * T9: Block peer identity.
     */
    suspend fun blockPeer(identityHashHex: String) = transactionMutex.withLock {
        val hashBytes = PureCryptoEngine.hexToBytes(identityHashHex)
        database.withTransaction {
            val entity = database.identityDao().getByIdentityHash(identityHashHex)
            if (entity != null) {
                database.identityDao().insertOrUpdate(entity.copy(trustState = TrustState.BLOCKED.name))
                database.peerDao().updateTrustStateByIdentityHash(identityHashHex, TrustState.BLOCKED.name)
                database.peerDao().setPeerBlocked(entity.nodeId64, true)
            }
        }
        IdentityManager.blockIdentity(identityStore, hashBytes)
    }

    /**
     * T10: Unblock peer identity.
     */
    suspend fun unblockPeer(identityHashHex: String) = transactionMutex.withLock {
        val hashBytes = PureCryptoEngine.hexToBytes(identityHashHex)
        database.withTransaction {
            val entity = database.identityDao().getByIdentityHash(identityHashHex)
            if (entity != null) {
                database.identityDao().insertOrUpdate(entity.copy(trustState = TrustState.SEEN.name))
                database.peerDao().updateTrustStateByIdentityHash(identityHashHex, TrustState.SEEN.name)
                database.peerDao().setPeerBlocked(entity.nodeId64, false)
            }
        }
        IdentityManager.unblockIdentity(identityStore, hashBytes)
    }

    /**
     * Acknowledges key change banner.
     * Sets peers.hasKeyChanged = false. MUST NEVER mutate trustState!
     */
    suspend fun acknowledgeKeyChange(nodeId: Long) = transactionMutex.withLock {
        database.peerDao().updateHasKeyChangedByNodeId(nodeId, false)
        val peer = database.peerDao().getPeerById(nodeId)
        if (peer?.identityHashHex != null) {
            val hashBytes = PureCryptoEngine.hexToBytes(peer.identityHashHex)
            val mem = identityStore.get(hashBytes)
            if (mem != null) {
                identityStore.upsert(mem.copy(hasKeyChanged = false))
            }
        }
    }

    /**
     * T4: Link established -> SEEN promotes to LINKED. VERIFIED is untouched.
     */
    suspend fun onLinkEstablished(identityHash: ByteArray) = transactionMutex.withLock {
        IdentityManager.onLinkEstablished(identityStore, identityHash)
        val hashHex = PureCryptoEngine.bytesToHex(identityHash)
        val mem = identityStore.get(identityHash)
        if (mem != null && mem.trustState == TrustState.LINKED) {
            database.identityDao().getByIdentityHash(hashHex)?.let {
                if (it.trustState == TrustState.SEEN.name) {
                    database.identityDao().insertOrUpdate(it.copy(trustState = TrustState.LINKED.name))
                    database.peerDao().updateTrustStateByIdentityHash(hashHex, TrustState.LINKED.name)
                }
            }
        }
    }

    /**
     * T5: Link closed -> LINKED demotes to SEEN if all links closed. VERIFIED is untouched.
     */
    suspend fun onLinkClosed(identityHash: ByteArray) = transactionMutex.withLock {
        IdentityManager.onLinkClosed(identityStore, identityHash)
        val hashHex = PureCryptoEngine.bytesToHex(identityHash)
        val mem = identityStore.get(identityHash)
        if (mem != null && mem.trustState == TrustState.SEEN) {
            database.identityDao().getByIdentityHash(hashHex)?.let {
                if (it.trustState == TrustState.LINKED.name) {
                    database.identityDao().insertOrUpdate(it.copy(trustState = TrustState.SEEN.name))
                    database.peerDao().updateTrustStateByIdentityHash(hashHex, TrustState.SEEN.name)
                }
            }
        }
    }

    /**
     * Returns true if any identity for this nodeId64 is currently CONFLICTED.
     * C-23: Unicast routing is suspended while in conflict.
     */
    fun isNodeConflicted(nodeId64: Long): Boolean {
        return identityStore.getAllByNodeId64(nodeId64).any { it.trustState == TrustState.CONFLICTED }
    }
}
