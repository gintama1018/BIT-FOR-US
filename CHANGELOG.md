# Changelog

All notable changes to the **MeshWhisper / BIT FOR US** platform are documented in this file.

The format is based on [Keep a Changelog](https://keepachangelog.com/en/1.0.0/), and this project adheres to semantic development milestones.

## [vNext Schema 13 - Emergency Location Beacon & Store-Carry-Forward Breadcrumbs] - (2026-09-23)
### Geolocation Privacy, Tactical Offline Rescue & Power-Aware Resilience

#### Added
- **Core Binary Breadcrumb Payload (`LocationBreadcrumbPayload.kt`)**:
  - Pairwise end-to-end encrypted sub-payload under `DIRECT_MESSAGE` (AES-256-GCM + Ed25519 signature); zero cleartext coordinates over the air.
  - Non-UTF8 magic prefix `[0xFF, 'B', 'C']` eliminating collisions with chat text starting with 'L' (byte `0xFF` is mathematically prohibited in RFC 3629 UTF-8).
  - Compact 27-byte big-endian struct ($10^7$ coordinate scaling, $0.1\text{m}$ accuracy, altitude, satellite fix timestamp, monotonic sequence counter).
  - Side-channel length defense: zero-padded to a **fixed 64 bytes** prior to encryption.
  - Trigger types: `PERIODIC_5MIN`, `MANUAL_SOS`, `BATTERY_15`, `BATTERY_10`, `BATTERY_5_DYING_GASP`, `REVOKE`.
- **Autonomous Offline Plus Code Encoder (`PlusCodeHelper.kt`)**:
  - 100% offline mathematical Open Location Code encoder (~13.5m precision, e.g. `8FVC7JVW+9V`) requiring zero external maps, places, or city databases. Formatted for verbal transmission over search-and-rescue two-way radio channels.
- **Power-Aware Dying Gasp & Location Manager (`LocationBreadcrumbManager.kt`)**:
  - Active GPS location listener with cached fix fallback.
  - Level-crossing battery state detector with hysteresis (15%, 10%, 5%) and charging guard.
  - Instant (0ms) cached GPS fallback at $\le 5\%$ to eliminate phone shutdown races during cold satellite acquisition.
  - Broadcasts custody handover frames to all 1-hop neighbors when route to destination is unknown.
- **Atomic Reinstall-Proof Ordering & Storage**:
  - SQLite conditional atomic update in `DAOs.kt`: `WHERE (:fixTimestamp > timestamp OR (:fixTimestamp = timestamp AND :sequenceNumber > sequenceNumber))`. Replays and out-of-order store-and-forward bursts are discarded while app data resets supersede older timestamps.
  - Arrival storage is never rate-limited; user heads-up/audible notifications throttled to 1 per 60s per sender.
  - Store-and-forward history table `BreadcrumbHistoryEntity` with unique index `(nodeId, sequenceNumber)` and automatic 50-entry history pruning.
- **Database Schema Migration v13 (`MIGRATION_12_13`)**:
  - Upgraded Room schema to version 13 with SQLCipher encryption-at-rest.
  - Added `shareLocationWithContact` to `peers` table (default 0 / false).
  - Added `sequenceNumber`, `receivedTimestamp`, `altitude`, `batteryPercent`, `triggerType`, and `note` to `last_known_locations`.
- **UI Enhancements**:
  - `RescueLocationCard.kt`: Pinned direct chat card displaying fix age color accents, bearing compass, offline Plus Code, and quick copy/share actions.
  - Direct chat safety number dialog: 1-tap emergency location sharing toggle and 1.5s hold-to-confirm manual beacon dispatch.
  - `MeshRadarScreen.kt`: Offline verified ghost pins with $2\times$ accuracy uncertainty circles and off-screen boundary direction arrows (`↗`).
- **Comprehensive Unit Tests**:
  - Added `LocationBreadcrumbPayloadTest.kt` (round-trip, 64-byte padding, non-UTF-8 collision rejection, bounds checking).
  - Added `PlusCodeHelperTest.kt` (Open Location Code algorithm determinism, pole boundaries, negative coordinates).
  - Added `LocationBreadcrumbIngressTest.kt` (reinstall recovery, replay drops, clock drift rejection, revoke handling).
  - Added `DatabaseMigrationP7Test.kt` (`MIGRATION_12_13` DDL verification and schema generation).
  - Expanded test suite to **347 passing tests** (100% offline, 0 failures, 0 ignored).

---

## [vNext Implementation Milestone] - Phases P0–P10 (2026-09-19)
### Architecture, Security, Protocol Freeze & Desktop Parity

#### Added
- **vNext Binary Wire Protocol (Phases P0–P1)**:
  - Canonical 56-byte header with protocolVersion (Byte 0), UUID messageId (16B), senderId (8B), recipientId (8B), TTL (1B), timestamp (4B), payloadLength (2B), and AES-GCM authTag (16B).
  - Single signature placement ($C\text{-}01$): Trailing 64-byte Ed25519 hop signature outside AEAD on all signed types.
  - 115-byte canonical `SIG_TRANSCRIPT` with purpose tag `CONTENT = 0x02` ($C\text{-}06$).
  - Canonical 37-byte AAD binding header to ciphertext.
  - Retired legacy `KEY_EXCHANGE` (`0x02`); added `LINK_AUTH` (`0x31`), `CUSTODY_OFFER` (`0x11`), `CUSTODY_ACCEPT` (`0x12`), and `CUSTODY_ACK` (`0x13`).
- **Packet Admission Pipeline S0–S7 (Phase P2)**:
  - Non-bypassable 8-stage gate pipeline in `:core` (`PacketPipeline.kt`).
  - Read-only S3 deduplication cache check, eliminating pre-auth cache poisoning ($C\text{-}05$).
  - Anti-spoofing validation: `BE_u64(identityHash[0..8]) == header.senderId` ($C\text{-}02$).
  - Rate-limited CPU signature verification budget (max 32 verifications/sec/link) protecting relays ($C\text{-}16$).
  - Zero auth-tag whitelist exemption strictly for `LINK_AUTH` ($C\text{-}08$).
- **Transport Security & LINK_AUTH (Phases P3–P4)**:
  - Mutual two-stage transport handshake (HELLO 169B, CONFIRM 65B) deriving symmetric session key $K_{\text{link}}$ with SIGMA identity-misbinding defense ($C\text{-}09$, $C\text{-}10$).
  - Post-auth AES-256-GCM encrypted transport frames on Wi-Fi TCP streams.
  - Hard cap of 5 concurrent authenticated links with duplicate active-identity rejection.
- **Relay Custody & Store-and-Forward (Phase P5)**:
  - Deterministic custody handoff protocol with bounded capacity (max 50/peer, 500 global, 24-hour expiration).
- **Voice Key Epoch Pinning & Media Integrity (Phase P6)**:
  - Pinned $K_{\text{call}}$ derived from OFFER packet epoch ($C\text{-}13$), preventing key disagreement across 1-hour boundaries.
  - Write-once per chunk index for broadcast media with SHA-256 commit backstop ($C\text{-}14$).
- **Media At-Rest Encryption & Panic Wipe (Phase P7)**:
  - Zero-plaintext media storage with `MWMEDIA1` header, HKDF per-file key derivation, and fail-closed tamper detection.
  - Emergency Station Wipe zero-fill erasing SQLite database, WAL, and SHM files, and deleting KeyStore alias.
  - Migrated Android database to Room v12 (`MIGRATION_11_12`).
- **Authoritative Trust State Machine (Phase P8)**:
  - Pure `:core` trust state machine (`TrustStateMachine.kt`) governing runtime transitions $T_1$–$T_{11}$ and migration transition $T_{12}$.
  - NodeId64 collision detection ($T_7$) transitioning colliding records to `CONFLICTED` and suspending unicast routing.
  - Out-of-band CameraX QR collision resolution ($T_8$) marking verified winner `VERIFIED` and imposter `BLOCKED`.
  - Ephemeral key rotation demotion ($T_6$) for verified peers; equivocation and rollback defenses ($C\text{-}12$).
- **Desktop Parity & Single Protocol Authority (Phase P9)**:
  - Desktop workstation node directly reuses `:core` for `PacketPipeline`, `LinkAuthSession`, `TrustStateMachine`, `InMemoryIdentityStore`, and `DirectMessagePacketBuilder`.
  - Shared `DirectMessagePacketBuilder` in `:core` used by both Android and Desktop.
  - Fail-closed PBKDF2-HMAC-SHA256 (100k iterations) AES-256-GCM `identity.vault`.
  - Collision-safe unicast destination lookup (`getUniqueIdentityByNodeId`).
  - Real OS network socket integration test `P9-NET-01` (`testP9RealNetworkSocketTransportFlow`) testing live TCP handshake, encrypted DMs, ACKs, disconnect $T_5$, and reconnect $T_4$ continuity.
  - T-ARCH-01 architectural purity: zero Android dependencies in `:desktop`.
- **Comprehensive Documentation & Release Hygiene (Phase P10)**:
  - Created implementation-independent `docs/PROTOCOL.md`, concrete `docs/SECURITY.md`, honest `docs/LIMITATIONS.md`, and complete `docs/TESTING.md`.
  - Synchronized `README.md`, `docs/ARCHITECTURE.md`, `docs/ROADMAP.md`, and `CONTRIBUTING.md`.
  - Test suite expanded to **331 passing tests with 0 failures and 0 ignored** across `:core` (191), `:app` (119), and `:desktop` (21).

#### Changed
- Deprecated legacy mutable verification booleans; Room v12 and SQLite database trust state driven exclusively by `:core` `TrustStateMachine`.
- Direct-message packet construction unified across Android and Desktop under shared `DirectMessagePacketBuilder`.

---

## [Milestone 4] - 2026-09-04
### Real-Time Direct 1-Hop Voice Communication

#### Added
- **Direct 1-Hop Voice Architecture**: Real-time push-to-talk and duplex audio communication strictly constrained to direct 1-hop physical neighbors ($ttl = 1$).
- **`VoiceCallManager`**: State machine managing call lifecycles (`IDLE`, `OUTGOING_RINGING`, `INCOMING_RINGING`, `ACTIVE`, `ENDED`) with 30-second ring timeouts and 10-second packet heartbeat watchdogs.
- **`AdpcmCodec`**: 4-bit IMA ADPCM audio compression engine encoding 16-bit 8 kHz mono linear PCM (20ms frames / 160 samples per frame) into compact 80-byte audio payloads (1:4 compression ratio).
- **`JitterBuffer`**: Adaptive playout buffer with a dynamic 60ms target latency, sequence-based reordering, packet loss concealment (PLC zero-fill insertion), and late-arrival packet discard.
- **Android Audio Pipeline**: Hardware audio integration using `AudioRecord` (`VOICE_COMMUNICATION` source with acoustic echo cancellation and noise suppression) and `AudioTrack` (`STREAM_VOICE_CALL`).
- **Binary Voice Protocol**:
  - `PacketType.VOICE_CALL_SIGNAL` (`0x0F`): 25-byte signaling payload (`callId`, `signalType`, `timestamp`).
  - `PacketType.VOICE_FRAME` (`0x10`): 108-byte audio wire frame (`callId`, `sequenceNumber`, `timestamp`, `codecId`, 80B ADPCM audio, 20B AEAD auth tag).
- **Automated Tests**: Added comprehensive unit tests for ADPCM encoding/decoding roundtrips, voice state transitions, jitter buffer sequencing, and voice packet wire serialization (test suite expanded to 118 passing tests).

#### Changed
- **Router Isolation**: Updated `MeshRouter` packet dispatch to route `VOICE_CALL_SIGNAL` and `VOICE_FRAME` directly to `VoiceCallManager`, completely bypassing store-and-forward queues and Room database persistence.

---

## [Milestone 3] - 2026-09-03
### Dynamic Routing, Multi-Hop Reliability & Relay Custody

#### Added
- **`MeshRouteEngine`**: Pure Kotlin Dijkstra shortest-path routing algorithm computing optimal next-hop paths based on transport link weights (Wi-Fi = 1, BLE = 5) and quarantine penalties (+50).
- **Directed Next-Hop Relaying**: Replaced blind epidemic flooding with deterministic next-hop forwarding; packets now traverse explicit paths towards destinations with fallback to controlled flooding if no path exists.
- **Relay Custody Protocol**: `RelayCustodyStore` maintains responsibility for relayed packets until a downstream next-hop or final destination ACK is observed.
- **Link Failure Quarantine & Failover**: 3 consecutive transmission timeouts flag a link as degraded, applying a 60-second routing penalty and recalculating alternative paths.
- **Lost-ACK Recovery**: Background re-emission mechanism detects unacknowledged custody packets and re-transmits along alternate routes.
- **Reconnection Directed Flushing**: Upon establishing a link with a peer, stored packets destined for that peer (or routable through it) are immediately dispatched.

#### Changed
- **Delivery Confirmation**: Enhanced end-to-end ACK processing with explicit custody release and delivery status callbacks.
- **Deduplication Engine**: Expanded LRU deduplication tracking to prevent packet loops during failover rerouting.

---

## [Milestone 2] - 2026-09-02
### Cryptographically Signed User Profiles & Anti-Rollback

#### Added
- **`ProfilePayload`**: Canonical binary framing for user profile distribution (display name, status, avatar hash, version counter).
- **Cryptographic Signature Verification**: Every profile update is signed with the user's Ed25519 identity key and verified by peers before acceptance.
- **Anti-Rollback Version Protection**: Monotonically increasing 64-bit epoch timestamp counter; older or duplicate profile updates are discarded, mitigating replay attacks.
- **Chunked Avatar Sync**: Reliable chunked transmission for user profile avatars with hard quota bounds (max 32 KB).
- **Room Database Schema Migration**: Updated database to Version 11, introducing `PeerProfileEntity` with signed profile fields, avatar hash, and version tracking.

#### Changed
- **Identity Decoupling**: Decoupled persistent human-readable user profiles from raw transport-level Node IDs.

---

## [Milestone 1] - 2026-09-01
### Traffic Prioritization (QoS) & Directed Store-and-Forward

#### Added
- **`TrafficScheduler`**: 4-tier prioritized egress queuing engine:
  - **Tier 0 (`CRITICAL_EMERGENCY`)**: Emergency SOS broadcasts, panic beacons (immediate preemptive transmission).
  - **Tier 1 (`HIGH_INTERACTIVE`)**: Delivery ACKs, routing control, key exchange, voice signaling.
  - **Tier 2 (`STANDARD_MESSAGING`)**: Point-to-point direct encrypted messages, public channel chat.
  - **Tier 3 (`BULK_TRANSFER`)**: Profile avatar chunks, media fragments, historical store-and-forward sync.
- **Starvation Protection**: Deficit-weighted round-robin scheduling ensuring lower-priority bulk transfers make progress during bursts of high-priority messaging.
- **Directed Store-and-Forward Queuing**: Replaced global flood broadcast of offline packets with peer-specific delivery queues.
- **Resource Quotas & Pruning**: Enforced per-peer capacity limits (50 packets/peer, 500 packets total) with automatic 24-hour expiration.

---

## [Foundation Release] - 2026-08-28
### Dual-Radio Mesh Architecture & Cryptographic Core

#### Added
- **`:core` Pure JVM Library**:
  - 56-byte binary wire protocol (`MeshPacket`) with CRC-32 validation and AAD generation.
  - `PureCryptoEngine`: BouncyCastle X25519 ECDH, Ed25519 signatures, HKDF-SHA256 session derivation, PBKDF2-HMAC-SHA256 (100k iterations) public channel isolation, and AES-256-GCM AEAD encryption.
  - `LruDedupCache`: Thread-safe LinkedHashMap LRU deduplication cache (4,000 packet IDs).
  - `MeshLogger`: Platform-neutral structured logging.
- **`:app` Android Engine**:
  - `MeshBleEngine`: Dual Central/Peripheral GATT manager with MAC address symmetry tie-breaking.
  - `BleFrameFramer`: Dynamic packet fragmentation and reassembly for BLE MTUs.
  - `MeshWifiEngine`: Offline local Wi-Fi UDP discovery beacon (port 42425) and high-throughput TCP socket streaming (port 42426).
  - `MeshRouter`: Dual-radio multiplexer and CSMA backoff jitter engine.
  - `MeshDatabase`: SQLCipher database encrypted with an AndroidKeyStore TEE-wrapped master key.
  - `LocationHelper`: Standalone Android `LocationManager` satellite acquisition without Google Play Services.
  - `CameraQrScanner`: CameraX 1.4.1 scanner with row-stride safe luminance analyzer for out-of-band Safety Number verification.
- **`:desktop` Companion Station**:
  - JVM desktop console with pure Java sockets, SQLite persistence, and CLI monitoring.
