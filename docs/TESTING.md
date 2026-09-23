# BIT FOR US / MeshWhisper — Verification & Testing Specification

**Status:** Up to Date with Phase P9/P10  
**Applies to:** `:core`, `:app`, `:desktop`  
**Supersedes:** Legacy Testing Specification v1.4  

---

## 1. Executive Testing Summary

All automated unit and integration tests compile and run **100% offline** without requiring active internet access, external mock servers, Android emulators, or physical Bluetooth hardware.

```powershell
.\gradlew.bat :core:test :app:testDebugUnitTest :desktop:test
```

### Verified Test Matrix (347 / 347 Tests Passing, 0 Failures, 0 Ignored)

| Module | Subsystem | Test Command | Tests | Passing | Failing | Skipped | Status |
| :--- | :--- | :--- | :---: | :---: | :---: | :---: | :---: |
| **`:core`** | Protocol, Pipeline, Crypto, Audio, Breadcrumbs | `.\gradlew.bat :core:test` | **198** | **198** | **0** | **0** | **100% PASS ✅** |
| **`:app`** | Android UI, DB v13, Voice, Migrations, Plus Codes | `.\gradlew.bat :app:testDebugUnitTest` | **128** | **128** | **0** | **0** | **100% PASS ✅** |
| **`:desktop`** | Workstation Parity, Vault, Real Socket | `.\gradlew.bat :desktop:test` | **21** | **21** | **0** | **0** | **100% PASS ✅** |
| **Combined** | Full Repository Multi-Module Suite | `.\gradlew.bat test` | **347** | **347** | **0** | **0** | **100% PASS ✅** |

---

## 2. Test Suite Breakdown by Module

### 2.1. Core Module (`:core` — 198 Tests)
Located in [`core/src/test/java/com/meshwhisper/core/`](file:///c:/Users/hp/Downloads/BIT%20FOR%20US/core/src/test/java/com/meshwhisper/core/):

- **`protocol/PacketPipelineTest.kt`**:
  - Sequential gate enforcement (S0 through S7).
  - Pre-auth deduplication cache poisoning immunity ($C\text{-}05$).
  - Future timestamp skew rejection (>120s) and past window expiration drops.
  - CPU signature budget rate-limiting (max 32 verifications/sec/link, $C\text{-}16$).
  - Anti-spoofing enforcement: `BE_u64(identityHash[0..8]) != header.senderId` drops ($C\text{-}02$).
  - Zero-auth-tag whitelist exemption strictly for `LINK_AUTH` ($C\text{-}08$).
- **`protocol/LocationBreadcrumbPayloadTest.kt` (7 Tests)**:
  - Big-endian 27-byte compact binary struct serialization and deserialization.
  - Side-channel defense: strict assertion that payload is padded to exactly 64 bytes.
  - Non-UTF8 magic prefix collision defense: strings starting with "L" ("Location? Hello", "Look at this") guaranteed not to parse as breadcrumb.
  - Coordinate bounds verification: clamping or rejection of out-of-bounds latitude/longitude.
  - Accuracy handling: $0.1\text{m}$ resolution and zero-decimeter unknown accuracy handling.
  - Revoke trigger payload encoding and decoding.
- **`transport/LinkAuthTest.kt`**:
  - Deterministic HELLO (169B) and CONFIRM (65B) serialization and parsing.
  - Transcript $T$ construction and $K_{\text{link}}$ key derivation.
  - SIGMA identity-misbinding defense: rejection of modified self/peer identity hashes in CONFIRM.
  - Reflection attack detection: rejection when peer identity matches local identity.
- **`identity/TrustStateMachineTest.kt`**:
  - Monotonic transitions $T_1$ through $T_{11}$ and migration rule $T_{12}$.
  - NodeId64 collision detection ($T_7$) transitioning colliding records to `CONFLICTED`.
  - Camera QR collision resolution ($T_8$) marking winner `VERIFIED` and loser `BLOCKED`.
  - Key rotation demotion ($T_6$) for verified peers vs. preservation for unverified peers.
- **`crypto/PureCryptoEngineTest.kt`**:
  - X25519 ECDH key exchange and Ed25519 digital signature roundtrips.
  - Canonical `identityHash` derivation: `SHA-256("MW/NODE/v2" || 0x00 || IK_pk)`.
  - Hourly epoch session key derivation and bounded 256-entry LRU cache.
  - AES-256-GCM authenticated encryption and 37-byte AAD tamper detection.
- **`protocol/DirectMessagePacketBuilderTest.kt`**:
  - Shared authoritative packet assembly for direct messages.
  - Content transcript building (115 bytes) and trailing hop signature placement ($C\text{-}01$).
- **`router/MeshRouteEngineTest.kt`**:
  - Dijkstra shortest-path calculations over direct links and topology edges.
  - Transport weighting (Wi-Fi = 1, BLE = 5) and quarantine penalties (+50).
  - Dynamic rerouting upon link failure.
- **`audio/AdpcmAndJitterBufferTest.kt`**:
  - 4-bit IMA ADPCM 4:1 compression fidelity (160 samples PCM $\to$ 80 bytes ADPCM).
  - Jitter buffer sequence reordering, loss concealment, and late frame dropping.
- **`protocol/TrafficControllerTest.kt`**:
  - 4-tier QoS scheduling (Tier 0 Emergency preemption over standard and bulk traffic).
  - Anti-starvation deficit-weighted scheduling across queues.

---

### 2.2. Android Application Module (`:app` — 128 Tests)
Located in [`app/src/test/java/com/meshwhisper/app/`](file:///c:/Users/hp/Downloads/BIT%20FOR%20US/app/src/test/java/com/meshwhisper/app/):

- **`location/PlusCodeHelperTest.kt` (3 Tests)**:
  - 100% offline 10-char Open Location Code generation (e.g. `8FVC7JVW+9V`).
  - Pole boundary conditions (+90° / -90°) and anti-meridian wrapping (+180° / -180°).
  - Deterministic encoding matching standard Open Location Code test vectors.
- **`location/LocationBreadcrumbIngressTest.kt` (4 Tests)**:
  - Reinstall ordering recovery: newer satellite fix timestamp supersedes reset sequence counter.
  - Out-of-order replay drops: stale fix timestamps or duplicate sequence numbers rejected.
  - Clock drift defense: fix timestamps $>10$ minutes in the future rejected.
  - Opt-in & revocation: `shareLocationWithContact` enforcement and history purge upon `REVOKE`.
- **`data/DatabaseMigrationP7Test.kt`**:
  - `MIGRATION_12_13` Room schema verification: adding `shareLocationWithContact` to `peers`, extending `last_known_locations`, and creating `breadcrumb_history`.
  - Room schema version 13 JSON schema validation.
- **`ui/TrustStateUiP8Test.kt`**:
  - Live UI rendering of trust state badges (`[VERIFIED]`, `[LINKED]`, `[SEEN]`, `[CONFLICTED]`, `[BLOCKED]`).
  - Security warning banners on key changes (`hasKeyChanged = true`).
  - Strict UI isolation from direct trust mutation.
- **`arch/P8ArchitectureRulesTest.kt`**:
  - Enforces that no Android UI or router components manufacture trust states directly.
  - Validates that Room v13 entities do not expose independent mutable verification booleans.
- **`identity/P8SecurityEdgeCasesTest.kt`**:
  - Equivocation attacks ($C\text{-}12$): same `keyVersion`, different `ekPub` drops packet without state mutation.
  - Rollback attacks ($C\text{-}12$): lower `keyVersion` drops packet.
  - Rapid connect/disconnect cycling maintaining trust state consistency ($T_4 \leftrightarrow T_5$).
- **`identity/P8CameraQrScanTest.kt`**:
  - Out-of-band QR code payload parsing and mutual key binding.
  - Collision resolution flow: scanning one party in a collision transitions it to `VERIFIED` and marks the imposter `BLOCKED`.
- **`data/P8MigrationTest.kt`**:
  - Room database schema migration from v11 to v12 (`MIGRATION_11_12`).
  - Column additions for media-at-rest encryption and trust state mappings ($T_{12}$).
- **`data/P8PanicWipeTest.kt`**:
  - Emergency panic wipe flow: closing database connections, zero-fill wiping SQLite/WAL/SHM files, and deleting AndroidKeyStore alias.
- **`voice/VoiceCallManagerTest.kt`**:
  - Strict direct 1-hop constraint ($ttl = 1$).
  - Signaling state machine (`OFFER`, `ANSWER`, `DECLINE`, `HANGUP`, `BUSY`).
  - Ringing timeout (30s) and heartbeat disconnect detection.
- **`wifi/WifiConnectionLimitTest.kt`**:
  - Enforces the hard connection cap of 5 concurrent authenticated sessions.
- **`router/MultiHopRelayReliabilityTest.kt`**:
  - End-to-end multi-hop delivery, lost-ACK recovery, and intermediate custody cleanup.

---

### 2.3. Desktop Module (`:desktop` — 21 Tests)
Located in [`desktop/src/test/java/com/meshwhisper/desktop/`](file:///c:/Users/hp/Downloads/BIT%20FOR%20US/desktop/src/test/java/com/meshwhisper/desktop/):

- **`DesktopParityP9Test.kt` (21 Tests)**:
  - `P9-A01`: T-ARCH-01 Architecture check — Desktop does not import Android SDK packages or reference forbidden tokens in router/media.
  - `P9-A02`: Desktop UI architecture check — `DesktopMainWindow` contains zero crypto logic or `PureCryptoEngine` imports.
  - `P9-LINK-01`: Deterministic LINK_AUTH handshake between Android credentials and Desktop node.
  - `P9-LINK-02`: Transport ingress with valid LINK_AUTH accepted as `AUTHENTICATED`.
  - `P9-LINK-03`: Transport ingress with pending link drops non-discovery traffic with `Link is PENDING` in S0.
  - `P9-LINK-04`: Transport disconnection transitions peer `LINKED -> SEEN` ($T_5$).
  - `P9-ID-01`: `DesktopPassphraseKeyStorage` encrypts and recovers identically across station restarts.
  - `P9-ID-02`: Fail-closed vault — wrong passphrase or corrupted file throws `SecurityException` and refuses to overwrite.
  - `P9-ID-03`: Fresh authenticated announce creates `SEEN` identity ($T_1$) in SQLite and runtime store.
  - `P9-ID-04`: Key rotation — `VERIFIED` peer rotating EK demotes to `LINKED` ($T_6$) with `hasKeyChanged = true`.
  - `P9-ID-05`: Key rotation — `SEEN`, `LINKED`, or `IMPORTED` peer rotating EK updates key material and preserves current trust state.
  - `P9-COLLISION-01`: NodeId64 collision detection ($T_7$) — distinct identity hashes with colliding `nodeId64` transition to `CONFLICTED`; unicast routing fails closed.
  - `P9-DB-01`: `DesktopDatabase` schema contains all required tables matching Room parity.
  - `P9-DB-02`: `getUniqueIdentityByNodeId` strict routing contracts verified.
  - `P9-DM-01`: Direct message built by `DirectMessagePacketBuilder` is decrypted and verified by recipient pipeline.
  - `P9-MEDIA-01`: Media at rest encryption starts with `MWMEDIA1`, uses per-file HKDF, and fails closed upon tampering.
  - `P9-INTEROP-04`: Node restart parity — restarted station reloads existing keys and identities from encrypted vault and SQLite database.
  - `P9-NET-01`: **Real OS Network Socket Transport Integration (`testP9RealNetworkSocketTransportFlow`)** (detailed below).

---

## 3. Real OS Socket Transport Integration (`P9-NET-01`)

The test **`testP9RealNetworkSocketTransportFlow`** explicitly exercises real host operating system TCP sockets against port `42426` (`127.0.0.1:42426`).

### What This Test Exercises
1. Binds a real OS `ServerSocket` on TCP port `42426` inside `DesktopWifiEngine`.
2. Connects a real client `Socket` simulating an Android peer over `127.0.0.1`.
3. Exchanges wire-level plaintext frames (`WifiFrameCodec.readFrame` / `writePlaintextFrame`) to complete the mutual `LINK_AUTH` handshake:
   - Android client reads Desktop's `HELLO` (169 B).
   - Android client sends its `HELLO` (169 B).
   - Android client reads Desktop's `CONFIRM` (65 B).
   - Android client sends its `CONFIRM` (65 B).
4. Verifies mutual derivation of $K_{\text{link}}$ and triggers transition $T_4$ (`SEEN -> LINKED`) in both `DesktopIdentityRepository` and persistent SQLite database.
5. Transmits an AES-256-GCM encrypted `DIRECT_MESSAGE` frame from Android to Desktop; verifies pipeline acceptance, SQLite storage, and UI emission.
6. Verifies Desktop automatically derives and dispatches an encrypted `ACK` back over the TCP socket.
7. Transmits an encrypted `DIRECT_MESSAGE` from Desktop to Android over the socket; decrypts and verifies content.
8. Closes the client socket; verifies Desktop detects socket closure and triggers $T_5$ (`LINKED -> SEEN`).
9. Reconnects with a new client socket on port `42426`; verifies full re-handshake and $T_4$ re-linking with preserved identity continuity.

> [!IMPORTANT]
> **Distinction Between Localhost and Physical Wi-Fi**:
> `P9-NET-01` verifies that real network byte streams, socket framers, and encryption work correctly over the OS network stack. It **does NOT** test physical Wi-Fi radio propagation, physical router NAT traversal, RF interference, or multi-device wireless latencies.

---

## 4. Physical Android ↔ Desktop LAN Acceptance

**Status: PENDING** *(Awaiting physical execution with a live Android phone and Desktop PC on a shared Wi-Fi router).*

### Field Verification Runbook

To physically prove end-to-end communication across real hardware:

1. **Launch Desktop Station**:
   ```powershell
   .\gradlew.bat :desktop:run
   ```
   - Enter your passphrase to unlock `identity.vault`.
   - Desktop UI starts listening on TCP port `42426` and broadcasts UDP beacons on port `42425`.
   - Note the Desktop IP displayed in the window status bar (e.g., `192.168.1.105`).
2. **Launch Android App**:
   - Connect the physical Android device to the **same Wi-Fi router**.
   - Launch MeshWhisper on Android.
3. **Observe Automated Authentication**:
   - Both nodes discover each other via UDP beacons on port `42425`.
   - Android connects to Desktop on TCP port `42426`.
   - Mutual `LINK_AUTH` executes; Desktop logs show:
     ```
     [INFO] [DesktopIdentityRepository] T4 transition: ... transitioned SEEN -> LINKED upon mutual LINK_AUTH
     ```
   - Android appears in the Desktop peer list with the badge **`[LINKED]`**.
4. **Bidirectional Direct Messaging**:
   - Send a direct message from Android to Desktop; confirm delivery and automatic ACK.
   - Send a direct message from Desktop to Android; confirm receipt on phone screen.
5. **Disconnect & Reconnect**:
   - Toggle Wi-Fi OFF on Android; Desktop logs $T_5$ (`LINKED -> SEEN`) and badge updates to **`[SEEN]`**.
   - Toggle Wi-Fi ON on Android; nodes reconnect, execute `LINK_AUTH`, and badge updates back to **`[LINKED]`** with full chat history preserved.
