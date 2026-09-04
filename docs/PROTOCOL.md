# BIT FOR US — Binary Wire Protocol Specification

Version: **v1.4 (Synchronized with Codebase)**  
Last Updated: **September 2026**

---

## 1. Protocol Design Philosophy

The BIT FOR US wire protocol is engineered for **low-bandwidth, high-loss, multi-hop wireless mesh networks** over Bluetooth Low Energy (BLE) and offline Wi-Fi sockets.

Key characteristics:
1. **Fixed Binary Overhead**: Total packet overhead is fixed at exactly **56 bytes** (40-byte structured header + 16-byte AEAD authentication tag).
2. **Deterministic Big-Endian Serialization**: Platform-independent wire representation ensuring byte-for-byte consistency across Android, Desktop (JVM), and embedded runtimes.
3. **Integrated AEAD Authentication**: Authenticated Additional Data (AAD) binds the entire 40-byte header to the ciphertext payload, preventing packet tampering, header substitution, or recipient redirection.
4. **QoS Priority Classes**: All 17 packet types map deterministically to 4 Quality-of-Service priority tiers.
5. **Strict Hop-Boundary Enforcement**: Clear separation between multi-hop routable packets ($ttl \le 7$) and strictly 1-hop real-time traffic ($ttl = 1$).

---

## 2. Packet Binary Frame Layout

```
 0                   1                   2                   3
 0 1 2 3 4 5 6 7 8 9 0 1 2 3 4 5 6 7 8 9 0 1 2 3 4 5 6 7 8 9 0 1
+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+
|  Packet Type  |                                               |
+-+-+-+-+-+-+-+-+                                               +
|                  Message ID (UUID - 16 bytes)                 |
|                                                               |
+               +-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+
|               |                                               |
+-+-+-+-+-+-+-+-+                                               +
|                   Sender Node ID (Long - 8 bytes)             |
+               +-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+
|               |                                               |
+-+-+-+-+-+-+-+-+                                               +
|                 Recipient Node ID (Long - 8 bytes)            |
+               +-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+
|               |      TTL      |      Timestamp (4 bytes)      |
+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+
|       Timestamp (cont.)       |    Payload Length (2 bytes)   |
+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+
|                                                               |
|                   Payload (0 .. 2048 bytes)                   |
|                                                               |
+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+
|                                                               |
|              AES-GCM Auth Tag (AEAD - 16 bytes)               |
|                                                               |
+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+
```

### Field Definitions

| Field | Offset | Length | Type | Description |
| :--- | :---: | :---: | :---: | :--- |
| **`Packet Type`** | 0 | 1 byte | `Byte` | Numerical opcode identifying packet semantics (see Section 3). |
| **`Message ID`** | 1 | 16 bytes | `UUID` | 128-bit universally unique message identifier (`mostSigBits` + `leastSigBits`). |
| **`Sender ID`** | 17 | 8 bytes | `Long` | 64-bit cryptographic identifier derived from sender's public key. |
| **`Recipient ID`** | 25 | 8 bytes | `Long` | Target node ID for directed packets, or `-1L` (`0xFFFFFFFFFFFFFFFF`) for public broadcasts. |
| **`TTL`** | 33 | 1 byte | `UInt8` | Time-to-Live hop limit. Decremented at each relay hop; dropped when reaches 0. |
| **`Timestamp`** | 34 | 4 bytes | `UInt32` | Unix epoch timestamp in seconds (`currentTimeMillis() / 1000L and 0xFFFFFFFFL`). |
| **`Payload Length`** | 38 | 2 bytes | `UInt16` | Length of payload in bytes ($0 \le N \le 2048$). |
| **`Payload`** | 40 | $N$ bytes | `ByteArray` | Ciphertext or raw data bytes. |
| **`Auth Tag`** | $40 + N$ | 16 bytes | `ByteArray` | 128-bit NIST SP 800-38D AES-GCM authentication tag. Zeroed for unencrypted packets. |

- **Header Size**: Exactly 40 bytes.
- **Auth Tag Size**: Exactly 16 bytes.
- **Minimum Packet Size** ($N = 0$): 56 bytes.
- **Maximum Payload Size** ($N = 2048$): 2104 bytes.

---

## 3. Packet Type Registry & QoS Mapping

The protocol defines 17 active packet types, grouped into 4 Quality-of-Service priority tiers defined in [`TrafficPriority.kt`](file:///c:/Users/hp/Downloads/BIT%20FOR%20US/core/src/main/java/com/meshwhisper/core/protocol/TrafficPriority.kt):

| Code | Packet Type | QoS Priority Tier | Default TTL | Description |
| :---: | :--- | :--- | :---: | :--- |
| `0x00` | `BROADCAST_MESSAGE` | Tier 2 (`STANDARD_MESSAGING`) | 7 | Public channel message encrypted with channel-derived key. |
| `0x01` | `DIRECT_MESSAGE` | Tier 2 (`STANDARD_MESSAGING`) | 7 | Unicast E2EE chat message encrypted with peer session key. |
| `0x02` | `KEY_EXCHANGE` | Tier 1 (`HIGH_INTERACTIVE`) | 7 | Public key announcement during peer discovery handshake. |
| `0x03` | `ACK` | Tier 1 (`HIGH_INTERACTIVE`) | 7 | End-to-end delivery confirmation returned to originator. |
| `0x04` | `PEER_ANNOUNCE` | Tier 2 (`STANDARD_MESSAGING`) | 7 | Periodic presence beacon containing alias and Ed25519 signature. |
| `0x05` | `MEDIA_INIT` | Tier 3 (`BULK_TRANSFER`) | 4 | Chunked media metadata session descriptor (SHA-256, dimensions). |
| `0x06` | `MEDIA_CHUNK` | Tier 3 (`BULK_TRANSFER`) | 4 | Individual chunk payload (up to 400 bytes). |
| `0x07` | `AVATAR_REQUEST` | Tier 3 (`BULK_TRANSFER`) | 4 | Unicast request to download a peer's profile picture. |
| `0x08` | `TYPING_INDICATOR` | Tier 1 (`HIGH_INTERACTIVE`) | 7 | Ephemeral status indicating peer is typing in direct chat. |
| `0x09` | `MEDIA_NACK` | Tier 3 (`BULK_TRANSFER`) | 4 | Selective retransmission request for missing chunk indices. |
| `0x0A` | `MEDIA_ACK` | Tier 3 (`BULK_TRANSFER`) | 4 | Confirmation that complete media file was verified and stored. |
| `0x0B` | `MEDIA_ABORT` | Tier 3 (`BULK_TRANSFER`) | 4 | Transfer cancellation signal. |
| `0x0C` | `SOS_MESSAGE` | Tier 0 (`CRITICAL_EMERGENCY`) | 7 | High-priority distress alert containing GPS coordinates. |
| `0x0D` | `PROFILE_UPDATE` | Tier 2 (`STANDARD_MESSAGING`) | 7 | Signed profile broadcast with monotonically increasing version counter. |
| `0x0E` | `PROFILE_REQUEST` | Tier 2 (`STANDARD_MESSAGING`) | 7 | Unicast request for latest signed profile of a peer. |
| `0x0F` | `VOICE_CALL_SIGNAL` | Tier 1 (`HIGH_INTERACTIVE`) | **1** | Call control signal (OFFER, ANSWER, DECLINE, HANGUP, BUSY). |
| `0x10` | `VOICE_FRAME` | Tier 1 (`HIGH_INTERACTIVE`) | **1** | Real-time 20ms compressed voice frame (IMA ADPCM). |

---

## 4. Specialized Binary Payload Specifications

### 4.1. Voice Call Signaling Payload (`VOICE_CALL_SIGNAL = 0x0F`)
Binary structure (fixed **25 bytes**):
```
 0                   1                   2                   3
 0 1 2 3 4 5 6 7 8 9 0 1 2 3 4 5 6 7 8 9 0 1 2 3 4 5 6 7 8 9 0 1
+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+
|  Action Code  |                                               |
+-+-+-+-+-+-+-+-+                                               +
|               Call Session ID (UUID - 16 bytes)               |
|                                                               |
+               +-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+
|               |                                               |
+-+-+-+-+-+-+-+-+                                               +
|                 Timestamp (Milliseconds - 8 bytes)            |
+               +-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+
|               |
+-+-+-+-+-+-+-+-+
```
**Action Codes**:
- `0x01` = `CALL_REQUEST` / `OFFER`
- `0x02` = `CALL_ACCEPT` / `ANSWER`
- `0x03` = `CALL_DECLINE`
- `0x04` = `CALL_END` / `HANGUP`
- `0x05` = `CALL_BUSY`

### 4.2. Voice Frame Payload (`VOICE_FRAME = 0x10`)
Binary structure (fixed **28 bytes header + 80 bytes audio = 108 bytes**):
```
 0                   1                   2                   3
 0 1 2 3 4 5 6 7 8 9 0 1 2 3 4 5 6 7 8 9 0 1 2 3 4 5 6 7 8 9 0 1
+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+
|                                                               |
+               Call Session ID (UUID - 16 bytes)               +
|                                                               |
+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+
|                 Sequence Number (Int - 4 bytes)               |
+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+
|                                                               |
+               Timestamp (Milliseconds - 8 bytes)              +
|                                                               |
+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+
|                                                               |
|        Compressed Audio Data (80 bytes, 4-bit IMA ADPCM)      |
|                                                               |
+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+
```

### 4.3. Profile Update Payload (`PROFILE_UPDATE = 0x0D`)
Canonical serialization format:
```
 0                   1                   2                   3
 0 1 2 3 4 5 6 7 8 9 0 1 2 3 4 5 6 7 8 9 0 1 2 3 4 5 6 7 8 9 0 1
+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+
|                  Magic: 0x50524F46 ("PROF")                   |
+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+
|                                                               |
+             Version Counter (Long - 8 bytes)                  +
|                                                               |
+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+
|                                                               |
+                 Node ID (Long - 8 bytes)                      +
|                                                               |
+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+
| Alias Len (1) | Display Name UTF-8 Bytes (max 32 bytes) ...   |
+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+
|   Bio Len (2 bytes)           | Bio UTF-8 Bytes ...           |
+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+
|                  Avatar Hash (Int - 4 bytes)                  |
+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+
|                                                               |
+             Ed25519 Digital Signature (64 bytes)              +
|                                                               |
+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+
```

---

## 5. Additional Authenticated Data (AAD) Construction

To prevent ciphertext relocation attacks, whenever a packet is encrypted via AES-GCM, the AEAD authentication tag is calculated over an **Authenticated Additional Data (AAD)** block that incorporates the exact packet header:

```kotlin
val aad = ByteBuffer.allocate(40).order(ByteOrder.BIG_ENDIAN).apply {
    put(type.code)
    putLong(messageId.mostSignificantBits)
    putLong(messageId.leastSignificantBits)
    putLong(senderId)
    putLong(recipientId)
    put((ttl and 0xFF).toByte())
    putInt((timestamp and 0xFFFFFFFFL).toInt())
    putShort((payloadLen and 0xFFFF).toShort())
}.array()
```

If an attacker alters the `recipientId`, `senderId`, `messageId`, or `timestamp` in transit, the AES-GCM tag verification fails immediately and the packet is rejected.

---

## 6. Strict Transport Boundary Rules

1. **Strict 1-Hop Constraint**:
   Packets with type `VOICE_CALL_SIGNAL` and `VOICE_FRAME` must be stamped with `ttl = 1`. Intermediate mesh nodes will **never** forward, relay, or queue them in Room store-and-forward tables.
2. **Multi-Hop Bounds**:
   Direct messages enforce a maximum TTL of 7 (`DEFAULT_TTL`). Packets received with `ttl <= 1` are never forwarded further.
3. **Anti-Replay Window**:
   Live broadcast and voice packets enforce a strict 10-minute timestamp freshness window. Direct messages delivered via store-and-forward are allowed a 24-hour validity window.
