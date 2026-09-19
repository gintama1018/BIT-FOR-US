# BIT FOR US / MeshWhisper — Secure Wire Protocol vNext Specification

**Status:** FROZEN Normative Specification  
**Protocol Version:** `2` (Wire Version: `1`)  
**Applies to:** `:core`, `:app`, `:desktop`, and third-party interoperable implementations  
**Supersedes:** Legacy Protocol Specification v1.4  

---

## 1. Protocol Design & Architecture Principles

The MeshWhisper vNext binary wire protocol is engineered for zero-infrastructure, hostile, low-bandwidth, and high-loss multi-hop wireless mesh networks operating over Bluetooth Low Energy (BLE) and offline local Wi-Fi TCP/UDP sockets.

### Invariant Rules
1. **Deterministic Binary Serialization**: All wire fields use Big-Endian (network byte order).
2. **Fixed Protocol Overhead**: Every mesh packet incurs exactly **56 bytes of overhead** (40-byte structured header + 16-byte AEAD authentication tag). Maximum packet size is bounded at **2104 bytes** (payload $\le$ 2048 bytes).
3. **AEAD Additional Authenticated Data (AAD)**: A 37-byte AAD structure binds the header to ciphertext payload, preventing packet tampering, header substitution, or recipient redirection.
4. **Single Signature Placement ($C\text{-}01$)**: The Ed25519 hop signature is **always** the trailing 64 bytes of `payload`, outside the AEAD, for every signed packet type. Unsigned packets ($ttl = 1$ voice) omit the trailing signature.
5. **No Independent Pre-Auth State ($C\text{-}05$)**: Pre-admission filters never insert into deduplication caches or databases before cryptographic authentication succeeds.
6. **Strict Cryptographic Attribution**: Every node address (`nodeId64`) is cryptographically derived from the node's long-term identity public key. Senders cannot spoof node IDs without failing admission ($C\text{-}02$).

---

## 2. Node Identity & Cryptographic Model

A MeshWhisper node maintains two distinct cryptographic key pairs:

```
                  Master Identity Seed (32 bytes)
                               │
            ┌──────────────────┴──────────────────┐
            ▼                                     ▼
Long-Term Ed25519 Identity Key          Rotatable X25519 Ephemeral Key
          (IK)                                  (EK)
    IK_sk (32 bytes)                      EK_sk (32 bytes)
    IK_pk (32 bytes)                      EK_pk (32 bytes)
            │                                     │
            ▼                                     │
       identityHash                               │
   SHA-256("MW/NODE/v2" ‖ 0x00 ‖ IK_pk)           │
            │                                     │
            ▼                                     ▼
         nodeId64                       Identity Binding Certificate
  BE_u64(identityHash[0..8])                         (IBC)
```

### 2.1. Long-Term Identity Key (IK)
- **Algorithm**: Ed25519 (RFC 8032).
- **Properties**: Static, long-term, non-rotating. Generated once upon identity creation.
- **Role**: Signs presence announcements, profile updates, hop authentication transcripts, and Identity Binding Certificates (IBC).
- **Identity Rotation Invariant**: `IK` cannot be rotated. A new `IK` constitutes an entirely distinct identity with a new `identityHash` and `nodeId64`, treated as a stranger ($C\text{-}12$).

### 2.2. Canonical `identityHash` Derivation
$$\text{identityHash} = \text{SHA-256}(\text{"MW/NODE/v2"} \parallel 0\text{x}00 \parallel IK_{\text{pk}})$$
- **Length**: Exactly 32 bytes.
- **Domain Separator**: `"MW/NODE/v2"` (10 bytes UTF-8) + `0x00` byte.

### 2.3. Node ID (`nodeId64`) Semantics
$$\text{nodeId64} = \text{BE\_u64}(\text{identityHash}[0..8])$$
- **Length**: 8 bytes (`Long` / unsigned 64-bit integer).
- **Unicast Destination**: Any non-negative 64-bit integer corresponding to a valid identity.
- **Broadcast Address**: `-1L` (`0xFFFFFFFFFFFFFFFF`), reserved for channel floods, SOS emergency broadcasts, and presence announcements.
- **Anti-Spoofing Check ($C\text{-}02$)**: Ingestion pipelines verify that $\text{BE\_u64}(\text{identityHash}[0..8]) == \text{header.senderId}$. If they do not match, the packet is immediately dropped with no fields parsed.

### 2.4. Rotatable Ephemeral Key (EK) & Identity Binding Certificate (IBC)
- **Algorithm**: X25519 (RFC 7748).
- **Properties**: Ephemeral, rotatable key pair used for Diffie-Hellman key agreement (ECDH) in direct messaging, session derivation, and transport links.
- **IBC Structure**: Binds $EK_{\text{pk}}$ to $IK_{\text{pk}}$ under a specific monotonically increasing `keyVersion`:
  $$\text{IBC\_Preimage} = \text{"MW/IBC/v2"} \parallel 0\text{x}00 \parallel 0\text{x}03 \parallel IK_{\text{pk}} \parallel EK_{\text{pk}} \parallel \text{BE\_u64}(\text{keyVersion}) \parallel \text{BE\_u64}(\text{notBefore})$$
  - **Preimage Length**: Exactly 114 bytes.
  - **Purpose Tag**: `0x03` (`IBC`).
  - **Signature**: $\text{ibcSignature} = \text{Ed25519Sign}(IK_{\text{sk}}, \text{IBC\_Preimage})$ (64 bytes).
- **Validation Rule ($C\text{-}11$)**:
  $$\text{notBefore} \le \text{packet.timestamp} + 120\text{ s}$$
  Certificates never expire; offline meshes do not have centralized clocks. Ordering is enforced purely by `keyVersion`.

---

## 3. Link-Layer Authentication (LINK_AUTH)

Before any non-discovery packet can traverse a direct physical connection (BLE GATT or Wi-Fi TCP), the peers must execute the mutual **`LINK_AUTH` (PacketType `0x31`)** handshake.

### 3.1. Handshake Flow
```
Initiator (Node A)                                    Responder (Node B)
        │                                                     │
        │─── Stage 0x01: HELLO (169 B) ──────────────────────▶│
        │◀── Stage 0x01: HELLO (169 B) ───────────────────────│
        │                                                     │
        │    [Both compute Transcript T & derive K_link]      │
        │                                                     │
        │─── Stage 0x02: CONFIRM (65 B) ─────────────────────▶│
        │◀── Stage 0x02: CONFIRM (65 B) ──────────────────────│
        │                                                     │
        │       [Link State: AUTHENTICATED -> T4 Applied]     │
```

### 3.2. Packet Formats

#### Stage `0x01`: HELLO (169 bytes)
```
 0                   1                   2                   3
 0 1 2 3 4 5 6 7 8 9 0 1 2 3 4 5 6 7 8 9 0 1 2 3 4 5 6 7 8 9 0 1
+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+
|  Stage (0x01) |                                               |
+-+-+-+-+-+-+-+-+           Nonce (32 bytes)                    +
|                                                               |
+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+
|                      IK_pk (32 bytes)                         |
+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+
|                      EK_pk (32 bytes)                         |
+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+
|                     keyVersion (8 bytes)                      |
+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+
|                      notBefore (8 bytes)                      |
+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+
|                                                               |
|                  ibcSignature (64 bytes)                      |
|                                                               |
+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+
```

#### Stage `0x02`: CONFIRM (65 bytes)
```
 0                   1                   2                   3
 0 1 2 3 4 5 6 7 8 9 0 1 2 3 4 5 6 7 8 9 0 1 2 3 4 5 6 7 8 9 0 1
+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+
|  Stage (0x02) |                                               |
+-+-+-+-+-+-+-+-+                                               +
|                                                               |
|                  confirmSig (64 bytes)                        |
|                                                               |
+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+
```

### 3.3. Cryptographic Derivation of $T$ and $K_{\text{link}}$
1. **Ordering**: Compare $\text{identityHash}_A$ and $\text{identityHash}_B$ lexicographically:
   - $\text{HELLO}_{\text{lo}}$ is the HELLO payload from the node with smaller identity hash.
   - $\text{HELLO}_{\text{hi}}$ is the HELLO payload from the node with larger identity hash.
2. **Handshake Transcript $T$**:
   $$T = \text{SHA-256}(\text{"MW/TCP/v2"} \parallel 0\text{x}00 \parallel \text{HELLO}_{\text{lo}} \parallel \text{HELLO}_{\text{hi}})$$
3. **Shared Secret & Link Key ($K_{\text{link}}$)**:
   $$\text{sharedSecret} = \text{X25519}(EK_{\text{sk}}, EK_{\text{pk\_peer}})$$
   $$K_{\text{link}} = \text{HKDF-SHA256}(\text{ikm} = \text{sharedSecret}, \text{salt} = \text{"MW/LINK/SALT/v2"}, \text{info} = \text{"MW/LINK/KEY/v2"} \parallel T, \text{len} = 32)$$
4. **CONFIRM Signature**:
   $$\text{Preimage} = \text{"MW/SIG/v2"} \parallel 0\text{x}00 \parallel 0\text{x}04 \parallel T \parallel \text{identityHash}_{\text{self}} \parallel \text{identityHash}_{\text{peer}}$$
   $$\text{confirmSig} = \text{Ed25519Sign}(IK_{\text{sk}}, \text{Preimage})$$
   *Note: Both `identityHash` values are included inside the signed statement to defend against SIGMA identity misbinding.*

---

## 4. Binary Wire Framing (MeshPacket)

Every packet on the mesh conforms byte-for-byte to the canonical 56-byte overhead frame.

```
 0                   1                   2                   3
 0 1 2 3 4 5 6 7 8 9 0 1 2 3 4 5 6 7 8 9 0 1 2 3 4 5 6 7 8 9 0 1
+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+
|V| Type (0..31)|                                               |
+-+-+-+-+-+-+-+-+                                               +
|                  messageId (UUID - 16 bytes)                  |
|                                                               |
|               +-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+
|               |                                               |
+-+-+-+-+-+-+-+-+                                               +
|                   senderId (UInt64 - 8 bytes)                 |
|               +-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+
|               |                                               |
+-+-+-+-+-+-+-+-+                                               +
|                  recipientId (UInt64 - 8 bytes)               |
|               +-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+
|               |      TTL      |      timestamp (4 bytes)      |
+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+
|       timestamp (cont.)       |    payloadLength (2 bytes)    |
+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+
|                                                               |
|                   Payload (0 .. 2048 bytes)                   |
|                   [Ciphertext ‖ HopSig (64B)]                 |
|                                                               |
+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+
|                                                               |
|               AEAD Auth Tag (AES-GCM - 16 bytes)              |
|                                                               |
+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+
```

### 4.1. Header Field Specifications

| Field | Offset | Length | Type | Wire Encoding & Constraints |
| :--- | :---: | :---: | :---: | :--- |
| **`Wire Type Byte`** | 0 | 1 byte | `Byte` | `(protocolVersion << 5) | (packetTypeCode & 0x1F)`. Protocol version MUST be `1`. |
| **`messageId`** | 1 | 16 bytes | `UUID` | 128-bit CSPRNG unique packet identifier (`mostSignificantBits` then `leastSignificantBits`). |
| **`senderId`** | 17 | 8 bytes | `UInt64` | Originator node ID, strictly matching `BE_u64(identityHash[0..8])`. |
| **`recipientId`** | 25 | 8 bytes | `UInt64` | Target node ID for unicast, or `-1L` (`0xFFFFFFFFFFFFFFFF`) for broadcast. |
| **`TTL`** | 33 | 1 byte | `UInt8` | Time-to-Live. Clamped to $\min(\text{rawTTL}, \text{MAX\_TTL}[\text{type}])$. Dropped when decremented to 0. |
| **`timestamp`** | 34 | 4 bytes | `UInt32` | Unix epoch in seconds (`System.currentTimeMillis() / 1000L & 0xFFFFFFFFL`). |
| **`payloadLength`**| 38 | 2 bytes | `UInt16` | Payload size in bytes ($0 \le N \le 2048$). |
| **`Payload`** | 40 | $N$ bytes | `ByteArray` | Ciphertext or raw data bytes. Signed packets append 64-byte Ed25519 signature. |
| **`Auth Tag`** | $40 + N$ | 16 bytes | `ByteArray` | AES-256-GCM 128-bit authentication tag. Non-zero for all packets except `LINK_AUTH` ($C\text{-}08$). |

---

## 5. Packet Type Registry & Resource Limits

| Code | Type Name | QoS Priority Tier | Max TTL | Signature | Past Window | Purpose |
| :---: | :--- | :--- | :---: | :---: | :---: | :--- |
| `0x00` | `BROADCAST_MESSAGE` | Tier 2 (`STANDARD`) | 7 | Signed | 86,400 s | Public channel chat messages |
| `0x01` | `DIRECT_MESSAGE` | Tier 2 (`STANDARD`) | 7 | Signed | 86,400 s | Point-to-point E2EE unicast message |
| `0x02` | `KEY_EXCHANGE` | — | — | — | — | **RETIRED in vNext** (0x22 rejected by codec) |
| `0x03` | `ACK` | Tier 1 (`INTERACTIVE`) | 7 | Signed | 86,400 s | End-to-end delivery confirmation |
| `0x04` | `PEER_ANNOUNCE` | Tier 2 (`STANDARD`) | 7 | Signed | 120 s | Periodic presence beacon with IBC |
| `0x05` | `MEDIA_INIT` | Tier 3 (`BULK`) | 4 | Signed | 120 s | Metadata descriptor for media transfer |
| `0x06` | `MEDIA_CHUNK` | Tier 3 (`BULK`) | 4 | Unsigned | 120 s | Chunk payload ($N \le 320$ B) |
| `0x07` | `AVATAR_REQUEST` | Tier 3 (`BULK`) | 4 | Signed | 120 s | Unicast avatar download request |
| `0x08` | `TYPING_INDICATOR` | Tier 1 (`INTERACTIVE`) | 7 | Signed | 60 s | Ephemeral typing state notification |
| `0x09` | `MEDIA_NACK` | Tier 3 (`BULK`) | 4 | Signed | 120 s | Selective chunk retransmission request |
| `0x0A` | `MEDIA_ACK` | Tier 3 (`BULK`) | 4 | Signed | 120 s | Complete media verification receipt |
| `0x0B` | `MEDIA_ABORT` | Tier 3 (`BULK`) | 4 | Signed | 120 s | Cancellation of active transfer |
| `0x0C` | `SOS_MESSAGE` | Tier 0 (`EMERGENCY`) | 7 | Signed | 86,400 s | High-priority distress beacon |
| `0x0D` | `PROFILE_UPDATE` | Tier 2 (`STANDARD`) | 7 | Signed | 86,400 s | Signed profile update with version |
| `0x0E` | `PROFILE_REQUEST` | Tier 2 (`STANDARD`) | 7 | Signed | 86,400 s | Unicast request for peer profile |
| `0x0F` | `VOICE_CALL_SIGNAL`| Tier 1 (`INTERACTIVE`) | **1** | Signed | 60 s | Call setup signaling (`OFFER`, `ANSWER`) |
| `0x10` | `VOICE_FRAME` | Tier 1 (`INTERACTIVE`) | **1** | Unsigned | 60 s | Real-time 20ms audio frame |
| `0x11` | `CUSTODY_OFFER` | Tier 1 (`INTERACTIVE`) | 7 | Signed | 86,400 s | Relay custody negotiation offer |
| `0x12` | `CUSTODY_ACCEPT` | Tier 1 (`INTERACTIVE`) | 7 | Signed | 86,400 s | Relay custody acceptance |
| `0x13` | `CUSTODY_ACK` | Tier 1 (`INTERACTIVE`) | 7 | Signed | 86,400 s | Downstream custody handoff confirmation |
| `0x31` | `LINK_AUTH` | Tier 0 (`EMERGENCY`) | **1** | Self-Contained | 60 s | Transport link mutual handshake |

---

## 6. Authenticated Encryption & Signature Transcripts

### 6.1. Additional Authenticated Data (AAD)
For every packet using AES-256-GCM encryption, the 37-byte AAD is computed as:
$$\text{AAD} = \text{type.code} \parallel \text{messageId (16B)} \parallel \text{senderId (8B)} \parallel \text{recipientId (8B)} \parallel \text{timestamp (4B)}$$
This binds the unencrypted routing header to the payload ciphertext. Modifying the sender, recipient, TTL timestamp, or message ID causes GCM authentication failure.

### 6.2. Canonical 115-Byte SIG_TRANSCRIPT ($C\text{-}06$)
All packet hop signatures (Purpose Tag `0x02` `CONTENT`) sign a standard 115-byte transcript:

```
Field Name                  Length      Value / Description
-------------------------------------------------------------------------
domain                      9 bytes     "MW/SIG/v2" (UTF-8)
zero_byte                   1 byte      0x00
purposeTag                  1 byte      0x02 (CONTENT)
protocolVersion             1 byte      0x01
packetTypeByte              1 byte      type.code
messageId                   16 bytes    UUID most + least sig bits
senderIdentityHash          32 bytes    Originator identityHash
senderNodeId64              8 bytes     Originator nodeId64
recipientNodeId64           8 bytes     Target nodeId64 (-1L for broadcast)
timestamp                   8 bytes     UInt64 epoch seconds
payloadLenExcludingSig      2 bytes     Ciphertext length N
ciphertextAndTagHash        32 bytes    SHA-256(ciphertext ‖ authTag)
-------------------------------------------------------------------------
TOTAL                                   115 bytes
```

Signature generation: $\text{hopSig} = \text{Ed25519Sign}(IK_{\text{sk}}, \text{SIG\_TRANSCRIPT})$.

---

## 7. Trust State Machine & Transition Rules

MeshWhisper enforces an authoritative, monotonic trust state machine. Room and SQLite tables store trust state directly, eliminating legacy verification booleans.

```
                   (none)
                     │
              T1     ▼
       ┌────────── SEEN ──────────┐
       │             │            │
   T4  │         T2  │            │ T7
       ▼             ▼            ▼
    LINKED ────────▶ VERIFIED  CONFLICTED
       ▲       T3        │
       │                 │ T6 (Key Rotation)
       └─────────────────┘
```

### 7.1. States
- **`SEEN`**: Peer discovered via authenticated `PEER_ANNOUNCE` or unblocked. Identity known; not verified.
- **`LINKED`**: Active direct physical link authenticated via mutual `LINK_AUTH`.
- **`IMPORTED`**: Migrated pre-vNext verified contact; receives a +1 routing cost until re-verified via camera QR.
- **`VERIFIED`**: Cryptographically authenticated out-of-band via CameraX QR scan.
- **`CONFLICTED`**: Multiple distinct identity hashes claiming the same `nodeId64`. Unicast routing suspended.
- **`BLOCKED`**: Explicitly blocked by user; all traffic silently dropped.
- **`LEGACY_UNVERIFIED`**: Pre-vNext unverified contact awaiting modern vNext announcement.

### 7.2. Complete Normative Transitions ($T_1$–$T_{11}$ Runtime, $T_{12}$ Migration)
- **$T_1$**: `(none) -> SEEN` upon first authenticated announcement.
- **$T_2$**: `SEEN -> VERIFIED` upon camera QR scan verification.
- **$T_3$**: `LINKED -> VERIFIED` upon camera QR scan verification.
- **$T_4$**: `SEEN / IMPORTED -> LINKED` upon mutual `LINK_AUTH` on a live link.
- **$T_5$**: `LINKED -> SEEN` upon live link disconnection (for non-verified peers).
- **$T_6$**: `VERIFIED -> LINKED` (`hasKeyChanged = true`) exclusively upon accepted EK key rotation for verified peers. Non-verified peers update keys while retaining current state.
- **$T_7$**: `(any) -> CONFLICTED` upon `nodeId64` collision across distinct identity hashes.
- **$T_8$**: `CONFLICTED -> VERIFIED` upon camera QR collision resolution (scanned peer becomes `VERIFIED`, colliding peer becomes `BLOCKED`).
- **$T_9$**: `(any) -> BLOCKED` upon explicit user block.
- **$T_{10}$**: `BLOCKED -> SEEN` upon explicit user unblock.
- **$T_{11}$**: `LEGACY_UNVERIFIED -> SEEN` upon first authenticated vNext announcement.
- **$T_{12}$** *(Migration Only)*: Pre-vNext unverified $\to$ `LEGACY_UNVERIFIED`; pre-vNext verified $\to$ `IMPORTED`.

---

## 8. Packet Admission Pipeline (S0–S7)

Every packet arriving at a node passes through a strict sequential gate pipeline:

```
[Ingress] ──▶ S0: Transport & Link Binding Check
                 │
              S1: Framing & Wire Sanity (Header 40B, Tag 16B)
                 │
              S2: Freshness Window Check (-120s .. +pastWindow)
                 │
              S3: Pre-Auth Dedup Check (Read-Only)
                 │
              S4: Identity Resolution & SenderId Binding
                 │
              S5: AEAD Payload Decryption
                 │
              S6: Hop Signature Verification (Rate-Limited Budget: 32/s)
                 │
              S7: Route Admission & Atomic Dedup Commit ──▶ [Dispatch / Relay]
```

- **S0 (Admission)**: Verifies physical link state. Drops non-discovery traffic if link is not `AUTHENTICATED`.
- **S1 (Framing)**: Enforces protocolVersion == 1, length == 56 + payloadLen, non-zero auth tag (except LINK_AUTH).
- **S2 (Freshness)**: Validates $-120\text{ s} \le \text{age} \le \text{PAST\_WINDOW}[\text{type}]$.
- **S3 (Dedup)**: Read-only check in RAM LRU and SQLite `processed_packets`. No inserts at this stage ($C\text{-}05$).
- **S4 (Identity)**: Resolves sender identity; checks `BE_u64(identityHash[0..8]) == senderId`.
- **S5 (AEAD)**: Verifies GCM auth tag against AAD.
- **S6 (Signature)**: Verifies Ed25519 hop signature against 115-byte transcript. Bounded by 32 verifications/sec/link.
- **S7 (Commit)**: Atomically inserts `processed_packets` row and dispatches packet to UI or relay engine.
