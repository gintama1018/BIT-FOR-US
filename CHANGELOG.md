# Changelog

All notable changes to the **MeshWhisper / BIT FOR US** platform are documented in this file.

The format is based on [Keep a Changelog](https://keepachangelog.com/en/1.0.0/), and this project adheres to semantic development milestones.

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
