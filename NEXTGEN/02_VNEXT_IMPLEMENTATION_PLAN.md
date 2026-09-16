# BIT FOR US / MeshWhisper — Secure Protocol vNext
# FILE 2 of 3: IMPLEMENTATION PLAN

**Reads with:** `01_VNEXT_PROTOCOL_FROZEN.md` (normative protocol), `03_VNEXT_TESTS_AND_AGENT_RULES.md` (tests, rules, done criteria).
**Scope:** repository `BIT-FOR-US-main` as committed. Every file named below exists in the repo; nothing is invented.

This file ends with **SECTION B — IMPLEMENTATION ROADMAP**.

---

# 9. FILE / MODULE IMPLEMENTATION MAP

Legend for **MIGRATION RISK**: 🟥 high (wire-visible or data-destructive) · 🟧 medium (behavioural) · 🟩 low (mechanical).

---

## 9.1 `:core` — protocol and crypto

### `core/src/main/java/com/meshwhisper/core/protocol/MeshPacket.kt`
- **CURRENT:** 40-byte header serialize/deserialize, `PacketType` enum (17 types, codes `0x00–0x10`), `computeAad`, `decrementTtl`, TTL/broadcast helpers.
- **REQUIRED CHANGE:** add version-encoded type bytes (`(v<<5)|code`) and reject `protocolVersion != 1` on parse. Enforce `payloadLen <= MAX_PAYLOAD_SIZE` and `bytes.size == OVERHEAD_SIZE + payloadLen` exactly (closes S-13). Add `MAX_TTL[type]` and clamp on parse. Add `PAST_WINDOW[type]`. Add `LINK_AUTH`, `CUSTODY_ACK`; mark `0x22` retired. Add `SIG_TRANSCRIPT` builder. **Leave `computeAad` byte-identical.**
- **NEW RESPONSIBILITY:** canonical, strict, version-aware wire codec + transcript construction. No trust logic.
- **DEPENDENCIES:** none.
- **RISK:** 🟥 wire format.

### `core/src/main/java/com/meshwhisper/core/crypto/PureCryptoEngine.kt`
- **CURRENT:** X25519 keygen/agreement, HKDF, Ed25519 sign/verify, `deriveNodeId` (X25519 pub → u64), AES-GCM with a legacy UUID-IV fallback, session-key LRU, PBKDF2 channel keys.
- **REQUIRED CHANGE:** **delete the legacy IV fallback** (`:268–283`, finding S-16). Add `deriveIdentityHash(IK_pk)`, `deriveNodeId64(identityHash)`, `buildIbcTranscript`, `verifyIbc`, `deriveLinkKey`, `deriveCallKey`, `deriveFileKey`. Change `deriveNodeId` to take `identityHash`, not a raw public key. Add key zeroization on cache eviction. **Do not touch** `deriveSigningPrivateKey`, `derivePeerSessionKey`, `getEpochForTimestamp`, `encrypt`, or the salts/info strings — changing any of them changes every existing device's identity.
- **NEW RESPONSIBILITY:** the only crypto surface; sole owner of identity derivation.
- **DEPENDENCIES:** BouncyCastle (already present).
- **RISK:** 🟥 identity derivation.

### `core/src/main/java/com/meshwhisper/core/protocol/ProfilePayload.kt`
- **CURRENT:** `MWP1` payload with `signingPublicKey` + `signature`, `computeCanonicalBytes()`, two `verifySignature()` overloads. Excellent parser; the fields are the S-1 vector.
- **REQUIRED CHANGE:** v2 format `MWP2` — **delete `signingPublicKey`, `signature`, `computeCanonicalBytes`, both `verifySignature` overloads**. Keep the strict bounds, domain tag and exact-length trailing check verbatim.
- **NEW RESPONSIBILITY:** pure profile content codec. Authentication belongs to the packet hop signature.
- **RISK:** 🟥 wire format + removes a public API used by `ProfilePayloadTest` and `ProfileAntiRollbackTest`.

### `core/src/main/java/com/meshwhisper/core/router/MeshRouteEngine.kt`
- **CURRENT:** Dijkstra over direct neighbours + gossiped edges, freshness filter, failed-link quarantine, `pruneStaleEntries()` (never called).
- **REQUIRED CHANGE:** edges keyed by `(identityHash, identityHash)` sorted; `STAGED`/`CONFIRMED` state; only `CONFIRMED` enters the graph; 512-origin LRU; `pruneStaleEntries()` on a 30 s timer; replace binary `markLinkFailed` with a `LinkTrust` score and the cost function from the vNext design; **cache the routing table** and recompute on edge/link events instead of per packet.
- **DEPENDENCIES:** identity store.
- **RISK:** 🟧 routing behaviour.

### `core/src/main/java/com/meshwhisper/core/protocol/MeshTrafficController.kt`
- **CURRENT:** correct 4-tier bounded queue with anti-starvation. **Instantiated at `MeshRouter.kt:52` and never used.**
- **REQUIRED CHANGE:** none to its logic. Wire it into the egress path (C-19) and add a per-identity relay slot accounting hook.
- **RISK:** 🟩 (the class itself is already tested).

### `core/src/main/java/com/meshwhisper/core/router/LruDedupCache.kt`
- **CURRENT:** synchronized LRU.
- **REQUIRED CHANGE:** none. It becomes read-through only (C-05) — that is a caller-side change, not a class change.
- **RISK:** 🟩.

### NEW in `:core` (no existing file covers these)
| New file | Responsibility |
|---|---|
| `core/.../protocol/PacketPipeline.kt` | S0–S7 + `AuthenticatedPacket` / `AdmittedChunk` construction. Private constructors. |
| `core/.../protocol/AuthenticatedPacket.kt` | the contract type |
| `core/.../identity/PeerIdentity.kt` | `identityHash`, `ikPub`, `ekPub`, `keyVersion`, `lastAnnounceCounter`, `trustState` |
| `core/.../identity/IdentityStore.kt` | interface; `:app` backs it with Room, `:desktop` with its own DB |
| `core/.../transport/LinkAuth.kt` | HELLO/CONFIRM construction and verification, transport-agnostic |
| `core/.../transport/Transport.kt` | `attemptSend` / `broadcast` / `events` — the test seam |
| `core/.../util/Clock.kt`, `RandomSource.kt` | determinism seams for tests |
| `core/.../custody/CustodyManager.kt` | ownership + release rules |
| `core/.../protocol/ResourceLimits.kt` | the §8 table as constants, single source of truth |

---

## 9.2 `:app` — Android

### `app/src/main/java/com/meshwhisper/app/router/MeshRouter.kt` (1 920 lines)
- **CURRENT:** parsing, crypto, routing, persistence, S&F, media, profiles, voice signalling, logging. Contains F-0 (`:372`, `:560`, `:1096`), S-1 (`:1697`), S-3 (`:243`), pre-auth persistence (`:271`, `:1846`), custody deletion on transport success (`:1457`, `:1470`).
- **REQUIRED CHANGE:** reduce to a dispatcher. Ingest moves to `PacketPipeline`; per-type logic becomes handlers taking `AuthenticatedPacket`. Delete `registerDirectNode` call site. Delete all in-handler crypto and identity lookups. Target ≤ 600 lines.
- **NEW RESPONSIBILITY:** dispatch, orchestration, outbound packet construction. **Zero trust decisions.**
- **DEPENDENCIES:** everything.
- **RISK:** 🟥 largest single change in the project.

### `app/src/main/java/com/meshwhisper/app/crypto/CryptoEngine.kt`
- **CURRENT:** Keystore wrapping of `S`, alias/channel prefs, thin delegation to `PureCryptoEngine`. `signingPublicKey` exists and is never transmitted.
- **REQUIRED CHANGE:** expose `identityHash`, `nodeId64`, `keyVersion`, `ibcSignature`, `rotateEncryptionKey()`. Persist `keyVersion`, `announceCounter`, `lastEmittedTimestamp`. `applyIdentity()` now derives `nodeId` from `identityHash`, not from `publicKeyBytes`.
- **RISK:** 🟥 the device's `nodeId` changes (see §11).

### `app/src/main/java/com/meshwhisper/app/wifi/MeshWifiEngine.kt`
- **CURRENT:** self-asserted nodeId handshake (`:287–296`, S-2); `activePeers[id] = session` overwrite; 10 MB frame alloc before rate check (`:315`, S-11); unbounded half-open handshakes on `Dispatchers.IO` (`:255–272`, S-10); unrate-limited UDP → router (`:363`, S-9); every packet UDP-broadcast to `255.255.255.255` (`:192`).
- **REQUIRED CHANGE:** LINK_AUTH handshake keyed by `identityHash`; reject duplicate identities instead of overwriting; `MAX_PACKET_SIZE = 2104`; length validated before allocation; bounded pending pool on a dedicated 4-thread dispatcher with a 3 s budget; UDP restricted to ≤128 B beacons; delete the raw-mesh-packet UDP branch and the global broadcast; `K_link` stream encryption after CONFIRM.
- **RISK:** 🟥 transport behaviour.

### `app/src/main/java/com/meshwhisper/app/ble/MeshBleEngine.kt`
- **CURRENT:** GATT server/client, catch-all `ScanFilter.Builder().build()` at `:504`, `registerDirectNode(address, nodeId)`, `sendDirectPacket` returning "queued" as success, 15 ms write pacing, MAC-based symmetry tie-break.
- **REQUIRED CHANGE:** remove the catch-all scan filter; replace `registerDirectNode` with `bindLink(linkHandle, identityHash)` callable only from LINK_AUTH completion; rename `sendDirectPacket` → `attemptSend`; pre-auth links accept only `LINK_AUTH`. **Preserve** the symmetry tie-break and the 15 ms pacing.
- **RISK:** 🟧 discovery behaviour on real hardware.

### `app/src/main/java/com/meshwhisper/app/ble/BleFrameFramer.kt`
- **CURRENT:** 1 024 B frames, 4 sessions/peer, `sessions.keys.filter { startsWith(...) }` per frame (O(n) scan), reassembly up to ~260 KB.
- **REQUIRED CHANGE:** 512 B frames; reassembly ≤ 2 104 B total; 2 sessions per link; per-link index instead of the prefix scan; key by link handle.
- **RISK:** 🟧.

### `app/src/main/java/com/meshwhisper/app/ble/GattWriteRateLimiter.kt`
- **CURRENT:** 50 writes/s keyed by MAC; `remove()` called on central disconnect only (client-role leak, S-21).
- **REQUIRED CHANGE:** key by link handle; clear on disconnect in both roles (C-22).
- **RISK:** 🟩.

### `app/src/main/java/com/meshwhisper/app/ble/BleConstants.kt`
- **REQUIRED CHANGE:** add frame-size and session constants from §8. **Do not change the service/characteristic UUIDs** — they are the discovery contract.
- **RISK:** 🟩.

### `app/src/main/java/com/meshwhisper/app/media/MediaTransferManager.kt` (1 454 lines)
- **CURRENT:** good MEDIA_INIT bounds (4 096 / 20 MB) and bounded sessions; unbounded chunk size (`:906`, S-7); attacker-controlled file extension; plaintext files written to `filesDir`; DB placeholder row per MEDIA_INIT.
- **REQUIRED CHANGE:** enforce `chunkData.size <= 320` and cumulative ≤ `totalSizeBytes`; write-once per index; extension allow-list; filename regex; `previewLen <= 512`; transfer admission for relays; per-identity 8 MB/hour budget; encrypt files at rest under `MeshWhisperMediaMasterKey`; placeholder row only after admission.
- **RISK:** 🟧 (🟥 for the at-rest encryption, which changes how existing media files are read).

### `app/src/main/java/com/meshwhisper/app/voice/VoiceCallManager.kt`, `VoiceCallSession.kt`, `AndroidAudioStreamer.kt`, `AudioStreamer.kt`
- **CURRENT:** plaintext signalling and frames; `callSessionId` in the clear; frames bypass freshness and dedup entirely (`MeshRouter.kt:222`).
- **REQUIRED CHANGE:** `VoiceSignalPayload` gains `signalSeq`; `VoiceFramePayload` becomes `seq ‖ audioData` with the deterministic-nonce scheme (§3.14 of file 1); `K_call` pinned at setup with the OFFER epoch; per-direction seq enforcement; recent-call LRU. The state machine itself is sound — keep it.
- **RISK:** 🟥 wire format.

### `app/src/main/java/com/meshwhisper/app/data/MeshDatabase.kt`
- **CURRENT:** Room v11 on SQLCipher; **software fallback DB key** at `:243–252` (S-6); `fallbackToDestructiveMigration()`; `exportSchema = false`; `performHardWipe` misses media, prefs and notifications.
- **REQUIRED CHANGE:** migration 11→12; `exportSchema = true`; remove `fallbackToDestructiveMigration()` and add the explicit failure screen (C-20); gate the software fallback behind `BuildConfig.DEBUG`; add `MeshWhisperMediaMasterKey`; rewrite `performHardWipe` to the C-26 ordering.
- **RISK:** 🟥 data.

### `app/src/main/java/com/meshwhisper/app/data/dao/DAOs.kt`
- **CURRENT:** no trim for `messages`, `peers`, `profiles`, `last_known_locations`; newest-wins trim for S&F (S-12); purges only invoked from `announcePresence`.
- **REQUIRED CHANGE:** add `IdentityDao`; trim queries for every capped table; partitioned S&F trim (300 own / 200 relayed); move all purges to a 30 s maintenance timer.
- **RISK:** 🟧.

### `app/src/main/java/com/meshwhisper/app/data/model/Entities.kt`
- **CURRENT:** `PeerEntity` keyed by `nodeId`; `MessageStatus { PENDING, SENT, RELAYED, DELIVERED, FAILED, CANCELLED }`; `TopologyEdgeEntity` keyed by `(fromNode, toNode)` Longs.
- **REQUIRED CHANGE:** new `IdentityEntity` (PK `identityHashHex`); `PeerEntity` gains nullable `identityHashHex` and `trustState`, `nodeId` becomes a non-unique index; `TopologyEdgeEntity` gains `state` + both `identityHashHex` columns; **append** `EXPIRED` and `CUSTODY_HELD` to `MessageStatus` (Room 2.6 persists enums by **name**, so appending is safe — reordering or renaming is forbidden); `hopCount` written as `-1` (C-18).
- **RISK:** 🟥 schema.

### `app/src/main/java/com/meshwhisper/app/data/model/ProfileEntity.kt`
- **REQUIRED CHANGE:** stop writing `signature`; keep the column for migration compatibility.
- **RISK:** 🟩.

### `app/src/main/java/com/meshwhisper/app/ui/viewmodel/MeshViewModel.kt` (759 lines)
- **CURRENT:** QR/deep-link peer registration setting `isVerified = true` (`:273`, S-17); `emergencyPanicWipe()` in `viewModelScope` (`:555`, S-19); typing/notification plumbing.
- **REQUIRED CHANGE:** import path sets `IMPORTED`, camera scan sets `VERIFIED` (T2/T3); panic wipe moves to an application-scoped `NonCancellable` coroutine with the C-26 ordering; expose trust state, conflict and key-rotation banners; surface `EXPIRED` messages.
- **RISK:** 🟧.

### `app/src/main/java/com/meshwhisper/app/service/MeshForegroundService.kt`
- **CURRENT:** 4 s/12 s announce heartbeat; 5 s notification rebuild; purges hidden inside `announcePresence`.
- **REQUIRED CHANGE:** emit FLOOD announces (no location) and, separately, NEIGHBOR announces at 30 s (C-03); add a 30 s maintenance tick owning all purges and `pruneStaleEntries()`; notification rebuild to 15 s.
- **RISK:** 🟩.

### `app/src/main/java/com/meshwhisper/app/service/MessageNotifier.kt`
- **REQUIRED CHANGE:** a `cancelAll()` entry point for step 1 of panic wipe.
- **RISK:** 🟩.

### `app/src/main/java/com/meshwhisper/app/MeshApplication.kt`
- **CURRENT:** Keystore unwrap + SQLCipher open + `MeshRouter` construction synchronously on the main thread in `onCreate()`.
- **REQUIRED CHANGE:** move DB open and router construction off the main thread behind a ready-state flow; construct the pipeline and identity store.
- **RISK:** 🟧 startup ordering.

### `app/src/main/java/com/meshwhisper/app/protocol/MeshPacket.kt`
- **CURRENT:** typealias bridge to `:core`. Correct.
- **REQUIRED CHANGE:** add aliases for the new core types. Nothing else.
- **RISK:** 🟩.

### `app/src/main/java/com/meshwhisper/app/ui/screens/PacketInspectorScreen.kt`
- **REQUIRED CHANGE:** read from the in-memory ring buffer; the screen is `BuildConfig.DEBUG`-only (S-20).
- **RISK:** 🟩.

### `app/src/main/java/com/meshwhisper/app/ui/screens/IdentitySettingsScreen.kt`, `DirectChatDetailScreen.kt`, `MeshRadarScreen.kt`, `ui/util/QrCodeGenerator.kt`, `ui/components/CameraQrScanner.kt`, `ui/components/QrCodeAnalyzer.kt`
- **REQUIRED CHANGE:** new QR format `meshwhisper://node/v2?ik=&ek=&kv=&nb=&ibc=&alias=`; safety number from `identityHash`; trust-state badges; conflict and rotation banners; old/new fingerprint reconciliation screen for one release.
- **RISK:** 🟧 UX.

### `app/src/main/java/com/meshwhisper/app/ui/MainActivity.kt`
- **REQUIRED CHANGE:** deep link handler produces `IMPORTED` only.
- **RISK:** 🟩.

### `app/proguard-rules.pro`
- **REQUIRED CHANGE:** add `-assumenosideeffects class android.util.Log { public static *** d(...); public static *** v(...); public static *** i(...); }` (S-20).
- **RISK:** 🟩.

### `app/build.gradle.kts`
- **REQUIRED CHANGE:** `exportSchema` room arg; add `kotlinx-coroutines-test` where missing; add a property-based testing dependency (jqwik or kotest-property) for fuzzing.
- **RISK:** 🟩.

### Unchanged in `:app`
`location/LocationHelper.kt`, `logging/AndroidLogger.kt`, `media/MediaCompressor.kt`, `media/AudioHelper.kt`, `media/DocxTextExtractor.kt`, `media/PdfPageRenderer.kt`, `security/BiometricAuthManager.kt`, `ui/BiometricUnlockActivity.kt`, `ui/MainScreen.kt`, `ui/theme/*`, `ui/graph/GraphPhysics.kt`, `ui/components/{Avatar,CallOverlayDialog,FloatingNavBar,MediaBubbles,PermissionHandler,SaharaTopAppBar}.kt`, `ui/screens/{DirectChatsScreen,PublicMeshScreen}.kt`.

---

## 9.3 `:desktop`

### `desktop/src/main/java/com/meshwhisper/desktop/router/DesktopMeshRouter.kt` (592 lines)
- **CURRENT:** duplicated router that reproduces F-0 at `:187` and `:311`.
- **REQUIRED CHANGE:** **delete the duplicated validation entirely.** Call `:core`'s `PacketPipeline`. Keep only desktop-specific dispatch.
- **RISK:** 🟥 but the right kind — two implementations of one security protocol is how one of them ends up wrong, and one already has.

### `desktop/.../wifi/DesktopWifiEngine.kt`
- **REQUIRED CHANGE:** LINK_AUTH handshake, same limits as `:app`.
- **RISK:** 🟧.

### `desktop/.../crypto/DesktopPassphraseKeyStorage.kt`, `db/DesktopDatabase.kt`, `media/DesktopMediaManager.kt`, `ui/*`, `Main.kt`
- **REQUIRED CHANGE:** identity/`keyVersion` persistence; schema parity; media at rest. UI unchanged.
- **RISK:** 🟧.

---

## 9.4 Tests

| Existing file | Fate |
|---|---|
| `core/.../MultiHopRelayAndSecurityMeshTest.kt` | **rewrite.** `testPeerAnnounceAeadEncryptionAndSignature` uses an invented `"ALIAS=…;PUB=…;GPS=…"` payload and verifies with the correct signing key — it is why F-0 shipped green. Replace with production-builder round-trips. |
| `core/.../ProfilePayloadTest.kt` | rewrite for `MWP2`; the signature tests move to packet level |
| `app/.../router/ProfileAntiRollbackTest.kt` | rewrite: forgery must be tested **through the pipeline**, not against `ProfilePayload` in isolation |
| `app/.../SecurityAndRoutingTest.kt` | split into pipeline, identity and routing suites |
| `app/.../protocol/PacketSerializationTest.kt` | extend with golden vectors and strict-length cases |
| `app/.../ble/BleFrameFramerTest.kt`, `ble/GattWriteRateLimiterTest.kt` | extend for the new bounds and link-handle keying |
| `app/.../wifi/WifiConnectionLimitTest.kt` | extend to handshake authentication and exhaustion |
| `app/.../voice/VoiceCallManagerTest.kt` | extend for key/nonce lifecycle |
| `core/.../TrafficControllerTest.kt`, `app/.../router/DirectedStoreForwardAndPriorityTest.kt` | keep; add egress-integration tests now that the controller is wired |
| `core/.../router/MeshRouteEngineTest.kt` | extend for STAGED/CONFIRMED and trust weighting |
| `app/.../media/{MediaTransferTest,ReliableTransferTest}.kt` | extend for chunk bounds, write-once, admission |
| `core/.../CoreProtocolAndCryptoTest.kt`, `audio/AdpcmAndJitterBufferTest.kt`, `app/.../ui/graph/GraphPhysicsTest.kt` | keep as-is |
| `app/src/androidTest/.../AndroidSecurityAndStorageTest.kt` | extend for panic wipe, media-at-rest, Keystore fail-closed |
| `desktop/.../DesktopEngineAndDatabaseTest.kt` | rewrite against the shared pipeline |

**New test infrastructure (does not exist yet):** `core/src/test/.../harness/InMemoryMesh.kt`, `harness/MaliciousPeer.kt`, `harness/FakeTransport.kt`, `harness/TestClock.kt`, `core/src/test/resources/vectors/*.hex`.

---

## 9.5 Documentation

| File | Change |
|---|---|
| `docs/PROTOCOL.md` | regenerate from file 1 §3 |
| `docs/SECURITY.md` | rewrite from file 1 §2 and Section A; **delete** the false Ed25519-nodeId claim, the "public broadcasts require channel passphrase" claim, and the "securely overwrites" claim |
| `docs/LIMITATIONS.md` | remove the wake-lock claim; remove "voice streams are end-to-end encrypted" until it is true; add the O(N²) beacon cost, the ttl=1 location rule, and the MEDIA_CHUNK residual risk |
| `docs/TESTING.md` | regenerate from file 3 |
| `README.md` | fix the QoS `SHIPPED` row; fix `MAX_CONCURRENT_WIFI_CONNECTIONS` (doc says 8, code says 5); remove the non-existent `TrafficSchedulerTest.kt` reference |
| `ARCHITECTURE.md`, `docs/ARCHITECTURE.md` | reconcile (two near-duplicates); strip `file:///c:/Users/hp/...` local paths |
| `CHANGELOG.md` | vNext entry with the breaking-change notice |
| `docs/ROADMAP.md`, `CONTRIBUTING.md`, `DESIGN_.md`, `BIT_FOR_US_ARCHITECTURE_ANALYSIS.md` | update references; the analysis doc is superseded by these three files |

---

# 10. IMPLEMENTATION PHASES

Ordering principle: **the application is never left in a half-secured state that is worse than v1.** The wire break lands in one phase (P3) rather than being spread across several.

---

## PHASE 0 — Test seam and golden vectors
- **Objective:** make protocol bugs *detectable* before anything is changed.
- **Changes:** extract `Transport`, `Clock`, `RandomSource`, `IdentityStore`, `PacketStore` interfaces into `:core`; make `MeshRouter` depend on interfaces; build `InMemoryMesh`, `FakeTransport`, `MaliciousPeer`, `TestClock`; add a property-testing dependency.
- **Files:** new `core/.../transport/`, `core/.../util/`, `core/src/test/.../harness/`; constructor changes in `MeshRouter.kt`, `MeshApplication.kt`.
- **Tests first:** T-INT-01 (production builder → real bytes → real ingest) written against **v1** and **expected to fail on PEER_ANNOUNCE**. This is the proof that F-0 is real and that the harness works.
- **Acceptance:** T-INT-01 red on announce, green on DM; all 125 existing tests still pass.
- **Rollback:** pure refactor, revertible.

## PHASE 1 — Core protocol codec
- **Objective:** strict, version-aware, canonical wire codec.
- **Changes:** `MeshPacket.kt` version bits, strict length, TTL clamp table, freshness table, new type bytes, `SIG_TRANSCRIPT` builder. `ProfilePayload` → `MWP2`.
- **Files:** `core/.../protocol/MeshPacket.kt`, `ProfilePayload.kt`, new `ResourceLimits.kt`.
- **Depends on:** P0.
- **Tests first:** T-WIRE-01..12 golden vectors; T-FUZZ-01 parser fuzzing; T-WIRE-20 trailing-byte rejection; T-WIRE-21 oversize `payloadLen` rejection.
- **Acceptance:** golden vectors byte-exact; fuzzer runs 1 M cases with no throw and no unbounded allocation.
- **Rollback:** codec is isolated; revert is mechanical.

## PHASE 2 — Identity
- **Objective:** Ed25519 identity, IBC, identityHash, nodeId64 demotion.
- **Changes:** `PureCryptoEngine` identity functions + delete the legacy IV fallback; `CryptoEngine` exposes identity, `keyVersion`, `announceCounter`, `lastEmittedTimestamp`; `PeerIdentity` / `IdentityStore`.
- **Files:** `core/.../crypto/PureCryptoEngine.kt`, `app/.../crypto/CryptoEngine.kt`, new `core/.../identity/*`.
- **Tests first:** T-ID-01..08 (IBC verify/forge/rollback, nodeId64 derivation, collision handling, the three-line F-0 regression test).
- **Acceptance:** the F-0 test (`verifySignature(x25519Pub, …) == false`) is red against old code and the new path verifies correctly with `IK_pk`.
- **Rollback:** identity derivation changes `nodeId`; revert before P5 (DB migration) is clean, after it is not.

## PHASE 3 — Authentication pipeline + PEER_ANNOUNCE (the wire break)
- **Objective:** every packet authenticated at one chokepoint; announce v2 live.
- **Changes:** `PacketPipeline` S0–S7; `AuthenticatedPacket` / `AdmittedChunk`; announce v2 build + validate (A1–A17); all `MeshRouter` handlers converted to take `AuthenticatedPacket`; delete in-handler crypto; delete pre-auth `markSeen` and `logPacket`; hop signatures on all signed types.
- **Files:** new `core/.../protocol/PacketPipeline.kt`, `AuthenticatedPacket.kt`; heavy edit of `app/.../router/MeshRouter.kt`; `desktop/.../router/DesktopMeshRouter.kt` validation deleted.
- **Depends on:** P1, P2.
- **Tests first:** T-INT-01 (now must pass for **all** types), T-NEG-01..40 (the negative matrix), T-REP-01..07, T-ARCH-01.
- **Acceptance:** every negative case drops **and** leaves every table's row count unchanged; no handler references crypto; announce round-trips through production code.
- **Rollback:** this is the wire break. After this phase, v1 and vNext do not interoperate. Rolling back means shipping v1 again — treat P3 as the point of no return.

## PHASE 4 — Transport authentication
- **Objective:** no link carries traffic until identity is proven.
- **Changes:** `LinkAuth` in `:core`; Wi-Fi handshake + `K_link` + bounded pending pool + 2 104 B frames + UDP beacons only; BLE `LINK_AUTH` + `bindLink` + `registerDirectNode` deleted; framer and rate-limiter bounds.
- **Files:** `core/.../transport/LinkAuth.kt`, `app/.../wifi/MeshWifiEngine.kt`, `app/.../ble/{MeshBleEngine,BleFrameFramer,GattWriteRateLimiter,BleConstants}.kt`, `desktop/.../wifi/DesktopWifiEngine.kt`.
- **Tests first:** T-LINK-01..10 (spoofed nodeId, hijack of an established identity, 200 concurrent half-open, 10 MB frame, UDP mesh packet rejection, replayed CONFIRM).
- **Acceptance:** with 200 half-open handshakes running, an unrelated DB read completes in < 100 ms; a spoofed identity never binds.
- **Rollback:** transport-only; revertible independently of P3.

## PHASE 5 — Routing and custody
- **Objective:** no unilateral route poisoning; no silent message loss.
- **Changes:** STAGED/CONFIRMED edges, `LinkTrust` cost, cached routing table, 30 s prune timer; `CustodyManager`, `CUSTODY_ACK`, retry ladder, `EXPIRED` / `CUSTODY_HELD` statuses; `attemptSend` rename.
- **Files:** `core/.../router/MeshRouteEngine.kt`, new `core/.../custody/CustodyManager.kt`, `app/.../router/MeshRouter.kt`, `app/.../data/dao/DAOs.kt`.
- **Tests first:** T-ROUTE-01..08, T-CUST-01..09.
- **Acceptance:** a unilateral edge claim never routes; in every custody scenario exactly one copy survives and the message is delivered or marked `EXPIRED` — **never silently lost**.
- **Rollback:** behavioural; revertible.

## PHASE 6 — Media, voice, resource controls
- **Objective:** close every exhaustion vector; encrypt voice.
- **Changes:** chunk bounds, write-once, admission, extension allow-list, per-identity media budget; voice `K_call` + deterministic nonce + seq enforcement; `MeshTrafficController` wired into egress with per-identity relay slots; every §8 limit enforced from `ResourceLimits.kt`.
- **Files:** `app/.../media/MediaTransferManager.kt`, `app/.../voice/*`, `core/.../protocol/MeshTrafficController.kt` (wiring), `app/.../router/MeshRouter.kt`.
- **Tests first:** T-RES-01..12, T-VOICE-01..05.
- **Acceptance:** 60 s of hostile traffic across every vector leaves every table at or under cap and heap growth bounded; no nonce reuse is constructible.
- **Rollback:** independent.

## PHASE 7 — Storage migration and at-rest encryption
- **Objective:** migrate data safely; make panic wipe true.
- **Changes:** Room 11→12; `exportSchema = true`; remove `fallbackToDestructiveMigration()` + failure screen; Keystore fallback behind `BuildConfig.DEBUG`; `MeshWhisperMediaMasterKey` + media at rest; `performHardWipe` rewritten to C-26; panic wipe moved to `NonCancellable`.
- **Files:** `app/.../data/MeshDatabase.kt`, `data/model/Entities.kt`, `ProfileEntity.kt`, `dao/DAOs.kt`, `ui/viewmodel/MeshViewModel.kt`, `service/MessageNotifier.kt`.
- **Tests first:** T-MIG-01..06, T-WIPE-01..04.
- **Acceptance:** migration from a seeded v11 DB preserves messages and produces `LEGACY_UNVERIFIED` peers; after wipe, `filesDir`, `databases/` and `shared_prefs/` are empty and no notification remains; cancelling mid-wipe still destroys keys first.
- **Rollback:** **none after a user has migrated.** Ship P7 only when P0–P6 are green.

## PHASE 8 — UI, QR, trust states
- **Changes:** trust badges, conflict/rotation banners, `IMPORTED` vs `VERIFIED`, new QR format, fingerprint reconciliation screen, `EXPIRED` message state, Packet Inspector debug-only.
- **Files:** `ui/viewmodel/MeshViewModel.kt`, `ui/MainActivity.kt`, `ui/screens/{IdentitySettings,DirectChatDetail,MeshRadar,PacketInspector}Screen.kt`, `ui/util/QrCodeGenerator.kt`, `ui/components/{CameraQrScanner,QrCodeAnalyzer}.kt`.
- **Tests first:** T-TRUST-01..06 (especially: deep link **never** yields `VERIFIED`).
- **Acceptance:** every transition in file 1 §5.2 is reachable only by its listed trigger.

## PHASE 9 — Desktop parity
- **Changes:** `DesktopMeshRouter` on the shared pipeline; desktop Wi-Fi handshake; identity persistence; schema parity.
- **Acceptance:** an Android node and a desktop node exchange authenticated DMs in `InMemoryMesh` and over a real LAN.

## PHASE 10 — Documentation and release hygiene
- **Changes:** §9.5 in full; ProGuard log stripping; CHANGELOG breaking-change notice.
- **Acceptance:** an independent reader can implement an interoperable node from `docs/PROTOCOL.md` alone. Every claim in `docs/SECURITY.md` maps to a passing test ID.

---

# 11. MIGRATION SAFETY

**Strategy: C — hard protocol break on the wire, with a data-preserving on-device migration.**

Justification, restated for the implementation agent: v1 announces transmit no signing key, so a v1 announce can never be authenticated by any means. "Backward compatible" would mean "permanently accepts unauthenticated identity claims", and "versioned coexistence" would mean an attacker simply speaks v1 — a downgrade oracle. `docs/LIMITATIONS.md §7` confirms no multi-device field validation has occurred, and F-0 means peer discovery never worked on real radios, so **there is no functioning deployed mesh to be compatible with.** The break will never be cheaper.

## 11.1 What survives

| Asset | Fate | Notes |
|---|---|---|
| Master seed `S` | **preserved** | reused as `EK_sk` and as the `IK_sk` derivation input |
| Ed25519 identity | **derived from existing `S`** | no regeneration; `deriveSigningPrivateKey` unchanged |
| `messages` table | **preserved verbatim** | historical `senderId` kept as a display-only attribution |
| Media files | **preserved, then encrypted in place** during first run after P7 | one-time re-encryption pass |
| `profiles` | preserved; `signature` column retained but never read again |
| Alias, channel settings | preserved |
| Biometric lock setting | preserved |

## 11.2 What is intentionally discarded

| Asset | Fate | Why |
|---|---|---|
| `nodeId64` | **changes** | identity must commit to the signing key |
| Fingerprints / safety numbers | **change** | derived from `identityHash` |
| `peers.isVerified` | **reset** | old verification attested to an X25519 key, not an identity |
| `store_forward_queue` | **dropped**; matching `messages` → `EXPIRED` | rows contain v1 wire bytes that are unparseable |
| `processed_packets` | **truncated** | v1 dedup keys are meaningless under v2 |
| `topology_edges` | **truncated** | v1 edges are unauthenticated by construction |
| `packet_logs` | **truncated** | moves to an in-memory ring |
| Old QR / deep-link peers | **`LEGACY_UNVERIFIED`** | the Ed25519 key is not derivable from a v1 QR; a re-scan is required |
| Cached session keys | in-memory only; gone on restart |
| Active calls / links | in-memory only; gone on restart |

## 11.3 Migration 11 → 12 — ordered steps

1. Create `identities`.
2. Add `identityHashHex` (nullable) and `trustState` (default `LEGACY_UNVERIFIED`) to `peers`; drop the unique constraint on `nodeId`, add a non-unique index.
3. Add `state`, `fromIdentityHashHex`, `toIdentityHashHex` to `topology_edges`.
4. `DELETE FROM store_forward_queue`; `UPDATE messages SET status='EXPIRED' WHERE status IN ('PENDING','SENT','RELAYED') AND isOutgoing=1`.
5. `DELETE FROM processed_packets`; `DELETE FROM topology_edges`; `DELETE FROM packet_logs`.
6. Leave `messages`, `profiles`, `last_known_locations` untouched.
7. On first run after migration: derive `IK_pk` from the existing `S`; compute the new `identityHash` and `nodeId64`; insert the local `identities` row with `keyVersion = 1`, `announceCounter = 1`; write the IBC; run the one-time media re-encryption pass.

## 11.4 Old-installation and rollback limitations

- A user who migrates **cannot roll back**. Schema 12 is not readable by a v1 build. This must be stated in the release notes and in the in-app upgrade prompt.
- A v1 node in range of a vNext mesh is invisible to it and it is invisible to them. The upgrade prompt must say so plainly rather than letting the user believe the mesh is broken.
- Desktop must ship vNext in the same release or be withdrawn from distribution.

---

# 13. "DO NOT TOUCH" CONTRACT

These mechanisms are correct. Change one only with a failing regression test that demonstrates it must change, and record that test ID in `CHANGELOG.md`.

| # | Mechanism | Location | Why it stays |
|---|---|---|---|
| 1 | **56-byte header, exact layout** | `core/.../MeshPacket.kt` serialize/deserialize | Compact and correct for BLE. Widening `senderId`/`recipientId` to 128 bits would add 16 bytes to every packet including 50/s voice frames, to defend against an attack that `identityHash` in the transcript already neutralises. |
| 2 | **Big-endian everywhere** | same | Platform-independent, already consistent across `:core`, `:app`, `:desktop`, and matches every published diagram. Mixed endianness is a perennial interop bug source. |
| 3 | **AAD = 37-byte header excluding TTL** | `computeAad()` | Binds type, messageId, both nodeIds and timestamp while leaving the hop counter mutable. Including TTL would make relaying impossible without re-encryption. `computeAad` must remain **byte-identical** — vNext builds on it, it does not replace it. |
| 4 | **Session-key epoch from the packet timestamp** | `derivePeerSessionKey(..., packet.timestamp)` | Both endpoints read the same value from the same header, so hourly rotation cannot fail on clock skew. Switching to local time would produce intermittent, unreproducible decryption failures at hour boundaries. |
| 5 | **ACK nonce strategy: fresh `ackPacketId`, original ID inside the ciphertext** | `MeshRouter.kt:1378–1392` | Deliberately avoids reusing the original messageId as a GCM IV. The comment explains the trap. This is the best-reasoned code in the repo. |
| 6 | **Atomic `INSERT OR IGNORE` dedup** | `ProcessedPacketDao.markSeen` returning `-1` | Replaced a check-then-act race. vNext only moves *when* it is called (into `AuthenticatedPacket` construction); the mechanism is unchanged. |
| 7 | **`messageId:packetType` dedup identity** | `MeshRouter.kt:250` | Prevents a DM and its ACK colliding in the dedup space. Dropping the type component would silently break ACK delivery. |
| 8 | **`:core` / `:app` boundary** | module layout, `app/protocol/MeshPacket.kt` typealiases | Real, not aspirational: no Android types leak into `:core`. It is what makes the pipeline testable on the JVM and shareable with `:desktop`. vNext *enlarges* `:core`; it must not blur the boundary. |
| 9 | **Bounded MEDIA_INIT metadata** | `MediaTransferManager.kt:686` (4 096 chunks / 20 MB) and the per-peer/global session caps with oldest-eviction | Already the correct pattern. vNext extends it to chunks; it does not replace it. |
| 10 | **BLE connection symmetry tie-break** | `MeshBleEngine.kt:552` | Deterministic MAC comparison prevents both peers dialling each other simultaneously. It decides **who initiates**; it never decides **who anyone is**, so the identity fix does not touch it. |
| 11 | **15 ms BLE write pacing** | `MeshBleEngine.kt:777`, `:810` | Clearly learned from real hardware. Removing it reintroduces write-queue saturation. |
| 12 | **Self-describing chunk framing (sessionId + index + total)** | `BleFrameFramer.kt` | Survives interleaved concurrent transmissions correctly. Only the *bounds* change. |
| 13 | **SHA-256 verification before writing reassembled media** | `MediaTransferManager.kt:1013` | The backstop that makes the MEDIA_CHUNK signature exemption acceptable. |
| 14 | **QR registration verifying `deriveNodeId(pub) == claimedId`** | `MeshViewModel.kt:231` | The exact check the radio path was missing. vNext generalises it; the QR path's logic is the model. |
| 15 | **Duplicate-DM ACK re-emission** | `MeshRouter.kt:258` | Correct lost-ACK recovery. Preserved as the single documented exception at S3. |

---

# SECTION B — IMPLEMENTATION ROADMAP

| Phase | Objective | Gate to the next phase |
|---|---|---|
| **P0** | Test seam: `Transport`/`Clock`/`RandomSource` interfaces, `InMemoryMesh`, `MaliciousPeer`, golden-vector harness | T-INT-01 red on PEER_ANNOUNCE (proves F-0 and proves the harness works); 125 existing tests still green |
| **P1** | Core codec: version bits, strict length, TTL clamp, freshness table, `SIG_TRANSCRIPT`, `MWP2` | golden vectors byte-exact; 1 M-case fuzz with no throw, no unbounded allocation |
| **P2** | Identity: Ed25519 `IK`, IBC, `identityHash`, nodeId64 demotion, legacy IV fallback deleted | T-ID-01..08 green; F-0 regression test green |
| **P3** | **Wire break.** Pipeline S0–S7, `AuthenticatedPacket`, announce v2, hop signatures, handlers converted, pre-auth persistence removed | full negative matrix drops **with zero table mutation**; T-ARCH-01 green; **point of no return** |
| **P4** | Transport auth: LINK_AUTH on TCP + BLE, `K_link`, bounded pools, UDP beacons only, `registerDirectNode` deleted | 200 concurrent half-open handshakes and an unrelated DB read still completes in < 100 ms |
| **P5** | Routing + custody: STAGED/CONFIRMED edges, trust weighting, `CustodyManager`, `CUSTODY_ACK`, `EXPIRED` | no unilateral edge routes; every custody scenario ends delivered or `EXPIRED`, never silently lost |
| **P6** | Media + voice + resources: chunk bounds, admission, `K_call` nonce scheme, traffic controller wired, §8 enforced | 60 s hostile-traffic soak: every table at or under cap, heap bounded, no constructible nonce reuse |
| **P7** | Storage: Room 11→12, `exportSchema`, no destructive fallback, media at rest, panic wipe rewritten | migration preserves messages; post-wipe directories empty; interrupted wipe still destroys keys first |
| **P8** | UI + trust states + new QR | every state transition reachable only by its listed trigger; deep link can never reach `VERIFIED` |
| **P9** | Desktop parity on the shared pipeline | Android ↔ desktop authenticated DM over a real LAN |
| **P10** | Documentation and release hygiene | every claim in `docs/SECURITY.md` maps to a passing test ID |

**Sequencing rules.**
- P0 before everything. No protocol change lands without the harness.
- P1 → P2 → P3 is a strict chain; they share the wire format.
- P4, P5, P6 may proceed in parallel after P3 if separate engineers own them; each must re-run the full suite before merge.
- **P7 ships last among the security phases.** It is the only irreversible step for user data.
- P8 and P10 track the phases they describe; documentation is never allowed to lag a merged behavioural change (see file 3, agent rule 12).
