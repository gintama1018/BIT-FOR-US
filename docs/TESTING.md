# BIT FOR US — Verification & Testing Specification

Version: **v1.4 (Synchronized with Codebase)**  
Last Updated: **September 2026**

---

## 1. Executive Testing Summary

All automated tests build and pass **100% offline** without requiring active internet connectivity, external mock servers, Android emulators, or physical Bluetooth hardware.

```powershell
gradlew.bat test
```

```
BUILD SUCCESSFUL in 4m 22s
59 actionable tasks: 15 executed, 44 up-to-date
```

| Module | Passing Unit Tests | Failures | Skipped | Status |
| :--- | :---: | :---: | :---: | :---: |
| **`:core`** (Pure Kotlin JVM) | **38** | **0** | **0** | **100% PASS** |
| **`:app`** (Android Unit Tests) | **77** | **0** | **0** | **100% PASS** |
| **`:desktop`** (Companion JVM Station) | **3** | **0** | **0** | **100% PASS** |
| **TOTAL** | **118** | **0** | **0** | **100% PASS** |

---

## 2. Test Suite Breakdown by Subsystem

### 2.1. Core Module (`:core` — 38 Tests)
Located in [`core/src/test/java/com/meshwhisper/core/`](file:///c:/Users/hp/Downloads/BIT%20FOR%20US/core/src/test/java/com/meshwhisper/core/):

- **`router/MeshRouteEngineTest.kt` (8 tests)**:
  - 1-hop direct route resolution.
  - 2-hop linear path ($A \to B \to C$) resolution.
  - 4-node linear multi-hop chain ($A \to B \to C \to D$).
  - Diamond topology shortest-path selection ($A \to B \to D$ vs $A \to C \to D$).
  - Dynamic link failure quarantine and failover rerouting.
  - Topology edge expiration after 120 seconds.
  - Acyclic loop prevention in cyclic graphs.
  - Reachable route discovery across disconnected subgraphs.
- **`audio/AdpcmAndJitterBufferTest.kt` (5 tests)**:
  - Exact 4:1 compression ratio (160 samples / 320 bytes PCM $\to$ 80 bytes ADPCM).
  - 440 Hz test tone waveform reconstruction fidelity (quantization error verification).
  - Extreme amplitude clamping and silence encoding.
  - Jitter buffer 40–80ms preloading and in-order popping.
  - Out-of-order packet reordering and duplicate/late frame dropping.
  - Packet loss skipping without stalling audio pipeline.
- **`protocol/TrafficControllerTest.kt` (5 tests)**:
  - Emergency Tier 0 (SOS) preemption over standard and bulk queues.
  - High interactive Tier 1 (ACK, Voice) scheduling.
  - Anti-starvation deficit-weighted scheduling.
  - Bounded queue capacity enforcement (max 100 packets/tier).
  - Packet lifetime expiration drop (30s lifetime).
- **`protocol/ProfilePayloadTest.kt` (4 tests)**:
  - Canonical binary framing serialization and parsing (`PROF` magic).
  - Ed25519 digital signature generation and verification.
  - Monotonically increasing version counter validation.
  - Forged signature detection and rejection.
- **`protocol/CoreProtocolAndCryptoTest.kt` (8 tests)**:
  - 56-byte header binary serialization and deserialization.
  - Additional Authenticated Data (AAD) generation and tamper detection.
  - Key agreement and AEAD encryption/decryption roundtrip.
- **`MultiHopRelayAndSecurityMeshTest.kt` (8 tests)**:
  - Multi-hop relay propagation.
  - Channel isolation using PBKDF2-derived keys.
  - Replay attack rejection.

---

### 2.2. Android App Module (`:app` — 77 Tests)
Located in [`app/src/test/java/com/meshwhisper/app/`](file:///c:/Users/hp/Downloads/BIT%20FOR%20US/app/src/test/java/com/meshwhisper/app/):

- **`voice/VoiceCallManagerTest.kt` (10 tests)**:
  - Direct 1-hop link prerequisite verification (call start fails if peer is not direct).
  - Outgoing call setup (`OFFER` signal generation with `ttl = 1`).
  - Incoming call offer handling and ringing state transition.
  - Call accept flow: transition to `CONNECTED`, `ANSWER` signal generation, audio stream start.
  - Call decline flow: transition to `ENDED`, `DECLINE` signal generation.
  - Busy rejection: third-party caller receives `BUSY` signal while active call is maintained.
  - Ringing timeout: automatic call termination after 30 seconds of unheeded ringing.
  - Direct link loss detection: immediate call termination (`CallEndReason.LINK_LOST`) when BLE/Wi-Fi disconnects.
  - Real-time voice frame routing directly to audio stream.
  - Real-time microphone mute and speakerphone routing toggles.
- **`router/DirectedStoreForwardAndPriorityTest.kt` (6 tests)**:
  - Traffic priority mapping across all 17 packet types.
  - Voice packets (`VOICE_CALL_SIGNAL` and `VOICE_FRAME`) mapped to Tier 1 (`HIGH_INTERACTIVE`).
  - Voice queue preemption over chat messages and bulk media.
  - Directed store-and-forward negative property (never broadcasts direct store-and-forward drainage).
  - Voice packets strict 1-hop invariant ($ttl = 1$).
- **`router/MultiHopRelayReliabilityTest.kt` (6 tests)**:
  - Full end-to-end delivery: $A \to B \to C \to ACK \to B \to A$.
  - Lost-ACK recovery: retransmitted DM causes re-emission of delivery ACK without duplicate DB rows.
  - Dynamic relay failover: disappearing relay $B$ automatically switches path through alternative node $D$.
  - Intermediate relay custody handoff and queue drainage.
  - TTL decrement and drop at 0.
  - Ingress split-horizon filter preventing broadcast echo loops.
- **`router/ProfileAntiRollbackTest.kt` (4 tests)**:
  - Profile update acceptance with higher version number.
  - Profile rollback rejection when update presents $\text{version} \le \text{current}$.
  - Forged profile signature rejection.
  - Avatar hash update detection.
- **`crypto/CryptoTest.kt` (8 tests)**:
  - X25519 ECDH key agreement.
  - Ed25519 digital signature signing and verification.
  - 1-hour epoch session key rotation.
  - AES-256-GCM AEAD encryption and decryption.
  - Safety number calculation and fingerprint consistency.
- **`media/ReliableTransferTest.kt` & `MediaTransferTest.kt` (12 tests)**:
  - Media session initialization (`MEDIA_INIT`).
  - Chunked transfer, SHA-256 hash verification.
  - Selective retransmission via NACK.
  - Transfer abort and timeout cleanup.
- **`ble/BleFrameFramerTest.kt` & `GattWriteRateLimiterTest.kt` (8 tests)**:
  - Frame chunking across MTU boundaries.
  - Ingress rate limiter enforcement (50 writes/sec).
- **`wifi/WifiConnectionLimitTest.kt` (5 tests)**:
  - TCP connection limit enforcement (max 8 concurrent sockets).
  - Handshake socket timeout protection.
- **`ui/graph/GraphPhysicsTest.kt` & `SecurityAndRoutingTest.kt` (18 tests)**:
  - Radar force-directed physics computation.
  - AAD tamper detection and packet serialization boundaries.

---

### 2.3. Desktop Module (`:desktop` — 3 Tests)
Located in [`desktop/src/test/java/com/meshwhisper/desktop/`](file:///c:/Users/hp/Downloads/BIT%20FOR%20US/desktop/src/test/java/com/meshwhisper/desktop/):
- SQLite embedded database storage and retrieval.
- Desktop Wi-Fi packet processing.
- Multiplatform router integration.

---

## 3. How to Run the Test Suite

### Run All Tests
```powershell
# Windows (PowerShell / Command Prompt)
set JAVA_HOME=C:\Program Files\Microsoft\jdk-17.0.20.8-hotspot
.\gradlew.bat test

# macOS / Linux
export JAVA_HOME=/path/to/jdk-17
./gradlew test
```

### Run Core Module Tests Only
```powershell
.\gradlew.bat :core:test
```

### Run Android App Unit Tests Only
```powershell
.\gradlew.bat :app:testDebugUnitTest
```

---

## 4. Real-Device Validation Status Matrix

To maintain rigorous technical transparency, we explicitly document the validation maturity of each layer:

| Subsystem | JVM Unit Tests | Android Emulator | Physical Device (1-to-1) | Physical Mesh (Multi-Device) |
| :--- | :---: | :---: | :---: | :---: |
| **Packet Protocol & Serialization** | ✅ Verified (100%) | ✅ Verified | ✅ Verified | ✅ Verified |
| **Cryptographic Engine (ECDH, AEAD)** | ✅ Verified (100%) | ✅ Verified | ✅ Verified | ✅ Verified |
| **Dijkstra Dynamic Routing Engine** | ✅ Verified (100%) | ✅ Verified | ⚠️ Bench Tested | ⏳ Field Trial Pending |
| **Directed Forwarding & Relay Custody**| ✅ Verified (100%) | ✅ Verified | ⚠️ Bench Tested | ⏳ Field Trial Pending |
| **QoS Traffic Controller (4 Tiers)** | ✅ Verified (100%) | ✅ Verified | ✅ Verified | ⏳ Field Trial Pending |
| **Profile Signing & Anti-Rollback** | ✅ Verified (100%) | ✅ Verified | ✅ Verified | ⏳ Field Trial Pending |
| **IMA ADPCM Audio Codec** | ✅ Verified (100%) | ✅ Verified | ✅ Verified | N/A (1-hop only) |
| **Bounded Jitter Buffer** | ✅ Verified (100%) | ✅ Verified | ✅ Verified | N/A (1-hop only) |
| **1-Hop Real-Time Voice Calls** | ✅ Verified (100%) | ✅ Verified | ⚠️ Bench Tested | N/A (1-hop only) |
| **BLE GATT Multi-Connection Stability** | ⚠️ Mocked/Isolated | ✅ Functional | ⚠️ Chipset-dependent | ⏳ Dense RF Trial Pending |
| **Wi-Fi Socket Discovery & Streaming** | ✅ Verified (100%) | ✅ Verified | ✅ Verified | ⏳ Field Trial Pending |
