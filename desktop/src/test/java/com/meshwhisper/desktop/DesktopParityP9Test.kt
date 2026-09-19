package com.meshwhisper.desktop

import com.google.common.truth.Truth.assertThat
import com.meshwhisper.core.crypto.PureCryptoEngine
import com.meshwhisper.core.identity.InMemoryIdentityStore
import com.meshwhisper.core.identity.PeerIdentity
import com.meshwhisper.core.identity.TrustState
import com.meshwhisper.core.logging.NoOpLogger
import com.meshwhisper.core.protocol.*
import com.meshwhisper.core.router.LruDedupCache
import com.meshwhisper.core.transport.*
import com.meshwhisper.core.util.DefaultRandomSource
import com.meshwhisper.core.util.SystemClock
import com.meshwhisper.desktop.crypto.DesktopCryptoEngine
import com.meshwhisper.desktop.crypto.DesktopPassphraseKeyStorage
import com.meshwhisper.desktop.crypto.DesktopPipelineFactory
import com.meshwhisper.desktop.db.DesktopDatabase
import com.meshwhisper.desktop.db.DesktopIdentity
import com.meshwhisper.desktop.db.DesktopPeer
import com.meshwhisper.desktop.identity.DesktopAnnounceTrustResult
import com.meshwhisper.desktop.identity.DesktopIdentityRepository
import com.meshwhisper.desktop.media.DesktopMediaManager
import com.meshwhisper.desktop.router.DesktopMeshRouter
import com.meshwhisper.desktop.router.DesktopPacketStore
import com.meshwhisper.desktop.wifi.DesktopWifiEngine
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.fail
import org.junit.Before
import org.junit.Test
import java.io.DataInputStream
import java.io.DataOutputStream
import java.io.File
import java.net.InetSocketAddress
import java.net.Socket
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.UUID
import java.util.concurrent.atomic.AtomicInteger

/**
 * Comprehensive verification suite for Phase P9: Desktop Parity.
 * Verifies protocol interoperability, deterministic LINK_AUTH, persistence,
 * key rotation, collision handling, media encryption at rest, and T-ARCH-01 conformance.
 */
class DesktopParityP9Test {

    private lateinit var tempDir: File
    private lateinit var dbFile: File
    private lateinit var database: DesktopDatabase
    private lateinit var keyStorage: DesktopPassphraseKeyStorage
    private val testPassphrase = "P9ParityTestSecretPassword#2026!".toCharArray()

    @Before
    fun setUp() {
        tempDir = File(System.getProperty("java.io.tmpdir"), "meshwhisper_p9_${UUID.randomUUID()}")
        tempDir.mkdirs()
        dbFile = File(tempDir, "p9_mesh.db")
        database = DesktopDatabase(dbFile)
        keyStorage = DesktopPassphraseKeyStorage(tempDir, testPassphrase)
    }

    @After
    fun tearDown() {
        tempDir.deleteRecursively()
    }

    private fun findRootDir(): File {
        val current = File(".").canonicalFile
        if (File(current, "app").exists() && File(current, "core").exists() && File(current, "desktop").exists()) {
            return current
        }
        if (current.parentFile != null && File(current.parentFile, "app").exists() && File(current.parentFile, "desktop").exists()) {
            return current.parentFile
        }
        return current
    }

    // =========================================================================
    // 1. Architecture & Boundaries (P9-A01, P9-A02)
    // =========================================================================

    @Test
    fun testP9A01ArchitectureNoForbiddenImportsInDesktop() {
        val rootDir = findRootDir()
        val desktopSrc = File(rootDir, "desktop/src/main/java/com/meshwhisper/desktop")
        assertThat(desktopSrc.exists()).isTrue()

        val forbiddenTokens = listOf("android.", "androidx.")
        val violations = mutableListOf<String>()

        desktopSrc.walkTopDown().filter { it.isFile && it.extension == "kt" }.forEach { file ->
            val lines = file.readLines()
            lines.forEachIndexed { idx, line ->
                if (line.trimStart().startsWith("import ")) {
                    for (token in forbiddenTokens) {
                        if (line.contains(token)) {
                            violations.add("${file.name}:${idx + 1}: imports forbidden '$token'")
                        }
                    }
                }
            }
        }
        assertThat(violations).isEmpty()

        // Router and Media must not reference PureCryptoEngine, verifySignature, or IdentityStore directly
        val decoupledDirs = listOf(
            File(desktopSrc, "router"),
            File(desktopSrc, "media")
        )
        val archForbidden = listOf("PureCryptoEngine", "verifySignature", "IdentityStore")
        val archViolations = mutableListOf<String>()
        for (dir in decoupledDirs) {
            dir.walkTopDown().filter { it.isFile && it.extension == "kt" }.forEach { file ->
                val content = file.readText()
                for (token in archForbidden) {
                    if (content.contains(token)) {
                        archViolations.add("${file.name} contains forbidden token '$token'")
                    }
                }
            }
        }
        assertThat(archViolations).isEmpty()
    }

    @Test
    fun testP9A02DesktopUiHasNoCryptoLogicOrPureCryptoImports() {
        val rootDir = findRootDir()
        val uiFile = File(rootDir, "desktop/src/main/java/com/meshwhisper/desktop/ui/DesktopMainWindow.kt")
        assertThat(uiFile.exists()).isTrue()

        val text = uiFile.readText()
        assertThat(text).doesNotContain("import com.meshwhisper.core.crypto.PureCryptoEngine")
        assertThat(text).doesNotContain("PureCryptoEngine.")
    }

    // =========================================================================
    // 2. Deterministic LINK_AUTH (P9-LINK-01 .. P9-LINK-04)
    // =========================================================================

    @Test
    fun testP9Link01DeterministicLinkAuthHandshake() {
        // Alice: Android node
        val (alicePriv, _) = PureCryptoEngine.generateX25519KeyPair()
        val aliceCreds = LinkAuthLocalCredentials.create(alicePriv)

        // Bob: Desktop node
        val (bobPriv, _) = PureCryptoEngine.generateX25519KeyPair()
        val bobCreds = LinkAuthLocalCredentials.create(bobPriv)

        val aliceSession = LinkAuthSession(
            linkHandle = "tcp-desktop-android-1",
            localCredentials = aliceCreds
        )
        val bobSession = LinkAuthSession(
            linkHandle = "tcp-desktop-android-1",
            localCredentials = bobCreds
        )

        // 1. Both create HELLO packets
        val helloAlice = aliceSession.createHelloPacket()
        val helloBob = bobSession.createHelloPacket()

        // 2. Exchange HELLO packets
        val aliceRes1 = aliceSession.processIncomingPacket(helloBob)
        val bobRes1 = bobSession.processIncomingPacket(helloAlice)
        assertThat(aliceRes1).isEqualTo(LinkAuthStepResult.InProgress)
        assertThat(bobRes1).isEqualTo(LinkAuthStepResult.InProgress)
        assertThat(aliceSession.state).isEqualTo(LinkAuthState.HELLO_EXCHANGED)
        assertThat(bobSession.state).isEqualTo(LinkAuthState.HELLO_EXCHANGED)

        // Symmetrical link keys match
        assertThat(aliceSession.linkKey).isEqualTo(bobSession.linkKey)

        // 3. Both create CONFIRM packets
        val confirmAlice = aliceSession.createConfirmPacket()
        val confirmBob = bobSession.createConfirmPacket()

        // 4. Exchange CONFIRM packets
        val bobRes2 = bobSession.processIncomingPacket(confirmAlice)
        val aliceRes2 = aliceSession.processIncomingPacket(confirmBob)

        assertThat(bobRes2).isInstanceOf(LinkAuthStepResult.Completed::class.java)
        assertThat(aliceRes2).isInstanceOf(LinkAuthStepResult.Completed::class.java)
        assertThat(bobSession.state).isEqualTo(LinkAuthState.AUTHENTICATED)
        assertThat(aliceSession.state).isEqualTo(LinkAuthState.AUTHENTICATED)

        val aliceProof = (aliceRes2 as LinkAuthStepResult.Completed).proof
        val bobProof = (bobRes2 as LinkAuthStepResult.Completed).proof
        assertThat(aliceProof.peerIdentityHash).isEqualTo(bobCreds.identityHash)
        assertThat(bobProof.peerIdentityHash).isEqualTo(aliceCreds.identityHash)
    }

    @Test
    fun testP9Link02TransportIngressWithValidLinkAuthAccepted() {
        val wifiEngine = DesktopWifiEngine(NoOpLogger)
        val router = DesktopMeshRouter(keyStorage, database, wifiEngine, NoOpLogger)

        // Peer setup
        val (peerPriv, _) = PureCryptoEngine.generateX25519KeyPair()
        val peerCreds = LinkAuthLocalCredentials.create(peerPriv)

        val linkHandle = "wifi-tcp-socket-42"
        val routerCreds = LinkAuthLocalCredentials.create(router.myPrivateKey)

        val peerSession = LinkAuthSession(linkHandle, peerCreds)
        val routerSession = LinkAuthSession(linkHandle, routerCreds)

        val peerHello = peerSession.createHelloPacket()
        val routerHello = routerSession.createHelloPacket()

        peerSession.processIncomingPacket(routerHello)
        routerSession.processIncomingPacket(peerHello)

        val peerConfirm = peerSession.createConfirmPacket()
        val routerConfirm = routerSession.createConfirmPacket()

        peerSession.processIncomingPacket(routerConfirm)
        val res = routerSession.processIncomingPacket(peerConfirm)
        assertThat(res).isInstanceOf(LinkAuthStepResult.Completed::class.java)
        val proof = (res as LinkAuthStepResult.Completed).proof

        router.bindLink(proof)
        assertThat(router.isLinkAuthenticated(linkHandle)).isTrue()
    }

    @Test
    fun testP9Link03TransportIngressWithPendingLinkDropsNonDiscovery() {
        val (bobPriv, _) = PureCryptoEngine.generateX25519KeyPair()
        val bobStore = InMemoryIdentityStore()
        val dedupCache = LruDedupCache<String, Long>(100)
        val clock = SystemClock()
        val packetStore = DesktopPacketStore(database)

        val bobRepo = DesktopIdentityRepository(
            database = database,
            keyStorage = keyStorage,
            clock = clock,
            identityStore = bobStore
        )

        val bobNodeId = 0x7BBBBBBBBBBBBBBL
        val pipeline = DesktopPipelineFactory.create(
            myNodeId = bobNodeId,
            myPrivateKey = bobPriv,
            packetStore = packetStore,
            database = database,
            identityRepository = bobRepo,
            clock = clock,
            dedupCache = dedupCache
        )

        // Construct direct message from Alice
        val (alicePriv, _) = PureCryptoEngine.generateX25519KeyPair()
        val aliceIkPub = PureCryptoEngine.deriveSigningPublicKey(alicePriv)
        val aliceIdHash = PureCryptoEngine.deriveIdentityHash(aliceIkPub)
        val aliceNodeId = PureCryptoEngine.deriveNodeId64(aliceIdHash)

        val dmPacket = DirectMessagePacketBuilder.build(
            senderNodeId64 = aliceNodeId,
            senderIdentityHash = aliceIdHash,
            senderPrivateKey = alicePriv,
            recipientNodeId64 = bobNodeId,
            peerPublicKey = PureCryptoEngine.derivePublicKey(bobPriv),
            plaintext = "Unauthenticated probe".toByteArray(Charsets.UTF_8),
            timestampSec = System.currentTimeMillis() / 1000L
        )
        val rawDm = MeshPacket.serialize(dmPacket)

        // Ingest under PENDING link (no boundIdentity)
        val pendingContext = LinkContext(
            linkHandle = "raw-socket-1",
            transport = TransportType.WIFI_TCP,
            boundIdentity = null,
            state = LinkState.PENDING
        )

        val result = pipeline.ingest(rawDm, pendingContext)
        assertThat(result).isInstanceOf(IngestResult.Dropped::class.java)
        val dropped = result as IngestResult.Dropped
        assertThat(dropped.stage).isEqualTo(PipelineStage.S0_ADMISSION)
        assertThat(dropped.reason).contains("Link is PENDING")
    }

    @Test
    fun testP9Link04TransportDisconnectionTransitionsLinkedToSeen() = runBlocking {
        val repo = DesktopIdentityRepository(
            database = database,
            keyStorage = keyStorage
        )

        val (peerPriv, peerPub) = PureCryptoEngine.generateX25519KeyPair()
        val peerIkPub = PureCryptoEngine.deriveSigningPublicKey(peerPriv)
        val peerIdHash = PureCryptoEngine.deriveIdentityHash(peerIkPub)
        val peerNodeId = PureCryptoEngine.deriveNodeId64(peerIdHash)

        // 1. Initial announce -> SEEN (T1)
        val ibcSig = PureCryptoEngine.signIbc(peerPriv, peerPub, 1L, 0L)
        val announce = PeerAnnouncePayload(
            announceVersion = 2,
            flags = 0,
            ikPub = peerIkPub,
            ekPub = peerPub,
            keyVersion = 1L,
            notBefore = 0L,
            ibcSignature = ibcSig,
            announceCounter = 1L,
            alias = "FieldMedic"
        )
        val peerIdentity = PeerIdentity(
            identityHash = peerIdHash,
            ikPub = peerIkPub,
            ekPub = peerPub,
            keyVersion = 1L,
            lastAnnounceCounter = 1L,
            trustState = TrustState.SEEN,
            nodeId64 = peerNodeId
        )
        val res1 = repo.onAuthenticatedAnnounce(peerIdentity, announce)
        assertThat(res1).isInstanceOf(DesktopAnnounceTrustResult.Success::class.java)
        assertThat((res1 as DesktopAnnounceTrustResult.Success).trustState).isEqualTo(TrustState.SEEN)

        // 2. Link established -> LINKED (T4)
        repo.onLinkEstablished(peerIdHash)
        assertThat(database.getIdentity(DesktopCryptoEngine.bytesToHex(peerIdHash))?.trustState).isEqualTo(TrustState.LINKED.name)
        assertThat(repo.identityStore.get(peerIdHash)?.trustState).isEqualTo(TrustState.LINKED)

        // 3. Link disconnected -> SEEN (T5)
        repo.onLinkDisconnected(peerIdHash)
        assertThat(database.getIdentity(DesktopCryptoEngine.bytesToHex(peerIdHash))?.trustState).isEqualTo(TrustState.SEEN.name)
        assertThat(repo.identityStore.get(peerIdHash)?.trustState).isEqualTo(TrustState.SEEN)
    }

    // =========================================================================
    // 3. Identity Persistence & Key Rotation (P9-ID-01 .. P9-ID-05)
    // =========================================================================

    @Test
    fun testP9Id01PassphraseKeyStorageEncryptsAndRecovers() {
        val (priv, _) = PureCryptoEngine.generateX25519KeyPair()
        keyStorage.storePrivateKey(priv)
        keyStorage.writeAlias("CommandCenter-1")
        val mediaKey = ByteArray(32) { 0x77 }
        keyStorage.storeMediaFileKey(mediaKey)

        // Reload fresh from disk using same passphrase
        val reloaded = DesktopPassphraseKeyStorage(tempDir, testPassphrase)
        assertThat(reloaded.getPrivateKey()).isEqualTo(priv)
        assertThat(reloaded.readAlias()).isEqualTo("CommandCenter-1")
        assertThat(reloaded.getMediaFileKey()).isEqualTo(mediaKey)
    }

    @Test
    fun testP9Id02FailClosedVaultOnWrongPassphraseOrCorruption() {
        val (priv, _) = PureCryptoEngine.generateX25519KeyPair()
        keyStorage.storePrivateKey(priv)

        // Case A: Wrong passphrase fails closed
        try {
            DesktopPassphraseKeyStorage(tempDir, "WrongPassphrase!".toCharArray())
            fail("Expected SecurityException on wrong passphrase")
        } catch (e: SecurityException) {
            assertThat(e.message).contains("Failed to decrypt existing identity.vault")
        }

        // Case B: Corrupted vault file fails closed and does not overwrite
        val vaultFile = File(tempDir, "identity.vault")
        assertThat(vaultFile.exists()).isTrue()
        val originalBytes = vaultFile.readBytes()
        // Corrupt ciphertext
        originalBytes[originalBytes.size - 5] = (originalBytes[originalBytes.size - 5].toInt() xor 0xFF).toByte()
        vaultFile.writeBytes(originalBytes)

        try {
            DesktopPassphraseKeyStorage(tempDir, testPassphrase)
            fail("Expected SecurityException on corrupted vault")
        } catch (e: SecurityException) {
            assertThat(e.message).contains("Failed to decrypt existing identity.vault")
        }
    }

    @Test
    fun testP9Id03FreshAnnounceCreatesSeenIdentity() = runBlocking {
        val repo = DesktopIdentityRepository(database, keyStorage)

        val (priv, pub) = PureCryptoEngine.generateX25519KeyPair()
        val ikPub = PureCryptoEngine.deriveSigningPublicKey(priv)
        val idHash = PureCryptoEngine.deriveIdentityHash(ikPub)
        val nodeId = PureCryptoEngine.deriveNodeId64(idHash)

        val ibcSig = PureCryptoEngine.signIbc(priv, pub, 1L, 0L)
        val announce = PeerAnnouncePayload(
            announceVersion = 2,
            flags = 0,
            ikPub = ikPub,
            ekPub = pub,
            keyVersion = 1L,
            notBefore = 0L,
            ibcSignature = ibcSig,
            announceCounter = 1L,
            alias = "Scout-Alpha"
        )
        val peerIdentity = PeerIdentity(
            identityHash = idHash,
            ikPub = ikPub,
            ekPub = pub,
            keyVersion = 1L,
            lastAnnounceCounter = 1L,
            trustState = TrustState.SEEN,
            nodeId64 = nodeId
        )

        val result = repo.onAuthenticatedAnnounce(peerIdentity, announce)
        assertThat(result).isInstanceOf(DesktopAnnounceTrustResult.Success::class.java)
        val success = result as DesktopAnnounceTrustResult.Success
        assertThat(success.trustState).isEqualTo(TrustState.SEEN)
        assertThat(success.isNew).isTrue()

        // Persistent identity in DB
        val hex = DesktopCryptoEngine.bytesToHex(idHash)
        val dbIdent = database.getIdentity(hex)
        assertThat(dbIdent).isNotNull()
        assertThat(dbIdent?.trustState).isEqualTo(TrustState.SEEN.name)
        assertThat(dbIdent?.alias).isEqualTo("Scout-Alpha")
    }

    @Test
    fun testP9Id04KeyRotationVerifiedDemotedToLinked() = runBlocking {
        val repo = DesktopIdentityRepository(database, keyStorage)

        val (priv, pub1) = PureCryptoEngine.generateX25519KeyPair()
        val ikPub = PureCryptoEngine.deriveSigningPublicKey(priv)
        val idHash = PureCryptoEngine.deriveIdentityHash(ikPub)
        val nodeId = PureCryptoEngine.deriveNodeId64(idHash)
        val hex = DesktopCryptoEngine.bytesToHex(idHash)

        // Seed peer as VERIFIED
        val ident = DesktopIdentity(
            identityHashHex = hex,
            ikPubHex = DesktopCryptoEngine.bytesToHex(ikPub),
            ekPubHex = DesktopCryptoEngine.bytesToHex(pub1),
            keyVersion = 1L,
            lastAnnounceCounter = 1L,
            trustState = TrustState.VERIFIED.name,
            nodeId64 = nodeId,
            alias = "FieldCommander"
        )
        database.upsertIdentity(ident)

        // Incoming rotation to keyVersion 2 with new ekPub
        val (_, pub2) = PureCryptoEngine.generateX25519KeyPair()
        val ibcSig2 = PureCryptoEngine.signIbc(priv, pub2, 2L, 0L)
        val announce2 = PeerAnnouncePayload(
            announceVersion = 2,
            flags = 0,
            ikPub = ikPub,
            ekPub = pub2,
            keyVersion = 2L,
            notBefore = 0L,
            ibcSignature = ibcSig2,
            announceCounter = 2L,
            alias = "FieldCommander"
        )
        val peerIdentity = PeerIdentity(
            identityHash = idHash,
            ikPub = ikPub,
            ekPub = pub2,
            keyVersion = 2L,
            lastAnnounceCounter = 2L,
            trustState = TrustState.VERIFIED,
            nodeId64 = nodeId
        )

        val result = repo.onAuthenticatedAnnounce(peerIdentity, announce2)
        assertThat(result).isInstanceOf(DesktopAnnounceTrustResult.Success::class.java)
        val success = result as DesktopAnnounceTrustResult.Success
        // T6: VERIFIED -> LINKED on accepted key rotation
        assertThat(success.trustState).isEqualTo(TrustState.LINKED)
        assertThat(success.isRotation).isTrue()

        val updated = database.getIdentity(hex)
        assertThat(updated?.trustState).isEqualTo(TrustState.LINKED.name)
        assertThat(updated?.keyVersion).isEqualTo(2L)
        val peer = database.getPeer(nodeId)
        assertThat(peer?.hasKeyChanged).isTrue()
    }

    @Test
    fun testP9Id05KeyRotationSeenOrLinkedPreservesTrustState() = runBlocking {
        val repo = DesktopIdentityRepository(database, keyStorage)

        val (priv, pub1) = PureCryptoEngine.generateX25519KeyPair()
        val ikPub = PureCryptoEngine.deriveSigningPublicKey(priv)
        val idHash = PureCryptoEngine.deriveIdentityHash(ikPub)
        val nodeId = PureCryptoEngine.deriveNodeId64(idHash)
        val hex = DesktopCryptoEngine.bytesToHex(idHash)

        // Seed peer as SEEN
        database.upsertIdentity(
            DesktopIdentity(
                identityHashHex = hex,
                ikPubHex = DesktopCryptoEngine.bytesToHex(ikPub),
                ekPubHex = DesktopCryptoEngine.bytesToHex(pub1),
                keyVersion = 1L,
                lastAnnounceCounter = 1L,
                trustState = TrustState.SEEN.name,
                nodeId64 = nodeId,
                alias = "Patrol-1"
            )
        )

        // Incoming rotation to keyVersion 2
        val (_, pub2) = PureCryptoEngine.generateX25519KeyPair()
        val ibcSig2 = PureCryptoEngine.signIbc(priv, pub2, 2L, 0L)
        val announce2 = PeerAnnouncePayload(
            announceVersion = 2,
            flags = 0,
            ikPub = ikPub,
            ekPub = pub2,
            keyVersion = 2L,
            notBefore = 0L,
            ibcSignature = ibcSig2,
            announceCounter = 2L,
            alias = "Patrol-1"
        )
        val peerIdentity = PeerIdentity(
            identityHash = idHash,
            ikPub = ikPub,
            ekPub = pub2,
            keyVersion = 2L,
            lastAnnounceCounter = 2L,
            trustState = TrustState.SEEN,
            nodeId64 = nodeId
        )

        val result = repo.onAuthenticatedAnnounce(peerIdentity, announce2)
        assertThat(result).isInstanceOf(DesktopAnnounceTrustResult.Success::class.java)
        val success = result as DesktopAnnounceTrustResult.Success
        // SEEN maintains SEEN (not demoted or promoted to LINKED)
        assertThat(success.trustState).isEqualTo(TrustState.SEEN)
        assertThat(success.isRotation).isTrue()

        val updated = database.getIdentity(hex)
        assertThat(updated?.trustState).isEqualTo(TrustState.SEEN.name)
        assertThat(updated?.keyVersion).isEqualTo(2L)
    }

    // =========================================================================
    // 4. Collision Detection (P9-COLLISION-01)
    // =========================================================================

    @Test
    fun testP9Collision01NodeId64CollisionTransitionsBothToConflicted() = runBlocking {
        val repo = DesktopIdentityRepository(database, keyStorage)

        val sharedNodeId64 = 0x1122334455667788L

        // Peer A
        val (privA, pubA) = PureCryptoEngine.generateX25519KeyPair()
        val ikPubA = PureCryptoEngine.deriveSigningPublicKey(privA)
        val idHashA = PureCryptoEngine.deriveIdentityHash(ikPubA)
        val hexA = DesktopCryptoEngine.bytesToHex(idHashA)

        database.upsertIdentity(
            DesktopIdentity(
                identityHashHex = hexA,
                ikPubHex = DesktopCryptoEngine.bytesToHex(ikPubA),
                ekPubHex = DesktopCryptoEngine.bytesToHex(pubA),
                keyVersion = 1L,
                lastAnnounceCounter = 1L,
                trustState = TrustState.SEEN.name,
                nodeId64 = sharedNodeId64,
                alias = "OriginalNode"
            )
        )

        // Peer B (colliding nodeId64 with different identityHash)
        val (privB, pubB) = PureCryptoEngine.generateX25519KeyPair()
        val ikPubB = PureCryptoEngine.deriveSigningPublicKey(privB)
        val idHashB = PureCryptoEngine.deriveIdentityHash(ikPubB)
        val hexB = DesktopCryptoEngine.bytesToHex(idHashB)
        assertThat(hexA).isNotEqualTo(hexB)

        val ibcSigB = PureCryptoEngine.signIbc(privB, pubB, 1L, 0L)
        val announceB = PeerAnnouncePayload(
            announceVersion = 2,
            flags = 0,
            ikPub = ikPubB,
            ekPub = pubB,
            keyVersion = 1L,
            notBefore = 0L,
            ibcSignature = ibcSigB,
            announceCounter = 1L,
            alias = "CollidingNode"
        )
        val peerIdentityB = PeerIdentity(
            identityHash = idHashB,
            ikPub = ikPubB,
            ekPub = pubB,
            keyVersion = 1L,
            lastAnnounceCounter = 1L,
            trustState = TrustState.SEEN,
            nodeId64 = sharedNodeId64
        )

        val result = repo.onAuthenticatedAnnounce(peerIdentityB, announceB)
        assertThat(result).isInstanceOf(DesktopAnnounceTrustResult.Collision::class.java)
        val col = result as DesktopAnnounceTrustResult.Collision
        assertThat(col.nodeId64).isEqualTo(sharedNodeId64)

        // Both records transitioned to CONFLICTED (T7)
        assertThat(database.getIdentity(hexA)?.trustState).isEqualTo(TrustState.CONFLICTED.name)
        assertThat(database.getIdentity(hexB)?.trustState).isEqualTo(TrustState.CONFLICTED.name)
        assertThat(database.getPeer(sharedNodeId64)?.trustState).isEqualTo(TrustState.CONFLICTED.name)

        // Unicast destination lookup MUST fail-closed (return null)
        assertThat(database.getUniqueIdentityByNodeId(sharedNodeId64)).isNull()
    }

    // =========================================================================
    // 5. Database Contracts (P9-DB-01, P9-DB-02)
    // =========================================================================

    @Test
    fun testP9Db01DatabaseSchemaParity() {
        val tables = mutableListOf<String>()
        dbFile.parentFile.mkdirs()
        java.sql.DriverManager.getConnection("jdbc:sqlite:${dbFile.absolutePath}").use { conn ->
            conn.createStatement().use { stmt ->
                val rs = stmt.executeQuery("SELECT name FROM sqlite_master WHERE type='table'")
                while (rs.next()) {
                    tables.add(rs.getString("name"))
                }
            }
        }
        val expected = listOf("identities", "peers", "messages", "packet_logs", "topology_edges", "store_forward", "processed_packets")
        for (exp in expected) {
            assertThat(tables).contains(exp)
        }
    }

    @Test
    fun testP9Db02GetUniqueIdentityByNodeIdContracts() {
        val testNodeId = 0x5555666677778888L

        // 1. 0 identities -> null
        assertThat(database.getUniqueIdentityByNodeId(testNodeId)).isNull()

        // 2. 1 CONFLICTED identity -> null
        database.upsertIdentity(
            DesktopIdentity(
                identityHashHex = "1111",
                ikPubHex = "2222",
                ekPubHex = "3333",
                keyVersion = 1L,
                lastAnnounceCounter = 1L,
                trustState = TrustState.CONFLICTED.name,
                nodeId64 = testNodeId,
                alias = "Conflicted"
            )
        )
        assertThat(database.getUniqueIdentityByNodeId(testNodeId)).isNull()

        // 3. 1 BLOCKED identity -> null
        database.upsertIdentity(
            DesktopIdentity(
                identityHashHex = "1111",
                ikPubHex = "2222",
                ekPubHex = "3333",
                keyVersion = 1L,
                lastAnnounceCounter = 1L,
                trustState = TrustState.BLOCKED.name,
                nodeId64 = testNodeId,
                alias = "Blocked"
            )
        )
        assertThat(database.getUniqueIdentityByNodeId(testNodeId)).isNull()

        // 4. 1 Routable identity (SEEN) -> returns identity
        database.upsertIdentity(
            DesktopIdentity(
                identityHashHex = "1111",
                ikPubHex = "2222",
                ekPubHex = "3333",
                keyVersion = 1L,
                lastAnnounceCounter = 1L,
                trustState = TrustState.SEEN.name,
                nodeId64 = testNodeId,
                alias = "Routable"
            )
        )
        val routable = database.getUniqueIdentityByNodeId(testNodeId)
        assertThat(routable).isNotNull()
        assertThat(routable?.identityHashHex).isEqualTo("1111")

        // 5. 2+ matching identities -> null (collision guard)
        database.upsertIdentity(
            DesktopIdentity(
                identityHashHex = "9999",
                ikPubHex = "8888",
                ekPubHex = "7777",
                keyVersion = 1L,
                lastAnnounceCounter = 1L,
                trustState = TrustState.SEEN.name,
                nodeId64 = testNodeId,
                alias = "Duplicate"
            )
        )
        assertThat(database.getUniqueIdentityByNodeId(testNodeId)).isNull()
    }

    // =========================================================================
    // 6. Direct Message & Pipeline Interop (P9-DM-01, P9-INTEROP-01, P9-INTEROP-02)
    // =========================================================================

    @Test
    fun testP9Dm01SharedDirectMessagePacketBuilderInteroperability() {
        val (senderPriv, _) = PureCryptoEngine.generateX25519KeyPair()
        val senderIkPub = PureCryptoEngine.deriveSigningPublicKey(senderPriv)
        val senderIdHash = PureCryptoEngine.deriveIdentityHash(senderIkPub)
        val senderNodeId = PureCryptoEngine.deriveNodeId64(senderIdHash)

        val (receiverPriv, receiverPub) = PureCryptoEngine.generateX25519KeyPair()
        val receiverIkPub = PureCryptoEngine.deriveSigningPublicKey(receiverPriv)
        val receiverIdHash = PureCryptoEngine.deriveIdentityHash(receiverIkPub)
        val receiverNodeId = PureCryptoEngine.deriveNodeId64(receiverIdHash)

        val timestamp = System.currentTimeMillis() / 1000L
        val originalText = "Encrypted tactical coordinates: 34.0522 N, 118.2437 W"

        val packet = DirectMessagePacketBuilder.build(
            senderNodeId64 = senderNodeId,
            senderIdentityHash = senderIdHash,
            senderPrivateKey = senderPriv,
            recipientNodeId64 = receiverNodeId,
            peerPublicKey = receiverPub,
            plaintext = originalText.toByteArray(Charsets.UTF_8),
            timestampSec = timestamp
        )

        assertThat(packet.type).isEqualTo(PacketType.DIRECT_MESSAGE)
        assertThat(packet.senderId).isEqualTo(senderNodeId)
        assertThat(packet.recipientId).isEqualTo(receiverNodeId)
        assertThat(packet.authTag.size).isEqualTo(16)

        // Receiver pipeline setup
        val receiverStore = InMemoryIdentityStore()
        receiverStore.upsert(
            PeerIdentity(
                identityHash = senderIdHash,
                ikPub = senderIkPub,
                ekPub = PureCryptoEngine.derivePublicKey(senderPriv),
                keyVersion = 1L,
                lastAnnounceCounter = 1L,
                trustState = TrustState.SEEN,
                nodeId64 = senderNodeId
            )
        )

        val receiverDbFile = File(tempDir, "receiver_db.db")
        val receiverDb = DesktopDatabase(receiverDbFile)
        val receiverKeyStorage = DesktopPassphraseKeyStorage(tempDir, "recpass#123".toCharArray())
        receiverKeyStorage.storePrivateKey(receiverPriv)

        receiverDb.upsertIdentity(
            DesktopIdentity(
                identityHashHex = DesktopCryptoEngine.bytesToHex(senderIdHash),
                ikPubHex = DesktopCryptoEngine.bytesToHex(senderIkPub),
                ekPubHex = DesktopCryptoEngine.bytesToHex(PureCryptoEngine.derivePublicKey(senderPriv)),
                keyVersion = 1L,
                lastAnnounceCounter = 1L,
                trustState = TrustState.SEEN.name,
                nodeId64 = senderNodeId,
                alias = "SenderNode"
            )
        )

        val receiverRepo = DesktopIdentityRepository(
            database = receiverDb,
            keyStorage = receiverKeyStorage,
            identityStore = receiverStore
        )

        val pipeline = DesktopPipelineFactory.create(
            myNodeId = receiverNodeId,
            myPrivateKey = receiverPriv,
            packetStore = DesktopPacketStore(receiverDb),
            database = receiverDb,
            identityRepository = receiverRepo,
            clock = SystemClock(),
            dedupCache = LruDedupCache(100)
        )

        val rawWire = MeshPacket.serialize(packet)
        val linkContext = LinkContext(
            linkHandle = "link-1",
            transport = TransportType.WIFI_TCP,
            boundIdentity = senderIdHash,
            state = LinkState.AUTHENTICATED
        )

        val ingestResult = pipeline.ingest(rawWire, linkContext)
        assertThat(ingestResult).isInstanceOf(IngestResult.Accepted::class.java)
        val accepted = ingestResult as IngestResult.Accepted
        val decryptedText = String(accepted.packet.decryptedPayload, Charsets.UTF_8)
        assertThat(decryptedText).isEqualTo(originalText)
    }

    // =========================================================================
    // 7. Media At-Rest Encryption (P9-MEDIA-01, P9-MEDIA-02)
    // =========================================================================

    @Test
    fun testP9Media01MediaAtRestEncryptionFormatAndTamperRejection() {
        val (priv, _) = PureCryptoEngine.generateX25519KeyPair()
        keyStorage.storePrivateKey(priv)
        val wifiEngine = DesktopWifiEngine(NoOpLogger)
        val scope = CoroutineScope(Dispatchers.Default + SupervisorJob())

        val mediaManager = DesktopMediaManager(
            myNodeId = 0x12345678L,
            myPrivateKey = priv,
            keyStorage = keyStorage,
            database = database,
            wifiEngine = wifiEngine,
            logger = NoOpLogger,
            scope = scope
        )

        val fileId = "test_media_${UUID.randomUUID()}"
        val plainBytes = "HIGH_RESOLUTION_SATELLITE_IMAGERY_DATA_BLOCK".toByteArray(Charsets.UTF_8)
        val destFile = File(tempDir, "media_store/$fileId.enc")

        // Encrypt and write
        mediaManager.encryptAndWriteMediaFile(fileId, plainBytes, destFile)
        assertThat(destFile.exists()).isTrue()

        val onDisk = destFile.readBytes()
        // Must start with magic header "MWMEDIA1"
        val header = onDisk.copyOfRange(0, 8)
        assertThat(header).isEqualTo(DesktopMediaManager.MAGIC_HEADER)
        // Must NOT contain plaintext string anywhere
        val diskString = String(onDisk, Charsets.ISO_8859_1)
        assertThat(diskString).doesNotContain("HIGH_RESOLUTION_SATELLITE")

        // Decrypt in memory
        val recovered = mediaManager.readAndDecryptMediaFile(fileId, destFile)
        assertThat(recovered).isEqualTo(plainBytes)

        // Tamper 1 byte of ciphertext -> must fail closed
        onDisk[onDisk.size - 2] = (onDisk[onDisk.size - 2].toInt() xor 0xFF).toByte()
        destFile.writeBytes(onDisk)

        try {
            mediaManager.readAndDecryptMediaFile(fileId, destFile)
            fail("Expected failure on tampered media file")
        } catch (e: Exception) {
            // Success: fail closed
        }
    }

    // =========================================================================
    // 8. Restart & Resilience (P9-INTEROP-04)
    // =========================================================================

    @Test
    fun testP9Interop04NodeRestartParity() = runBlocking {
        // Run 1: Bootstrap station, discover peer, save state
        val (peerPriv, peerPub) = PureCryptoEngine.generateX25519KeyPair()
        val peerIkPub = PureCryptoEngine.deriveSigningPublicKey(peerPriv)
        val peerIdHash = PureCryptoEngine.deriveIdentityHash(peerIkPub)
        val peerNodeId = PureCryptoEngine.deriveNodeId64(peerIdHash)

        val wifiEngine1 = DesktopWifiEngine(NoOpLogger)
        val router1 = DesktopMeshRouter(keyStorage, database, wifiEngine1, NoOpLogger)
        val myNodeId1 = router1.myNodeId

        // Discover peer
        val ibcSig = PureCryptoEngine.signIbc(peerPriv, peerPub, 1L, 0L)
        val announce = PeerAnnouncePayload(
            announceVersion = 2,
            flags = 0,
            ikPub = peerIkPub,
            ekPub = peerPub,
            keyVersion = 1L,
            notBefore = 0L,
            ibcSignature = ibcSig,
            announceCounter = 1L,
            alias = "PersistentPeer"
        )
        val peerIdent = PeerIdentity(
            identityHash = peerIdHash,
            ikPub = peerIkPub,
            ekPub = peerPub,
            keyVersion = 1L,
            lastAnnounceCounter = 1L,
            trustState = TrustState.SEEN,
            nodeId64 = peerNodeId
        )
        router1.identityRepository.onAuthenticatedAnnounce(peerIdent, announce)
        router1.stop()

        // Run 2: Station restarts using same database and key vault
        val reloadedKeyStorage = DesktopPassphraseKeyStorage(tempDir, testPassphrase)
        val reloadedDb = DesktopDatabase(dbFile)
        val wifiEngine2 = DesktopWifiEngine(NoOpLogger)
        val router2 = DesktopMeshRouter(reloadedKeyStorage, reloadedDb, wifiEngine2, NoOpLogger)

        // Invariant: Node ID and keys remain identical
        assertThat(router2.myNodeId).isEqualTo(myNodeId1)

        // Invariant: Discovered peer identity is restored into runtime IdentityStore
        val restoredInStore = router2.identityRepository.identityStore.get(peerIdHash)
        assertThat(restoredInStore).isNotNull()
        assertThat(restoredInStore?.trustState).isEqualTo(TrustState.SEEN)

        // Invariant: DM can be sent without rediscovery
        val sentMsgId = router2.sendDirectMessage(peerNodeId, "Hello after station restart!")
        assertThat(sentMsgId).isNotNull()
        router2.stop()
    }

    // =========================================================================
    // 9. Real OS Socket Transport Integration (P9-NET-01)
    // =========================================================================

    @Test
    fun testP9RealNetworkSocketTransportFlow() {
        // 1. Initialize Desktop node with real TCP/UDP engines
        val desktopWifiEngine = DesktopWifiEngine(NoOpLogger)
        val desktopRouter = DesktopMeshRouter(keyStorage, database, desktopWifiEngine, NoOpLogger)
        val desktopNodeId = desktopRouter.myNodeId
        val desktopPubKey = desktopRouter.myPublicKey

        desktopRouter.start() // Binds ServerSocket on TCP_DATA_PORT (42426)

        try {
            // 2. Initialize simulated Android node credentials
            val (androidPriv, androidPub) = PureCryptoEngine.generateX25519KeyPair()
            val androidIkPub = PureCryptoEngine.deriveSigningPublicKey(androidPriv)
            val androidIdHash = PureCryptoEngine.deriveIdentityHash(androidIkPub)
            val androidNodeId = PureCryptoEngine.deriveNodeId64(androidIdHash)
            val androidCredentials = LinkAuthLocalCredentials.create(androidPriv)

            // Announce Android peer to Desktop node (sets initial trust state SEEN)
            val ibcSig = PureCryptoEngine.signIbc(androidPriv, androidPub, 1L, 0L)
            val announce = PeerAnnouncePayload(
                announceVersion = 2,
                flags = 0,
                ikPub = androidIkPub,
                ekPub = androidPub,
                keyVersion = 1L,
                notBefore = 0L,
                ibcSignature = ibcSig,
                announceCounter = 1L,
                alias = "AndroidPixel9"
            )
            val androidIdentity = PeerIdentity(
                identityHash = androidIdHash,
                ikPub = androidIkPub,
                ekPub = androidPub,
                keyVersion = 1L,
                lastAnnounceCounter = 1L,
                trustState = TrustState.SEEN,
                nodeId64 = androidNodeId
            )
            runBlocking {
                desktopRouter.identityRepository.onAuthenticatedAnnounce(androidIdentity, announce)
            }
            assertThat(desktopRouter.identityRepository.identityStore.get(androidIdHash)?.trustState).isEqualTo(TrustState.SEEN)

            // 3. Connect real TCP client socket to Desktop ServerSocket
            val clientSocket = Socket()
            clientSocket.connect(InetSocketAddress("127.0.0.1", DesktopWifiEngine.TCP_DATA_PORT), 3000)
            clientSocket.tcpNoDelay = true
            val inStream = DataInputStream(clientSocket.getInputStream())
            val outStream = DataOutputStream(clientSocket.getOutputStream())

            val androidLinkHandle = "127.0.0.1:${clientSocket.localPort}"
            val androidLinkAuthSession = LinkAuthSession(
                linkHandle = androidLinkHandle,
                localCredentials = androidCredentials,
                clock = SystemClock()
            )

            // 4. Complete wire-level LINK_AUTH handshake over live TCP socket
            // Desktop sends HELLO
            val desktopHelloBytes = WifiFrameCodec.readFrame(inStream, isPostAuth = false, AtomicInteger(0))
            assertThat(desktopHelloBytes).isNotNull()
            val desktopHello = MeshPacket.deserialize(desktopHelloBytes!!)!!
            val helloRes = androidLinkAuthSession.processIncomingPacket(desktopHello)
            assertThat(helloRes).isInstanceOf(LinkAuthStepResult.InProgress::class.java)

            // Android sends HELLO
            val androidHello = androidLinkAuthSession.createHelloPacket()
            WifiFrameCodec.writePlaintextFrame(outStream, MeshPacket.serialize(androidHello))

            // Desktop sends CONFIRM
            val desktopConfirmBytes = WifiFrameCodec.readFrame(inStream, isPostAuth = false, AtomicInteger(0))
            assertThat(desktopConfirmBytes).isNotNull()
            val desktopConfirm = MeshPacket.deserialize(desktopConfirmBytes!!)!!
            val confirmRes = androidLinkAuthSession.processIncomingPacket(desktopConfirm)
            assertThat(confirmRes).isInstanceOf(LinkAuthStepResult.InProgress::class.java)

            // Android sends CONFIRM (transitions Android session to AUTHENTICATED)
            val androidConfirm = androidLinkAuthSession.createConfirmPacket()
            WifiFrameCodec.writePlaintextFrame(outStream, MeshPacket.serialize(androidConfirm))
            val androidProof = androidLinkAuthSession.getSuccessProof()!!

            // 5. Verify T4 transition (SEEN -> LINKED) in Desktop repository & database
            runBlocking {
                var isLinked = false
                for (i in 1..30) {
                    delay(100)
                    if (desktopRouter.identityRepository.identityStore.get(androidIdHash)?.trustState == TrustState.LINKED) {
                        isLinked = true
                        break
                    }
                }
                assertThat(isLinked).isTrue()
            }
            assertThat(database.getIdentity(PureCryptoEngine.bytesToHex(androidIdHash))?.trustState).isEqualTo(TrustState.LINKED.name)

            // 6. Android -> Desktop DM over real TCP socket with AES-GCM link frame encryption
            val dmPacket = DirectMessagePacketBuilder.build(
                senderNodeId64 = androidNodeId,
                senderIdentityHash = androidIdHash,
                senderPrivateKey = androidPriv,
                recipientNodeId64 = desktopNodeId,
                peerPublicKey = desktopPubKey,
                plaintext = "Hello from Android over real Wi-Fi TCP!".toByteArray(Charsets.UTF_8),
                timestampSec = System.currentTimeMillis() / 1000L,
                messageId = UUID.randomUUID()
            )
            val dmRawBytes = MeshPacket.serialize(dmPacket)
            WifiFrameCodec.writeEncryptedFrame(outStream, dmRawBytes, androidProof.linkKey)

            // Verify Desktop receives, validates, and stores DM in database
            runBlocking {
                var received = false
                for (i in 1..30) {
                    delay(100)
                    val msgs = database.getDirectConversation(androidNodeId, desktopNodeId)
                    if (msgs.any { it.text == "Hello from Android over real Wi-Fi TCP!" }) {
                        received = true
                        break
                    }
                }
                assertThat(received).isTrue()
            }

            // Verify Desktop sends automated ACK back to Android over socket (along with presence announce)
            var ackPacket: MeshPacket? = null
            for (attempt in 1..5) {
                val frameBytes = WifiFrameCodec.readFrame(inStream, isPostAuth = true, AtomicInteger(0))
                if (frameBytes != null) {
                    val framePlaintext = PureCryptoEngine.decryptTransportFrame(frameBytes, androidProof.linkKey)
                    val p = MeshPacket.deserialize(framePlaintext)!!
                    if (p.type == PacketType.ACK) {
                        ackPacket = p
                        break
                    }
                }
            }
            assertThat(ackPacket).isNotNull()
            assertThat(ackPacket!!.type).isEqualTo(PacketType.ACK)
            assertThat(ackPacket!!.recipientId).isEqualTo(androidNodeId)

            // 7. Desktop -> Android DM over socket
            val sentMsgId = desktopRouter.sendDirectMessage(androidNodeId, "Hello back to Android from Desktop!")
            assertThat(sentMsgId).isNotNull()

            var desktopDmPacket: MeshPacket? = null
            for (attempt in 1..5) {
                val frameBytes = WifiFrameCodec.readFrame(inStream, isPostAuth = true, AtomicInteger(0))
                if (frameBytes != null) {
                    val framePlaintext = PureCryptoEngine.decryptTransportFrame(frameBytes, androidProof.linkKey)
                    val p = MeshPacket.deserialize(framePlaintext)!!
                    if (p.type == PacketType.DIRECT_MESSAGE) {
                        desktopDmPacket = p
                        break
                    }
                }
            }
            assertThat(desktopDmPacket).isNotNull()
            assertThat(desktopDmPacket!!.type).isEqualTo(PacketType.DIRECT_MESSAGE)
            assertThat(desktopDmPacket!!.recipientId).isEqualTo(androidNodeId)

            // 8. Disconnect socket -> Verify T5 (LINKED -> SEEN)
            clientSocket.close()
            runBlocking {
                var isSeen = false
                for (i in 1..30) {
                    delay(100)
                    if (desktopRouter.identityRepository.identityStore.get(androidIdHash)?.trustState == TrustState.SEEN) {
                        isSeen = true
                        break
                    }
                }
                assertThat(isSeen).isTrue()
            }
            assertThat(database.getIdentity(PureCryptoEngine.bytesToHex(androidIdHash))?.trustState).isEqualTo(TrustState.SEEN.name)

            // 9. Reconnect socket -> Verify T4 re-linking & identity continuity
            val clientSocket2 = Socket()
            clientSocket2.connect(InetSocketAddress("127.0.0.1", DesktopWifiEngine.TCP_DATA_PORT), 3000)
            clientSocket2.tcpNoDelay = true
            val inStream2 = DataInputStream(clientSocket2.getInputStream())
            val outStream2 = DataOutputStream(clientSocket2.getOutputStream())

            val androidLinkAuthSession2 = LinkAuthSession(
                linkHandle = "127.0.0.1:${clientSocket2.localPort}",
                localCredentials = androidCredentials,
                clock = SystemClock()
            )

            // Handshake 2
            val dHelloBytes2 = WifiFrameCodec.readFrame(inStream2, isPostAuth = false, AtomicInteger(0))!!
            androidLinkAuthSession2.processIncomingPacket(MeshPacket.deserialize(dHelloBytes2)!!)
            val aHello2 = androidLinkAuthSession2.createHelloPacket()
            WifiFrameCodec.writePlaintextFrame(outStream2, MeshPacket.serialize(aHello2))

            val dConfirmBytes2 = WifiFrameCodec.readFrame(inStream2, isPostAuth = false, AtomicInteger(0))!!
            androidLinkAuthSession2.processIncomingPacket(MeshPacket.deserialize(dConfirmBytes2)!!)
            val aConfirm2 = androidLinkAuthSession2.createConfirmPacket()
            WifiFrameCodec.writePlaintextFrame(outStream2, MeshPacket.serialize(aConfirm2))

            // Verify re-linked
            runBlocking {
                var reLinked = false
                for (i in 1..30) {
                    delay(100)
                    if (desktopRouter.identityRepository.identityStore.get(androidIdHash)?.trustState == TrustState.LINKED) {
                        reLinked = true
                        break
                    }
                }
                assertThat(reLinked).isTrue()
            }
            clientSocket2.close()

        } finally {
            desktopRouter.stop()
        }
    }
}
