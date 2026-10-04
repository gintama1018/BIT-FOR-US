# BIT FOR US — Engineering Roadmap

Version: **v1.4 (Synchronized with Codebase)**  
Last Updated: **September 2026**

---

## 1. Completed Milestones

### Foundation — Dual-Radio Mesh Core
- 56-byte binary wire protocol with AEAD authentication tag and Big-Endian serialization.
- Dual-role BLE GATT engine (Central + Peripheral) with deterministic symmetry tie-breaking.
- Offline Wi-Fi LAN/Hotspot UDP discovery (port 42425) and TCP streaming (port 42426).
- End-to-end encryption via X25519 ECDH and AES-256-GCM.
- SQLCipher hardware-wrapped encrypted database (Room v11; upgraded to Room v12 in Phase P7).
- Out-of-band Safety Numbers with CameraX QR scanner.
- Emergency SOS GPS broadcast and offline radar compass.

### Milestone 1 — Traffic Prioritization & Directed Store-and-Forward
- 4-tier QoS `MeshTrafficController` (Tier 0 `CRITICAL_EMERGENCY`, Tier 1 `HIGH_INTERACTIVE`, Tier 2 `STANDARD_MESSAGING`, Tier 3 `BULK_TRANSFER`).
- Bounded FIFO queues (100 packets/tier) with packet expiration (30s).
- Anti-starvation deficit-weighted scheduling.
- Directed store-and-forward drainage: unicast delivery exclusively to connected target nodes, eliminating wasteful global broadcasts.

### Milestone 2 — Decoupled Profiles & Signed Version Anti-Rollback
- Decoupled user profile entities from cryptographic Node IDs.
- Canonical `ProfilePayload` binary serialization format.
- Ed25519 digital signatures on all profile updates.
- Monotonically increasing version counter preventing rollback and replay attacks.
- Avatar transfer via chunked media protocol.

### Milestone 3 — Dynamic Shortest-Path Routing & Relay Reliability ($A \to B \to C$)
- Pure Kotlin Dijkstra's algorithm in `MeshRouteEngine` over live direct radio neighbors and topology edges.
- Directed unicast next-hop forwarding for direct messages and delivery ACKs.
- Dynamic link failure quarantine (60s penalty) and automatic reroute failover.
- Relay custody cleanup: intermediate nodes immediately purge forwarded messages upon verified next-hop handoff.
- Lost-ACK recovery: duplicate delivery triggers immediate ACK re-emission without database corruption.
- UI delivery status progression (`PENDING` $\to$ `SENT` $\to$ `RELAYED` $\to$ `DELIVERED`).

### Milestone 4 — Direct 1-Hop Real-Time Voice Calls
- Strict direct 1-hop constraint ($ttl = 1$), keeping voice completely outside store-and-forward queues and multi-hop mesh relays.
- Pure Kotlin 4-bit IMA/DVI ADPCM codec (8 kHz mono, 160 samples / 320 bytes PCM compressed to 80 bytes; total packet 164 bytes fitting within BLE ATT MTU without fragmentation).
- Thread-safe bounded jitter buffer (40–80ms preload, out-of-order reassembly, duplicate/late frame drops, loss skipping).
- Decoupled `AudioStreamer` interface and `AndroidAudioStreamer` implementing `AudioRecord` mic capture and `AudioTrack` low-latency playback.
- Full signaling state machine (`VoiceCallManager`): `OFFER`, `ANSWER`, `DECLINE`, `HANGUP`, `BUSY`, with 30-second ringing timeout and link-loss disconnect detection.
- Sahara call HUD: direct chat top-bar trigger, ringing dialog with pulsing avatar animation, call timer (`mm:ss`), mute mic toggle, and speakerphone toggle.

### vNext Implementation Milestone (Phases P0–P10)
- **Phase P0/P1 (Protocol Freeze)**: Canonical 56-byte binary wire framing, trailing 64B hop signatures ($C\text{-}01$), 115-byte `SIG_TRANSCRIPT` ($C\text{-}06$), 37-byte AAD binding.
- **Phase P2 (Pipeline)**: 8-stage gate pipeline (S0–S7), pre-auth dedup read-only cache poisoning immunity ($C\text{-}05$), anti-spoofing check ($C\text{-}02$), CPU signature rate limit ($C\text{-}16$).
- **Phase P3/P4 (Transport Security)**: `LINK_AUTH` mutual handshake with $K_{\text{link}}$ derivation ($C\text{-}09$, $C\text{-}10$) and 5-connection hard ceiling.
- **Phase P5 (Relay Custody)**: Deterministic custody handoffs (single `CUSTODY_ACK` confirmation, no separate offer/accept packets) and store-and-forward buffer bounds.
- **Phase P6 (Media & Voice Pinning)**: Call key epoch pinning ($C\text{-}13$), broadcast chunk write-once and SHA-256 commit backstop ($C\text{-}14$).
- **Phase P7 (Media At-Rest & Wipe)**: `MWMEDIA1` per-file HKDF AES-GCM encryption, panic station wipe, Room v12 migration (`MIGRATION_11_12`).
- **Phase P8 (Trust State Machine)**: Monotonic trust transitions $T_1$–$T_{11}$ and $T_{12}$ migration, $T_7$ collision detection, $T_8$ out-of-band CameraX QR resolution, $T_6$ rotation demotion ($C\text{-}12$).
- **Phase P9 (Desktop Parity)**: Single `:core` protocol authority, shared `DirectMessagePacketBuilder`, fail-closed PBKDF2 AES-GCM vault, real OS socket integration test `P9-NET-01` (`testP9RealNetworkSocketTransportFlow`).
- **Phase P10 (Documentation & Hygiene)**: Implementation-independent `docs/PROTOCOL.md`, concrete `docs/SECURITY.md`, honest `docs/LIMITATIONS.md`, complete `docs/TESTING.md`, synchronized `README.md`, and 331 tests verified (expanded to 347 in Schema 13).

### Emergency Location Beacon & Store-Carry-Forward Breadcrumbs (Schema 13)
- **Zero Cleartext Over-The-Air**: Encrypted sub-payload inside `DIRECT_MESSAGE` (AES-256-GCM + Ed25519 signature); relays route without seeing coordinates.
- **Non-UTF8 Prefix Collision Immunity**: Binary prefix `[0xFF, 'B', 'C']` eliminating text collisions.
- **27-Byte Compact Struct with 64B Padding**: Big-endian struct ($10^7$ scaling, $0.1\text{m}$ accuracy, satellite fix time, sequence counter) padded to fixed 64 bytes to eliminate length fingerprinting.
- **Anti-Stalking Opt-in & Revocation**: Per-contact opt-in (`shareLocationWithContact`), 1-tap `REVOKE` trail purge.
- **Dying Gasp Hysteresis & 0ms Cached Fix**: 15%/10%/5% level-crossing detector with charging guard and instant cached GPS fallback at $\le 5\%$ to protect against cold GPS power shutdowns.
- **Atomic Reinstall-Proof Ordering**: SQLite conditional update `(fixTimestamp, sequenceNumber)` dropping replayed store-and-forward packets.
- **Autonomous Offline Plus Codes**: 10-char Open Location Code encoder (`8FVC7JVW+9V`) for two-way analog radio voice readouts to search-and-rescue teams.
- **Rescue Card & Ghost Radar Pins**: Pinned direct chat `RescueLocationCard` and radar ghost pins with $2\times$ accuracy uncertainty circles and off-screen boundary direction arrows (`↗`).

---

## 2. Current Status

- **Phase**: vNext Implementation Milestone (Phases P0–P10) & Emergency Location Beacon (Schema 13) **COMPLETED & FROZEN**.
- **Verification**: **347 automated tests passing 100% offline** (198 `:core`, 128 `:app`, 21 `:desktop`).
- **Physical Acceptance**: Physical Android $\leftrightarrow$ Desktop real Wi-Fi LAN acceptance pending field execution.

---

## 3. Near-Term Priorities (Next Milestone)

### Physical Multi-Device RF Field Trials
- Validate BLE GATT stability and throughput under dense physical conditions (10–20 real Android devices in an active RF environment).
- Profile battery consumption across varying duty cycles in `MeshForegroundService`.
- Measure actual physical voice latency over Bluetooth across multiple Android OEM chipsets (Qualcomm, MediaTek, Exynos).

### Transport Optimization
- Adaptively tune BLE connection intervals (`CONNECTION_PRIORITY_HIGH` during active voice calls or media bursts, returning to `CONNECTION_PRIORITY_BALANCED` when idle).
- Multi-link bandwidth bonding: opportunistic parallel transfer of image tiles across simultaneous BLE and Wi-Fi links when available.

### Dual-Codec Audio Architecture
- Investigate opportunistic switching to Opus (8–16 kbps) when peers are connected over high-bandwidth Wi-Fi direct sockets, while retaining zero-dependency IMA ADPCM for BLE links.

---

## 4. Long-Term Exploration

- **Auxiliary Hardware Bridges**: Serial / USB-OTG integration with external LoRa transceivers (Semtech SX1262) for long-range (5–15 km) low-bandwidth emergency text and coordinate relaying.
- **Delay-Tolerant Networking (DTN)**: Epidemic bundle protocol extensions for sparsely populated disaster zones where physical courier nodes bridge physically disconnected mesh partitions.
- **Forward Secrecy Evolution**: Evaluation of Signal-style Double Ratchet protocols for long-lived asynchronous pairwise direct chats.
