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
- SQLCipher hardware-wrapped encrypted database (Room v11).
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

---

## 2. Current Status

- **Phase**: Comprehensive documentation re-engineering, specification alignment, and architecture truth audit.
- **Verification**: **118 automated tests passing 100% offline** (38 `:core`, 77 `:app`, 3 `:desktop`).

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
