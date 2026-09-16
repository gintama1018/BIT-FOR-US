# BIT FOR US / MeshWhisper — Secure Protocol vNext
# FILE 3 of 3: TEST MATRIX, AGENT RULES, DEFINITION OF DONE

**Reads with:** `01_VNEXT_PROTOCOL_FROZEN.md` (normative protocol), `02_VNEXT_IMPLEMENTATION_PLAN.md` (file map and phases).

This file ends with **SECTION C — DEFINITION OF DONE**.

---

# 12. TEST ARCHITECTURE AND MATRIX

## 12.1 Why this exists

The repository has 125 passing tests and shipped a total authentication failure (F-0). The cause is precise and must not recur: **no test could feed bytes into `MeshRouter`**, so every protocol test invented its own payload format. `testPeerAnnounceAeadEncryptionAndSignature` (`core/src/test/.../MultiHopRelayAndSecurityMeshTest.kt:267`) builds `"ALIAS=…;PUB=…;GPS=…"` — a format production never emits — and verifies with `deriveSigningPublicKey()` — a key production never transmits. It proved a protocol that does not exist.

**Rule zero for this test suite: protocol tests use production builders and the production ingest pipeline. Test-only packet formats are forbidden.**

## 12.2 The harness

Four seams extracted in Phase 0, all in `:core`:

```
Transport      attemptSend(link, bytes) / broadcast(bytes, excludeLink) / events: Flow<LinkEvent>
Clock          nowSeconds()
RandomSource   bytes(n)
IdentityStore  get(identityHash) / getByNodeId64(id) / upsert(...) / all()
```

**`InMemoryMesh`** — N virtual nodes, each a full production stack over `FakeTransport`. Configurable per-link loss ratio, latency distribution, reordering, partition and reconnect. `TestClock` and a seeded `RandomSource` make every run deterministic and every failure reproducible from a seed.

**`MaliciousPeer`** — not a mock. A real vNext participant with a hostile API. Every audit finding maps to one call:

```
spoofNodeId(victim)          replay(captured)              replayWithFreshMessageId(captured)
replayWithTimestamp(t)       omitSignature()               signWithForeignKey()
liftSignature(from, into)    mutateTranscriptField(field)  truncate(n) / pad(n)
zeroAuthTag()                oversizeFrame(bytes)          oversizePayloadLen()
floodUniqueMessageIds(rate)  claimEdge(a, b)               claimEdgeUnilaterally(victim)
greyhole(dropRatio)          halfOpenConnect(count)        hijackNodeId(victim)
rollbackKeyVersion()         rollbackAnnounceCounter()     equivocateEkAtSameKeyVersion()
collideNodeId64(victim)      oversizeChunk(bytes)          declareSmallSendLarge()
raceChunkIndex(idx, junk)    replayVoiceSeq(seq)           replayLinkConfirm()
sendV1Packet()               announceWithLocationAtTtl7()  pathTraversalFileName()
```

**Golden vectors** — frozen hex fixtures in `core/src/test/resources/vectors/`. Encode must reproduce the fixture byte-for-byte; decode must reproduce the struct. These are what make "two engineers produce interoperable bytes" verifiable rather than aspirational.

## 12.3 The universal post-condition

Every negative test asserts two things, not one:

1. the packet is **dropped**, and
2. **no observable state changed** — row counts in `messages`, `peers`, `identities`, `profiles`, `last_known_locations`, `store_forward_queue`, `processed_packets`, `topology_edges`, `packet_logs` are all identical before and after; no file created in `filesDir`; no UI callback fired; no relay emitted.

A test that only asserts "returns false" is insufficient and will be rejected in review. Finding S-8 exists precisely because dropping and not-persisting were treated as the same thing when they are not.

---

## 12.4 TEST MATRIX

### A. Integration — production bytes through production ingest

| ID | Feature | Attack / input | Expected | State that must not change |
|---|---|---|---|---|
| T-INT-01 | all 19 v2 types | production builder → bytes → `PacketPipeline.ingest` | `Accepted`, `senderIdentity.hash == builder identity` | — |
| T-INT-02 | PEER_ANNOUNCE | `announcePresence()` output fed to `handleIncomingPacket` | peer inserted, `trustState = SEEN` | **the F-0 regression test** |
| T-INT-03 | 3-node relay | A→B→C DM over `InMemoryMesh` | delivered, ACK returns to A, S&F empties at A | — |
| T-INT-04 | discovery convergence | 5 nodes cold start | all 5 mutually `SEEN` within 30 virtual seconds | — |
| T-INT-05 | Android ↔ desktop | same pipeline, both stacks | authenticated DM both directions | — |

### B. Negative authentication — one row per finding

| ID | Feature | Attack / input | Expected | State that must not change |
|---|---|---|---|---|
| T-NEG-01 | **F-0** | `verifySignature(x25519Pub, data, sig)` | `false` (documents the type confusion) | — |
| T-NEG-02 | **F-0** | announce whose signature was made over the wrong key type | DROP | peers, identities |
| T-NEG-03 | **F-1** | SOS with a 66-byte payload and no signature | DROP | messages, locations |
| T-NEG-04 | **F-1** | broadcast with a 63-byte payload and no signature | DROP | messages |
| T-NEG-05 | **F-1** | SOS forged with the public channel key, arbitrary `senderId` | DROP | messages, locations, **and no relay emitted** |
| T-NEG-06 | **S-1** | profile claiming a victim's nodeId, signed by Mallory, **through the pipeline** | DROP | profiles, peers |
| T-NEG-07 | **S-1** | profile with `version = Long.MAX_VALUE` from a forged identity | DROP; victim's real updates still apply afterwards | profiles |
| T-NEG-08 | signature scope | valid signature lifted onto a different packet type | DROP | all |
| T-NEG-09 | signature scope | valid payload + fresh `messageId` | DROP | all |
| T-NEG-10 | signature scope | valid payload + modified `timestamp` | DROP | all |
| T-NEG-11 | signature scope | valid payload + modified `recipientId` | DROP | all |
| T-NEG-12 | announce | `IK_pk` whose hash ≠ `header.senderId` | DROP (C-02) | identities |
| T-NEG-13 | IBC | `ibcSignature` forged | DROP | identities |
| T-NEG-14 | IBC | `keyVersion` lower than stored | DROP; stored `ekPub` unchanged (C-12) | identities |
| T-NEG-15 | IBC | same `keyVersion`, different `ekPub` (equivocation) | DROP; **no** `CONFLICTED` transition; warning counter +1 | identities |
| T-NEG-16 | announce | `announceCounter` ≤ stored | DROP | identities, topology |
| T-NEG-17 | structure | trailing bytes after the auth tag | DROP | all |
| T-NEG-18 | structure | `payloadLen` > 2048 | DROP, **no allocation** | all |
| T-NEG-19 | structure | all-zero auth tag on a non-LINK_AUTH type | DROP | all |
| T-NEG-20 | structure | retired type `0x22` | DROP | all |
| T-NEG-21 | version | v1 packet (`protocolVersion == 0`) | DROP with no parse attempt | all |
| T-NEG-22 | announce | `flags.hasLocation` with `ttl = 7` | DROP (C-03) | locations |
| T-NEG-23 | announce | reserved flag bit set | DROP | all |
| T-NEG-24 | announce | unsorted or duplicate neighbour entries | DROP | topology |
| T-NEG-25 | announce | `neighborCount = 200` | DROP | topology |
| T-NEG-26 | blocked peer | valid packet from a `BLOCKED` identity | DROP | all |
| T-NEG-27 | unknown sender | valid DM from an identity never announced | DROP (I-1) | messages |
| T-NEG-28 | relay | packet from an origin this node has never authenticated | not relayed (I-11, C-15) | — |
| T-NEG-29 | **S-13** | `payloadLen` shorter than the actual payload | DROP | all |
| T-NEG-30 | **pre-auth persistence** | 10 000 malformed packets with unique `messageId`s | all dropped; `processed_packets` and `packet_logs` row counts **unchanged** (I-10) | **the C-05 + S-8 regression** |
| T-NEG-31 | dedup poisoning | malformed packet carrying an in-flight genuine `messageId`, then the genuine packet | genuine packet **accepted** (C-05) | — |

### C. Replay — the seven required outcomes

| ID | Input | Expected | State |
|---|---|---|---|
| T-REP-01 | valid packet delivered normally | accepted once | — |
| T-REP-02 | identical bytes replayed | DROP | no duplicate row |
| T-REP-03 | captured payload, fresh `messageId` | DROP | all |
| T-REP-04 | captured payload, modified `timestamp` | DROP | all |
| T-REP-05 | signature moved to another packet | DROP | all |
| T-REP-06 | old `keyVersion` | DROP | identities |
| T-REP-07 | old `announceCounter` | DROP | identities, topology |
| T-REP-08 | duplicate DM addressed to us | DROP **but** delivery ACK re-emitted (preserved v1 behaviour) | messages |

### D. Transport

| ID | Feature | Attack / input | Expected | State |
|---|---|---|---|---|
| T-LINK-01 | **S-2** | TCP peer claims a victim's `nodeId64` without `IK_sk` | handshake fails, socket closed | `activePeers` |
| T-LINK-02 | **S-2** | attacker connects claiming an already-live identity | **rejected, existing session untouched** | `activePeers` |
| T-LINK-03 | LINK_AUTH | replayed CONFIRM against a fresh nonce | rejected | link state |
| T-LINK-04 | LINK_AUTH | CONFIRM naming the wrong `identityHash_peer` | rejected (SIGMA misbinding) | link state |
| T-LINK-05 | **S-10** | 200 concurrent half-open handshakes | pending pool ≤ 8; **an unrelated DB read completes in < 100 ms** | thread pool |
| T-LINK-06 | **S-11** | TCP frame declaring 10 MB | rejected before allocation; heap delta < 1 MB | — |
| T-LINK-07 | **S-9** | UDP datagram carrying a mesh packet | dropped unconditionally | all tables |
| T-LINK-08 | UDP | beacon > 128 B | dropped | — |
| T-LINK-09 | **S-3** | packet with `ttl = 7` from an unbound BLE link | **no address→nodeId binding created** | link bindings |
| T-LINK-10 | pre-auth link | non-LINK_AUTH packet on a `PENDING` link | dropped | all tables |
| T-LINK-11 | BLE framer | 300 chunks totalling 260 KB | rejected at 2 104 B | heap |
| T-LINK-12 | rate limiter | MAC rotation to reset the budget | budget keyed by link handle, not reset (C-22) | — |

### E. Routing and topology

| ID | Feature | Attack / input | Expected | State |
|---|---|---|---|---|
| T-ROUTE-01 | **S-4** | unilateral edge claim "I link to victim" | edge `STAGED`, **never routed** | routing table |
| T-ROUTE-02 | reciprocity | both endpoints assert within 90 s | `CONFIRMED`, routable | — |
| T-ROUTE-03 | expiry | one side goes stale | back to `STAGED`, evicted at 180 s | — |
| T-ROUTE-04 | poisoning | 512 origins × 16 edges flood | origin map capped at 512, LRU | memory |
| T-ROUTE-05 | greyhole | authenticated relay drops 100% over 20 messages | cost escalates, alternate route selected | — |
| T-ROUTE-06 | prune | 30 s timer | `topologyEdges` shrinks (S-21 regression) | — |
| T-ROUTE-07 | route cache | 10 000 packets through a 512-node graph | Dijkstra runs ≤ number of topology change events, **not** per packet | — |
| T-ROUTE-08 | loop | cyclic topology | path acyclic, ≤ 50 hops, terminates | — |

### F. Custody

| ID | Scenario | Expected | State |
|---|---|---|---|
| T-CUST-01 | `attemptSend` returns true, no ACK | originator copy **retained** | `store_forward_queue` |
| T-CUST-02 | end-to-end ACK arrives | originator deletes; `messages.status = DELIVERED` | — |
| T-CUST-03 | relay hands off, receives `CUSTODY_ACK` | relay deletes, **originator does not** | — |
| T-CUST-04 | relay hands off, no `CUSTODY_ACK` | relay retains, retries per ladder | — |
| T-CUST-05 | next hop vanishes mid-transfer | exactly one copy survives; delivered on reconnect | — |
| T-CUST-06 | recipient offline 24 h | `EXPIRED`, surfaced in UI, never silently lost | — |
| T-CUST-07 | forged `CUSTODY_ACK` from a third party | DROP; custody retained | — |
| T-CUST-08 | **S-12** | 200 hostile relayed S&F entries | own-message partition (300) untouched | `store_forward_queue` |
| T-CUST-09 | duplicate delivery | recipient dedups, re-ACKs, single message row | `messages` |

### G. Resources

| ID | Attack | Expected | State |
|---|---|---|---|
| T-RES-01 | **S-7** chunk of 64 KB | rejected at 320 B | heap |
| T-RES-02 | **S-7** 4 096 chunks × 60 KB, `totalSizeBytes = 1000` | rejected on cumulative overrun; heap delta < 25 MB | heap |
| T-RES-03 | **S-8** 60 s flood of unique MEDIA_INITs | `messages` at cap; disk delta < 10 MB | all tables |
| T-RES-04 | **S-8** 10 000 fake announces | `identities`/`peers` at 1 024, LRU; `VERIFIED` never evicted | tables |
| T-RES-05 | preview 64 KB in MEDIA_INIT | rejected at 512 B | `messages` |
| T-RES-06 | verification budget | 100 signed packets/s on one link | ≤ 32/s verified, rest dropped, no backlog | CPU |
| T-RES-07 | media budget | one identity pushes 40 MB in an hour | cut off at 8 MB | disk |
| T-RES-08 | broadcast media race (C-14) | attacker races `chunkIndex` 5 | first value wins; clean transfers unaffected | — |
| T-RES-09 | SOS rate | 20 SOS from one identity in 10 min | 3 accepted | `messages` |
| T-RES-10 | path traversal | `originalFileName = "a./../../evil"` | extension from the allow-list; file written inside `filesDir/media` only | filesystem |
| T-RES-11 | 60 s full-spectrum hostile soak | every table ≤ cap; heap bounded; no ANR | all |
| T-RES-12 | egress | 500 media chunks + 1 SOS queued | SOS transmitted first (traffic controller wired, C-19) | — |

### H. Voice

| ID | Attack | Expected | State |
|---|---|---|---|
| T-VOICE-01 | passive capture of a live call | audio **not** recoverable without `K_call` | — |
| T-VOICE-02 | frame injection with a known `callSessionId` | rejected (AEAD under `K_call`) | audio pipeline |
| T-VOICE-03 | replayed frame `seq` | rejected before decryption | jitter buffer |
| T-VOICE-04 | call crossing an hour boundary | `K_call` unchanged; audio continuous (C-13) | — |
| T-VOICE-05 | `seqPlain` ≠ decrypted `seq` | dropped | — |
| T-VOICE-06 | `callSessionId` reuse | rejected via the 64-entry recent-call LRU | — |
| T-VOICE-07 | spoofed OFFER from an unbound link | dropped (requires `LINKED`) | call state |

### I. Fuzzing and property tests

| ID | Target | Property |
|---|---|---|
| T-FUZZ-01 | `MeshPacket.deserialize` | never throws; never allocates > 2 104 B; 1 M cases |
| T-FUZZ-02 | `ProfilePayload.deserialize` | never throws; never allocates > 207 B |
| T-FUZZ-03 | `VoiceFramePayload`, `VoiceSignalPayload` | never throws |
| T-FUZZ-04 | announce parser | never throws; bounded allocation |
| T-FUZZ-05 | UDP beacon parser | never throws |
| T-PROP-01 | codec | `deserialize(serialize(p)) == p` for all valid `p` |
| T-PROP-02 | transcript | transcript is a deterministic 115-byte function of its inputs |
| T-PROP-03 | dedup | replaying any accepted packet never produces a second row |
| T-PROP-04 | TTL | clamped TTL ≤ `MAX_TTL[type]` for every input, including 255 |

### J. Storage, wipe, migration, architecture

| ID | Feature | Expected |
|---|---|---|
| T-MIG-01 | 11→12 | `messages` preserved; `peers` → `LEGACY_UNVERIFIED`; S&F dropped; matching messages `EXPIRED` |
| T-MIG-02 | identity | `IK_pk` derived from the preserved `S`; `nodeId64` changes; message history still attributed |
| T-MIG-03 | old QR | v1 QR → `LEGACY_UNVERIFIED`, not routable |
| T-MIG-04 | no destructive fallback | unknown schema version → explicit error screen, **DB not deleted** (C-20) |
| T-MIG-05 | enum | `MessageStatus.EXPIRED` round-trips through Room |
| T-MIG-06 | media | one-time re-encryption pass; files unreadable without the Keystore alias |
| T-WIPE-01 | panic wipe | `filesDir`, `databases/`, `shared_prefs/` empty; DB Keystore alias gone |
| T-WIPE-02 | panic wipe | notifications cancelled |
| T-WIPE-03 | interrupted wipe | cancel after step 2 → DB permanently undecryptable (C-26) |
| T-WIPE-04 | wipe scope | media and avatars removed |
| T-KEY-01 | **S-6** | Keystore unavailable in a release build → **fail closed**, no software fallback key |
| T-ARCH-01 | I-21 | no file under `router/` or `media/` references `PureCryptoEngine`, `verifySignature`, or `IdentityStore` |
| T-ARCH-02 | pipeline | `AuthenticatedPacket` has no public constructor and no public factory |
| T-ARCH-03 | single path | exactly one call site constructs `AuthenticatedPacket` |
| T-TRUST-01..06 | state machine | every transition in file 1 §5.2 reachable **only** by its listed trigger; deep link can never reach `VERIFIED` (S-17) |

### K. Golden vectors

`T-WIRE-01..19` — one per v2 type. Encode reproduces the fixture byte-for-byte; decode reproduces the struct; a single mutated byte anywhere fails verification.

---

# 14. RULES FOR THE IMPLEMENTATION AGENT

These are binding. A change that violates any of them is reverted regardless of whether tests pass.

1. **Do not invent protocol behaviour.** If it is not in `01_VNEXT_PROTOCOL_FROZEN.md`, it is not in the protocol. If you find something genuinely underspecified, stop and raise it — do not fill the gap with a guess. That is exactly how F-0 entered the codebase: an implementation improvised against a documented design it did not match.

2. **Do not silently change wire formats.** Any change to a field, size, order, constant, domain string or type byte requires a protocol version bump, a new frozen spec, and new golden vectors. "It was easier this way" is not a reason.

3. **Do not weaken authentication to make a test pass.** If a test fails, the implementation is wrong or the test is wrong. Fix one of them explicitly. Never relax a check, widen an acceptance window, or add a fallback to turn a test green.

4. **Do not preserve v1 compatibility.** No parser for `protocolVersion == 0`. No "legacy" branch. No "accept unsigned if the peer looks old". The break is deliberate and is the whole point of the migration (file 2 §11).

5. **Do not introduce a second authentication path.** There is exactly one `PacketPipeline` and exactly one call site that constructs `AuthenticatedPacket`. Not one per transport, not one for desktop, not a "fast path" for voice. T-ARCH-03 enforces this.

6. **Do not put trust decisions inside packet handlers.** A handler that calls a crypto function, resolves an identity, checks a signature, or asks "do I know this sender" is a bug. If a handler needs a decision the pipeline did not make, the pipeline is wrong — fix the pipeline.

7. **Do not persist anything before authentication.** No Room write, no file write, no `packet_logs` row, no dedup row, no counter row. In-memory counters and the bounded ring buffer only. This is invariant I-10 and it is the cheapest storage-exhaustion defence in the system.

8. **Do not use a payload-supplied public key as a trust anchor.** The single exception is PEER_ANNOUNCE, and only after `SHA-256("MW/NODE/v2"‖0x00‖IK_pk)` reproduces `header.senderId` **and** the IBC verifies. This is finding S-1; the corresponding fields were deleted from `ProfilePayload` precisely so the mistake cannot be repeated.

9. **Do not delete custody because a transport write returned true.** `attemptSend()` means "bytes handed to a radio". Only an end-to-end ACK (originator) or a valid `CUSTODY_ACK` (intermediate) or expiry releases a copy. Never rename `attemptSend` back to something that reads like a delivery guarantee.

10. **Do not add cryptographic primitives.** X25519, Ed25519, AES-256-GCM, HKDF-SHA256, SHA-256. That is the complete set. No new curves, no new AEADs, no truncated tags, no custom KDFs, no clever nonce compression. If you believe a new primitive is required, stop and raise it.

11. **Do not change a "DO NOT TOUCH" mechanism** (file 2 §13) without a failing regression test that demonstrates it must change, and record that test ID in `CHANGELOG.md`.

12. **Update documentation in the same commit as the behaviour.** `docs/PROTOCOL.md`, `docs/SECURITY.md` and `docs/LIMITATIONS.md` are part of the deliverable, not an epilogue. The repository currently claims a QoS scheduler that nothing calls, wake locks that do not exist, and encrypted voice that is plaintext. Do not add to that list.

13. **Run the full suite after every phase**, not at the end. A phase is not complete until every prior phase's tests still pass.

14. **Never claim a vulnerability is fixed without a regression test.** The test must be **red before** the fix and **green after**. Reference the finding ID (F-0, F-1, S-1…S-21) in the test name. "I checked it manually" does not close a finding.

15. **Preserve the failure direction.** Every ambiguity resolves toward dropping the packet. If you are unsure whether to accept or reject, reject. A dropped legitimate packet is a retry; an accepted forged packet is a compromise.

16. **Do not optimise the pipeline's ordering.** S0→S7 is ordered by cost for a reason (file 1 §C-17, §C-16). Moving an expensive check earlier creates a DoS primitive; moving a persistence step earlier violates I-10.

17. **Do not silence a security counter.** Signature failures, equivocation, nodeId64 collisions and rate-limit breaches increment counters that surface in diagnostics. They are attack indicators, not noise.

18. **Ask before deviating.** Every question this specification does not answer is a design decision, and design decisions are not made during implementation.

---

# SECTION C — DEFINITION OF DONE

BIT FOR US vNext is ready for **controlled multi-device field testing** when every box below is checked. Not before. Partial completion does not qualify; several of these findings are individually sufficient to make a field test misleading.

## C.1 Findings closed — each with a named, previously-red regression test

- [ ] **F-0** Ed25519 verified against the correct key — `T-NEG-01`, `T-NEG-02`, `T-INT-02`
- [ ] **F-1** SOS/broadcast unforgeable; short payloads cannot skip verification — `T-NEG-03..05`
- [ ] **S-1** Profile forgery impossible; payload key fields deleted from the format — `T-NEG-06`, `T-NEG-07`
- [ ] **S-2** Wi-Fi handshake authenticated; no session overwrite — `T-LINK-01`, `T-LINK-02`
- [ ] **S-3** `registerDirectNode` deleted; TTL binds nothing — `T-LINK-09`
- [ ] **S-4** Unilateral edge claims never route — `T-ROUTE-01`
- [ ] **S-5** Voice encrypted and authenticated — `T-VOICE-01..03`
- [ ] **S-6** Keystore fails closed in release — `T-KEY-01`
- [ ] **S-7** Chunk size and cumulative bytes bounded — `T-RES-01`, `T-RES-02`
- [ ] **S-8** Every reachable table capped — `T-RES-03`, `T-RES-04`, `T-NEG-30`
- [ ] **S-9** UDP carries beacons only — `T-LINK-07`, `T-LINK-08`
- [ ] **S-10** Bounded handshake pool off `Dispatchers.IO` — `T-LINK-05`
- [ ] **S-11** Frame length validated before allocation — `T-LINK-06`
- [ ] **S-12** Partitioned S&F quotas — `T-CUST-08`
- [ ] **S-13** Strict length, no trailing bytes, `MAX_PAYLOAD_SIZE` enforced on parse — `T-NEG-17`, `T-NEG-18`, `T-NEG-29`
- [ ] **S-14** 256-bit `identityHash` in every transcript and DB key — `T-ID-*`
- [ ] **S-15** Requests demoted to ttl=1 and rate-capped — `T-RES-09` and the request rate tests
- [ ] **S-16** Legacy UUID-IV fallback deleted — codec tests
- [ ] **S-17** Deep link cannot produce `VERIFIED` — `T-TRUST-01..06`
- [ ] **S-18** Announce plaintext fallback deleted — `T-NEG-02`
- [ ] **S-19** Panic wipe uncancellable, ordered, complete — `T-WIPE-01..04`
- [ ] **S-20** Logs stripped in release; `packet_logs` in memory — ProGuard rule + `T-NEG-30`
- [ ] **S-21** Rate-limiter and topology maps pruned — `T-ROUTE-06`, `T-LINK-12`

## C.2 Invariants enforced

- [ ] All 21 invariants (file 1, Section A) have at least one passing test.
- [ ] `T-ARCH-01..03` pass: no handler touches crypto; `AuthenticatedPacket` has one construction site and no public constructor.
- [ ] Every negative test asserts **zero state mutation**, not merely a drop.

## C.3 Protocol correctness

- [ ] Golden vectors exist and pass for all 19 v2 types.
- [ ] `deserialize(serialize(p)) == p` holds under property testing.
- [ ] 1 M-case fuzzing of every parser: no throw, no unbounded allocation.
- [ ] All seven replay outcomes (`T-REP-01..07`) pass.
- [ ] A v1 packet is rejected with no parse attempt; a v1 node and a vNext node are mutually invisible.

## C.4 Resource behaviour

- [ ] 60-second full-spectrum hostile soak (`T-RES-11`): every table at or under its §8 cap, heap bounded, no ANR, no crash.
- [ ] 200 concurrent half-open handshakes: an unrelated DB read completes in < 100 ms.
- [ ] Sustained flood: `processed_packets` and `packet_logs` row counts do not grow.
- [ ] No single limit in the codebase contradicts the §8 table; `ResourceLimits.kt` is the only source of these constants.

## C.5 Data and migration

- [ ] `T-MIG-01..06` pass against a seeded v11 database.
- [ ] `exportSchema = true`; migration test infrastructure in place.
- [ ] `fallbackToDestructiveMigration()` removed; the failure path shows an explicit screen and does not delete data.
- [ ] After panic wipe: `filesDir`, `databases/` and `shared_prefs/` enumerate empty; no notification remains; all three Keystore aliases are gone.
- [ ] No plaintext media file exists anywhere in the app sandbox.

## C.6 Documentation truth

- [ ] Every claim in `docs/SECURITY.md` maps to a passing test ID.
- [ ] `docs/PROTOCOL.md` is sufficient for an independent engineer to build an interoperable node — verified by having someone who did not write the implementation produce a packet that the implementation accepts.
- [ ] `docs/LIMITATIONS.md` states: the O(N²) beacon cost, the ttl=1 location restriction, the MEDIA_CHUNK signature exemption and its residual risk, the nodeId64 collision trade-off, and the loss of multi-hop avatar/profile fetch.
- [ ] `README.md` status table contains no feature marked `SHIPPED` that no code path exercises.
- [ ] `CHANGELOG.md` records the breaking change and the no-rollback warning.

## C.7 Field-test readiness gates

- [ ] `InMemoryMesh` runs a 20-node simulation for 10 virtual minutes with 20% packet loss and periodic partitions: no message silently lost, no unbounded table, no deadlock.
- [ ] 3 physical devices: discovery, DM, ACK, media, voice, SOS, and reconnect-after-partition all verified by hand.
- [ ] 1 device running `MaliciousPeer` against 2 honest devices: no forged message displayed, no identity spoofed, no unbounded resource growth on the honest devices.
- [ ] Battery: 1 hour idle with 2 peers connected, measured and recorded as a baseline — the catch-all BLE scan filter removal should be visible here.
- [ ] A rollback plan is documented and the release notes state plainly that migrated users cannot downgrade.

---

**When every box above is checked, and not before, BIT FOR US vNext is ready for controlled multi-device field testing.**

"Controlled" remains the operative word. Field testing is the next evidence-gathering step, not a declaration that the system is safe for people whose safety depends on it.
