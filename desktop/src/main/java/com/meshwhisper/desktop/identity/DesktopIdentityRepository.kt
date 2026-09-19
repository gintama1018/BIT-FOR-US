package com.meshwhisper.desktop.identity

import com.meshwhisper.core.crypto.PureCryptoEngine
import com.meshwhisper.core.identity.InMemoryIdentityStore
import com.meshwhisper.core.identity.PeerIdentity
import com.meshwhisper.core.identity.TrustState
import com.meshwhisper.core.logging.MeshLogger
import com.meshwhisper.core.logging.StdoutLogger
import com.meshwhisper.core.protocol.PeerAnnouncePayload
import com.meshwhisper.core.util.Clock
import com.meshwhisper.core.util.SystemClock
import com.meshwhisper.desktop.crypto.DesktopCryptoEngine
import com.meshwhisper.desktop.crypto.DesktopPassphraseKeyStorage
import com.meshwhisper.desktop.db.DesktopDatabase
import com.meshwhisper.desktop.db.DesktopIdentity
import com.meshwhisper.desktop.db.DesktopPeer
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

sealed class DesktopAnnounceTrustResult {
    data class Success(val trustState: TrustState, val isNew: Boolean, val isRotation: Boolean) : DesktopAnnounceTrustResult()
    data class Collision(val nodeId64: Long, val collidingIdentities: List<String>) : DesktopAnnounceTrustResult()
    data class Dropped(val reason: String) : DesktopAnnounceTrustResult()
}

/**
 * Authoritative persistence-backed repository for peer identities and trust states in :desktop.
 * Complies with NEXTGEN/01_VNEXT_PROTOCOL_FROZEN.md §2.7, §5, §6, C-12, C-23, and T-ARCH-01.
 *
 * Ensures:
 * 1. Single authoritative InMemoryIdentityStore instance shared with DesktopPipelineFactory.
 * 2. Trust state transitions strictly follow the frozen vNext model:
 *    - T1: none -> SEEN (first authenticated announcement)
 *    - T4: SEEN / IMPORTED -> LINKED (mutual LINK_AUTH established)
 *    - T5: LINKED -> SEEN (live link closed, if neither VERIFIED nor IMPORTED)
 *    - T6: VERIFIED -> LINKED (accepted key rotation, hasKeyChanged = true)
 *    - T7: (any) -> CONFLICTED (nodeId64 collision across distinct identity hashes)
 *    - T9: (any) -> BLOCKED (explicit user block)
 *    - T10: BLOCKED -> SEEN (explicit user unblock)
 * 3. Atomic persistence to DesktopDatabase before IdentityStore refresh.
 */
class DesktopIdentityRepository(
    val database: DesktopDatabase,
    val keyStorage: DesktopPassphraseKeyStorage,
    val clock: Clock = SystemClock(),
    val logger: MeshLogger = StdoutLogger,
    val identityStore: InMemoryIdentityStore = InMemoryIdentityStore()
) {
    companion object {
        private const val TAG = "DesktopIdentityRepository"
    }

    private val mutex = Mutex()

    init {
        initialize()
    }

    private fun initialize() {
        // 1. Seed own identity
        val priv = keyStorage.getPrivateKey()
        if (priv != null && priv.size == 32) {
            val ikPub = DesktopCryptoEngine.deriveSigningPublicKey(priv)
            val ekPub = DesktopCryptoEngine.derivePublicKey(priv)
            val idHash = DesktopCryptoEngine.deriveIdentityHash(ikPub)
            val nodeId = DesktopCryptoEngine.deriveNodeId64(idHash)
            val keyVersion = keyStorage.getKeyVersion()

            identityStore.upsert(
                PeerIdentity(
                    identityHash = idHash,
                    ikPub = ikPub,
                    ekPub = ekPub,
                    keyVersion = keyVersion,
                    lastAnnounceCounter = 0L,
                    trustState = TrustState.VERIFIED,
                    nodeId64 = nodeId
                )
            )
        }

        // 2. Load existing persistent identities from DesktopDatabase into runtime IdentityStore
        val existingIdentities = database.getAllIdentities()
        for (ident in existingIdentities) {
            try {
                val idHash = DesktopCryptoEngine.hexToBytes(ident.identityHashHex)
                val ik = DesktopCryptoEngine.hexToBytes(ident.ikPubHex)
                val ek = DesktopCryptoEngine.hexToBytes(ident.ekPubHex)
                val state = try { TrustState.valueOf(ident.trustState) } catch (_: Exception) { TrustState.SEEN }

                identityStore.upsert(
                    PeerIdentity(
                        identityHash = idHash,
                        ikPub = ik,
                        ekPub = ek,
                        keyVersion = ident.keyVersion,
                        lastAnnounceCounter = ident.lastAnnounceCounter,
                        trustState = state,
                        nodeId64 = ident.nodeId64
                    )
                )
            } catch (e: Exception) {
                logger.w(TAG, "Failed to load identity ${ident.identityHashHex} into store: ${e.message}")
            }
        }
    }

    suspend fun onAuthenticatedAnnounce(
        senderIdentity: PeerIdentity,
        announce: PeerAnnouncePayload
    ): DesktopAnnounceTrustResult = mutex.withLock {
        val senderHashHex = DesktopCryptoEngine.bytesToHex(senderIdentity.identityHash)
        val ikPubHex = DesktopCryptoEngine.bytesToHex(announce.ikPub)
        val ekPubHex = DesktopCryptoEngine.bytesToHex(announce.ekPub)
        val senderNodeId64 = senderIdentity.nodeId64
        val nowMs = System.currentTimeMillis()

        // 1. Collision detection inside persistent SQLite state (T7)
        val existingWithNodeId = database.findIdentitiesByNodeId(senderNodeId64)
        val colliding = existingWithNodeId.filter { it.identityHashHex != senderHashHex }

        if (colliding.isNotEmpty()) {
            logger.w(TAG, "Collision detected on nodeId64=0x${String.format("%016X", senderNodeId64)}: ${colliding.map { it.identityHashHex }} vs $senderHashHex. Transitioning all to CONFLICTED (T7).")

            // Mark all colliding identities and peers as CONFLICTED
            for (col in colliding) {
                database.upsertIdentity(
                    col.copy(
                        trustState = TrustState.CONFLICTED.name,
                        lastSeenAt = nowMs
                    )
                )
                database.updatePeerTrustState(col.identityHashHex, TrustState.CONFLICTED.name)
            }

            // Mark incoming identity as CONFLICTED
            val incomingConflicted = DesktopIdentity(
                identityHashHex = senderHashHex,
                ikPubHex = ikPubHex,
                ekPubHex = ekPubHex,
                keyVersion = announce.keyVersion,
                lastAnnounceCounter = announce.announceCounter,
                trustState = TrustState.CONFLICTED.name,
                nodeId64 = senderNodeId64,
                alias = announce.alias,
                createdAt = nowMs,
                lastSeenAt = nowMs
            )
            database.upsertIdentity(incomingConflicted)

            val peerConflicted = DesktopPeer(
                nodeId = senderNodeId64,
                identityHashHex = senderHashHex,
                publicKeyHex = ekPubHex,
                alias = announce.alias,
                trustState = TrustState.CONFLICTED.name,
                lastSeen = nowMs,
                publicFingerprint = DesktopCryptoEngine.generateFingerprint(announce.ikPub),
                keyVersion = announce.keyVersion
            )
            database.upsertPeer(peerConflicted)

            // Refresh runtime IdentityStore
            for (col in colliding) {
                val colHash = DesktopCryptoEngine.hexToBytes(col.identityHashHex)
                val mem = identityStore.get(colHash)
                if (mem != null) {
                    identityStore.upsert(mem.copy(trustState = TrustState.CONFLICTED))
                }
            }
            identityStore.upsert(senderIdentity.copy(trustState = TrustState.CONFLICTED))

            return DesktopAnnounceTrustResult.Collision(
                nodeId64 = senderNodeId64,
                collidingIdentities = colliding.map { it.identityHashHex } + senderHashHex
            )
        }

        // 2. No collision: Check existing identity record in DB
        val existing = database.getIdentity(senderHashHex)
        var isNew = false
        var isRotation = false
        val targetTrustState: TrustState
        var hasKeyChanged = false

        if (existing == null) {
            // T1: (none) -> SEEN
            isNew = true
            targetTrustState = TrustState.SEEN
        } else {
            val existingTrust = try { TrustState.valueOf(existing.trustState) } catch (_: Exception) { TrustState.SEEN }

            if (announce.keyVersion < existing.keyVersion) {
                logger.w(TAG, "Key rollback attempt detected for $senderHashHex (stored: ${existing.keyVersion}, incoming: ${announce.keyVersion}). Dropping.")
                return DesktopAnnounceTrustResult.Dropped("KeyVersion rollback attempt")
            }

            if (announce.keyVersion == existing.keyVersion) {
                if (existing.ekPubHex != ekPubHex) {
                    logger.w(TAG, "Key equivocation detected for $senderHashHex at version ${announce.keyVersion}. Dropping.")
                    return DesktopAnnounceTrustResult.Dropped("Key equivocation detected")
                }
                targetTrustState = existingTrust
            } else {
                // Key rotation: announce.keyVersion > existing.keyVersion
                isRotation = true
                hasKeyChanged = true
                // T6 applies specifically when VERIFIED -> demotes to LINKED.
                // SEEN / LINKED / IMPORTED maintain their existing trust state.
                targetTrustState = if (existingTrust == TrustState.VERIFIED) {
                    TrustState.LINKED
                } else {
                    existingTrust
                }
            }
        }

        // 3. Commit to DesktopDatabase atomically
        val updatedIdentity = DesktopIdentity(
            identityHashHex = senderHashHex,
            ikPubHex = ikPubHex,
            ekPubHex = ekPubHex,
            keyVersion = announce.keyVersion,
            lastAnnounceCounter = announce.announceCounter,
            trustState = targetTrustState.name,
            nodeId64 = senderNodeId64,
            alias = announce.alias,
            createdAt = existing?.createdAt ?: nowMs,
            lastSeenAt = nowMs
        )
        database.upsertIdentity(updatedIdentity)

        val updatedPeer = DesktopPeer(
            nodeId = senderNodeId64,
            identityHashHex = senderHashHex,
            publicKeyHex = ekPubHex,
            alias = announce.alias,
            rssi = -50,
            hops = 1,
            lastSeen = nowMs,
            publicFingerprint = DesktopCryptoEngine.generateFingerprint(announce.ikPub),
            hasKeyChanged = hasKeyChanged,
            trustState = targetTrustState.name,
            isVerified = (targetTrustState == TrustState.VERIFIED),
            keyVersion = announce.keyVersion
        )
        database.upsertPeer(updatedPeer)

        // 4. Refresh runtime IdentityStore
        identityStore.upsert(
            senderIdentity.copy(
                trustState = targetTrustState,
                keyVersion = announce.keyVersion,
                ekPub = announce.ekPub,
                lastAnnounceCounter = announce.announceCounter,
                hasKeyChanged = hasKeyChanged
            )
        )

        return DesktopAnnounceTrustResult.Success(
            trustState = targetTrustState,
            isNew = isNew,
            isRotation = isRotation
        )
    }

    suspend fun onLinkEstablished(peerIdentityHash: ByteArray) = mutex.withLock {
        val hashHex = DesktopCryptoEngine.bytesToHex(peerIdentityHash)
        val existing = database.getIdentity(hashHex) ?: return@withLock
        val currentTrust = try { TrustState.valueOf(existing.trustState) } catch (_: Exception) { TrustState.SEEN }

        // T4: SEEN, IMPORTED -> LINKED
        if (currentTrust == TrustState.SEEN || currentTrust == TrustState.IMPORTED) {
            val newTrust = TrustState.LINKED
            database.upsertIdentity(existing.copy(trustState = newTrust.name, lastSeenAt = System.currentTimeMillis()))
            database.updatePeerTrustState(hashHex, newTrust.name)

            val mem = identityStore.get(peerIdentityHash)
            if (mem != null) {
                identityStore.upsert(mem.copy(trustState = newTrust))
            }
            logger.i(TAG, "T4 transition: $hashHex transitioned $currentTrust -> LINKED upon mutual LINK_AUTH")
        }
    }

    suspend fun onLinkDisconnected(peerIdentityHash: ByteArray) = mutex.withLock {
        val hashHex = DesktopCryptoEngine.bytesToHex(peerIdentityHash)
        val existing = database.getIdentity(hashHex) ?: return@withLock
        val currentTrust = try { TrustState.valueOf(existing.trustState) } catch (_: Exception) { TrustState.SEEN }

        // T5: LINKED -> SEEN (only if not VERIFIED or IMPORTED)
        if (currentTrust == TrustState.LINKED) {
            val newTrust = TrustState.SEEN
            database.upsertIdentity(existing.copy(trustState = newTrust.name, lastSeenAt = System.currentTimeMillis()))
            database.updatePeerTrustState(hashHex, newTrust.name)

            val mem = identityStore.get(peerIdentityHash)
            if (mem != null) {
                identityStore.upsert(mem.copy(trustState = newTrust))
            }
            logger.i(TAG, "T5 transition: $hashHex transitioned LINKED -> SEEN upon link disconnect")
        }
    }

    suspend fun blockPeer(peerIdentityHash: ByteArray) = mutex.withLock {
        val hashHex = DesktopCryptoEngine.bytesToHex(peerIdentityHash)
        val existing = database.getIdentity(hashHex) ?: return@withLock

        // T9: (any) -> BLOCKED
        val newTrust = TrustState.BLOCKED
        database.upsertIdentity(existing.copy(trustState = newTrust.name, lastSeenAt = System.currentTimeMillis()))
        database.updatePeerTrustState(hashHex, newTrust.name)

        val mem = identityStore.get(peerIdentityHash)
        if (mem != null) {
            identityStore.upsert(mem.copy(trustState = newTrust))
        }
        logger.i(TAG, "T9 transition: $hashHex transitioned to BLOCKED")
    }

    suspend fun unblockPeer(peerIdentityHash: ByteArray) = mutex.withLock {
        val hashHex = DesktopCryptoEngine.bytesToHex(peerIdentityHash)
        val existing = database.getIdentity(hashHex) ?: return@withLock

        // T10: BLOCKED -> SEEN
        val newTrust = TrustState.SEEN
        database.upsertIdentity(existing.copy(trustState = newTrust.name, lastSeenAt = System.currentTimeMillis()))
        database.updatePeerTrustState(hashHex, newTrust.name)

        val mem = identityStore.get(peerIdentityHash)
        if (mem != null) {
            identityStore.upsert(mem.copy(trustState = newTrust))
        }
        logger.i(TAG, "T10 transition: $hashHex transitioned BLOCKED -> SEEN")
    }

    fun isNodeConflicted(nodeId64: Long): Boolean {
        val matches = database.findIdentitiesByNodeId(nodeId64)
        if (matches.size > 1) return true
        return matches.any { it.trustState == TrustState.CONFLICTED.name }
    }
}
