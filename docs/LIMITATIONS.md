# BIT FOR US — Engineering Limitations & Real-World Constraints

Version: **v1.4 (Synchronized with Codebase)**  
Last Updated: **September 2026**

---

## 1. Introduction

BIT FOR US is engineered with technical honesty. Building decentralized mesh networks on consumer mobile hardware involves severe physical, radio frequency, and operating system constraints.

This document outlines the **confirmed architectural and physical limitations** of the current system.

---

## 2. Voice Call Limitations

### 2.1. Strictly Direct 1-Hop Only
> [!IMPORTANT]
> **Real-time multi-hop voice is explicitly NOT supported.**

- Voice calls (`VOICE_CALL_SIGNAL` and `VOICE_FRAME`) require an active direct radio link (BLE GATT direct connection or local Wi-Fi direct socket).
- Packets enforce `ttl = 1`. Intermediate mesh nodes will never forward, relay, or queue voice frames.
- **Rationale**: Streaming 50 audio frames per second across a multi-hop BLE mesh causes compounding per-hop latency (>500–1200ms) and severe packet collision cascades, rendering bidirectional voice unintelligible.
- **Workaround for Multi-Hop**: Multi-hop voice communication is supported asynchronously via chunked **Voice Notes** (recorded AAC/MP4 memos transferred through store-and-forward media transfer).

### 2.2. Audio Codec Fidelity
- The real-time voice pipeline uses 4-bit IMA ADPCM sampled at 8,000 Hz mono (32 kbps).
- While clear and intelligible for tactical coordination and walkie-talkie telephony, it has voice-band telephone quality (comparable to G.711 / standard cellular 2G calls) and does not provide wideband high-fidelity audio.

---

## 3. Bluetooth Low Energy (BLE) Constraints

### 3.1. Hardware Connection Limits
- Most consumer Android Bluetooth chipsets impose a physical limit of **3 to 7 concurrent peripheral GATT connections**.
- The codebase enforces a hard ceiling of `MAX_CONCURRENT_GATT_CONNECTIONS = 5`.
- In dense peer environments, a node can maintain direct links with at most 5 physical neighbors simultaneously. Other peers must be reached via multi-hop relay or local Wi-Fi.

### 3.2. BLE ATT MTU Variance
- Although the protocol formats frames to fit within typical negotiated MTUs (185–512 bytes), BLE MTU negotiation is controlled by Android OS and peer hardware.
- If a low-end peer negotiates the minimum default BLE MTU of 23 bytes (20-byte payload), the `BleFrameFramer` must fragment packets across multiple GATT writes, reducing effective throughput.

### 3.3. RF Interference in 2.4 GHz Spectrum
- BLE and Wi-Fi share the crowded 2.4 GHz ISM band. In dense environments with active Wi-Fi routers, microwaves, or dozens of broadcasting smartphones, packet error rates (PER) increase significantly, requiring link rerouting or packet retransmissions.

---

## 4. Android Operating System Restrictions

### 4.1. Background Execution & Doze Mode
- When an Android device is unplugged, stationary, and the screen is off, the OS enters **Doze Mode**.
- While `MeshForegroundService` maintains a persistent notification and wake locks, certain aggressive OEM battery managers (e.g., Xiaomi MIUI, Huawei EMUI, Samsung OneUI) may still throttle background BLE scanning or terminate background services after prolonged inactivity.
- **Requirement**: Users must manually exempt the app from battery optimization ("Unrestricted" battery setting).

### 4.2. Bluetooth Peripheral Mode Support
- Certain older or budget Android smartphones do not support BLE Peripheral Mode (advertising).
- While these devices can function as Central scanners, they cannot be discovered by other Central-only devices.

---

## 5. Storage & Store-and-Forward Capacity

To prevent SQLite database bloat and flash memory wear, store-and-forward persistence enforces strict quotas:
- **Per-Recipient Limit**: At most **50 pending direct messages** are queued per offline peer.
- **Global Queue Limit**: At most **500 total messages** can be buffered in `store_forward_queue`.
- **Expiration**: Queued messages automatically expire after **24 hours**.

---

## 6. Privacy & Metadata Exposure

While message payloads and voice streams are end-to-end encrypted:
1. **Network Metadata**: The 40-byte packet header (containing `senderId`, `recipientId`, `messageId`, `timestamp`, and `ttl`) is transmitted in plaintext to allow intermediate nodes to make routing and deduplication decisions.
2. **Traffic Analysis**: An adversary intercepting RF packets can observe which Node IDs are communicating, the frequency of communication, and approximate hop distances. The protocol does not currently implement onion routing or cover-traffic padding.

---

## 7. Real-Device Physical Validation Gap

> [!WARNING]
> **Status: 118 automated tests pass 100% offline on JVM.**

While the automated test suite thoroughly verifies protocol serialization, cryptographic algorithms, state machine transitions, Dijkstra shortest paths, and failover logic:
- **Physical Multi-Device Validation**: The system has not yet undergone dense multi-phone field trials (e.g., 20+ real phones placed across physical buildings under heavy RF interference).
- Edge cases related to specific Android hardware Bluetooth stack crashes, OEM Bluetooth bugs, or extreme radio attenuation must be validated through physical device testing.
