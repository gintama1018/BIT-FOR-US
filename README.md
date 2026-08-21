# MeshWhisper

Offline peer-to-peer messaging for Android over Bluetooth Low Energy (BLE) multi-hop flood-relay mesh networks.

---

## 1. Problem and Motivation

In disaster response zones, remote field operations, dense public gatherings, or network-denied environments, centralized communications infrastructure (cellular towers, DNS root servers, and ISP backbones) frequently suffers catastrophic failure, severe congestion, or intentional shutdowns. MeshWhisper provides local, internet-independent text and multimedia messaging directly between nearby Android devices without requiring cellular towers, SIM cards, Wi-Fi access points, or external servers.

---

## 2. Key Features

- **Multi-Hop Flood Relay**: Automatically relays packets through intermediate devices so nodes out of direct Bluetooth radio range can communicate.
- **End-to-End Encryption**: 1-to-1 direct chats are encrypted with ephemeral session keys derived via X25519 Elliptic Curve Diffie-Hellman and HKDF-SHA256.
- **Authenticated Additional Data (AAD)**: 40-byte binary packet headers are cryptographically bound to the AES-256-GCM authentication tag to prevent tampering by intermediate relays.
- **Encrypted Local Database**: Local SQLite storage (messages, contacts, logs, and store-and-forward queues) is encrypted at rest using SQLCipher with its 256-bit passphrase sealed inside the Android Keystore.
- **TOFU Safety Number Verification**: Detects public key fingerprint changes and displays visual alert banners to prevent man-in-the-middle impersonation.
- **Decentralized Topology Visualization**: Real-time force-directed canvas ("Web of Nodes") driven by a neighbor-gossip protocol extension showing real direct vs. relayed links.
- **Paced Multimedia Messaging**: Chunked transfer of downscaled images (max 640px JPEG, ≤60KB) and voice notes (AAC mono at 24kbps, ≤30s) with single-transfer concurrency caps and 70ms chunk pacing.
- **Two-Layer Replay Protection**: Fast 4,000-entry in-memory `LruCache` coupled with a persistent Room database replay table.
- **Store-and-Forward for DMs**: Caches undelivered direct messages on intermediate relay nodes for up to 24 hours until the destination node announces presence.
- **Emergency Panic Duress Wipe**: Hardware Keystore master key purge and cryptographic database zeroization.

---

## 3. Architecture Overview

### Main Components
- **`MeshBleEngine`** (`ble/MeshBleEngine.kt`): Manages simultaneous BLE Central (scanning, GATT client) and Peripheral (advertising, GATT server) roles, negotiates MTU up to 512 bytes, and exposes live connected GATT node sets (`connectedNodeIds`).
- **`BleFrameFramer`** (`ble/BleFrameFramer.kt`): Fragments raw packets into MTU-bounded frames and reassembles them across concurrent multi-session Bluetooth connections.
- **`MeshRouter`** (`router/MeshRouter.kt`): Manages packet deduplication, TTL decrementing, store-and-forward queueing, neighbor-gossip encoding, and packet dispatching.
- **`CryptoEngine`** (`crypto/CryptoEngine.kt`): Generates X25519 identity keypairs, derives hourly epoch session keys via HKDF-SHA256, performs AES-256-GCM AEAD encryption/decryption with AAD binding, and wraps master keys in Android Keystore.
- **`MediaTransferManager`** (`media/MediaTransferManager.kt`): Handles image compression, voice note recording/playback, chunked outbound transmission with fresh per-packet initialization vectors, and inbound out-of-order reassembly in private app storage.
- **`GraphPhysics`** (`ui/graph/GraphPhysics.kt`): Pure-Kotlin force-directed physics simulation computing Coulomb repulsion, Hooke's spring attraction, centering gravity, and velocity damping.
- **`MeshDatabase`** (`data/MeshDatabase.kt`): SQLCipher-backed Room database storing `PeerEntity`, `MessageEntity`, `StoreForwardEntity`, `PacketLogEntity`, `ProcessedPacketEntity`, and `TopologyEdgeEntity`.

### Message Flow (Compose to Display)
1. **Compose**: User inputs a text or media message in `DirectChatDetailScreen` or `PublicMeshScreen`.
2. **Encrypt**: `CryptoEngine` derives an epoch session key using the peer's X25519 public key and encrypts the payload using AES-256-GCM. The 40-byte routing header is authenticated as AAD, producing a 16-byte authentication tag.
3. **Transmit**: `MeshRouter` builds the `MeshPacket` (`DEFAULT_TTL = 7` or `MEDIA_TTL = 4`) and passes the binary frame to `BleFrameFramer` to fragment across negotiated BLE MTU frames via `MeshBleEngine`.
4. **Relay**: Intermediate nodes receive frames, reassemble the packet, verify timestamp validity (±10 minute window), and check the two-layer deduplication cache. If not the recipient and `TTL > 1`, the relay decrements the TTL by 1 and rebroadcasts the raw binary frame over BLE.
5. **Decrypt & Display**: The recipient node verifies the AAD header and AEAD auth tag, decrypts the payload with its private session key, stores the message in `MeshDatabase`, and displays it in the Compose UI.

```mermaid
graph TD
    subgraph Sender ["Sender Node"]
        UI_Send["Compose / Media Picker"] --> Crypto["CryptoEngine (X25519 + AES-256-GCM)"]
        Crypto --> Router_Send["MeshRouter (TTL = 7 / 4)"]
        Router_Send --> Framer_Send["BleFrameFramer (MTU Fragment)"]
        Framer_Send --> BLE_TX["MeshBleEngine (GATT Server/Client)"]
    end

    subgraph Relay ["Intermediate Relay Node"]
        BLE_RX_R["MeshBleEngine"] --> Framer_Relay["BleFrameFramer (Reassemble)"]
        Framer_Relay --> Router_Relay["MeshRouter (Dedup + TTL - 1)"]
        Router_Relay --> BLE_TX_R["MeshBleEngine (Rebroadcast)"]
    end

    subgraph Recipient ["Recipient Node"]
        BLE_RX_Rec["MeshBleEngine"] --> Framer_Rec["BleFrameFramer (Reassemble)"]
        Framer_Rec --> Router_Rec["MeshRouter (Destination Match)"]
        Router_Rec --> Decrypt["CryptoEngine (AES-GCM Auth Tag + AAD Check)"]
        Decrypt --> DB[("MeshDatabase (SQLCipher v5)")]
        DB --> UI_Rec["UI Message Bubble / Media View"]
    end

    BLE_TX -->|BLE Radio| BLE_RX_R
    BLE_TX_R -->|BLE Radio| BLE_RX_Rec
```

---

## 4. Security Model

### Cryptographic Primitives
- **Identity & Key Exchange**: X25519 Elliptic Curve Diffie-Hellman (ECDH) via BouncyCastle (`X25519KeyPairGenerator`, `X25519Agreement`).
- **Key Derivation**: HKDF-SHA256 (`HKDFBytesGenerator`) with epoch-bound info context (`"MESHWHISPER_SESSION_KEY_V1_EPOCH_<epoch>"`) rotating session keys every 3,600 seconds (1 hour).
- **Symmetric Cipher**: AES-256-GCM (`AES/GCM/NoPadding`, 128-bit authentication tag) via Java Cryptography Architecture (JCA).
- **Nonce / IV Generation**: 12-byte initialization vectors derived uniquely per transmission from `messageId` (UUID). Every individual packet (including each media chunk) generates a fresh `UUID.randomUUID()`, eliminating AES-GCM nonce reuse risks.
- **At-Rest Storage Encryption**: 256-bit SQLCipher (`net.zetetic:sqlcipher-android:4.6.0`) database encryption with passphrase sealed in the Android Keystore (`AndroidKeyStore`, TEE/StrongBox backed).

### Encrypted vs. Plaintext Data
- **Encrypted**:
  - `DIRECT_MESSAGE` payloads (end-to-end encrypted with peer session key).
  - `MEDIA_INIT` and `MEDIA_CHUNK` payloads (end-to-end encrypted for DMs; community-key encrypted for public broadcast).
  - `BROADCAST_MESSAGE` payloads (encrypted with shared community key derived from static salt).
- **Plaintext (Unencrypted, but Authenticated)**:
  - 40-byte routing headers: `type` (1B), `messageId` (16B), `senderId` (8B), `recipientId` (8B), `ttl` (1B), `timestamp` (4B), `payloadLength` (2B).
  - *Header Authentication*: Headers are fed into AES-GCM as Additional Authenticated Data (AAD). If an intermediate relay tampers with routing fields (such as `senderId` or `timestamp`), the recipient's AEAD tag verification fails and the packet is dropped.
  - `PEER_ANNOUNCE` payloads: `alias` (string), `publicKey` (32B), and `neighborNodeIds` (8B * N) are unencrypted to enable discovery and topology mapping.

### TOFU (Trust-On-First-Use) Protection
When a node receives a `PEER_ANNOUNCE`, it hashes the 32-byte public key to derive a 64-bit Node ID and a SHA-256 visual safety number (`XX:XX:XX:XX`). If a known Node ID broadcasts a different public key, `MeshRouter` flags `hasKeyChanged = true`, invalidates cached session keys, and displays an in-chat security warning banner to prevent impersonation attacks.

---

## 5. How the Mesh Works

MeshWhisper uses a **TTL-based flood-relay** architecture. It does not maintain dynamic routing tables or compute shortest paths.

### Relay Mechanics
1. When a packet is broadcast over BLE, every reachable node receives and parses it.
2. The node evaluates its **Two-Layer Deduplication Cache**:
   - *Layer 1 (Fast RAM)*: 4,000-entry `LruCache` keyed by `"${messageId}:${packetType}"`.
   - *Layer 2 (Persistent)*: `processed_packets` SQLite table.
   If the packet was previously seen, it is dropped immediately to prevent broadcast loops.
3. If the packet is not addressed to the receiving node (or is a broadcast), and `packet.ttl > 1`, the receiving node decrements the TTL by 1 (`packet.ttl - 1`) and rebroadcasts the raw binary frame to its local BLE neighborhood.

### Protocol Parameters
- **`DEFAULT_TTL = 7`**: Text messages traverse up to 6–7 serial hops.
- **`MEDIA_TTL = 4`**: Lower TTL specifically for media chunks to reduce mesh traffic flood.
- **`CHUNK_PAYLOAD_SIZE = 1800` bytes**: Maximum raw media slice per `MEDIA_CHUNK` packet (fits within `MAX_PAYLOAD_SIZE = 2048`).
- **Transmission Pacing**: Outbound media chunks are paced with a `70ms` inter-chunk delay, with concurrency capped to 1 active media transfer per device.

*Tradeoff Note*: Flood routing trades bandwidth efficiency for absolute architectural simplicity and resilience against node mobility. It requires no route discovery handshakes and has no single point of failure, but scales with $O(N)$ transmissions per message across the network.

---

## 6. Multi-Floor / Large-Venue Deployment Guide

Bluetooth Low Energy (2.4 GHz) signals travel 10–30 meters line-of-sight in open corridors, but suffer severe attenuation through reinforced concrete and rebar floor slabs (often dropping signal strength below receiver sensitivity in under 2 meters of solid concrete).

```
[4th Floor: Room 402] ───BLE───▶ [Stairwell Bridge Node #4]
                                            │ (Open stair airgap)
                                            ▼
[3rd Floor: Lab 301]  ◀───BLE─── [Stairwell Bridge Node #3]
                                            │
                                            ▼
[2nd Floor: Aud-2]    ◀───BLE─── [Stairwell Bridge Node #2]
                                            │
                                            ▼
[1st Floor: Lobby]    ◀───BLE─── [Stairwell Bridge Node #1]
```

### Deployment Recommendations:
- **Station Stairwell Bridge Nodes**: Place a dedicated phone running MeshWhisper at each staircase landing. Stairwells provide an open vertical air corridor allowing BLE packets to hop between floors without penetrating solid slab.
- **Avoid Elevator Shafts**: Metallic elevator cabs and reinforced counterweight shafts act as Faraday cages that block 2.4 GHz RF propagation.
- **Disable OS Battery Optimization**: Ensure bridge devices have battery optimization set to **Unrestricted** (`Settings -> Apps -> MeshWhisper -> Battery -> Unrestricted`). This prevents Android OEM background execution limits from killing the foreground BLE service.
- **TTL Budgeting**: With `DEFAULT_TTL = 7`, a message can traverse up to 6 intermediate relays. For venues exceeding 5 floors, ensure bridge nodes are placed directly along vertical stairwell axes to minimize wasted horizontal hops.
- **Pre-Event Validation Protocol**: Send a test text from the top floor to the ground floor. Verify that the received message bubble displays `⚡ X hops (Relayed)`, confirming multi-hop propagation.

---

## 7. Getting Started

### Prerequisites
- Android Studio Ladybug / Meerkat (or JDK 17+)
- Android SDK 35 (Minimum SDK: 26 — Android 8.0 Oreo)
- Physical Android device with Bluetooth Low Energy support (emulators cannot test real BLE mesh functionality)

### Required Android Permissions
Configured in `app/src/main/AndroidManifest.xml`:
- `android.permission.BLUETOOTH_SCAN` (`neverForLocation` flag on API 31+)
- `android.permission.BLUETOOTH_ADVERTISE`
- `android.permission.BLUETOOTH_CONNECT`
- `android.permission.FOREGROUND_SERVICE` & `FOREGROUND_SERVICE_CONNECTED_DEVICE`
- `android.permission.RECORD_AUDIO` (for voice notes)
- `android.permission.WAKE_LOCK`

### Build Instructions

```powershell
# 1. Run Unit Tests (Crypto, Routing, Deduplication, Graph Physics, Media)
.\gradlew.bat testDebugUnitTest

# 2. Build Sideloadable Debug APK
.\gradlew.bat assembleDebug

# 3. Build Minified Release APK (R8 / ProGuard enabled)
.\gradlew.bat assembleRelease
```

Debug APK output location: `app/build/outputs/apk/debug/app-debug.apk`  
Release APK output location: `app/build/outputs/apk/release/app-release-unsigned.apk`

### Sideloading to Device
```powershell
adb install -r app/build/outputs/apk/debug/app-debug.apk
```

---

## 8. Known Limitations

- **Flood Scalability**: Because every packet is relayed by every node in range until TTL expires, total radio channel utilization grows with $O(N)$ per transmission. Deployments with dozens of concurrent active talkers in dense RF environments will experience packet collisions.
- **Live-Only Media Transfers**: To prevent intermediate relay devices from filling their encrypted databases with large binary chunks, `MEDIA_INIT` and `MEDIA_CHUNK` packets are **excluded from the store-and-forward queue**. If the recipient is offline when media is sent, the transfer will not be delivered later.
- **In-Memory Chunk Buffering**: Inbound media chunk reassembly occurs in RAM before writing the finalized file to private storage. If the app process is terminated mid-transfer, in-flight chunks are lost and must be re-sent.
- **Concrete Penetration**: BLE signals cannot reliably penetrate reinforced concrete floor slabs without dedicated bridge nodes placed in open air corridors.

---

## 9. License

This project is licensed under the Apache License 2.0 / MIT License. (See repository root for license details).
