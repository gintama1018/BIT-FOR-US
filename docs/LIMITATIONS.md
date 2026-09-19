# BIT FOR US / MeshWhisper — Engineering Limitations & Verification Matrix

**Status:** Honest Technical Grounding  
**Applies to:** `:core`, `:app`, `:desktop`  
**Supersedes:** Legacy Limitations Specification v1.4  

---

## 1. Engineering Philosophy & Verification Classification

MeshWhisper is engineered with complete technical transparency. Building decentralized, offline mesh networks on consumer mobile and desktop hardware involves fundamental radio frequency, cryptographic, operating system, and battery constraints.

Every claim and capability in this repository is categorized into one of four explicit verification tiers:

| Verification Tier | Definition | Current Systems in This Tier |
| :--- | :--- | :--- |
| **`PROVEN`** | Formally tested and deterministically verified by automated tests in continuous integration. | Wire serialization, cryptographic derivations, admission pipeline S0–S7, trust state machine ($T_1$–$T_{11}$), fail-closed vaults, media-at-rest encryption, localhost OS socket transport flow (`P9-NET-01`). |
| **`TESTED IN SIMULATION`** | Verified algorithmically across simulated multi-node virtual meshes on JVM. | Dijkstra shortest-path routing, link failure quarantine, store-and-forward custody handoffs, packet dedup loops. |
| **`MANUAL / PENDING`** | Fully implemented in software but awaiting physical field validation on hardware. | Physical Android $\leftrightarrow$ Desktop real Wi-Fi LAN acceptance, multi-device dense BLE mesh field trials. |
| **`KNOWN LIMITATION`** | Unavoidable physical, operating system, or protocol boundary documented by design. | Strict 1-hop voice boundary, BLE peripheral caps, Android Doze mode throttling, unauthenticated broadcast media chunk race ($C\text{-}14$), plaintext routing headers. |

---

## 2. Protocol & Cryptographic Boundaries

### 2.1. Unauthenticated Broadcast Media Chunk Race ($C\text{-}14$)
- **Classification**: `KNOWN LIMITATION`
- **Description**: For broadcast media transfers (channel public images), `MEDIA_CHUNK` packets carry no digital signature to avoid doubling BLE frame overhead on high-volume traffic. 
- **Residual Risk**: A local attacker within radio range can transmit fabricated chunks for an in-flight broadcast `mediaId`, causing the reassembled image to fail its final SHA-256 integrity check and be discarded.
- **Mitigations Enforced**:
  1. **Write-Once per Chunk Index**: The first chunk received for an index within a session is final. Attackers can only race the legitimate sender, not overwrite a clean transmission.
  2. **No NACK Retransmission Storms**: Broadcast SHA-256 mismatches discard the file silently without emitting NACKs.
  3. **Sender Rate Limits**: Maximum 2 re-attempts per `mediaId` from a given `senderId64` per hour.
  4. **Directed Transfers Immune**: Direct media transfers are encrypted with pairwise AEAD session keys, making chunk forgery cryptographically impossible.

### 2.2. Absence of Central PKI & Clock Revocation ($C\text{-}11$)
- **Classification**: `KNOWN LIMITATION`
- **Description**: In an offline disaster mesh, nodes have no access to NTP time servers, certificate authorities, or revocation lists (CRLs).
- **Enforcement**: Identity Binding Certificates (IBC) enforce ordering via `keyVersion` and validate $\text{notBefore} \le \text{packet.timestamp} + 120\text{s}$, but certificates never expire based on wall-clock time. Revocation of compromised identity keys is impossible without out-of-band communication.

### 2.3. Network Metadata & Traffic Analysis
- **Classification**: `KNOWN LIMITATION`
- **Description**: The 40-byte packet header (`senderId`, `recipientId`, `messageId`, `timestamp`, `ttl`) is transmitted in plaintext over radio hops so intermediate relay nodes can make routing and deduplication decisions without holding payload encryption keys.
- **Residual Risk**: An adversary monitoring RF airwaves can observe network topology, active node IDs, communication frequencies, and hop counts. Onion routing and cover-traffic padding are not implemented.

---

## 3. Real-Time Voice Calling Limitations

### 3.1. Strict 1-Hop Constraint ($ttl = 1$)
- **Classification**: `KNOWN LIMITATION`
- **Description**: Real-time voice calls (`VOICE_CALL_SIGNAL` and `VOICE_FRAME`) are strictly point-to-point between direct radio neighbors. Voice packets enforce $ttl = 1$ and are rejected by multi-hop relay pipelines.
- **Engineering Rationale**: Streaming 50 audio packets per second across multi-hop BLE relays causes compounding latency (>500–1500ms), packet loss bursts, and channel saturation, rendering duplex voice unintelligible.
- **Asynchronous Alternative**: Multi-hop voice communication is supported asynchronously via recorded **Voice Notes** transmitted through the store-and-forward media pipeline.

### 3.2. Audio Codec Fidelity
- **Classification**: `PROVEN`
- **Description**: Real-time voice uses 4-bit IMA ADPCM sampled at 8,000 Hz mono (32 kbps). Audio quality is comparable to standard telephone voice-band (G.711) and does not support wideband audio.

### 3.3. Voice Key Setup Pinning ($C\text{-}13$)
- **Classification**: `PROVEN`
- **Description**: Call encryption keys ($K_{\text{call}}$) are derived once from the epoch of the `OFFER` packet and pinned for the duration of the call, preventing key disagreement across 1-hour boundaries.

---

## 4. Hardware & Operating System Constraints

### 4.1. Android OS Doze Mode & OEM Battery Throttling
- **Classification**: `KNOWN LIMITATION`
- **Description**: While `MeshForegroundService` maintains a persistent notification and CPU wake locks, aggressive OEM battery management frameworks (e.g., Xiaomi MIUI/HyperOS, Huawei EMUI, Samsung OneUI) may suppress background BLE scanning or kill foreground services during deep device sleep.
- **Mitigation**: Users must manually exempt the app from battery optimization ("Unrestricted" battery setting).

### 4.2. Bluetooth Low Energy Hardware Caps
- **Classification**: `KNOWN LIMITATION`
- **Description**: Consumer smartphone Bluetooth chipsets support a maximum of 3 to 7 concurrent peripheral GATT connections. The codebase enforces a hard ceiling of `MAX_CONCURRENT_GATT_CONNECTIONS = 5`.
- **Impact**: A single node can maintain direct physical BLE links with at most 5 neighbors. Denser meshes rely on multi-hop forwarding or Wi-Fi.

### 4.3. Platform Transport Parity
- **Classification**: `PROVEN` (Architectural Parity) / `KNOWN LIMITATION` (Radio Support)
- **Description**: 
  - Android nodes support dual-radio transports: BLE Central/Peripheral + Wi-Fi TCP/UDP.
  - Desktop nodes (Windows / macOS) currently support **Wi-Fi TCP/UDP only**. Desktop nodes do not currently implement BLE GATT host drivers.

---

## 5. Network Scale & Verification Status

### 5.1. Simulated vs. Physical Evidence
- **Automated Unit & Integration Test Suite**: **331 / 331 tests passing (100%)**.
- **Real OS Socket Integration (`P9-NET-01`)**: **`PROVEN`**. Verifies live OS TCP socket loopback (`127.0.0.1:42426`) with full `LINK_AUTH`, $K_{\text{link}}$ derivation, bidirectional encrypted direct messaging, automated ACKs, socket closure $T_5$, and reconnect $T_4$ continuity.
- **Physical Multi-Device Android ↔ Desktop LAN Acceptance**: **`MANUAL / PENDING`**. Fully wired and ready for execution using `.\gradlew.bat :desktop:run`, but awaiting recorded physical execution on a live multi-device Wi-Fi router.
- **Dense Physical Multi-Device RF Mesh**: **`MANUAL / PENDING`**. Meshes beyond 5 physical hardware devices under heavy RF interference require dedicated operational field testing.

---

## 6. Storage & Capacity Quotas

To prevent database bloating and memory exhaustion on embedded hardware, strict quotas are enforced:
- **Per-Peer Store & Forward Buffer**: Maximum **50 messages** per offline recipient.
- **Global Store & Forward Buffer**: Maximum **500 total messages** buffered across all peers.
- **Message Expiration**: Queued store-and-forward messages expire automatically after **24 hours**.
- **Inbound Media Transfers**: Maximum **16 concurrent inbound transfers**, automatically cleaned up after 60 seconds of inactivity.
- **Dedup RAM Cache**: Bounded at **4,000 entries** with LRU eviction, backed by persistent SQLite storage.
