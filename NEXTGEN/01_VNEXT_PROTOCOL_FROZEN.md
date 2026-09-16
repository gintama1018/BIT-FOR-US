# BIT FOR US / MeshWhisper — Secure Protocol vNext
# FILE 1 of 3: FROZEN PROTOCOL SPECIFICATION

**Status:** FROZEN. Normative.
**Applies to:** repository `BIT-FOR-US-main`, modules `:core`, `:app`, `:desktop`.
**Supersedes:** `docs/PROTOCOL.md`, `docs/SECURITY.md` (both are to be rewritten from this file in Phase 10).
**Companion files:** `02_VNEXT_IMPLEMENTATION_PLAN.md`, `03_VNEXT_TESTS_AND_AGENT_RULES.md`.

Keywords **MUST**, **MUST NOT**, **SHALL**, **MAY** are normative.
Any value in this file marked **[WIRE]** affects interoperability and **MUST NOT** be changed without a version bump.

This file ends with **SECTION A — FINAL SECURITY CONTRACT**.

---

# 1. FINAL CONSISTENCY REVIEW

Every item below is a contradiction, gap or ambiguity found in the vNext design during this pass. Each is resolved here. There are no TBDs.

---

### C-01 — Signature placement: inside the AEAD (announce) vs outside (everything else)

**Contradiction.** The announce layout placed `announceSignature` as field 15 *inside* the AEAD plaintext. The general rule placed the hop signature *outside* the AEAD, appended to the payload.

**Resolution.** The hop signature is **always** the trailing 64 bytes of `payload`, outside the AEAD, for every signed type including PEER_ANNOUNCE.

**Final rule.** `payload = ciphertext ‖ hopSignature(64)` for all signed types. `payload = ciphertext` for unsigned (ttl=1) types. The announce's field 15 is deleted; the announce plaintext ends at the location block.

**Why authoritative.** A confidential team-channel BROADCAST_MESSAGE cannot be decrypted by relays, so a signature inside the AEAD would be unverifiable at exactly the nodes that must verify it before forwarding. One placement rule means one parser, one verifier, one test suite. Two placements means two authentication paths, which is how F-0 happened.

---

### C-02 — nodeId64 mismatch: "DROP" vs "discard the claim and continue"

**Contradiction.** The validation sequence said DROP on `BE_u64(identityHash[0..8]) != header.senderId`; the identity section said the receiver "discards the header's claimed nodeId64".

**Resolution.** DROP.

**Final rule.** If the nodeId64 recomputed from the transmitted `IK_pk` does not equal `header.senderId`, the packet is dropped. No field of it is used.

**Why authoritative.** Continuing with a corrected value means processing a packet whose header lied. Fail closed (I-1). There is no legitimate sender that produces this mismatch.

---

### C-03 — Location in PEER_ANNOUNCE: "only when ttl == 1" vs the check `ttl != MAX_TTL_FOR_ANNOUNCE → DROP`

**Contradiction.** Self-contradictory text; also implied either a flooded position beacon (a tracking device) or two announce packet types.

**Resolution.** Location is permitted only on ttl = 1 announces, delivered by **unicast over an authenticated link**. No new packet type.

**Final rule.** A node emits two kinds of PEER_ANNOUNCE from one beacon loop:

| Variant | ttl | Transport | `flags.hasLocation` | Cadence |
|---|---|---|---|---|
| **FLOOD** | 7 | broadcast, relayed | **MUST be 0** | 4 s with peers / 12 s idle |
| **NEIGHBOR** | 1 | unicast to each `LINKED` peer, never relayed | MAY be 1 | 30 s, only if the user enabled location sharing |

Receiver rule: `flags.hasLocation == 1` **requires** `ttl == 1` **and** arrival on an authenticated link whose bound `identityHash` equals the announce's. Otherwise DROP.

**Why authoritative.** A signed, precise, flooded position every 4 s is a tracking beacon; signing it does not fix it. Unicast-to-neighbours preserves the offline map and homing features for the range where they are useful, and shrinks the flooded beacon by 28 bytes, which matters because the beacon is the dominant control-plane cost.

---

### C-04 — `announceCounter` under unicast fan-out

**Ambiguity.** Whether the counter increments per emission or per recipient.

**Resolution.** The counter increments once per **constructed announce**. Identical bytes replicated to N peers carry one counter value and one `messageId`.

**Final rule.** `announceCounter` is a per-identity u64 incremented once each time an announce payload is built. FLOOD and NEIGHBOR announces share the counter space. A receiver that sees a lower counter after a higher one drops the lower one (out-of-order arrival of a stale announce is not an error, it is a stale packet).

---

### C-05 — Pre-auth dedup poisoning

**Security gap.** The pipeline checked the RAM dedup LRU at S3 *and* inserted at S3. An attacker who observes a `messageId` in flight could send a malformed packet carrying that `messageId`, poisoning the cache so the genuine packet is dropped as a duplicate.

**Resolution.** S3 **reads only**. Insertion happens exactly once, at `AuthenticatedPacket` construction, atomically with the persistent `INSERT OR IGNORE`.

**Final rule.** The persistent `processed_packets` row is the single source of truth for dedup. The RAM LRU is a read-through cache populated **only** from successful commits. Nothing unauthenticated ever enters either structure.

**Why authoritative.** It preserves the existing atomic dedup (a DO-NOT-TOUCH mechanism), closes the poisoning gap, and keeps I-10 (no pre-auth persistence) intact.

---

### C-06 — `purposeTag` proliferation and the PROFILE_UPDATE second transcript

**Ambiguity + second authentication path.** Six purpose tags were listed, and PROFILE_UPDATE was specified as "MWP1 canonical bytes extended with the header transcript" — a different transcript shape from every other packet.

**Resolution.** Three purpose tags only. All packet-carried hop signatures use `0x02`.

**Final rule.**

| Tag | Value | Used by |
|---|---|---|
| `CONTENT` | `0x02` | **every** packet hop signature, all types, no exceptions |
| `IBC` | `0x03` | identity binding certificate only |
| `LINK` | `0x04` | LINK_AUTH CONFIRM only |

`packetType` is already inside the transcript, so per-type tags are redundant. PROFILE_UPDATE uses the standard hop signature transcript like everything else.

---

### C-07 — ProfilePayload still carries `signingPublicKey` and `signature`

**Accidental security gap.** These two fields are exactly the payload-supplied trust anchor that caused finding S-1. Leaving them in the wire format leaves the vector reachable even if the current call site is fixed.

**Resolution.** Delete both fields from the wire format. The packet hop signature is the only authenticator.

**Final rule.** `ProfilePayload` v2 = `nodeId ‖ version ‖ displayName ‖ bio ‖ avatarHash`. `signingPublicKey`, `signature`, `computeCanonicalBytes()`, `verifySignature()` and `verifySignature(expected)` are removed from `core/protocol/ProfilePayload.kt`. Identity binding is `payload.nodeId == header.senderId`, which is itself bound to `identityHash` by the hop signature.

**Trade-off, accepted.** A node can no longer re-serve a third party's profile on their behalf. Verified against the code: `handleProfileRequest` (`MeshRouter.kt:1743`) only ever returns the node's **own** profile, so nothing that exists today is lost.

**Why authoritative.** The strongest fix for a misused field is deleting the field. `profiles.signature` remains as a DB column for migration compatibility and is never read or written again.

---

### C-08 — `authTag != 0x00*16` structural check vs LINK_AUTH

**Contradiction.** S1 rejects an all-zero auth tag. LINK_AUTH has no key yet and therefore no AEAD.

**Resolution.** LINK_AUTH is the sole exemption.

**Final rule.** `authTag` **MUST** be non-zero for every type except `LINK_AUTH (0x31)`, where it **MUST** be exactly 16 zero bytes. This is a whitelist of one, hardcoded in S1; it is not a flag, not a capability, not extensible.

---

### C-09 — LINK_AUTH transcript vs the standard packet transcript

**Impossible requirement.** The standard transcript contains `senderIdentityHash`, which by definition is not yet resolved during a handshake.

**Resolution.** LINK_AUTH CONFIRM uses its own transcript (purpose tag `0x04`) over the handshake transcript `T`. This is the **only** transcript exception in the protocol, and it exists because the handshake is what *produces* a resolved identity.

**Final rule.** `CONFIRM_sig = Ed25519_IK( "MW/SIG/v2" ‖ 0x00 ‖ 0x04 ‖ T ‖ identityHash_self ‖ identityHash_peer )` where `T = SHA-256("MW/TCP/v2" ‖ 0x00 ‖ HELLO_lo ‖ HELLO_hi)`, `lo`/`hi` ordered by lexicographic comparison of the two `identityHash` values.

Both `identityHash` values are inside the signed statement. This is the SIGMA identity-misbinding defence and it **MUST NOT** be simplified away.

---

### C-10 — Is LINK_AUTH a MeshPacket or a raw stream message?

**Ambiguity.** The handshake was described as HELLO/CONFIRM messages without a container.

**Resolution.** LINK_AUTH is a normal MeshPacket on both transports.

**Final rule.** One wire format, one parser, both transports. Over TCP it is sent as a length-prefixed frame in the clear before `K_link` exists; every frame after a successful CONFIRM is `K_link`-encrypted. Over BLE it travels through the existing `BleFrameFramer` unchanged. Before handshake completion, a link **MUST** discard every packet whose type is not `LINK_AUTH`.

---

### C-11 — `notBefore` semantics undefined

**Ambiguity.** The field was in the IBC transcript with no stated validation.

**Resolution.** `notBefore` is an ordering aid, not an expiry mechanism. There is no `notAfter`.

**Final rule.** Accept if `notBefore <= packet.timestamp + 120`. Reject otherwise. Certificates never expire.

**Why authoritative.** An offline mesh has no time source and no revocation channel. Clock-based expiry converts a clock problem into a total communication failure at the worst possible moment. Rotation ordering is provided by `keyVersion`, which needs no clock.

---

### C-12 — Key rotation vs `isVerified` vs `CONFLICTED`

**Ambiguity.** Unclear whether rotation produces a state change, a warning, or a conflict; and whether equivocation is a state.

**Resolution.** Three distinct events with three distinct outcomes.

| Event | Detection | Outcome |
|---|---|---|
| **EK rotation** | valid IBC, `keyVersion > stored` | Accept. Replace `ekPub`, bump stored `keyVersion`, invalidate cached session keys for this identity, set `hasKeyChanged = true`, demote `VERIFIED → LINKED`. |
| **Rollback** | valid IBC, `keyVersion < stored` | DROP the packet. No state change. |
| **Equivocation** | valid IBC, `keyVersion == stored`, `ekPub != stored` | DROP the packet. Raise a security counter and a UI warning. **No state change and no `CONFLICTED` transition.** |

**Why the equivocation rule is authoritative.** Only the real holder of `IK_sk` can produce that signature. It is provable misbehaviour or a buggy peer, but it is not evidence that our stored binding is wrong — so we keep the binding we have and refuse the new one. Transitioning to `CONFLICTED` here would let a compromised peer disable itself for everyone, which is a worse outcome than a warning.

**IK rotation does not exist.** A new `IK` is a new identity with a new `identityHash` and a new `nodeId64`, and is treated as a stranger. There is no identity-rotation protocol and none is to be added.

---

### C-13 — Voice key lifetime across an epoch boundary

**Security gap.** `K_call` derives from `sessionKey_epoch`. A call crossing an hour boundary would silently re-derive a different key on each side at a different moment — and, worse, restarting the sequence counter under a *new* key is safe but restarting it under the *same* key is catastrophic. The design did not say which.

**Resolution.** `K_call` is pinned at call setup and never re-derived.

**Final rule.** `K_call = HKDF(sessionKey(epoch_of_OFFER), salt = HKDF_DM_SALT, info = "MW/VOICE/v2" ‖ callSessionId(16), 32)` where `epoch_of_OFFER = OFFER_packet.timestamp / 3600`. Both sides read that epoch from the OFFER packet header, which is inside the AAD, so they cannot disagree. The value is stored with the call and used until termination.

Nonce reuse is impossible because: `callSessionId` is 128 bits of CSPRNG and is rejected if it appears in the 64-entry recent-call LRU; the direction byte separates the two endpoints; and the sequence is strictly increasing per direction per call. See §6.7 for the full argument.

---

### C-14 — Broadcast MEDIA_CHUNK has no authenticator at all

**Accidental gap.** The chunk exemption was justified for directed transfers (recipient AEAD is the backstop). For broadcast transfers the key is the public channel key, so any nearby device can inject a chunk and corrupt the transfer.

**Resolution.** Accepted as a bounded, documented residual risk, with two mitigations.

**Final rule.**
1. **Write-once per index.** Within a session, the first value received for a `chunkIndex` is final. Later values for that index are discarded. An attacker can only *race* the legitimate sender, not overwrite a clean transfer.
2. **No NACK on broadcast SHA-256 mismatch.** Discard silently. At most 2 re-attempts of the same `mediaId` from the same `senderId64` are admitted per hour.
3. **Directed transfers are unaffected** — the session-key AEAD makes forged chunks unacceptable there.

**Residual risk, stated for the record.** A nearby attacker can cause broadcast media transfers to fail. They cannot cause forged media to be *accepted* (SHA-256 over the reassembled bytes, committed in a signed MEDIA_INIT, is the backstop). Broadcast media is a convenience feature; degrading it to "sometimes fails under attack" is acceptable. Adding per-chunk signatures to fix it would cost a 2× BLE frame count on the highest-volume packet type.

---

### C-15 — Relaying requires prior knowledge of the origin identity

**Underspecified consequence.** Hop signature verification requires the origin's `IK_pk`. A node that has never seen an announce from an origin cannot verify, and therefore cannot relay, that origin's packets.

**Resolution.** This is correct behaviour and is now normative.

**Final rule.** A node relays a packet only for an origin identity it has authenticated at least once. PEER_ANNOUNCE is the bootstrap: it is self-contained (carries `IK_pk` + IBC), so discovery floods before anything else can.

**Consequence, accepted.** In a freshly-formed mesh, the first DM to a distant node may be undeliverable until announces have propagated. Mitigation: the S&F queue holds it; announces flood every 4 s; convergence is seconds.

---

### C-16 — Verification budget vs BLE frame rate

**Apparent conflict.** 50 frames/s per link vs 32 Ed25519 verifications/s per link.

**Resolution.** No conflict; the numbers are consistent by construction.

**Final rule.** Frames are fragments. A signed control packet at MTU 512 occupies 1–2 frames, so 50 frames/s caps signed packets at ≈25/s, below the 32/s verification budget. The budget binds only when a peer sends abnormally small signed packets at maximum frame rate, which is precisely the abuse case it exists to cap.

---

### C-17 — AEAD filter ordering breaks for confidential-channel broadcasts

**Gap.** "Cheap AEAD tag first, expensive signature second" assumes the relay holds the AEAD key. A relay outside a confidential team channel does not.

**Resolution.** Ordering is per-role, not global.

**Final rule.**
- **Packet addressed to me, or broadcast on a channel I hold the key for:** AEAD first (S5), then signature (S6).
- **Packet I am only relaying, on a channel I cannot decrypt:** S5 is skipped; go straight to the verification budget and S6.

The verification budget (§8) is what protects the relay path. This is why the budget is mandatory and not an optimisation.

---

### C-18 — `hopCount` and the direct-link heuristic both trust TTL

**Gap.** `MeshRouter.kt:243` binds address→nodeId when `ttl == DEFAULT_TTL`; `:568` stores `hopCount = DEFAULT_TTL - ttl` on messages. TTL is unauthenticated and now clamped, so both are unsound.

**Resolution.**
- The address→nodeId heuristic is **deleted**. Link binding comes only from LINK_AUTH (§7).
- `MessageEntity.hopCount` is retained as a column but is written as `-1` (unknown) and is **not displayed as a trust or distance signal** in the UI.

---

### C-19 — `MeshTrafficController`: wire it or delete it?

**Unresolved decision.** It is instantiated at `MeshRouter.kt:52`, never enqueued into, never polled, but documented as `SHIPPED`.

**Resolution.** Wire it into the egress path.

**Why authoritative.** §8 requires a bounded per-identity relay budget, which needs a bounded egress queue. The controller already is one, already has anti-starvation scheduling, and already has passing tests. Deleting it would mean building the same thing again under a different name. SOS priority is also a product requirement that only a scheduler can deliver.

---

### C-20 — Removing `fallbackToDestructiveMigration()` can hard-crash on an unknown DB version

**Gap.** Removing it is required (silent data destruction during a protocol migration is unacceptable); leaving it is also unacceptable.

**Resolution.** Remove it, and handle the failure explicitly.

**Final rule.** `MeshDatabase.getInstance` wraps the open in a try/catch. On a migration failure the app shows a blocking error screen offering exactly two choices: **Export diagnostics** and **Erase and start fresh** (an explicit, confirmed, user-initiated wipe). The database is never destroyed without a user action.

---

### C-21 — What happens to v1 store-and-forward bytes at migration?

**Gap.** Truncating `store_forward_queue` silently discards the user's undelivered messages.

**Resolution.** Truncate the blobs; make the loss visible.

**Final rule.** Migration 11→12 drops all `store_forward_queue` rows and sets the corresponding `messages` rows to `MessageStatus.EXPIRED` (new constant). The UI shows "not delivered before the security upgrade". Nothing disappears silently.

`MessageStatus` gains `EXPIRED` and `CUSTODY_HELD`, **appended to the end of the enum**. Room 2.6 persists enums by constant **name**, so appending is safe; reordering or renaming existing constants is **forbidden**.

---

### C-22 — BLE rate-limiter keyed by MAC address

**Gap.** BLE resolvable private addresses rotate, so a MAC-keyed budget is resettable by the attacker and the map grows without bound.

**Resolution.** Key by link handle.

**Final rule.** `GattWriteRateLimiter` is keyed by the connection/link handle, cleared on disconnect in **both** roles (the client-role disconnect currently leaks). A MAC string is never an identity and never a budget key.

---

### C-23 — Two identities with the same `nodeId64`

**Underspecified.** Both pass the nodeId64 check legitimately.

**Final rule.** `identities` is keyed by `identityHash`; `nodeId64` is a **non-unique index**. On collision:
- both identities → `CONFLICTED`;
- unicast E2E traffic to that `nodeId64` is **suspended** (we do not guess which key to encrypt to);
- the label is removed from the routing table as a destination and as a relay;
- existing S&F entries for that `nodeId64` are **held, not deleted**, until expiry or resolution;
- broadcast/SOS from both identities continues to work (verified per-signature, not per-label);
- resolution requires an in-app QR scan of one of them, which promotes that one to `VERIFIED` and demotes the other to `BLOCKED`.

---

### C-24 — Clock rollback on the local device

**Gap.** A regressed clock makes our own outgoing timestamps decrease, so peers reject our packets as future/stale, and the announce counter ordering could look inconsistent.

**Final rule.** Persist `lastEmittedTimestamp`. Every outgoing packet uses `timestamp = max(wallClockSeconds, lastEmittedTimestamp + 1)`, then stores it. Our emitted timestamps are monotonic regardless of the system clock. `announceCounter` is independent of the clock and is persisted the same way.

---

### C-25 — Freshness window arithmetic was written ambiguously

**Final rule.** `age = now - packet.timestamp`. Accept iff `-FUTURE_SKEW <= age <= PAST_WINDOW[type]`. `FUTURE_SKEW = 120` seconds globally. `PAST_WINDOW` is per type (§2.10).

---

### C-26 — Panic-wipe ordering must be monotonic under interruption

**Gap.** The design said "keys first" without an ordered list, and the current implementation runs in `viewModelScope` (cancellable).

**Final rule.** `NonCancellable`, application-scoped, in this exact order. Every prefix of this sequence leaves the device in a safer state than the previous step:

```
1. notificationManager.cancelAll()            // fastest visible leak, needs no keys
2. delete Keystore alias  MeshWhisperDbMasterKey
3. delete Keystore alias  MeshWhisperIdentityMasterKey
4. delete Keystore alias  MeshWhisperMediaMasterKey
5. close DB; delete meshwhisper_encrypted_db{,-wal,-shm,-journal}
6. deleteRecursively filesDir/media, filesDir/avatars
7. clear ALL SharedPreferences files in shared_prefs/ (commit)
8. Process.killProcess(myPid())
```

After step 2 the database is permanently undecryptable even if steps 5+ never run. That is the property that makes interruption safe.

---

### C-27 — "Encrypted database ≠ encrypted media" needs a concrete mechanism

**Final rule.** A third Keystore alias `MeshWhisperMediaMasterKey` wraps a 32-byte file key. Every file written to `filesDir/media` and `filesDir/avatars` is AES-256-GCM encrypted under `HKDF(fileKey, salt=fileId, info="MW/FILE/v2")`, with a 12-byte random IV prefix. Decryption happens on read into memory for display. No plaintext media is ever written to disk, cache, or external storage.

---

### C-28 — AVATAR_REQUEST / PROFILE_REQUEST demoted to ttl=1

**Consequence, stated.** Avatars and profiles can only be fetched from direct neighbours. Multi-hop avatar fetch is removed.

**Why.** Both are unauthenticated amplifiers today (finding S-15): one attacker announce causes a victim broadcast. At ttl=1 over an authenticated link, the amplification vector is gone and the request is attributable.

---

# 2. FINAL FROZEN PROTOCOL RULES

## 2.1 Identity **[WIRE]**

```
S            32 B   master seed. == the device's existing X25519 private key.
IK_sk        32 B   HKDF(S, salt=HKDF_DM_SALT, info="MESHWHISPER_ED25519_SIGNING_KEY_V1", 32)
IK_pk        32 B   Ed25519 public key of IK_sk.          ← THE IDENTITY
EK_sk        32 B   == S
EK_pk        32 B   X25519 public key of EK_sk.           ← encryption key, rotatable
```

`IK_sk` derivation reuses the **existing, unchanged** `PureCryptoEngine.deriveSigningPrivateKey()`. Do not change its salt or info string: doing so would change every existing device's identity.

## 2.2 identityHash **[WIRE]**

```
identityHash = SHA-256( "MW/NODE/v2" ‖ 0x00 ‖ IK_pk )        32 B
```

`"MW/NODE/v2"` is 10 ASCII bytes. `0x00` is a single terminator byte. Total preimage 43 bytes.
`identityHash` is the canonical identity everywhere: database primary key, signature transcripts, safety numbers, custody receipts, topology edges, link bindings.

## 2.3 nodeId64 **[WIRE]**

```
nodeId64 = big-endian u64 of identityHash[0..8]
```

**nodeId64 is a routing label. It carries no trust.** It may appear in headers, routing tables and UI. It **MUST NOT** be used as a database primary key for identity, and **MUST NOT** be the sole input to any trust decision.

## 2.4 Identity Binding Certificate (IBC) **[WIRE]**

```
IBC_transcript = "MW/SIG/v2" ‖ 0x00 ‖ 0x03 ‖ IK_pk ‖ EK_pk ‖ u32 keyVersion ‖ u32 notBefore
                   9 B        1 B    1 B    32 B     32 B      4 B              4 B     = 83 B
ibcSignature   = Ed25519_IK( IBC_transcript )                                            64 B
```

Validation: `notBefore <= packet.timestamp + 120`. No expiry. Cache keyed `(identityHash, keyVersion)`; a cached entry means the signature is not re-verified.

## 2.5 keyVersion **[WIRE]**

u32, starts at 1, strictly increasing, incremented only on EK rotation. Semantics: C-12.

## 2.6 announceCounter **[WIRE]**

u64, starts at 1, incremented once per constructed announce. Persisted. Monotonicity rules: §7.

## 2.7 Trust states

`SEEN`, `LINKED`, `IMPORTED`, `VERIFIED`, `CONFLICTED`, `BLOCKED`, `LEGACY_UNVERIFIED`. Full state machine: §5.

## 2.8 Signature transcript **[WIRE]**

```
SIG_TRANSCRIPT (115 bytes, big-endian throughout)
  offset  size  field
       0     9  "MW/SIG/v2"                     ASCII, no terminator inside
       9     1  0x00                            separator
      10     1  purposeTag                      0x02 CONTENT | 0x03 IBC | 0x04 LINK
      11     1  protocolVersion                 u8, value 1 for vNext
      12     1  packetType                      the full wire type byte, e.g. 0x24
      13    16  messageId                       UUID, msb then lsb
      29    32  senderIdentityHash
      61     8  senderNodeId64
      69     8  recipientNodeId64
      77     4  timestamp                       u32 seconds
      81     2  payloadLenExcludingSig          u16 = payloadLen - 64
      83    32  SHA-256( ciphertext ‖ authTag )
     115        end
hopSignature = Ed25519_IK( SIG_TRANSCRIPT )     64 B
```

Construction order: encrypt → `SHA-256(ciphertext ‖ authTag)` → build transcript → sign → assemble.
Excluded from the transcript, deliberately: **`ttl`** (mutated at every hop; see §2.11) and the payload plaintext (the ciphertext hash is equivalent under AEAD and lets key-less relays verify).

## 2.9 Protocol version **[WIRE]**

```
typeByte = (protocolVersion << 5) | typeCode
protocolVersion 0 = v1 legacy   (type bytes 0x00–0x1F)
protocolVersion 1 = vNext       (type bytes 0x20–0x3F)
```

A vNext node **MUST** reject `protocolVersion == 0` at S1 with no parse attempt. A v1 node's `PacketType.fromCode` returns null for vNext bytes. Mutual invisibility. Six versions remain.

`SIG_TRANSCRIPT.protocolVersion` is the 3-bit value zero-extended to u8.

## 2.10 Freshness windows **[WIRE-ADJACENT]**

`FUTURE_SKEW = 120 s` for all types.

| Type | PAST_WINDOW |
|---|---|
| DIRECT_MESSAGE, ACK | 86 400 s |
| PEER_ANNOUNCE, BROADCAST_MESSAGE, SOS_MESSAGE, PROFILE_UPDATE, CUSTODY_ACK | 600 s |
| MEDIA_INIT, MEDIA_CHUNK, MEDIA_NACK, MEDIA_ACK, MEDIA_ABORT | 600 s |
| LINK_AUTH | 60 s |
| PROFILE_REQUEST, AVATAR_REQUEST | 120 s |
| VOICE_CALL_SIGNAL, VOICE_FRAME, TYPING_INDICATOR | 30 s |

## 2.11 TTL handling **[WIRE]**

TTL is **never trusted**. At S1: `ttl = min(header.ttl, MAX_TTL[type])`. The clamped value is what is relayed.

| MAX_TTL | Types |
|---|---|
| 7 | BROADCAST_MESSAGE, DIRECT_MESSAGE, ACK, PEER_ANNOUNCE, SOS_MESSAGE, PROFILE_UPDATE |
| 4 | MEDIA_INIT, MEDIA_CHUNK, MEDIA_NACK, MEDIA_ACK, MEDIA_ABORT |
| 1 | AVATAR_REQUEST, PROFILE_REQUEST, TYPING_INDICATOR, VOICE_CALL_SIGNAL, VOICE_FRAME, LINK_AUTH, CUSTODY_ACK |

TTL **MUST NOT** be used for: link binding, hop-count display, trust, storage decisions, or any branch other than "relay / do not relay".

## 2.12 Replay rules

Summary; full matrix in §7.

- Every type: `(messageId, packetType)` dedup, RAM read-through + persistent `INSERT OR IGNORE`, committed only at `AuthenticatedPacket` construction.
- PEER_ANNOUNCE: additionally strictly-increasing `announceCounter` per identity.
- PROFILE_UPDATE: additionally strictly-increasing `version` per identity.
- Any IBC: `keyVersion` strictly increasing per identity.
- VOICE_FRAME: strictly-increasing `seq` per `(callSessionId, direction)`.
- VOICE_CALL_SIGNAL: strictly-increasing `signalSeq` per `callSessionId`.
- LINK_AUTH: 32-byte nonces from both sides inside `T`.

## 2.13 Packet authentication rules

1. Every type is authenticated by an **E2E authenticator** (AEAD under a pairwise or channel key), a **hop authenticator** (Ed25519 hop signature), or both. The matrix is §3.3.
2. A packet that is relayed **MUST** carry a hop signature. The sole exception is MEDIA_CHUNK, governed by transfer admission (C-14).
3. A hop signature is verified against the `IK_pk` **stored** for the resolved identity. A key inside a payload is a trust anchor **only** in PEER_ANNOUNCE, and only after `SHA-256(IK_pk)` reproduces the claimed nodeId64 and the IBC verifies.
4. Signature presence is determined by **packet type**, never by payload length. There is no "short payload skips verification" path anywhere.
5. No fallback. A failed decryption is a drop. A missing authenticator is a drop.

## 2.14 Transport authentication

No link carries mesh traffic until LINK_AUTH completes. Bindings are `linkHandle → identityHash`. Full spec: §3.2 (wire) and §4 (pipeline interaction). `registerDirectNode()` is deleted.

## 2.15 Topology trust

A node is authoritative only for links it terminates. An edge becomes routable only when **both** endpoints have independently asserted it inside a valid signed announce within `EDGE_FRESHNESS = 90 s`. Single-sided claims are `STAGED` and invisible to routing. Full rules: §2.16 table and file 2 §7.

| Constant | Value |
|---|---|
| `MAX_NEIGHBORS_PER_ANNOUNCE` | 16 |
| `EDGE_FRESHNESS_MS` | 90 000 |
| `EDGE_EVICT_MS` | 180 000 |
| `MAX_TOPOLOGY_ORIGINS` | 512 |
| edge set per origin | replaced wholesale per announce, never merged |
| `pruneStaleEntries()` cadence | every 30 000 ms, on a timer |

## 2.16 Custody semantics

| Actor | May delete its copy when |
|---|---|
| **Originator** | end-to-end ACK received, **or** 24 h expiry. **Never** on a custody receipt. **Never** on a transport write returning true. |
| **Intermediate custodian** | end-to-end ACK observed, **or** a valid `CUSTODY_ACK` from the next hop, **or** 24 h expiry. |

`attemptSend()` returning `true` means "bytes handed to a radio". It proves nothing and releases nothing.

---

# 3. EXACT WIRE SPECIFICATION

## 3.1 Common frame **[WIRE]**

All fields big-endian. The 40-byte header and 16-byte tag are **unchanged from v1**.

```
offset  size  field
     0     1  typeByte            (protocolVersion<<5)|typeCode
     1    16  messageId           UUID msb ‖ lsb
    17     8  senderNodeId64
    25     8  recipientNodeId64   -1 (0xFFFF...F) = broadcast
    33     1  ttl
    34     4  timestamp           u32 epoch seconds
    38     2  payloadLen          u16
    40     N  payload
  40+N    16  authTag             AES-GCM 128-bit tag; all-zero ONLY for LINK_AUTH

payload (signed types)    = ciphertext(N-64) ‖ hopSignature(64)
payload (unsigned ttl=1)  = ciphertext(N)
ciphertext                = iv(12) ‖ rawCiphertext         [AEAD types]
AAD                       = computeAad(type, messageId, senderId, recipientId, timestamp)  — 37 B, UNCHANGED
```

Absolute parser limits: `56 <= totalSize <= 2104`; `payloadLen <= 2048`; `totalSize == 56 + payloadLen` **exactly** (no trailing bytes).

## 3.2 Type registry **[WIRE]**

| Byte | Type | MAX_TTL | Signed | AEAD key | Relay |
|---|---|---|---|---|---|
| 0x20 | BROADCAST_MESSAGE | 7 | ✅ | active channel key | ✅ |
| 0x21 | DIRECT_MESSAGE | 7 | ✅ | epoch session key | ✅ |
| 0x22 | *retired (was KEY_EXCHANGE)* | — | — | — | **reject** |
| 0x23 | ACK | 7 | ✅ | epoch session key | ✅ |
| 0x24 | PEER_ANNOUNCE | 7 / 1 | ✅ | publicChannelKey | ✅ (flood only) |
| 0x25 | MEDIA_INIT | 4 | ✅ | session or channel key | ✅ |
| 0x26 | MEDIA_CHUNK | 4 | ❌ | session or channel key | ✅ (admission) |
| 0x27 | AVATAR_REQUEST | 1 | ❌ | epoch session key | ❌ |
| 0x28 | TYPING_INDICATOR | 1 | ❌ | epoch session key | ❌ |
| 0x29 | MEDIA_NACK | 4 | ✅ | session or channel key | ✅ |
| 0x2A | MEDIA_ACK | 4 | ✅ | epoch session key | ✅ |
| 0x2B | MEDIA_ABORT | 4 | ✅ | session or channel key | ✅ |
| 0x2C | SOS_MESSAGE | 7 | ✅ | publicChannelKey | ✅ |
| 0x2D | PROFILE_UPDATE | 7 | ✅ | publicChannelKey | ✅ |
| 0x2E | PROFILE_REQUEST | 1 | ❌ | epoch session key | ❌ |
| 0x2F | VOICE_CALL_SIGNAL | 1 | ❌ | epoch session key | ❌ |
| 0x30 | VOICE_FRAME | 1 | ❌ | K_call | ❌ |
| 0x31 | LINK_AUTH | 1 | special (C-09) | **none** | ❌ |
| 0x32 | CUSTODY_ACK | 1 | ✅ | publicChannelKey | ❌ |

## 3.3 PEER_ANNOUNCE (0x24)

**AEAD plaintext**, big-endian:

| # | Field | Size | Rule |
|---|---|---|---|
| 1 | announceVersion | 1 | `0x02` exactly |
| 2 | flags | 1 | bit0 hasLocation, bit1 hasNeighbors, bit2 hasAvatarHash, bits3–7 **MUST** be 0 |
| 3 | IK_pk | 32 | |
| 4 | EK_pk | 32 | |
| 5 | keyVersion | 4 | u32 ≥ 1 |
| 6 | notBefore | 4 | u32 seconds |
| 7 | ibcSignature | 64 | over §2.4 transcript |
| 8 | announceCounter | 8 | u64 ≥ 1 |
| 9 | aliasLen | 1 | 0–64 |
| 10 | alias | 0–64 | UTF-8, no C0/C1 control chars |
| 11 | avatarHashPrefix | 4 | first 4 B of SHA-256(avatar); `00000000` = none |
| 12 | neighborCount | 1 | 0–16; `0` iff `flags.bit1 == 0` |
| 13 | neighbors[] | 9×N | `nodeId64(8) ‖ linkQuality(1)`; entries **MUST** be sorted ascending by nodeId64 and unique |
| 14 | location | 0 or 28 | present iff `flags.bit0`; `lat f64 ‖ lon f64 ‖ accuracy f32 ‖ fixTime u64` |

- Plaintext size: **min 143** (`aliasLen=0`, 0 neighbours, no location), **max 388**.
- Ciphertext = `12 + plaintext`. payload = `ciphertext + 64`. Total wire: **min 231**, **max 520**.
- Encryption boundary: fields 1–14 inside AEAD under `publicChannelKey`, AAD = 37-byte header AAD.
- Signature boundary: `hopSignature` over `SIG_TRANSCRIPT` with `purposeTag = 0x02`, outside the AEAD.
- Replay: `(messageId, 0x24)` dedup **and** monotonic `announceCounter`.
- Parser: reject if declared sizes do not sum **exactly** to `payloadLen - 64`; reject any reserved flag bit set; reject `flags.hasLocation && ttl != 1`; reject unsorted or duplicate neighbour entries; reject a neighbour equal to the sender's own nodeId64 or to 0.

## 3.4 LINK_AUTH (0x31)

Header: `ttl = 1`, `authTag = 16 zero bytes`, `payloadLen = 1 + stagePayload`. No AEAD, no ciphertext prefix.
`recipientNodeId64` = `0` for HELLO, = peer's claimed nodeId64 for CONFIRM. `senderNodeId64` = own nodeId64 (informational until verified).

```
payload = stage(1) ‖ stagePayload

stage 0x01 HELLO    (169 B payload total)
    nonce          32
    IK_pk          32
    EK_pk          32
    keyVersion      4
    notBefore       4
    ibcSignature   64

stage 0x02 CONFIRM  (65 B payload total)
    confirmSig     64
```

- `T = SHA-256( "MW/TCP/v2" ‖ 0x00 ‖ HELLO_lo ‖ HELLO_hi )` over the **169-byte HELLO payloads** (excluding the stage byte), ordered by lexicographic comparison of the two `identityHash` values.
- `confirmSig = Ed25519_IK( "MW/SIG/v2" ‖ 0x00 ‖ 0x04 ‖ T ‖ identityHash_self ‖ identityHash_peer )`.
- `K_link = HKDF( X25519(EK_sk_self, EK_pk_peer), salt = "MW/LINK/SALT/v2", info = "link" ‖ T, 32 )`.
- Replay: both nonces are inside `T`; a replayed CONFIRM cannot match a fresh `T`.
- Parser: exact payload lengths; `stage ∈ {0x01, 0x02}`; nonce **MUST NOT** be all-zero; reject a second HELLO on a link already in CONFIRM state.

## 3.5 CUSTODY_ACK (0x32)

AEAD plaintext (48 B) under `publicChannelKey`, then a hop signature:

```
custodyMessageId   16   the messageId whose custody is being accepted
originIdentityHash 32   identity of the ORIGINATOR of that message
```

Total wire: `40 + (12 + 48) + 64 + 16 = 180` bytes, fixed.
`recipientNodeId64` = the node handing off custody. `ttl = 1`, never relayed.
Replay: `(messageId, 0x32)` dedup; the receiver additionally requires that `custodyMessageId` currently exists in its own S&F queue.
Parser: exact size; reject `originIdentityHash` of all zeros.

## 3.6 BROADCAST_MESSAGE (0x20)

```
plaintext = textLen(2) ‖ text(UTF-8, ≤ 1024 B)
```
AEAD under the **active channel key**; AAD = header AAD. Hop signature over `SIG_TRANSCRIPT`.
Wire: min `40+12+2+64+16 = 134`, max `40+12+1026+64+16 = 1158`.
Replay: `(messageId, 0x20)` + 600 s window. Persistence: `messages` under quota. Relay: ✅ ttl ≤ 7.
Parser: `textLen` must equal `plaintextLen - 2`; text must be valid UTF-8.
**Removed vs v1:** the trailing 64-byte in-payload signature and the `size >= 64` conditional. Authentication is now the hop signature, which is mandatory by type.

## 3.7 SOS_MESSAGE (0x2C)

```
plaintext = flags(1) ‖ textLen(2) ‖ text(≤ 512 B) ‖ [lat f64 ‖ lon f64 ‖ accuracy f32 ‖ fixTime u64]
```
`flags` bit0 = hasLocation; bits1–7 **MUST** be 0. Location block present iff bit0, exactly 28 B.
AEAD under `publicChannelKey`; hop signature mandatory.
Wire: min `40+12+3+64+16 = 135`, max `40+12+543+64+16 = 675`.
Additional rate rule: at most **3 SOS accepted per identity per 10 minutes**; beyond that, drop and count.
Persistence: `messages` (isSos = 1) and `last_known_locations` — **only** after full authentication.

## 3.8 DIRECT_MESSAGE (0x21)

```
plaintext = text (UTF-8, ≤ 1024 B)
```
AEAD under `sessionKey(epoch = packet.timestamp / 3600)` — **unchanged derivation**. Hop signature mandatory so relays can authenticate without the session key.
Wire: max `40+12+1024+64+16 = 1156`.
Replay: `(messageId, 0x21)` + 86 400 s window.
Persistence: `messages`, `store_forward_queue`.

## 3.9 ACK (0x23)

```
plaintext = originalMessageId(16)
```
**Nonce strategy unchanged:** a fresh `ackPacketId` is the packet's `messageId`; the original messageId travels inside the ciphertext. AEAD under the epoch session key; hop signature mandatory.
Wire: fixed `40 + (12+16) + 64 + 16 = 148`.
Effect on receipt: `messages.status = DELIVERED`, delete the `store_forward_queue` row. This is the **only** end-to-end delivery proof in the system.

## 3.10 PROFILE_UPDATE (0x2D)

ProfilePayload **v2** plaintext:

```
DOMAIN_TAG "MWP2"     4
nodeId                8
version               8
displayNameLen        1     ≤ 32
displayName        0–32
bioLen                2     ≤ 120
bio               0–120
avatarHash           32
```
Min plaintext 55, max 207. AEAD under `publicChannelKey`; hop signature mandatory.
`signingPublicKey` and `signature` are **deleted** from the format (C-07).
Replay: `(messageId, 0x2D)` + strictly increasing `version` per identity.
Parser: `nodeId` **MUST** equal `header.senderNodeId64`; declared lengths must sum exactly; reject `MWP1`.

## 3.11 MEDIA_INIT (0x25)

Structure is **preserved from v1** (`mediaId ‖ typeCode ‖ mediaVersion ‖ totalChunks ‖ totalSizeBytes ‖ durationMs ‖ sha256 ‖ fileNameLen ‖ fileName ‖ previewLen ‖ preview ‖ captionLen ‖ caption ‖ [tiling block]`), with these frozen changes:

| Field | v1 | vNext |
|---|---|---|
| `previewLen` | u16, ≤ 65535 | u16, **≤ 512** |
| `fileNameLen` | u8 | u8, **≤ 64**, and the name **MUST** match `^[A-Za-z0-9._-]{1,64}$` with no leading dot |
| `captionLen` | u8 | u8, ≤ 200 |
| `totalChunks` | ≤ 4096 | ≤ 4096 (unchanged) |
| `totalSizeBytes` | ≤ 20 MB | ≤ 20 MB (unchanged) |
| file extension | derived from `fileName` | derived from an **allow-list**; anything else → `.bin` |

Hop signature mandatory. Creates a **transfer admission** at every node that relays or receives it: `(senderIdentityHash, mediaId, totalChunks, totalSizeBytes, expiry = now + 300 s)`.

## 3.12 MEDIA_CHUNK (0x26)

```
plaintext = mediaId(16) ‖ chunkIndex(2) ‖ chunkData(≤ CHUNK_PAYLOAD_SIZE)
CHUNK_PAYLOAD_SIZE = 320       [WIRE]  (was 400)
```
Wire max: `40 + (12 + 16 + 2 + 320) + 16 = 406` — single BLE frame at MTU 512.
**No hop signature** (C-14). Relay permitted only under a matching unexpired admission, with `chunkIndex < totalChunks`, `chunkData.size <= 320`, cumulative relayed bytes `<= totalSizeBytes * 1.1`, and each index forwarded at most twice.
Receiver: write-once per index; cumulative accepted bytes `<= totalSizeBytes`; SHA-256 over the reassembly is the acceptance gate.
Produces `AdmittedChunk`, **not** `AuthenticatedPacket` (§4).

## 3.13 VOICE_CALL_SIGNAL (0x2F)

```
plaintext = action(1) ‖ callSessionId(16) ‖ signalSeq(4) ‖ timestampMs(8)   = 29 B
```
AEAD under the epoch session key; AAD = header AAD. No hop signature (ttl = 1).
Wire: fixed `40 + (12+29) + 16 = 97`.
`action ∈ {OFFER 0x01, ANSWER 0x02, DECLINE 0x03, HANGUP 0x04, BUSY 0x05}`.
Replay: `(messageId, 0x2F)` + strictly increasing `signalSeq` per `callSessionId`; `callSessionId` rejected if present in the 64-entry recent-call LRU.
**Removed vs v1:** `callSessionId` is no longer plaintext on the wire. This closes the passive-sniffer audio-injection path.

## 3.14 VOICE_FRAME (0x30)

```
plaintext = seq(8) ‖ audioData(≤ 160 B)
```
AEAD under `K_call` with a **deterministic nonce**:

```
nonce = direction(1) ‖ 0x00 0x00 0x00 ‖ seq(8)      12 B, NOT transmitted
direction = 0x01 caller→callee, 0x02 callee→caller
```
The nonce is reconstructed by the receiver from `seq` in the plaintext… which it cannot read before decrypting. Therefore `seq` is **also carried in the clear** as the first 8 bytes of the payload, before the ciphertext:

```
payload = seqPlain(8) ‖ rawCiphertext          (no IV prefix — nonce is derived)
```
The receiver builds the nonce from `seqPlain` and the known direction, decrypts, and **MUST** verify that the decrypted `seq` equals `seqPlain`. A mismatch is a drop. AAD = header AAD, which binds sender, recipient and timestamp.

Wire: `40 + 8 + 8 + 160 + 16 = 232` max; typical ADPCM 80-byte frame → 152 bytes.
Replay: `seq` strictly increasing per `(callSessionId, direction)`; `seq` starts at 1; `seq = 0` is reserved and rejected.
`callSessionId` is not on the wire; the frame is bound to the call by `K_call` — a frame for another call will not decrypt.

## 3.15 PROFILE_REQUEST (0x2E) / AVATAR_REQUEST (0x27) / TYPING_INDICATOR (0x28)

Empty or 1-byte plaintext, AEAD under the epoch session key, AAD = header AAD, `ttl = 1`, never relayed, never persisted.
- PROFILE_REQUEST plaintext: empty. (The v1 8-byte nodeId body is removed; the header already carries it.)
- AVATAR_REQUEST plaintext: empty.
- TYPING_INDICATOR plaintext: `isTyping(1)`.
Rate: ≤ 1 accepted per identity per 10 s for PROFILE_REQUEST and AVATAR_REQUEST; ≤ 1 per 2 s for TYPING_INDICATOR.

---

# 4. EXACT AUTHENTICATION PIPELINE

Location: `:core`, a pure function of `(bytes, LinkContext, IdentityStore, Clock)`. Both `:app` and `:desktop` call it. No other ingest path exists.

**The hard line:** *nothing* may be written to any database, file, or Room table before `AuthenticatedPacket` construction. Stages S0–S7 are memory-only.

---

### S0 — ADMISSION
- **INPUT:** raw frame bytes, `LinkContext { linkHandle, transport, boundIdentity: identityHash?, state: PENDING|AUTHENTICATED }`.
- **VALIDATION:** link rate budget; frame size ≤ transport max; if `state == PENDING`, the packet's `typeByte` **MUST** be `0x31`.
- **ALLOCATIONS:** none beyond the received frame.
- **STATE MUTATION:** rate-limiter counters only (bounded, evicted on link close).
- **FAILURE:** drop, increment a counter. No log to disk.

### S1 — STRUCTURE
- **INPUT:** frame bytes.
- **VALIDATION:** `56 <= size <= 2104`; `protocolVersion == 1`; `typeCode` known and not `0x22`; `size == 56 + payloadLen`; `payloadLen <= 2048`; `authTag != 0^16` unless type is `0x31`; for signed types `payloadLen >= 64 + minCiphertext[type]`; **clamp** `ttl = min(ttl, MAX_TTL[type])`; type-specific min/max payload bounds from §3.
- **ALLOCATIONS:** header struct + slices into the existing buffer. **No allocation sized by an attacker-declared length before that length is validated.**
- **STATE MUTATION:** none.
- **FAILURE:** drop.

### S2 — FRESHNESS
- **VALIDATION:** `-120 <= (now - timestamp) <= PAST_WINDOW[type]`.
- **STATE MUTATION:** none. **FAILURE:** drop.

### S3 — PRE-AUTH DEDUP (READ ONLY)
- **VALIDATION:** if `(messageId, typeCode)` is in the RAM LRU → drop. The single exception preserved from v1: a duplicate `DIRECT_MESSAGE` addressed to us re-emits the delivery ACK (this behaviour is correct and is retained).
- **STATE MUTATION:** **NONE.** No insert here (C-05).
- **FAILURE:** drop.

### S4 — IDENTITY RESOLUTION
- **VALIDATION:** look up `senderNodeId64` in `IdentityStore`.
  - Found, unique, state ∉ {BLOCKED, CONFLICTED} → proceed with that `PeerIdentity`.
  - Found but `BLOCKED` → drop.
  - `CONFLICTED` → drop for unicast types; for BROADCAST/SOS/ANNOUNCE proceed (per-signature verification).
  - Not found → drop, **unless** type is `PEER_ANNOUNCE` or `LINK_AUTH`, which are the two self-bootstrapping types.
- **STATE MUTATION:** none.
- **FAILURE:** drop.

### S5 — CHEAP CRYPTO (AEAD)
- **VALIDATION:** AES-GCM decrypt under the key selected by type. Skipped for LINK_AUTH (no key) and for relay-only handling of a channel we cannot decrypt (C-17).
- **ALLOCATIONS:** one plaintext buffer, size known from `payloadLen`.
- **STATE MUTATION:** none.
- **FAILURE:** drop. **No fallback path of any kind.**

### S6 — EXPENSIVE CRYPTO (Ed25519)
- **VALIDATION:** consume one token from the per-link verification bucket (32/s) and the global bucket (256/s); empty → drop. Then, by type:
  - PEER_ANNOUNCE: recompute `identityHash` and `nodeId64` from `IK_pk`; compare to `header.senderNodeId64` (DROP on mismatch, C-02); verify IBC unless `(identityHash, keyVersion)` is cached; apply the keyVersion rules of C-12; verify `hopSignature` over `SIG_TRANSCRIPT`; enforce `announceCounter` monotonicity.
  - Other signed types: verify `hopSignature` against the **stored** `IK_pk` for the resolved identity.
  - LINK_AUTH: verify IBC, then `confirmSig` over `T`.
  - Unsigned ttl=1 types: no work; the AEAD at S5 is the authenticator.
- **STATE MUTATION:** none. (`keyVersion` / `announceCounter` are *checked* here, *committed* after.)
- **FAILURE:** drop, increment the security counter (a signature failure is an attack indicator, not noise).

### S7 — SEMANTIC VALIDATION
- **VALIDATION:** parse the plaintext per §3 with exact-length arithmetic; every declared length must sum precisely; UTF-8 validity; reserved bits zero; `payload.nodeId == header.senderNodeId64` where applicable; `flags.hasLocation ⇒ ttl == 1 && link is authenticated as this identity`; enum values in range; ordering/uniqueness constraints on neighbour lists.
- **ALLOCATIONS:** parsed structures, all bounded by §3 maxima.
- **STATE MUTATION:** none.
- **FAILURE:** drop the **whole** packet. Partial acceptance is forbidden (I-3).

### AuthenticatedPacket CONSTRUCTION
Private constructor in `:core`. No public factory. Construction performs, atomically and in this order:
1. Persistent `INSERT OR IGNORE` into `processed_packets` (the existing atomic dedup, unchanged). Returns `-1` → this is a duplicate that raced us → abandon (and re-emit the delivery ACK if it is a DM for us).
2. Insert `(messageId, typeCode)` into the RAM LRU.
3. Commit `announceCounter` / `keyVersion` / `profile.version` advances.

Guarantees, as stated in vNext §3.5: canonical parse, freshness, non-null resolved identity, verified authenticator over every readable field, dedup committed exactly once, sender not blocked, TTL clamped and marked untrusted.

### POST-AUTH: PERSISTENCE
Now, and only now, are `messages`, `peers`, `identities`, `profiles`, `last_known_locations`, `store_forward_queue` and `topology_edges` writable, each under its §8 quota.

### POST-AUTH: ROUTING
Route lookups use the cached routing table. Topology updates are **staged**, never immediately believed (§2.15).

### POST-AUTH: RELAY
Permitted only if: `ttl > 1` after clamping, the type is relayable, the hop signature was verified **by this node** (or a MEDIA_CHUNK admission matched), and the per-identity relay budget has capacity. Relayed bytes are the original bytes with the clamped, decremented TTL.

### HANDLER DISPATCH
Handlers receive `AuthenticatedPacket` (or `AdmittedChunk`) and nothing else. A handler that calls a crypto function, an identity lookup, or a signature check is a bug and is caught by an architectural test (T-ARCH-01).

---

## 4.1 Checks that MUST precede any database write

S0, S1, S2, S3, S4, S5, S6, S7 — **all of them**, without exception. Specifically, these three current behaviours are removed:

| Current code | Behaviour | Required |
|---|---|---|
| `MeshRouter.kt:271` | `processedPacketDao().markSeen(...)` before authentication | moved into `AuthenticatedPacket` construction |
| `MeshRouter.kt:1846` | `logPacket(...)` writes a `packet_logs` row for every packet including drops | in-memory ring buffer of 500 in release; disk table only in `BuildConfig.DEBUG` |
| `MeshRouter.kt:401–494` | peer/edge/location/profile writes inside `handlePeerAnnounce` before signature verification succeeds | after `AuthenticatedPacket` |

---

# 5. IDENTITY / TRUST STATE MACHINE

## 5.1 Capabilities per state

| State | Routable dest. | E2E messaging | Relay for them | Topology participant | Green shield | Evictable |
|---|---|---|---|---|---|---|
| `SEEN` | ✅ | ✅ | ✅ | ✅ | ❌ | ✅ (LRU) |
| `LINKED` | ✅ | ✅ | ✅ | ✅ | ❌ | ✅ (LRU, lowest priority) |
| `IMPORTED` | ✅ (+1 route cost) | ✅ | ❌ | ❌ | ❌ | ✅ (LRU) |
| `VERIFIED` | ✅ | ✅ | ✅ | ✅ | ✅ | ❌ never |
| `CONFLICTED` | ❌ | ❌ | ❌ | ❌ | ❌ | ❌ never |
| `BLOCKED` | ❌ | ❌ | ❌ | ❌ | ❌ | ❌ never |
| `LEGACY_UNVERIFIED` | ❌ | ❌ | ❌ | ❌ | ❌ | ❌ (history) |

`IMPORTED` is not a relay or topology participant because it has never completed a link handshake — we hold its keys but have never proven it is reachable or live.

## 5.2 Transitions — no implicit transitions exist

| # | From | To | Trigger | Validation required |
|---|---|---|---|---|
| T1 | *(none)* | `SEEN` | first authenticated PEER_ANNOUNCE | full S0–S7 incl. IBC + hopSig + nodeId64 match |
| T2 | *(none)* | `IMPORTED` | deep link / NFC import | IBC verifies; `SHA-256(IK_pk)` reproduces the claimed nodeId64 |
| T3 | *(none)*, `SEEN`, `LINKED`, `IMPORTED` | `VERIFIED` | **in-app camera QR scan only** | IBC verifies; nodeId64 match; user confirms the 60-digit safety number |
| T4 | `SEEN`, `IMPORTED` | `LINKED` | LINK_AUTH CONFIRM verified on a live link | §3.4 in full |
| T5 | `LINKED` | `SEEN` | link closed | none |
| T6 | `VERIFIED` | `LINKED` | accepted EK rotation (`keyVersion` increment) | C-12 rotation row |
| T7 | any | `CONFLICTED` | a second distinct `identityHash` presents the same `nodeId64` | both identities independently authenticated |
| T8 | `CONFLICTED` | `VERIFIED` | user QR-scans one of the colliding identities | T3 validation; the other identity → `BLOCKED` |
| T9 | any | `BLOCKED` | explicit user block | user action only |
| T10 | `BLOCKED` | `SEEN` | explicit user unblock | user action only |
| T11 | `LEGACY_UNVERIFIED` | `SEEN` | a vNext announce arrives whose `identityHash` matches the migrated record | T1 validation |
| T12 | *(none)* | `LEGACY_UNVERIFIED` | DB migration 11→12 | migration only; unreachable at runtime |

**Forbidden transitions, enforced by test:** anything → `VERIFIED` by any path other than T3 or T8. A browsable deep link **MUST NOT** produce `VERIFIED` (finding S-17).

---

# 6. KEY LIFECYCLE

## 6.1 Generation
`S` = 32 CSPRNG bytes, generated once on first run. **Existing installations keep their existing `S`** (§11 in file 2).

## 6.2 Persistence
`S` is AES-GCM wrapped by `MeshWhisperIdentityMasterKey` (AndroidKeyStore) and stored hex-encoded in `meshwhisper_identity_prefs`. Unchanged from v1. On desktop, `DesktopPassphraseKeyStorage` wraps it with a passphrase-derived key via the existing `SecureKeyStorage` interface.

**If AndroidKeyStore is unavailable, key storage fails closed.** `CryptoEngine` already throws `SecurityException`; `MeshDatabase`'s software fallback (`MeshDatabase.kt:243–252`) is moved behind `BuildConfig.DEBUG` (finding S-6).

## 6.3 Derivation
| Key | Derivation | Lifetime |
|---|---|---|
| `IK_sk` | `HKDF(S, HKDF_DM_SALT, "MESHWHISPER_ED25519_SIGNING_KEY_V1")` — unchanged | device lifetime |
| `EK_sk` | `= S` | until rotation |
| `sessionKey` | `HKDF(X25519(EK_sk, EK_pk_peer), HKDF_DM_SALT, "MESHWHISPER_SESSION_KEY_V1_EPOCH_$epoch")` — **unchanged** | 1 hour, cached |
| `K_link` | `HKDF(X25519(EK_sk, EK_pk_peer), "MW/LINK/SALT/v2", "link" ‖ T)` | link lifetime |
| `K_call` | `HKDF(sessionKey(epoch_of_OFFER), HKDF_DM_SALT, "MW/VOICE/v2" ‖ callSessionId)` | call lifetime, **pinned** |
| `fileKey` | `HKDF(mediaMasterKey, salt = fileId, "MW/FILE/v2")` | file lifetime |

`epoch = packet.timestamp / 3600` — **preserved exactly**. Both endpoints read it from the same packet header, so there is no clock-skew failure mode.

## 6.4 Rotation
Only `EK` rotates. Procedure: generate a new `EK_sk`; `keyVersion += 1`; re-sign the IBC; persist; clear all cached `sessionKey`s for all peers; emit an announce immediately. Peers apply C-12. `IK` never rotates (C-12).

## 6.5 Cache lifetime and invalidation
- `sessionKeyEpochCache`: 256-entry LRU keyed `min:max:epoch` — unchanged.
- **Invalidated on:** peer `keyVersion` increment, peer `BLOCKED`, peer → `CONFLICTED`, own EK rotation, panic wipe, `clearAllSessionKeys()`.
- IBC cache keyed `(identityHash, keyVersion)`, 512 entries, LRU.
- Recent-call LRU: 64 `callSessionId` values, in memory only.

## 6.6 Zeroization
Every derived key buffer (`sessionKey`, `K_link`, `K_call`, `fileKey`) is overwritten with zeros on eviction and on session end. `S` and `IK_sk` live for the process lifetime; `IK_sk` is re-derived per signing operation and zeroed immediately after (it is already derived on demand in `PureCryptoEngine.sign`). Zeroization is best-effort on a JVM and is documented as such — it is not a defence against a heap dump, it is hygiene.

## 6.7 Voice nonce lifecycle — the complete argument

**Claim:** nonce reuse under a fixed `K_call` is impossible.

1. `K_call` is a function of `(sessionKey(epoch_of_OFFER), callSessionId)`.
2. `callSessionId` is 128 bits of CSPRNG, generated by the caller, and is rejected by the callee if it appears in the 64-entry recent-call LRU. Collision probability across a device's lifetime is negligible.
3. Therefore each call has a distinct `K_call` with overwhelming probability, **and** `K_call` is pinned at setup and never re-derived mid-call (C-13), so it cannot silently change at an epoch boundary.
4. Within one call, `nonce = direction(1) ‖ 0x000000 ‖ seq(8)`. `direction` is `0x01` for the caller and `0x02` for the callee, so the two endpoints occupy disjoint nonce spaces under the same key.
5. Within one direction, `seq` starts at 1 and is incremented by the sender for every frame. The sender **MUST NOT** reuse a `seq`; retransmission of voice frames is forbidden (voice is loss-tolerant by design and the `JitterBuffer` already handles gaps).
6. The receiver enforces `seq > lastSeq[direction]` and rejects equal or lower values, so a replayed frame is dropped before decryption is even attempted.
7. `seq` exhaustion at 2⁶⁴ is unreachable (≈11 billion years at 50 frames/s). If `seq` would wrap, the call **MUST** be terminated.
8. The plaintext `seq` and the clear `seqPlain` **MUST** match after decryption, which prevents an attacker from steering the receiver to an arbitrary nonce.

Steps 2–7 are each individually testable; see T-VOICE-01..05 in file 3.

## 6.8 Process restart
`S`, `keyVersion`, `announceCounter`, `lastEmittedTimestamp`, peer `identities`, `lastAnnounceCounter`, profile `version` — all persisted and reloaded. All caches (session keys, IBC, routing table, call state, link bindings, media sessions) are in-memory and rebuilt. Active calls and links do not survive restart; this is correct.

## 6.9 Device restore / reinstall
A restore replays old prefs; `allowBackup="false"` already prevents cloud backup, so a restore means a fresh install: new `S`, new identity, new `nodeId64`. Peers see a stranger. A reinstall with the **same** `S` (manual key export, not currently a feature) would regress `announceCounter` — handled by the three permitted regression paths: a `keyVersion` increment, a 24 h absence, or QR re-verification. A silent regression is rejected.

---

# 7. REPLAY MODEL

## 7.1 Per-type matrix

| Type | Freshness | Dedup key | Monotonic counter | State location | Updated at | Survives restart | Clock rollback | Key rotation |
|---|---|---|---|---|---|---|---|---|
| PEER_ANNOUNCE | 600 s | `(msgId, 0x24)` | `announceCounter` u64 | `identities` row | AuthPacket construction | ✅ | own emissions monotonic via `lastEmittedTimestamp`; peer counters unaffected | counter may regress **only** with a `keyVersion` increment |
| BROADCAST_MESSAGE | 600 s | `(msgId, 0x20)` | — | `processed_packets` | AuthPacket | ✅ | stale packets rejected by window | none |
| SOS_MESSAGE | 600 s | `(msgId, 0x2C)` | — (+3 per 10 min rate cap) | `processed_packets` | AuthPacket | ✅ | window | none |
| DIRECT_MESSAGE | 86 400 s | `(msgId, 0x21)` | — | `processed_packets` | AuthPacket | ✅ | window | session key changes → old packets fail AEAD |
| ACK | 86 400 s | `(ackPacketId, 0x23)` | — | `processed_packets` | AuthPacket | ✅ | window | as above |
| PROFILE_UPDATE | 600 s | `(msgId, 0x2D)` | `version` u64 | `profiles` row | AuthPacket | ✅ | window | none |
| MEDIA_INIT | 600 s | `(msgId, 0x25)` | — | `processed_packets` | AuthPacket | ✅ | window | none |
| MEDIA_CHUNK | 600 s | `(mediaId, chunkIndex)` within session | write-once index | in-memory session | on accept | ❌ (in-memory) | window | none |
| MEDIA_NACK/ACK/ABORT | 600 s | `(msgId, type)` | — | `processed_packets` | AuthPacket | ✅ | window | none |
| VOICE_CALL_SIGNAL | 30 s | `(msgId, 0x2F)` | `signalSeq` u32 per call | in-memory call | on accept | ❌ | window | none |
| VOICE_FRAME | 30 s | — (seq check replaces dedup) | `seq` u64 per direction | in-memory call | on accept | ❌ | window | call ends on rotation |
| LINK_AUTH | 60 s | — | 32-byte nonces in `T` | in-memory link | handshake | ❌ | window | new handshake |
| CUSTODY_ACK | 600 s | `(msgId, 0x32)` | — | `processed_packets` | AuthPacket | ✅ | window | none |
| PROFILE_REQ / AVATAR_REQ / TYPING | 120/120/30 s | `(msgId, type)` | — + per-identity rate cap | `processed_packets` | AuthPacket | ✅ | window | none |

## 7.2 The seven required adversary outcomes

| # | Input | Required outcome | Mechanism |
|---|---|---|---|
| R1 | captured valid packet, delivered normally | **accepted once** | normal path |
| R2 | same bytes replayed unchanged | **dropped, no state change** | dedup on `(messageId, type)`, persistent + RAM |
| R3 | captured payload, fresh `messageId` | **dropped** | `messageId` is inside `SIG_TRANSCRIPT`; the signature no longer verifies |
| R4 | captured payload, modified `timestamp` | **dropped** | `timestamp` is inside `SIG_TRANSCRIPT` **and** inside the AAD |
| R5 | signature lifted onto a different packet | **dropped** | transcript binds `packetType`, `messageId`, both nodeIds, `identityHash`, length, and `SHA-256(ct‖tag)` |
| R6 | old `keyVersion` in an IBC | **dropped, stored binding unchanged** | strict `>` comparison in C-12 |
| R7 | old `announceCounter` | **dropped, no topology/peer update** | strict `>` comparison; regression only via the three permitted events |

Each row maps to a test in file 3 (T-REP-01..07).

---

# 8. RESOURCE CONTRACT

Single canonical table. Any number appearing elsewhere in any document that disagrees with this table is wrong.

| Surface | Max input | Per-link | Per-identity | Global | Timeout | Response on breach |
|---|---|---|---|---|---|---|
| **BLE frame** | 512 B | 50 frames/s | — | — | — | drop frame |
| **BLE reassembly** | 2 104 B total | 2 concurrent sessions | — | 16 sessions | 10 s | drop session |
| **BLE GATT links** | — | — | — | 5 concurrent | — | refuse connect |
| **Wi-Fi TCP frame** | **2 104 B** | 50 frames/s | — | — | — | drop frame, close on 3 oversize |
| **Wi-Fi pending handshakes** | 169 B HELLO | 1 | 1 | **8 slots** | **3 s total** | close socket immediately |
| **Wi-Fi authenticated sessions** | — | — | 1 (keyed by `identityHash`) | 5 | idle 120 s | reject new, **never overwrite** |
| **UDP beacon** | **128 B** | 10/s per source IP | — | 100/s | — | drop; never persisted, never relayed |
| **UDP mesh packets** | **0 — forbidden** | — | — | — | — | drop unconditionally |
| **Packet parse** | 2 104 B | — | — | — | — | drop |
| **Ed25519 verify** | — | **32/s** | — | **256/s** | — | drop packet |
| **Media chunk** | **320 B** payload | — | — | — | — | drop chunk |
| **Media session (inbound)** | 4 096 chunks / 20 MB | — | **2** | **8** | 300 s idle | evict oldest for that identity |
| **Media bandwidth** | — | — | **8 MB / hour** | 32 MB/hour | rolling | drop MEDIA_INIT |
| **Media relay** | — | — | `totalSizeBytes × 1.1`, each index ≤ 2× | — | 300 s admission | stop relaying that transfer |
| **Topology neighbours** | 16 per announce | — | 16 | — | 90 s fresh / 180 s evict | truncate → drop packet |
| **Topology origins** | — | — | 1 edge-set (replaced) | **512** | 30 s prune timer | LRU by `lastConfirmedAt` |
| **`identities` / `peers`** | — | — | — | **1 024** | — | LRU by `lastAuthenticatedAt`; `VERIFIED`, `CONFLICTED`, `BLOCKED` never evicted |
| **`profiles`** | 207 B payload | — | 1 | **1 024** | — | LRU; only for identities present in `identities` |
| **`last_known_locations`** | 28 B | — | 1 | **512** | — | LRU by timestamp |
| **`messages`** | 1 026 B text | — | — | **10 000 / conversation, 100 000 global** | — | LRU by timestamp + UI archive warning |
| **`store_forward_queue`** | 2 104 B blob | — | 50 per recipient | **500 (300 own / 200 relayed)** | 24 h | reject new relayed entries when the relayed partition is full |
| **`processed_packets`** | — | — | — | **50 000 rows** | 24 h purge on a timer | ring-evict by insertion order |
| **`packet_logs`** | — | — | — | **500, in-memory ring** | — | **disk table only in `BuildConfig.DEBUG`** |
| **Egress queue (`MeshTrafficController`)** | — | — | 25 relay slots | 100 per tier × 4 | 30 s packet lifetime | drop oldest in tier (existing behaviour) |
| **Coroutines / blocking IO** | — | — | — | Wi-Fi handshakes on a **dedicated 4-thread dispatcher**, never `Dispatchers.IO` | 3 s | close |
| **SOS acceptance** | 675 B | — | **3 per 10 min** | — | — | drop + count |
| **PROFILE_REQ / AVATAR_REQ** | 96 B | — | 1 per 10 s | — | — | drop |

Derived consistency check (C-16): 50 frames/s ÷ ~2 frames per signed packet ≈ 25 signed packets/s < 32 verifications/s. The budgets do not fight each other.

---

# SECTION A — FINAL SECURITY CONTRACT

These twenty-one invariants are the contract. Each is a testable assertion with a single enforcement point. Violating any of them is a release blocker.

| ID | Invariant | Enforced at |
|---|---|---|
| **I-1** | An unknown sender is never a trusted sender. `AuthenticatedPacket.senderIdentity` is non-null; there is no `UNKNOWN` variant. | S4 |
| **I-2** | A missing signature is never a valid signature. Signature requirement is a function of **packet type** only, never of payload length. | S6 dispatch table |
| **I-3** | A malformed authenticated payload is discarded whole. No field is consumed from a payload whose declared lengths do not sum exactly. | S7 |
| **I-4** | An unauthenticated identity is never routable. A nodeId64 enters routing only via a `CONFIRMED` edge or an authenticated link. | topology + link binding |
| **I-5** | A transport connection is not an authenticated peer. A `PENDING` link accepts only LINK_AUTH and mutates no shared state. | S0 |
| **I-6** | A successful socket write is not delivery. Only an end-to-end ACK releases originator custody. | custody module |
| **I-7** | A payload-supplied public key is never a trust anchor. The only exception is PEER_ANNOUNCE, and only after nodeId64 reproduction + IBC verification. | S6 |
| **I-8** | A deep link is not human verification. Only the in-app camera scanner may produce `VERIFIED`. | T3 / T8 |
| **I-9** | An encrypted database is not encrypted media. Every file in `filesDir` is AES-GCM encrypted under a Keystore-wrapped key. | media write path |
| **I-10** | No persistent write of any kind precedes full authentication — including dedup rows and diagnostic logs. | S0–S7 boundary |
| **I-11** | No relay precedes authentication. A packet this node cannot authenticate is a packet this node does not forward. | relay gate |
| **I-12** | TTL is never trusted. Clamped at S1; used only for the relay branch. | S1 |
| **I-13** | A signature valid in one context is invalid in every other. All transcripts carry a purpose tag and a packet type. | §2.8 |
| **I-14** | Key rotation is explicit, ordered and monotonic. `keyVersion` regression drops. Rotation clears `VERIFIED`. | C-12 |
| **I-15** | A nodeId64 collision suspends unicast rather than guessing a key. | C-23 |
| **I-16** | There is no cryptographic fallback path. A failed decryption or a missing authenticator is a drop. | S5, S6 |
| **I-17** | Key storage that cannot be hardware-backed fails closed in release builds. | `MeshDatabase`, `CryptoEngine` |
| **I-18** | An attacker-declared length never sizes an allocation before that length is validated against a protocol constant. | S1 |
| **I-19** | Every remotely-reachable table has a hard cap and an eviction policy. | §8 |
| **I-20** | Panic wipe is uncancellable and monotonic: every prefix of the sequence leaves the device safer. | C-26 |
| **I-21** | Handlers make no trust decisions. A handler calling crypto or identity resolution is a bug. | architectural test |

**Frozen wire constants:** `"MW/NODE/v2"`, `"MW/SIG/v2"`, `"MW/TCP/v2"`, `"MW/LINK/SALT/v2"`, `"MW/VOICE/v2"`, `"MW/FILE/v2"`, `"MWP2"`, purpose tags `0x02/0x03/0x04`, protocol version `1`, type bytes `0x20–0x32`, transcript length `115`, IBC transcript length `83`, `CHUNK_PAYLOAD_SIZE = 320`, `FUTURE_SKEW = 120`, `MAX_NEIGHBORS_PER_ANNOUNCE = 16`, `EDGE_FRESHNESS_MS = 90000`.

Changing any of the above requires a protocol version bump and a new frozen spec. It is not an implementation decision.
