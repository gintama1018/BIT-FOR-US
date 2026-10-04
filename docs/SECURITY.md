# BIT FOR US / MeshWhisper — Security Architecture & Evidence Specification

**Status:** FROZEN Normative Specification  
**Security Model:** Zero-Trust Decentralized Mesh  
**Applies to:** `:core`, `:app`, `:desktop`  
**Supersedes:** Legacy Security Specification v1.4  

---

## 1. Security Architecture & Trust Boundaries

MeshWhisper treats all physical radio environments (BLE advertisements, GATT characteristics, and Wi-Fi broadcast/multicast packets) as untrusted, hostile transport media. 

```
                                Hostile Radio Airwaves (BLE / Wi-Fi)
                                                  │
 ┌────────────────────────────────────────────────▼────────────────────────────────────────────────┐
 │ S0: Transport & Link Authentication Boundary (LINK_AUTH, K_link frame encryption)              │
 ├────────────────────────────────────────────────────────────────────────────────────────────────┤
 │ S1–S3: Pre-Authentication Gate (Length validation, freshness window, read-only dedup cache)    │
 ├────────────────────────────────────────────────────────────────────────────────────────────────┤
 │ S4–S6: Cryptographic Verification (Identity binding, AEAD tag check, Ed25519 hop signature)    │
 ├────────────────────────────────────────────────────────────────────────────────────────────────┤
 │ S7: Admission & State Commitment (Atomic processed_packets commit, route/custody dispatch)    │
 ├────────────────────────────────────────────────────────────────────────────────────────────────┤
 │ Storage Boundary (Room v13 SQLCipher TEE-backed on Android / PBKDF2 AES-GCM Vault on Desktop)  │
 └────────────────────────────────────────────────────────────────────────────────────────────────┘
```

### Core Security Invariants
1. **Fail-Closed by Default (I-1)**: Any packet, frame, certificate, signature, or vault record that fails structural, cryptographic, or freshness validation is immediately discarded. Missing or ambiguous state never defaults to permissive admission.
2. **Cryptographic Identity Attribution (I-2)**: Node addresses (`nodeId64`) are mathematically derived from Ed25519 identity public keys. Senders cannot spoof or claim addresses they do not cryptographically control.
3. **No Unauthenticated State Mutation (I-10)**: Deduplication caches, databases, session registries, and trust state machines are never modified by pre-auth or malformed packets.
4. **Single Protocol Authority (T-ARCH-01)**: Desktop and Android execute identical cryptographic validation rules via the shared `:core` module.

---

## 2. Packet Admission Pipeline (S0–S7)

Every packet arriving at a node passes through a strict, non-bypassable 8-stage gate pipeline ([`PacketPipeline.kt`](file:///c:/Users/hp/Downloads/BIT%20FOR%20US/core/src/main/java/com/meshwhisper/core/protocol/PacketPipeline.kt)):

```
[Packet Ingress]
       │
       ▼
   Stage S0: Transport Admission & Link State Check
       │     (Drops non-discovery traffic if link is not AUTHENTICATED)
       ▼
   Stage S1: Framing & Wire Format Sanity
       │     (protocolVersion == 1, 56B overhead, length == 56 + payloadLen, non-zero auth tag)
       ▼
   Stage S2: Freshness Window Enforcement
       │     (Rejects future skew > 120s or packets older than PAST_WINDOW[type])
       ▼
   Stage S3: Pre-Authentication Deduplication Check (READ-ONLY)
       │     (Checks RAM LRU & DB; NO insertions allowed to prevent pre-auth cache poisoning)
       ▼
   Stage S4: Identity Resolution & Anti-Spoofing Check
       │     (Validates BE_u64(identityHash[0..8]) == header.senderId; checks IBC)
       ▼
   Stage S5: AEAD Payload Decryption
       │     (Validates 37-byte AAD; verifies AES-256-GCM auth tag; decrypts payload)
       ▼
   Stage S6: Hop Signature Verification (Budget-Enforced)
       │     (Verifies Ed25519 signature on 115B transcript; rate-limited to 32 verifications/s/link)
       ▼
   Stage S7: Admission, Atomic Dedup Commit & Dispatch
       │     (Atomically writes processed_packets; emits AuthenticatedPacket to UI/Relay)
       ▼
[Accepted & Committed]
```

### Pipeline Details & Defenses
- **S0 (Transport Admission)**: Enforces that unauthenticated physical links only exchange `LINK_AUTH` and initial discovery packets. Any data, chat, voice, or media packets arriving on a pending link are discarded.
- **S1 (Wire Sanity)**: Rejects retired packet types (`KEY_EXCHANGE` `0x02`), invalid protocol versions ($\ne 1$), payload lengths exceeding 2048 bytes, and non-conforming lengths. Auth tags must be non-zero for all types except `LINK_AUTH` ($C\text{-}08$).
- **S2 (Freshness)**: Defends against replay attacks by enforcing $-120\text{ s} \le \text{age} \le \text{PAST\_WINDOW}[\text{type}]$. Out-of-window packets are dropped without state mutation.
- **S3 (Read-Only Dedup)**: Defends against **Pre-Auth Cache Poisoning ($C\text{-}05$)**. Attackers observing a message ID in flight cannot send a malformed pre-auth packet to poison the deduplication cache. Cache insertion happens only at S7.
- **S4 (Identity Anti-Spoofing)**: Enforces $\text{BE\_u64}(\text{identityHash}[0..8]) == \text{header.senderId}$ ($C\text{-}02$). Mismatched packets are dropped immediately.
- **S5 (AEAD Verification)**: Authenticates the 37-byte header AAD. Prevents header mutation, recipient redirection, or ciphertext bit-flipping.
- **S6 (Signature Verification & CPU DoS Protection)**: Verifies the 64-byte trailing Ed25519 signature against the 115-byte canonical transcript. Protected by a **32 verifications/second/link** rate limit ($C\text{-}16$, $C\text{-}17$), preventing signature flood attacks from starving the CPU.
- **S7 (Atomic Commitment)**: Atomically executes `INSERT OR IGNORE INTO processed_packets` and populates the RAM LRU read-through cache.

---

## 3. Link-Layer Transport Security (LINK_AUTH)

Direct peer connections (BLE GATT connections and Wi-Fi TCP streams) execute mutual cryptographic authentication before establishing an active session.

- **Handshake Protocol**: Two-stage mutual exchange: Stage `0x01` HELLO (169 B) followed by Stage `0x02` CONFIRM (65 B).
- **Transcript Binding**: Both nodes compute $T = \text{SHA-256}(\text{"MW/TCP/v2"} \parallel 0\text{x}00 \parallel \text{HELLO}_{\text{lo}} \parallel \text{HELLO}_{\text{hi}})$.
- **Key Derivation ($K_{\text{link}}$)**:
  $$K_{\text{link}} = \text{HKDF-SHA256}(\text{X25519}(EK_{\text{sk}}, EK_{\text{pk\_peer}}), \text{salt} = \text{"MW/LINK/SALT/v2"}, \text{info} = \text{"MW/LINK/KEY/v2"} \parallel T, 32)$$
- **SIGMA Identity-Misbinding Defense ($C\text{-}09$)**: Both $\text{identityHash}_{\text{self}}$ and $\text{identityHash}_{\text{peer}}$ are bound inside the signed CONFIRM preimage, preventing MITM identity-substitution attacks.
- **Cutover to Encrypted Transport**: Upon mutual confirmation, all post-auth TCP frames are encrypted with AES-256-GCM under $K_{\text{link}}$ (12B IV + plaintext + 16B tag).
- **Session Registry Limits**: Strict ceiling of **5 concurrent authenticated links** (`ResourceLimits.MAX_WIFI_AUTHENTICATED_SESSIONS`). Duplicate active identities on simultaneous connections are rejected.

---

## 4. Key Management, Rotation & Anti-Equivocation

```
                           Incoming Announcement with Valid IBC
                                             │
                                             ▼
                        Compare keyVersion with Stored Key Version
                                             │
                      ┌──────────────────────┼──────────────────────┐
                      ▼                      ▼                      ▼
             keyVersion > stored    keyVersion == stored   keyVersion < stored
                      │                      │                      │
                      ▼                      ▼                      ▼
                 [Accepted]             [Check ekPub]           [Rejected]
                 Update EK              ┌──────┴──────┐          Rollback
              Demote T6 if VERIFIED     ▼             ▼          Drop Packet
             hasKeyChanged = true     Matches       Differs
                                     [Ignored]   [Equivocation]
                                     No change    Drop Packet
                                                 Security Alert
```

- **EK Key Rotation ($T_6$)**: If a peer rotates its ephemeral key with a valid IBC signed by $IK_{\text{sk}}$ and $\text{keyVersion} > \text{storedVersion}$:
  - The new $EK_{\text{pk}}$ is accepted.
  - Cached session keys are invalidated.
  - If the peer was `VERIFIED`, it is automatically demoted to `LINKED` ($T_6$) with `hasKeyChanged = true` to alert the user of key changes.
- **Equivocation Defense ($C\text{-}12$)**: If an announcement arrives with $\text{keyVersion} == \text{storedVersion}$ but a different $EK_{\text{pk}}$, it is dropped as an equivocation attack. No state change is applied, preventing attackers from disabling peers.
- **Rollback Defense ($C\text{-}12$)**: Announcements with $\text{keyVersion} < \text{storedVersion}$ are dropped as stale or replayed.

---

## 5. Trust State Machine & Identity Verification

MeshWhisper strictly isolates trust state authority to the `:core` state machine ([`TrustStateMachine.kt`](file:///c:/Users/hp/Downloads/BIT%20FOR%20US/core/src/main/java/com/meshwhisper/core/identity/TrustStateMachine.kt)), removing legacy mutable verification booleans.

### 5.1. Runtime Trust Transitions ($T_1$–$T_{11}$)
- **$T_1$ (`(none) -> SEEN`)**: First discovery of an identity via authenticated announce.
- **$T_2$ (`SEEN -> VERIFIED`)**: User scans peer's QR code out-of-band via CameraX.
- **$T_3$ (`LINKED -> VERIFIED`)**: User scans connected peer's QR code out-of-band via CameraX.
- **$T_4$ (`SEEN / IMPORTED -> LINKED`)**: Mutual `LINK_AUTH` completed on active physical link.
- **$T_5$ (`LINKED -> SEEN`)**: Physical connection closed (for non-verified peers).
- **$T_6$ (`VERIFIED -> LINKED`)**: Accepted EK key rotation demotes trust and flags `hasKeyChanged = true`.
- **$T_7$ (`(any) -> CONFLICTED`)**: Collision detected — two distinct identity hashes share the same `nodeId64`. Unicast routing to this node ID is immediately suspended.
- **$T_8$ (`CONFLICTED -> VERIFIED`)**: Camera QR collision resolution — the scanned peer transitions to `VERIFIED`; the colliding impostor transitions to `BLOCKED`.
- **$T_9$ (`(any) -> BLOCKED`)**: Explicit user block; drops all packets from peer.
- **$T_{10}$ (`BLOCKED -> SEEN`)**: Explicit user unblock; restores basic discovery.
- **$T_{11}$ (`LEGACY_UNVERIFIED -> SEEN`)**: First authenticated vNext announcement from a legacy pre-vNext contact.

### 5.2. Migration Transition ($T_{12}$)
- Executed exclusively during Room/SQLite schema migrations:
  - Pre-vNext unverified contacts migrate to `LEGACY_UNVERIFIED`.
  - Pre-vNext verified contacts migrate to `IMPORTED` (receives a +1 routing cost penalty until re-verified via vNext QR scan).

---

## 6. Storage Security & Fail-Closed Vaults

### 6.1. Android Storage Security
- **Database Encryption**: Room v13 backed by SQLCipher AES-256-CBC.
- **Hardware Protection**: Database passphrase and master seed are wrapped using AES-256-GCM with a hardware-backed key inside AndroidKeyStore (TEE / StrongBox).
- **Panic Wipe (`P8PanicWipeTest`)**: Securely deletes SQLite database, `-wal`, and `-shm` files, erases the KeyStore alias, and clears in-memory state.
- **Database Migrations**: Verified migrations `MIGRATION_11_12` (Room v12) and `MIGRATION_12_13` (Room v13 breadcrumbs schema).

### 6.2. Desktop Storage Security (`DesktopPassphraseKeyStorage`)
- **Key Vault**: `identity.vault` encrypted with AES-256-GCM.
- **Key Derivation**: PBKDF2-HMAC-SHA256 with 100,000 iterations and a random 16-byte salt.
- **Fail-Closed Guarantee**: If `identity.vault` exists but cannot be decrypted (wrong passphrase or corrupted ciphertext), startup throws `SecurityException` and terminates. Replacement keys are never silently generated, and corrupt vaults are never overwritten.

### 6.3. Media At-Rest Encryption (`DesktopMediaManager` & `MediaAtRestManager`)
- **Format**: Magic header `MWMEDIA1` (8 bytes) + IV (12 bytes) + Ciphertext + Auth Tag (16 bytes).
- **Key Derivation**: Per-file encryption key derived via HKDF-SHA256:
  $$K_{\text{file}} = \text{HKDF-SHA256}(\text{ikm} = \text{masterMediaKey}, \text{salt} = \text{fileId}, \text{info} = \text{"MW/FILE/v2"}, 32)$$
- **Tamper Detection**: Altering any byte of the encrypted file causes AES-GCM tag validation failure; read operations fail closed and return zero plaintext.

---

## 7. Emergency Location Beacon & Geolocation Privacy

MeshWhisper's breadcrumb and rescue beacon subsystems are engineered with zero-trust location privacy:

### 7.1. Zero Cleartext Geolocation Over-The-Air ($C\text{-}18$)
- Coordinates, altitude, speed, bearing, and emergency distress notes are **never** broadcast in plaintext over RF airwaves.
- Breadcrumbs are transmitted exclusively as pairwise E2EE sub-payloads inside `DIRECT_MESSAGE` (AES-256-GCM under HKDF peer session keys and signed with Ed25519).
- Relays forward frames without cryptographic capability to read or modify location coordinates.

### 7.2. Anti-Stalking Per-Contact Opt-In & Revocation ($C\text{-}19$)
- Verified trust state (`trustState == 'VERIFIED'`) is a mandatory prerequisite, but **never grants automatic location access**.
- Every peer record maintains `shareLocationWithContact` defaulting to `0` (false). Location sharing must be explicitly toggled on per contact.
- **Immediate Revocation**: Disabling sharing dispatches an encrypted `BreadcrumbTriggerType.REVOKE` frame that immediately purges stored coordinates and breadcrumb history on the receiver's device.

### 7.3. Dying Gasp Battery Hysteresis & Shutdown Protection ($C\text{-}20$)
- Level-crossing state transitions at 15%, 10%, and 5% battery with hysteresis prevents oscillation storms.
- When `isCharging == true`, low-battery dying gasp alarms are strictly suppressed.
- **Cold GPS Shutdown Race Elimination**: Polling fresh GPS satellite fixes at $\le 5\%$ battery draws 50–100mA and risks premature Android OS shutdown before transmission. At $\le 5\%$, the system **strictly reuses the cached last GPS fix (0ms delay)**, queuing the dying gasp frame immediately at `TrafficPriority.EMERGENCY`.

### 7.4. Replay & Reinstall Ordering Guarantees ($C\text{-}21$)
- Packets are conditionally committed via SQLite:
  $$\text{WHERE nodeId} = :nodeId \text{ AND } (:fixTimestamp > timestamp \text{ OR } (:fixTimestamp = timestamp \text{ AND } :sequenceNumber > sequenceNumber))$$
- Replayed or delayed store-and-forward packets are discarded. If a sender resets device data or reinstalls the app (resetting sequence to 1), newer hardware GPS satellite timestamps naturally supersede older stored records.

---

## 8. Security Claims to Evidence Mapping Table

Every security guarantee made by BIT FOR US / MeshWhisper is backed by automated test suites and architectural enforcement:

| Security Claim | Architectural Enforcement Location | Test ID / Evidence | Status |
| :--- | :--- | :--- | :---: |
| **No Pre-Auth Dedup Cache Poisoning ($C\text{-}05$)** | [`PacketPipeline.kt`](file:///c:/Users/hp/Downloads/BIT%20FOR%20US/core/src/main/java/com/meshwhisper/core/protocol/PacketPipeline.kt) (S3 read-only, S7 commit) | `T-RES-05`, `PacketPipelineTest` | **VERIFIED ✅** |
| **Node ID Anti-Spoofing ($C\text{-}02$)** | [`PacketPipeline.kt`](file:///c:/Users/hp/Downloads/BIT%20FOR%20US/core/src/main/java/com/meshwhisper/core/protocol/PacketPipeline.kt) (S4 derivation check) | `T-RES-02`, `PacketPipelineTest` | **VERIFIED ✅** |
| **Single Hop Signature Placement ($C\text{-}01$)** | [`MeshPacket.kt`](file:///c:/Users/hp/Downloads/BIT%20FOR%20US/core/src/main/java/com/meshwhisper/core/protocol/MeshPacket.kt) (Trailing 64B of payload) | `T-ROUTE-01`, `DirectMessagePacketBuilderTest` | **VERIFIED ✅** |
| **SIGMA Identity-Misbinding Defense ($C\text{-}09$)** | [`LinkAuth.kt`](file:///c:/Users/hp/Downloads/BIT%20FOR%20US/core/src/main/java/com/meshwhisper/core/transport/LinkAuth.kt) (Both ID hashes in CONFIRM) | `P9-LINK-01`, `LinkAuthTest` | **VERIFIED ✅** |
| **CPU Verification Budget Limit ($C\text{-}16$)** | [`PacketPipeline.kt`](file:///c:/Users/hp/Downloads/BIT%20FOR%20US/core/src/main/java/com/meshwhisper/core/protocol/PacketPipeline.kt) (32 sigs/sec/link cap) | `T-RES-07`, `PacketPipelineTest` | **VERIFIED ✅** |
| **Equivocation & Rollback Defense ($C\text{-}12$)** | [`IdentityManager.kt`](file:///c:/Users/hp/Downloads/BIT%20FOR%20US/core/src/main/java/com/meshwhisper/core/identity/IdentityManager.kt) | `P9-ID-04`, `P9-ID-05` | **VERIFIED ✅** |
| **NodeId Collision Fail-Closed ($T_7$)** | [`TrustStateMachine.kt`](file:///c:/Users/hp/Downloads/BIT%20FOR%20US/core/src/main/java/com/meshwhisper/core/identity/TrustStateMachine.kt), `getUniqueIdentityByNodeId` | `P9-COLLISION-01`, `T-TRUST-07` | **VERIFIED ✅** |
| **Camera QR Collision Winner/Loser ($T_8$)** | [`TrustStateMachine.kt`](file:///c:/Users/hp/Downloads/BIT%20FOR%20US/core/src/main/java/com/meshwhisper/core/identity/TrustStateMachine.kt), `P8CameraQrScanner` | `P8CameraQrScanTest` | **VERIFIED ✅** |
| **Shared Direct Message Construction** | [`DirectMessagePacketBuilder.kt`](file:///c:/Users/hp/Downloads/BIT%20FOR%20US/core/src/main/java/com/meshwhisper/core/protocol/DirectMessagePacketBuilder.kt) (Core authority) | `P9-DM-01` | **VERIFIED ✅** |
| **Desktop Passphrase Vault Fail-Closed** | [`DesktopPassphraseKeyStorage.kt`](file:///c:/Users/hp/Downloads/BIT%20FOR%20US/desktop/src/main/java/com/meshwhisper/desktop/crypto/DesktopPassphraseKeyStorage.kt) | `P9-ID-01`, `P9-ID-02` | **VERIFIED ✅** |
| **Media At-Rest Encryption Parity** | [`DesktopMediaManager.kt`](file:///c:/Users/hp/Downloads/BIT%20FOR%20US/desktop/src/main/java/com/meshwhisper/desktop/media/DesktopMediaManager.kt), `MediaAtRestManager.kt` | `P9-MEDIA-01` | **VERIFIED ✅** |
| **Real OS Socket Wire LINK_AUTH & DM** | [`DesktopWifiEngine.kt`](file:///c:/Users/hp/Downloads/BIT%20FOR%20US/desktop/src/main/java/com/meshwhisper/desktop/wifi/DesktopWifiEngine.kt) (TCP :42426) | `P9-NET-01` (`testP9RealNetworkSocketTransportFlow`) | **VERIFIED ✅** |
| **Station Restart Identity Continuity** | [`DesktopMeshRouter.kt`](file:///c:/Users/hp/Downloads/BIT%20FOR%20US/desktop/src/main/java/com/meshwhisper/desktop/router/DesktopMeshRouter.kt), SQLite reload | `P9-INTEROP-04` | **VERIFIED ✅** |
| **T-ARCH-01 Desktop Architectural Purity** | Zero Android SDK imports in `:desktop` and `:core` | `P9-A01`, `P9-A02`, `P8ArchitectureRulesTest` | **VERIFIED ✅** |
| **Database Migration Integrity** | Room v12 migration (`MIGRATION_11_12`) & v13 (`MIGRATION_12_13`) | `P8MigrationTest`, `DatabaseMigrationP7Test` | **VERIFIED ✅** |
| **Zero-Trace Panic Wipe** | Secure file overwrite + KeyStore alias deletion | `P8PanicWipeTest` | **VERIFIED ✅** |
| **Zero Cleartext Geolocation OTA ($C\text{-}18$)** | `DIRECT_MESSAGE` E2EE sub-payload, non-UTF8 prefix `[0xFF, 'B', 'C']` | `LocationBreadcrumbPayloadTest` | **VERIFIED ✅** |
| **Anti-Stalking Opt-in & Revocation ($C\text{-}19$)** | `shareLocationWithContact` gating + `REVOKE` trail purge | `LocationBreadcrumbIngressTest` | **VERIFIED ✅** |
| **Dying Gasp Hysteresis & Cached Fix ($C\text{-}20$)** | `LocationBreadcrumbManager` 0ms fix fallback at $\le 5\%$, charging guard | `LocationBreadcrumbIngressTest` | **VERIFIED ✅** |
| **Reinstall vs Replay Ordering ($C\text{-}21$)** | SQLite conditional update `(fixTimestamp, sequenceNumber)` | `LocationBreadcrumbIngressTest` | **VERIFIED ✅** |
