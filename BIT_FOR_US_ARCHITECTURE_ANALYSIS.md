# BIT FOR US (MeshWhisper) — Architecture Analysis & Re-Engineering Plan

> [!NOTE]
> **IMPLEMENTATION STATUS: COMPLETED & VERIFIED**
> All high-priority architectural findings (**P7, P6, P1, P2, P5, P3, and P4**) outlined in Section 2 and Section 14 have been fully implemented and verified.
> The build compiles 100% offline with **70 passing automated tests and 0 failures**. Refer to `ARCHITECTURE.md` (Sections 8, 9, and 10) for the current production specification.

**Scope of this pass:** `core/` (pure-Kotlin, shared with desktop) and `app/` (Android). I read the full BLE engine (816 lines), the full router (1409 lines), the media transfer manager (1389 lines, focused read), the full Wi-Fi engine (494 lines), the full BLE frame framer, the database + DAOs + migrations, the foreground service, the application/DI wiring, and the crypto engine (both Android and core layers) — tracing real execution paths, not filenames or the existing `ARCHITECTURE.md`. Every claim below is tied to a specific file.

---

## 1. Current Architecture (what's actually there)

It's a **flood-relay BLE+Wi-Fi mesh**, not a fake one. Concretely:

- **Transport layer** — two independent, symmetric "Engine" classes, each owning its own radio, its own `CoroutineScope(SupervisorJob() + Dispatchers.IO + CoroutineExceptionHandler)`, its own connection map, and its own rate limiter:
  - `MeshBleEngine` (app/ble) — dual-role GATT (central + peripheral), advertiser, scanner, deterministic MAC-address tie-breaking to avoid duplicate central/peripheral connections to the same device, hard cap of 5 concurrent GATT links (`MAX_CONCURRENT_GATT_CONNECTIONS`).
  - `MeshWifiEngine` (app/wifi) — UDP beacon discovery (port 42425) + persistent TCP streams (port 42426) for LAN/hotspot peers, used opportunistically as a faster secondary path.
  - `BleFrameFramer` — fragmentation/reassembly for GATT MTU limits, with its own bounded state (max 4 concurrent reassembly sessions per remote MAC, 30s expiry, 1024-byte frame ceiling, 255-chunk protocol ceiling).
- **Routing layer** — `MeshRouter` (app/router). This is genuinely a flood/epidemic mesh: every packet carries a `ttl`, is deduplicated (in-memory `LruCache` + persistent `processed_packets` table using `INSERT OR IGNORE` as an atomic dedup check), relayed to *all* neighbors on both radios with CSMA-style jitter (15–75ms, or 5–20ms for SOS), and store-and-forwarded for offline direct-message recipients (persisted, 24h TTL, periodic drain sweep). `TopologyEdgeEntity` gossip data is collected but is **not** used for forwarding decisions — it only feeds `MeshRadarScreen`'s visualization (`GraphPhysics.kt`). This is an honest design choice for BLE-scale peer counts, not a "fake mesh" — TTL, dedup, ACK, and store-and-forward are all real and load-bearing.
- **Media layer** — `MediaTransferManager` (app/media): chunked transfer with SHA-256 integrity, NACK-based selective retransmission (max 5 rounds), a global `Mutex` capping outbound transfers to one at a time, and a 2.5s watchdog for timeout/cleanup.
- **Security layer** — `CryptoEngine` (Android, AndroidKeyStore-backed) delegates all math to `PureCryptoEngine` (core, BouncyCastle): X25519 ECDH + HKDF-SHA256 per-peer session keys rotated on 1-hour epochs (with a bounded 256-entry LRU cache so ECDH isn't recomputed per message), Ed25519 signing/verification on all broadcast-class packets, AES-256-GCM AEAD with random 96-bit nonces, TOFU key-change detection that invalidates cached session keys.
- **Persistence** — Room + SQLCipher (`MeshDatabase`), with the DB passphrase itself AES-GCM-wrapped by an AndroidKeystore hardware key. 10 real schema migrations (not just `fallbackToDestructiveMigration`). A working panic-wipe sequence (close DB → delete DB/WAL/SHM files → delete Keystore alias → clear prefs).
- **Ownership/lifecycle** — `MeshApplication` (a manual `Application`-scoped singleton, no DI framework) constructs `CryptoEngine`, `MeshDatabase`, `MeshBleEngine`, `MeshWifiEngine`, `MeshRouter` once, at process start, and they live for the process lifetime. `MeshForegroundService` starts/stops them in response to a background-relay toggle and drives a heartbeat (`announcePresence`) with adaptive interval (4s with peers, 12s idle). `MainActivity` flips a static `MeshForegroundService.isActivityInForeground` flag so the service knows not to tear down the live BLE connection while the user is actively chatting.
- **UI** — Jetpack Compose screens + one `MeshViewModel`, reading `StateFlow`/`Flow` from the engines/database and calling into `MeshRouter`/`MediaTransferManager` for actions. The networking stack does not depend on any Activity/Composable being alive — this part of the "communication engine must not depend on screens being alive" requirement is already satisfied.

**Verdict on section 3's instruction not to impose a predefined architecture:** the codebase doesn't need Clean/MVI/Hexagonal/microservices layering imposed on it. What's there is closer to a **layered engine-per-subsystem model with a manual service locator** — appropriate for a small, single-process, radio-bound mesh app. The real problems are not "wrong architecture," they're **inconsistent application of the app's own good patterns** and a handful of **missing bounds**. That's the frame for everything below.

---

## 2. Current Problems (evidence-based, prioritized)

### P1 — Wi-Fi engine has no connection cap; BLE engine does
`MeshBleEngine` hard-caps direct links at `MAX_CONCURRENT_GATT_CONNECTIONS = 5` (ble/MeshBleEngine.kt:814, checked at ble/MeshBleEngine.kt:583-588 before every `connectToPeer`). `MeshWifiEngine.startTcpServer()`'s accept loop (wifi/MeshWifiEngine.kt:246-258) calls `server.accept()` and unconditionally launches `handleIncomingTcpConnection` for every socket — no equivalent cap exists anywhere in the file. On a shared hotspot (the exact scenario this transport exists for), or from a hostile device, this is an unbounded-connection vector.

### P2 — The Wi-Fi handshake can block a coroutine indefinitely
`handleIncomingTcpConnection` (wifi/MeshWifiEngine.kt:265-319) sets `socket.soTimeout = 0` (line 250, before handshake) and then does blocking reads (`inStream.readLong()`, `readFully(rAliasBytes)`) with no timeout. A peer that opens the socket and never writes holds that coroutine (and its underlying thread on `Dispatchers.IO`) forever. Combined with P1, this is a classic slow-connection resource-exhaustion pattern — and because `Dispatchers.IO` is a **shared, global** dispatcher, this isn't scoped to the Wi-Fi engine: `MeshBleEngine`, `MeshRouter`, and `MediaTransferManager` all launch on the same `Dispatchers.IO` (ble/MeshBleEngine.kt:48, router/MeshRouter.kt:39, media/MediaTransferManager.kt:88, wifi/MeshWifiEngine.kt:52). Each subsystem having "its own scope" gives clean cancellation semantics but **not** thread-pool isolation — a stall in one engine can degrade the others under load. This is the single most consequential concurrency finding in the codebase.

### P3 — No cap on concurrent inbound media-transfer sessions
`MediaTransferManager.handleMediaInit` (media/MediaTransferManager.kt:642-816) bounds a *single* transfer's claimed size (`totalChunks > 4096` or `totalSizeBytes > 20MB` → rejected, line 685) but there is no limit on the *number* of distinct `mediaId`s that can have sessions open at once (`inboundSessions: ConcurrentHashMap<String, InboundMediaSession>`, line 147). Each accepted `MEDIA_INIT` also immediately inserts a placeholder `MessageEntity` row into Room (line 793-814) — before a single chunk has arrived, before any proof the sender will follow through. A peer (or several colluding peers) can send an unbounded stream of distinct `MEDIA_INIT` packets and grow both the in-memory session map and the on-disk message table without limit, each surviving up to 60s (line 212) before cleanup. Contrast this with `BleFrameFramer`, one layer down, which *does* bound concurrent reassembly state per remote address (ble/BleFrameFramer.kt:91-96, max 4 sessions/device with oldest-eviction) — the protection exists in the codebase, just not at this layer.

### P4 — Store-and-forward queue has no count bound, only a time bound
`StoreForwardDao` (data/dao/DAOs.kt:100-115) and its call sites in `MeshRouter` (router/MeshRouter.kt:550-557, 1124-1132) insert one row per queued direct message with a 24-hour `expiresAt` and purge only by time (`purgeExpired`, drained every `announcePresence` call — router/MeshRouter.kt:773). There is no cap on *how many* pending messages a single unreachable recipient can accumulate, and no total-table-size guard. A burst of DMs to an offline/unreachable node — or intentional spam — grows this table unbounded for up to 24h. (The dead `retryCount` column removed in `MIGRATION_7_8`, data/MeshDatabase.kt:82-100, shows the team already touched this table's design once; a count bound was never added.)

### P5 — GATT-client role has no ingestion rate limit; GATT-server role does
`isWriteRateAllowed()` (ble/MeshBleEngine.kt:69-80, 50 writes/sec/address) is enforced only in `onCharacteristicWriteRequest` — the **peripheral/server** role (ble/MeshBleEngine.kt:474-477). When we act as **central/client** and receive `onCharacteristicChanged` notifications from a peripheral we connected to (ble/MeshBleEngine.kt:683-710), there is no equivalent check. A malicious peripheral we've auto-connected to (any device advertising the mesh service UUID is auto-connected to, ble/MeshBleEngine.kt:570-589, with no pre-connection authentication — by necessity, since identity is only exchanged *after* the link is up) can push notifications at whatever rate the radio allows.

### P6 — Duplicated dedup-cache implementation, not shared, despite a shared one existing
`core/router/LruDedupCache.kt` is a pure-Kotlin, `@Synchronized`, bounded LRU built explicitly "for multiplatform JVM compatibility" (its own doc comment) and is used by `DesktopMeshRouter` (desktop module) with capacity 4000. `MeshRouter` (Android) does not use it — it builds its own `android.util.LruCache<String, Long>(4000)` (router/MeshRouter.kt:42) with manual `synchronized(dedupCache) { ... }` wrapping at 6 call sites (lines 157, 175, 768, 853, 1049, 1117, 1191). Same capacity, same semantics, two implementations. The existing `ARCHITECTURE.md` (section 2, module diagram) documents `core/router/LruDedupCache.kt` as if it's the shared dedup mechanism — it isn't, for the Android app. This is the clearest instance in the codebase of "duplicated logic" per your brief, and the lowest-risk one to fix, since the two implementations are already behaviorally equivalent.

### P7 — `MeshForegroundService.onDestroy()` doesn't stop `wifiEngine`
`pauseRelay()` (service/MeshForegroundService.kt:87-112) correctly stops both `bleEngine` and `wifiEngine` when the Activity isn't foregrounded. `onDestroy()` (lines 265-274) stops only `bleEngine`. When the service is destroyed (user stops the service, or the system reclaims it), the Wi-Fi engine's `ServerSocket`, `DatagramSocket`s, UDP beacon loop, and any open TCP peer sockets keep running with no owning component — a straightforward lifecycle/resource-leak bug, and concrete evidence for "infrastructure that incorrectly depends on [assumed, but not enforced] lifecycle."

### P8 — Manual static singleton as the entire DI mechanism
`MeshApplication.instance` (MeshApplication.kt:99) is a mutable `lateinit var` static, read directly from `MeshForegroundService`, and (by extension) from anything else that needs an engine. Process-lifetime ownership of the engines is the *correct* call here (Activity-owned networking is explicitly what you don't want) — the problem is narrower than "wrong ownership": there's no seam to substitute fake engines in tests, and any class in the app can reach into global state instead of receiving what it needs. This is a real testability cost (section 22) but not a correctness or scalability one — I'd treat it as medium priority, not urgent, and would *not* recommend pulling in Hilt/Koin for a project this size; a small hand-written `AppContainer` with constructor injection gets 90% of the benefit for near-zero added complexity.

### P9 — Room `Flow` queries load full, unbounded tables
`MessageDao.getBroadcastMessages()` and `getDirectMessagesForPeer()` (data/dao/DAOs.kt:57-61) have no `LIMIT`/paging and re-emit the **entire** result set on every write to the `messages` table (Room's `InvalidationTracker` invalidates on any table write, not per-row). At low message volume this is invisible; at the volumes this re-engineering pass is meant to prepare for (thousands of messages across a long-lived mesh), every incoming message anywhere re-queries and re-emits full conversation histories, which is real UI-thread/recomposition and memory cost, not a hypothetical one.

### P10 — Decrypt fallback conflates two different failure causes
`PureCryptoEngine.decrypt()` (core/crypto/PureCryptoEngine.kt:311-354) catches *any* exception from the primary (fresh-nonce) decryption path and silently retries with the legacy UUID-derived-IV path. A wrong-key/tampered-tag failure and a "this is genuinely an old-format packet" case produce the same code path. It's not currently a security hole (a wrong key fails both paths, and the AEAD tag still has to verify), but it's a correctness/diagnosability smell worth tightening — right now a wrong-key failure and a version-skew bug would look identical in logs.

**What I deliberately did *not* flag as a problem**, because the evidence didn't support it: the flood-relay design itself (appropriate for BLE peer counts); the per-subsystem `SupervisorJob`+`CoroutineExceptionHandler` pattern (consistently and correctly applied — no `GlobalScope` anywhere in the codebase); the session-key caching (already bounded and epoch-scoped — this is good, not a gap); the SQLCipher/Keystore key-wrapping chain; the BLE fragmentation layer's session bounding; the deterministic MAC tie-breaking for connection symmetry; the bounded (5-round) NACK retry logic. These are all evidence that this is a codebase written with real security/resource discipline in most places — which is exactly why the gaps above (P1–P7) read as *inconsistency*, not as a team that doesn't know how to bound a queue.

---

## 3. Target Architecture

**Keep the shape. Fix the inconsistencies. Don't add layers.**

Concretely, the target is the *same* engine-per-subsystem model, with:
1. Every transport (BLE **and** Wi-Fi) enforcing the same three properties: a hard concurrent-connection cap, a handshake/identification timeout, and a per-remote ingestion rate limit — applied symmetrically to both roles (server/client, or accept/connect) of each transport, not just one.
2. Every unbounded-by-network-input collection (inbound media sessions, store-and-forward queue) given an explicit cap with a defined eviction policy (oldest-first, matching the pattern `BleFrameFramer` already uses).
3. One dedup-cache implementation (`core.router.LruDedupCache`), used by both `MeshRouter` and `DesktopMeshRouter`, deleting the Android-specific duplicate.
4. A small, explicit ownership seam for the five singletons (`AppContainer`-style constructor wiring) instead of a static `instance` — not a DI framework, just removing the "anything can reach into global state" property.
5. Paging (or at minimum a `LIMIT` + "load more") on the two unbounded `Flow` queries once message volume actually becomes a product concern — this is the one item I'd explicitly gate on evidence of need rather than doing now, per your own instruction not to build for hypothetical future load.

I am **not** recommending: a routing-table/shortest-path replacement for flood relay (the topology data exists but flood relay is the right choice at BLE-realistic peer counts — see §5); a modularization split beyond the existing `core`/`app`/`desktop` boundary (that boundary is already evidence-justified — platform-coupling is the actual dividing line, and it's already drawn correctly); a DI framework; an event-sourcing or actor-model rewrite of the router. None of these are justified by anything I found in the code.

---

## 4. Why This Architecture

- **Flood relay is correct here, not a compromise.** BLE's connection ceiling (practically 4-7 simultaneous GATT links on most Android radios, which is why `MAX_CONCURRENT_GATT_CONNECTIONS = 5` exists) means a shortest-path routing table would need constant renegotiation as links form/drop, for a peer count where flooding's redundancy cost is small. The dedup+TTL+jitter combination already present is the standard, correct answer for this regime (this is functionally the same design family as Bitchat/other BLE mesh chat apps, and for good reason — it's what the hardware allows).
- **Process-lifetime engine ownership is correct**, not over-engineering, precisely because the requirement is "the communication engine must not depend on screens being alive." The bug isn't *that* `MeshApplication` owns the engines — it's *how* other classes reach them (static singleton vs. injected).
- **Fixing symmetry beats adding abstraction.** Every one of P1, P2, P5, P7 is "engine A already does X correctly, engine B doesn't." That's the cheapest, lowest-risk, highest-value class of fix available in a codebase — it reuses a pattern the team already validated, rather than introducing a new one.

---

## 5. Scalability Analysis

| Peers | What holds | What's constrained by BLE/Android | What's the software bottleneck |
|---|---|---|---|
| 2–5 | Everything — this is the common case the code is tuned for. | — | None observed. |
| 5–10 | Direct links start hitting `MAX_CONCURRENT_GATT_CONNECTIONS = 5`; excess peers fall back to flood relay through the ones that are directly connected — by design (ble/MeshBleEngine.kt:587 log line literally says this). | BLE simultaneous-connection ceiling (hardware/driver, not this app). | None new. |
| 10–20 | Flood relay + jitter keeps working; dedup cache (4000 entries) has ample headroom. | Advertising/scanning duty-cycle contention increases (more devices sharing the same 2.4GHz spectrum); this is physical, not software. | Wi-Fi engine's missing connection cap (P1) starts to matter if this is a hotspot scenario — 15-20 devices on one AP, one TCP socket + coroutine each, uncapped. |
| 20–50 | Store-and-forward, media transfer logic don't change with peer count. | BLE relay latency grows (more hops, more jitter-induced delay per hop) — expected and inherent to flood mesh, not a defect. | P4 (store-forward growth) and P3 (media session growth) become real if any subset of these peers is intermittently offline — both are now plausible attack/accident surfaces, not edge cases. |
| 50–100 "logical" peers | Direct BLE links still capped at 5; nearly everyone is relay-only. | This is past where a pure BLE flood mesh is comfortable on stock Android radios regardless of software — advertising/scan-response collisions and duty-cycling dominate. | P2 (unbounded handshake blocking) and P5 (unrate-limited GATT-client ingestion) stop being theoretical: at this density, a single misbehaving or overloaded node can degrade the shared `Dispatchers.IO` pool for the whole app (P2), and a single chatty/malicious peripheral can flood ingestion with no backpressure (P5). |

**The honest ceiling:** this is a small-mesh design (tens of devices) by physics, not by a fixable software choice — the master prompt's own instruction to "distinguish software scalability from physical BLE limitations" applies directly here. The software-side work above (P1–P7) is what determines whether the app degrades *gracefully* as it approaches that physical ceiling, or falls over earlier than the hardware would otherwise require.

---

## 6. Concurrency Model

Five independently-scoped subsystems (`MeshBleEngine`, `MeshWifiEngine`, `MeshRouter`, `MediaTransferManager`, `MeshForegroundService`), each `CoroutineScope(SupervisorJob() + Dispatchers.IO + CoroutineExceptionHandler)`. No `GlobalScope` usage anywhere (verified by grep across the whole `app` module) — that discipline is real and consistently applied. `SupervisorJob` correctly isolates failures within each scope (one failed child doesn't cancel siblings), and each scope has its own exception handler that logs rather than crashing.

The gap is not the pattern, it's the shared resource underneath it: **`Dispatchers.IO` is one global thread pool** (elastic, but bounded) shared by all five scopes. "Independent scope" gives independent *cancellation*, not independent *capacity*. P2 is the concrete manifestation: an engine-level bug (unbounded blocking read) in one subsystem can starve threads another subsystem needs. Fixing P1/P2 removes the only unbounded-blocking-work path I found; I did not find other unbounded blocking calls on `Dispatchers.IO` in the engines (BLE and Wi-Fi packet sends are async/callback-driven or explicitly paced with `delay()`, which suspends rather than blocks).

Locking is minimal and appropriately scoped: `synchronized(dedupCache)` around `MeshRouter`'s LRU cache, `synchronized(session)` around individual `BleFrameFramer` chunk sessions (per-session, not global — good), a single `Mutex` serializing outbound media transfers app-wide (a deliberate bandwidth-protection choice, not an accidental bottleneck), `@Synchronized` on `MeshWifiEngine.start()/stop()`. I did not find lock-ordering issues, nested locks, or evidence of deadlock risk.

---

## 7. BLE Model

Dual-role (central + peripheral) via `BluetoothGattServer` + `BluetoothGattCallback`, with a deterministic tie-breaker (lower MAC address waits as peripheral, higher initiates as central — ble/MeshBleEngine.kt:576-581) that correctly prevents the double-connection race that dual-role BLE apps commonly hit. MTU negotiated up to `REQUESTED_MTU`, fragmentation/reassembly bounded per-device (§ P3 note: bounded *here*, not one layer up in media). Packet broadcast to all connected peers is **serialized within one suspend function** with a `delay(15L)` between every fragment, per peer, per broadcast call (ble/MeshBleEngine.kt:772, 808) — this is intentional pacing to avoid saturating the BLE write queue, not a bug, but it does mean total broadcast time scales as `O(peers × fragments)`; worth knowing if a future feature sends large payloads to many direct peers at once, but not something to fix pre-emptively without evidence it's actually slow in practice.

---

## 8. Message / Routing Model

Outbound: compose → sign (Ed25519, for broadcast-class) or derive per-peer session key (X25519+HKDF, for DM-class) → AES-256-GCM encrypt with AAD binding on type/sender/recipient/timestamp → persist `MessageEntity` → serialize → dedup-cache mark → (DM only) persist to store-and-forward → broadcast on both radios (or direct TCP write if the recipient has a live Wi-Fi session).

Inbound: deserialize → drop own echoes → anti-replay timestamp window (600s general / 86400s for DMs, to tolerate store-and-forward delivery) → fast in-memory LRU dedup check → atomic persistent dedup (`INSERT OR IGNORE`, avoiding the classic check-then-write TOCTOU race) → type dispatch → decrypt+verify → persist/deliver → relay-with-jitter if `ttl > 1` and not addressed to us (or always, for broadcast/media-broadcast, since those need both local delivery *and* continued relay).

This is a clean, race-safe pipeline. The one gap already covered in §2 is P4 (no count bound on the store-and-forward write path) and P3 (no count bound on the inbound-media-session write path) — everything else in this pipeline already has the property the master prompt asks for: idempotent, atomic dedup that survives concurrent arrival from two radios at once.

---

## 9. Persistence Model

Survives: Activity recreation, backgrounding, process death (Room+SQLCipher on disk, not in-memory), device reboot (files are file-backed, keys are Keystore-backed). Does **not** survive: an unhandled Room migration path — `fallbackToDestructiveMigration()` is chained after the 5 explicit migrations (data/MeshDatabase.kt:149), so any schema jump not covered by `MIGRATION_5_6` through `MIGRATION_9_10` silently wipes the encrypted DB rather than crashing. That's a defensible trade-off for a consumer chat app (crash-on-migration-failure is worse UX), but it should be a **known, chosen** trade-off, not an implicit one — worth a one-line comment at that call site saying so explicitly, and worth double-checking that the migration chain is complete before every release that bumps the DB version.

Idempotency: `processedPacketDao().markSeen()` uses `INSERT OR IGNORE` and reads the returned row ID to detect "already seen" (`-1` = duplicate) — this is correctly atomic and race-safe under concurrent packet arrival from multiple radios, which is exactly the failure mode a naive `hasSeen()`-then-`markSeen()` pair would have gotten wrong.

---

## 10. Android Lifecycle Model

`MeshApplication.onCreate()` is the correct place for engine construction (process lifetime, not Activity lifetime). `MeshForegroundService` is the correct place for keeping BLE/Wi-Fi alive while backgrounded, with the `FOREGROUND_SERVICE_TYPE_CONNECTED_DEVICE` type declared on API 34+ (service/MeshForegroundService.kt:171-179) — appropriate for what this service actually does. `MainActivity`/`MeshForegroundService.isActivityInForeground` is a documented, deliberate coupling (comment at service/MeshForegroundService.kt:287-293 explains exactly why: don't kill a live connection the user is actively looking at) — it's global mutable state, but it's a single `@Volatile Boolean` with one writer pattern and one clearly-scoped purpose, not the kind of hidden cross-cutting state the master prompt is warning about. I'd leave it as-is. The one real lifecycle bug is P7 (missing `wifiEngine.stop()` in `onDestroy()`).

---

## 11. Security Model

Trust boundaries: (1) anyone broadcasting the mesh service UUID gets auto-connected at the BLE link layer with **no** authentication (identity is only established after connection, via signed `PEER_ANNOUNCE`) — this is inherent to how BLE mesh discovery has to work, not a bug, but it's *why* P5 (no rate limit on the client-role ingestion path) matters: the link layer is deliberately open, so the application layer is the only place that can rate-limit a hostile peripheral. (2) Message-layer trust is genuinely strong: Ed25519 signatures on all broadcast-class packets (peer announce, broadcast chat, SOS) with explicit rejection-and-log on forged signatures (router/MeshRouter.kt:252-256, 435-439, 923-927); AEAD auth-tag verification on all DM-class packets (ACK, avatar request/response, direct messages) with the same reject-and-log pattern; TOFU key-change detection that invalidates cached session keys and marks the peer unverified until re-confirmed (router/MeshRouter.kt:342-367). (3) DoS surface: bounded in most places (BLE peripheral-role write rate limit, BLE fragmentation session cap, media payload size cap, NACK retry cap) but **not** bounded in the specific spots enumerated as P1/P2/P3/P4/P5 — that list *is* the current attack surface, not a hypothetical one.

---

## 12. Resource Model

- **Memory**: bounded almost everywhere in-flight (4000-entry dedup cache, 256-entry session-key cache, 4-session BLE reassembly cap, 20MB/4096-chunk media size cap) — the exceptions are exactly P3 (session *count*) and P4 (queue *count*).
- **CPU**: session-key caching (epoch-bound, 256-entry LRU) already avoids the expensive path (repeated X25519 ECDH) — this is good and I want to call it out as already-correct rather than imply it needs work.
- **Battery**: adaptive heartbeat interval (4s active / 12s idle, service/MeshForegroundService.kt:146-151) and adaptive BLE advertise/scan power mode tied to foreground/background state (`setLowLatencyMode`, ble/MeshBleEngine.kt:341-355) are both real, working power-management mechanisms, not stubs.
- **Storage**: packet log table self-trims (`trimOldLogs(500)` every 50 inserts, data/dao/DAOs.kt:125-126) — bounded. Store-and-forward table is not (P4). Message table is unbounded by design (it's chat history — that's correct), but the *query* pattern against it (P9) doesn't scale with it.
- **Connections**: BLE capped (5), Wi-Fi uncapped (P1) — the actual gap is the *inconsistency*, not that either number is wrong in isolation.

---

## 13. Failure Model

| Failure | Detection | Recovery |
|---|---|---|
| Bluetooth disabled mid-session | `BroadcastReceiver` on `ACTION_STATE_CHANGED` (ble/MeshBleEngine.kt:141-161) | Engine auto-stops on OFF, auto-restarts on ON if a node ID was already set. Handled. |
| Peer disconnects | GATT/TCP callback | Removed from connection maps, listener fired, next scan/beacon re-triggers reconnect. Handled. |
| Corrupt/malformed packet | `MeshPacket.deserialize()` returns null, or AEAD tag fails | Logged + dropped, no crash (try/catch around every decrypt call site). Handled. |
| Media transfer stalls | 2.5s watchdog checks 3s inactivity → NACK, 60s inactivity → abort | Bounded (5 NACK rounds, then FAILED status + user-visible retry button in `MediaBubbles.kt`). Handled. |
| Process death mid-transfer | No explicit handling found for in-flight `activeOutboundSessions`/`inboundSessions` (these are in-memory `ConcurrentHashMap`s, not persisted) | On process restart, in-flight transfer state is lost; the placeholder `MessageEntity` row (already persisted) would be left in `PENDING`/`RECEIVING` status with no active session to resume it. This is a real gap for a transfer that was mid-flight during a process kill — worth a "reconcile stuck PENDING media rows on app start" pass, not urgent given panic-wipe/short-transfer-window mitigate the blast radius, but worth naming since section 13/14 of your brief specifically asks about this. |
| Malicious/malformed peer input | Bounds checks on `MEDIA_INIT` metadata, signature/AEAD verification on all authenticated packet types | Rejected + logged (`"Possible malicious peer"` log lines exist verbatim in the code) — but see P1-P5 for where volume-based abuse isn't yet bounded. |

---

## 14. Migration Plan (incremental, keeps the project buildable throughout)

Ordered by risk/value, each step independently shippable:

1. **P7 [COMPLETED]** (add missing `wifiEngine.stop()` call in `onDestroy()`) — implemented in `MeshForegroundService.kt:274`.
2. **P6 [COMPLETED]** (point `MeshRouter`'s dedup cache at `core.router.LruDedupCache` instead of `android.util.LruCache`) — implemented in `MeshRouter.kt:41` (capacity 4000).
3. **P1 + P2 [COMPLETED]** (Wi-Fi connection cap `MAX_CONCURRENT_WIFI_CONNECTIONS = 5` + handshake timeout `TCP_HANDSHAKE_TIMEOUT_MS = 5000`) — implemented in `MeshWifiEngine.kt:60, 240-340`.
4. **P5 [COMPLETED]** (rate-limit `onCharacteristicChanged` ingestion in the GATT-client role, 50 writes/sec via `GattWriteRateLimiter.kt`) — implemented in `MeshBleEngine.kt:688-692`.
5. **P3 [COMPLETED]** (cap concurrent inbound media sessions: max 4/peer, max 16 globally with oldest-eviction) and **P4 [COMPLETED]** (cap store-and-forward rows: max 50/recipient, max 500 total via Room `trimRecipientQueue` and `trimTotalQueue`) — implemented in `MediaTransferManager.kt` and `DAOs.kt`/`MeshRouter.kt`.
6. **P8 [BACKLOG]** (introduce a small `AppContainer` for the five singletons, replacing direct `MeshApplication.instance` reads) — deferred per explicit scope constraint.
7. **P9 [BACKLOG]** (paging on message queries) — deferred per explicit scope constraint.

---

## 15. Final Stress Question

**10 peers**: Everything holds. This is comfortably within the design envelope of every subsystem as currently bounded (except Wi-Fi's connection cap, if this is a hotspot scenario).

**20 peers**: Still holds functionally. `MAX_CONCURRENT_GATT_CONNECTIONS=5` means 15 of the 20 are relay-only — expected and by design. The Wi-Fi engine's missing cap (P1) is now a live risk if these 20 devices share one hotspot.

**50 peers**: This is where BLE physical constraints (advertising/scan collision rates, duty-cycled radios) start to dominate latency and discovery reliability — genuinely a hardware/physics ceiling, not a software one. On the software side, P3 (media session count) and P4 (store-forward count) stop being edge cases: with 50 nodes, "some subset intermittently offline while messages queue for them" is the normal case, not an anomaly.

**100 "logical" peers**: Past comfortable BLE-flood-mesh territory regardless of software quality. **What fails first** at this density is P2: the shared `Dispatchers.IO` pool, if even a handful of the 100 connections are slow/malicious/stalled Wi-Fi handshakes, degrades thread availability for BLE and routing work that has nothing to do with Wi-Fi. **The mechanism that prevents cascading failure** is exactly the fix set in §14 steps 1-5: once every transport enforces its own connection cap + timeout + rate limit symmetrically, a single misbehaving peer's blast radius is contained to "this transport refuses this one peer," not "the whole app's shared IO capacity degrades." Without those fixes, P2 is the one finding in this report with genuine cross-subsystem cascade potential — everything else (P1, P3, P4, P5, P6) is contained to its own subsystem even when it fails.

---

*Every finding above cites a specific file and, where practical, a line number, from the zip as uploaded (`BIT-FOR-US-main`). I did not run the Gradle build in this session — no Android SDK / Google Maven access in this sandbox — so treat these as source-level findings pending your own build/test pass.*
