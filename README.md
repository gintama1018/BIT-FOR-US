# MeshWhisper / BIT FOR US

**Offline, infrastructure-free peer-to-peer communications, tactical coordination, and emergency distress mesh networking over a hybrid Bluetooth Low Energy (BLE) + Offline Local Wi-Fi transport.**

[![License](https://img.shields.io/badge/License-Apache_2.0-blue.svg)](LICENSE)
[![Min SDK](https://img.shields.io/badge/Min%20SDK-26%20(Android%208.0)-brightgreen.svg)](https://developer.android.com)
[![Target SDK](https://img.shields.io/badge/Target%20SDK-35%20(Android%2015)-orange.svg)](https://developer.android.com)
[![Kotlin](https://img.shields.io/badge/Kotlin-2.0.21-purple.svg)](https://kotlinlang.org)
[![Tests](https://img.shields.io/badge/Tests-347%20Passing%20(100%25%20Offline)-success.svg)](docs/TESTING.md)
[![Database](https://img.shields.io/badge/Database-SQLCipher%20Room%20v13-blueviolet.svg)](docs/ARCHITECTURE.md)
[![Protocol](https://img.shields.io/badge/Protocol-vNext%20v2%20(FROZEN)-brightgreen.svg)](docs/PROTOCOL.md)
[![Security Architecture](https://img.shields.io/badge/Security-Ed25519%20%2B%20X25519%20%2B%20AES--256--GCM-brightgreen.svg)](docs/SECURITY.md)

---

## 1. Problem Statement & System Overview

Centralized telecommunication networks depend on cellular base stations, centralized switches, internet service provider backbones, and public DNS roots. In disaster scenarios, search-and-rescue operations, remote expeditions, and network-denied environments, centralized infrastructure is vulnerable to physical destruction, power loss, or intentional blackouts.

**BIT FOR US (MeshWhisper)** provides resilient, 100% offline text messaging, emergency distress signaling, media synchronization, offline rescue breadcrumbs, and direct voice communications directly between devices without requiring cellular towers, SIM cards, internet access, or central servers.

Participating devices act as autonomous relay nodes, forming a self-healing, multi-hop mesh network over a **Dual-Radio Hybrid Transport (Bluetooth Low Energy + Local Wi-Fi Sockets)**.

```
                      Shared Protocol Authority (:core)
        ┌───────────────────────────────────────────────────────────┐
        │ PacketPipeline (S0–S7)        LinkAuthSession (LINK_AUTH) │
        │ TrustStateMachine (T1–T11)    DirectMessagePacketBuilder  │
        │ PureCryptoEngine              InMemoryIdentityStore       │
        │ LocationBreadcrumbPayload     MeshRouteEngine             │
        └─────────────────────────────┬─────────────────────────────┘
                                      │
               ┌──────────────────────┴──────────────────────┐
               ▼                                             ▼
        Android Platform                             Desktop Platform
        Module: :app                                 Module: :desktop
        ┌─────────────────────────┐                  ┌─────────────────────────┐
        │ MeshRouter              │                  │ DesktopMeshRouter       │
        │ IdentityRepository      │                  │ DesktopIdentityRepo     │
        │ Room v13 (SQLCipher)    │                  │ SQLite JDBC (xerial)    │
        │ LocationBreadcrumbMgr   │                  │ Wi-Fi TCP / UDP         │
        │ BLE GATT + Wi-Fi TCP    │                  │ Java Swing UI           │
        │ Jetpack Compose UI      │                  │                         │
        └───────────┬─────────────┘                  └───────────┬─────────────┘
                    │                                            │
                    └─────────────────────┬──────────────────────┘
                                          ▼
                             Hostile Radio / Network Media
                                  (BLE Airwaves / LAN)
```

---

## 2. Comprehensive Documentation Suite

Technical specifications, threat models, wire framing, and operational limits are documented in the **`docs/`** directory:

| Specification | Key Contents |
| :--- | :--- |
| **[docs/PROTOCOL.md](docs/PROTOCOL.md)** | Canonical 56-byte wire framing, packet type registry, AEAD AAD binding, 115-byte canonical signature transcripts, LINK_AUTH handshake, trust transitions ($T_1$–$T_{11}$), and Emergency Location Beacon & Breadcrumb binary specification. |
| **[docs/SECURITY.md](docs/SECURITY.md)** | Zero-trust security model, 8-stage admission pipeline (S0–S7), fail-closed storage vaults, media-at-rest encryption (`MWMEDIA1`), panic wipe, anti-stalking location privacy, and comprehensive Claims-to-Evidence Table. |
| **[docs/LIMITATIONS.md](docs/LIMITATIONS.md)** | Honest real-world constraints: 1-hop voice boundary, BLE connection caps (max 5), Android Doze mode, unauthenticated broadcast media chunk race ($C\text{-}14$), and verification status breakdown. |
| **[docs/TESTING.md](docs/TESTING.md)** | Breakdown of all 347 automated tests across `:core` (198), `:app` (128), and `:desktop` (21), real OS socket transport test (`P9-NET-01`), and physical Wi-Fi LAN acceptance runbook. |
| **[docs/ARCHITECTURE.md](docs/ARCHITECTURE.md)** | Subsystem overview, module boundaries, shared `:core` authority, Dijkstra routing, store-and-forward custody, Room v13 database schema, and `LocationBreadcrumbManager`. |
| **[docs/ROADMAP.md](docs/ROADMAP.md)** | Completed Milestones 1–4, completed vNext Implementation Phases P0–P10, Emergency Location Beacon & Breadcrumbs, and future operational readiness phases. |
| **[CONTRIBUTING.md](CONTRIBUTING.md)** | Development environment setup, coding conventions, architectural invariants, and PR checklist. |
| **[CHANGELOG.md](CHANGELOG.md)** | Chronological history of milestones, vNext protocol freeze, and security re-engineering. |

---

## 3. Core Architectural Capabilities

### 3.1. Single Protocol Authority & Desktop Parity
- **Shared `:core` Pipeline**: Both Android and Desktop nodes execute identical packet admission, wire framing, cryptographic validation, and trust transitions via the shared `:core` module.
- **Shared Message Construction**: Direct messages are constructed exclusively through `DirectMessagePacketBuilder`, ensuring identical wire bytes across platforms.
- **Desktop Companion Workstation**: First-class desktop station node (`:desktop`) running on Windows and macOS with fail-closed encrypted key storage (`identity.vault`) and embedded SQLite persistence.

### 3.2. Dynamic Shortest-Path Next-Hop Routing
- **`MeshRouteEngine`**: Pure Kotlin Dijkstra shortest-path engine calculating optimal next-hop paths based on physical radio links and gossiped topology edges.
- **Transport Weighting**: High-throughput Wi-Fi links are preferred (weight = 1) over constrained BLE links (weight = 5).
- **Directed Relay**: Replaces blind epidemic flooding with directed next-hop unicast forwarding, falling back to controlled flood only if the destination route is unknown.
- **Relay Custody & Automatic Cleanup**: Intermediate relay nodes buffer packets in custody queues and purge them upon downstream next-hop handoff or destination ACK.
- **Link Failure Quarantine & Rerouting**: 3 consecutive transmission timeouts apply a 60-second quarantine penalty to the failing link, triggering automatic reroute recalculation.

### 3.3. 4-Tier Quality-of-Service (QoS) Traffic Scheduling
- **`TrafficScheduler`**: Outbound traffic is scheduled across four bounded priority FIFO queues (100 packets max per queue) with anti-starvation protection:
  - **Tier 0 (`CRITICAL_EMERGENCY`)**: Emergency SOS distress beacons, Dying Gasp location packets, and `LINK_AUTH` (immediate zero-delay dispatch).
  - **Tier 1 (`HIGH_INTERACTIVE`)**: Delivery ACKs, typing indicators, voice call signaling, voice frames, custody negotiation.
  - **Tier 2 (`STANDARD_MESSAGING`)**: Point-to-point encrypted direct messages, periodic location breadcrumbs, public channel chat, profile updates.
  - **Tier 3 (`BULK_TRANSFER`)**: Media chunks, avatar fragments, historical store-and-forward synchronization.

### 3.4. Real-Time 1-Hop Duplex Voice Calling
- **Strict 1-Hop Constraint**: Voice calling is strictly point-to-point between direct radio neighbors ($ttl = 1$). Voice packets are volatile real-time streams and never enter multi-hop relay queues or database tables.
- **`VoiceCallManager`**: State machine managing call lifecycles (`IDLE`, `OUTGOING_RINGING`, `INCOMING_RINGING`, `CONNECTED`, `ENDED`) with 30-second ringing timeouts and 10-second heartbeat watchdogs.
- **`AdpcmCodec`**: 4-bit IMA ADPCM audio compression encoding 16-bit 8 kHz mono linear PCM (20ms frames / 160 samples per frame) into 80-byte audio payloads (1:4 compression ratio, 32 kbps).
- **`JitterBuffer`**: Adaptive playout buffer with a dynamic 60ms target latency, sequence reordering, packet loss concealment (PLC), and late-arrival discard.
- **Call Key Pinning ($C\text{-}13$)**: Voice call encryption keys are derived once at call setup and pinned, preventing key disagreement across 1-hour epoch boundaries.

### 3.5. Cryptographically Signed Profiles & Anti-Rollback
- **`ProfilePayload`**: Canonical binary framing for user profile distribution (display name, bio, avatar hash, version counter).
- **Ed25519 Signatures**: Every profile update is signed with the user's private identity key; peers reject unsigned or invalid profiles.
- **Anti-Rollback Version Protection**: Monotonically increasing 64-bit epoch timestamp counter; older or duplicate updates are rejected to defeat replay attacks.

### 3.6. Emergency Location Beacon & Store-Carry-Forward Breadcrumbs
- **Zero Cleartext Over-The-Air**: Coordinates, accuracy, altitude, and battery levels are transported strictly as pairwise E2E-encrypted sub-payloads inside `DIRECT_MESSAGE` frames (AES-256-GCM + Ed25519 signature). Relays see only ciphertext routing headers.
- **Non-UTF8 Prefix Collision Immunity**: Binary header `[0xFF, 'B', 'C']`. Byte `0xFF` is mathematically illegal in UTF-8 (RFC 3629), eliminating false-positive collision with normal chat text.
- **27-Byte Compact Binary Struct**: Fixed-size big-endian struct ($10^7$ coordinate scaling, $0.1\text{m}$ accuracy, satellite fix timestamp, sequence number), padded to a **fixed 64 bytes** to eliminate side-channel length leakage.
- **Anti-Stalking Opt-in & Revocation**: Sharing is disabled by default (`shareLocationWithContact = 0`) and requires verified trust. Toggling off dispatches an encrypted `BreadcrumbTriggerType.REVOKE` payload that instantly purges stored coordinates and breadcrumb history on the peer device.
- **Multi-Stage Dying Gasp & Cached GPS**: Level-crossing detector with hysteresis (15%, 10%, 5%) and charging guard. At $\le 5\%$, the system grabs the cached GPS fix instantly (0ms delay) to prevent phone battery shutdown during cold satellite acquisition. Broadcasts custody frames to all 1-hop neighbors if direct route is absent.
- **Atomic Reinstall-Proof Ordering**: SQLite conditional update `WHERE (:fixTimestamp > timestamp OR (:fixTimestamp = timestamp AND :sequenceNumber > sequenceNumber))` guarantees replayed store-and-forward packets are dropped, while app reinstalls reset cleanly.
- **Offline 10-Character Plus Codes (`8FVC7JVW+9V`)**: Autonomous mathematical encoder for search-and-rescue verbal communication over two-way VHF/HAM radios.
- **Rescue Card & Ghost Radar Pins**: Pinned `RescueLocationCard` in direct chat and ghost pins with $2\times$ accuracy uncertainty circles and off-screen boundary direction arrows (`↗`) on the campus radar.

---

## 4. Current Verification & Development Status

- **Automated Verification**: **347 / 347 unit & integration tests passing 100% offline** (198 `:core`, 128 `:app`, 21 `:desktop`).
- **Real OS Socket Integration**: Formally verified via `P9-NET-01` (`testP9RealNetworkSocketTransportFlow`) testing live TCP socket `LINK_AUTH`, encrypted direct messaging, ACKs, disconnect $T_5$, and reconnect $T_4$ continuity over `127.0.0.1:42426`.
- **Physical Multi-Device LAN Acceptance**: Currently **`PENDING`** field testing across real physical Android and Desktop hardware on a live Wi-Fi router.
- **Milestone Phase**: **vNext Implementation Milestone (Phases P0–P10) & Emergency Location Beacon (Schema 13)** complete and frozen.
