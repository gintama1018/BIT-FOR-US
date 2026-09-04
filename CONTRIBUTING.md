# Contributing to MeshWhisper / BIT FOR US

Thank you for your interest in contributing to **MeshWhisper**! This project is an open-source, 100% offline, peer-to-peer mesh communications platform operating over hybrid Bluetooth Low Energy (BLE) and offline Wi-Fi local networks.

---

## 1. Engineering Philosophy & Core Principles

Before submitting code or documentation, please understand our foundational design tenets:

1. **Code is the Source of Truth**: Documentation, claims, and specifications must reflect the exact implementation in the codebase. Never document planned, theoretical, or aspirational features as shipped.
2. **100% Offline by Design**: MeshWhisper must function without internet access, cellular networks, SIM cards, cloud dependencies, or central servers. Any pull request introducing a cloud or third-party server dependency will be closed immediately.
3. **Zero Android Dependencies in `:core`**: The `:core` module is a pure Kotlin JVM library. It must never import `android.*` classes. It must compile and run on any standard JVM runtime (Android, desktop, embedded).
4. **Strict Scope Discipline**:
   - **Voice Communication**: Strictly constrained to **direct 1-hop** ($ttl = 1$). Voice packets (`VOICE_CALL_SIGNAL`, `VOICE_FRAME`) are volatile real-time streams and must **never** be routed into multi-hop store-and-forward queues or persisted to the Room database.
   - **Multi-Hop Relaying**: Text, emergency broadcasts, presence, and chunked media traverse the multi-hop mesh via directed Dijkstra next-hop routing with LRU deduplication and custody acknowledgment.
5. **Zero-Trust Cryptographic Invariants**: All packets must either be authenticated broadcasts (Ed25519 signed) or end-to-end encrypted (X25519 ECDH + AES-256-GCM AEAD) with associated data (AAD) binding the packet header. Never log private keys, ephemeral seeds, or raw decrypted payloads.

---

## 2. Repository Structure & Module Boundaries

The codebase is organized into three decoupled Gradle modules:

```
MeshWhisper/
├── core/       # Pure Kotlin JVM library (protocol wire framing, pure crypto, LRU cache)
├── app/        # Android application (BLE GATT, Wi-Fi UDP/TCP, Room v11, Jetpack Compose, audio)
└── desktop/    # JVM station console (pure Java sockets, SQLite, CLI management)
```

### Module Responsibilities & Constraints

| Module | Permitted Dependencies | Forbidden Dependencies | Primary Responsibilities |
| :--- | :--- | :--- | :--- |
| **`:core`** | Kotlin Stdlib, Coroutines, BouncyCastle (`bcprov-jdk18on`) | `android.*`, AndroidX, Jetpack Compose, Room, SQLCipher | 56-byte wire framing (`MeshPacket`), `PureCryptoEngine`, `LruDedupCache`, `MeshLogger` |
| **`:app`** | AndroidX, Jetpack Compose, Room, SQLCipher, CameraX, BouncyCastle | Cloud SDKs, external analytics, remote push notification libraries | `MeshBleEngine`, `MeshWifiEngine`, `MeshRouter`, `VoiceCallManager`, `AdpcmCodec`, `MeshDatabase` |
| **`:desktop`** | Kotlin Stdlib, Coroutines, BouncyCastle, `sqlite-jdbc` | Android SDK, GUI heavy frameworks | `DesktopWifiEngine`, `DesktopMeshRouter`, `DesktopDatabase`, CLI console |

---

## 3. Development Environment & Setup

### Prerequisites

- **Java Development Kit (JDK)**: JDK 17 (Microsoft Build of OpenJDK 17 or Eclipse Temurin 17 recommended).
- **Android SDK**:
  - `compileSdk`: **35** (Android 15)
  - `minSdk`: **26** (Android 8.0 Oreo)
  - `targetSdk`: **35**
- **Android Studio**: Android Studio Koala Feature Drop (2024.1.2) or Ladybug (2024.2.1)+.
- **Gradle**: Wrapper provided (Gradle 8.11.1).

### Initializing the Project

1. **Clone the Repository**:
   ```bash
   git clone https://github.com/gintama1018/BIT-FOR-US.git
   cd BIT-FOR-US
   ```
2. **Verify JDK Setup**:
   Ensure `JAVA_HOME` points to JDK 17:
   ```bash
   # Windows PowerShell
   $env:JAVA_HOME
   java -version
   ```
3. **Build the Project**:
   ```bash
   # Windows
   .\gradlew.bat assembleDebug

   # Linux / macOS
   ./gradlew assembleDebug
   ```

---

## 4. Testing Requirements

We maintain a strict automated testing policy. **All pull requests must pass 100% of the unit test suite offline before merge.**

### Running Tests Locally

Run the complete test suite across all modules:

```bash
# Windows
.\gradlew.bat test

# Linux / macOS
./gradlew test
```

To run module-specific test suites:

```bash
# Core pure JVM tests (38 tests)
.\gradlew.bat :core:test

# Android app local JVM tests (77 tests)
.\gradlew.bat :app:testDebugUnitTest

# Desktop station JVM tests (3 tests)
.\gradlew.bat :desktop:test
```

### Test Standards & Guidelines

- **Zero Network Flakiness**: Tests must run completely offline without listening on external internet interfaces or accessing remote servers.
- **Deterministic Concurrency**: Coroutine tests must use `runTest`, `StandardTestDispatcher`, or virtualized time clocks to prevent race conditions and timeout flakes.
- **Transports Mocking**: Transport engines (`MeshBleEngine`, `MeshWifiEngine`) must be mocked or abstracted when testing routing and store-and-forward behavior.
- **Regression Protection**: Any bug fix must include a test reproducing the original issue and verifying the resolution.

---

## 5. Coding Conventions & Architectural Guidelines

### Kotlin Style
- Follow official [Kotlin Coding Conventions](https://kotlinlang.org/docs/coding-conventions.html).
- Use descriptive naming for cryptographic keys (`identityKeyPair`, `ephemeralKeyPair`, `sharedSecret`).
- Avoid `!!` (force unwrapping); handle nullability explicitly with safe calls (`?.`), Elvis operators (`?:`), or early returns.

### Concurrency & Threading
- **Dispatchers**:
  - `Dispatchers.IO`: File I/O, database queries, cryptographic key derivation/encryption.
  - `Dispatchers.Default`: Intensive computation, routing graph path calculation (Dijkstra).
  - `Dispatchers.Main`: Jetpack Compose UI state updates only.
- **State Synchronization**: Guard shared mutable state using `kotlinx.coroutines.sync.Mutex` or thread-safe atomic primitives (`AtomicBoolean`, `AtomicInteger`). Do not use raw Java `synchronized` blocks inside coroutine scopes.

### Adding New Packet Types
If introducing a new protocol packet type:
1. Register the type code in `com.meshwhisper.core.protocol.PacketType` within `:core`.
2. Update the default TTL, serialization, and deserialization in `MeshPacket.kt`.
3. Verify that `calculateAad()` in `MeshPacket.kt` correctly includes the new type.
4. Add handling in `MeshRouter.kt` (`:app`) and verify prioritization in `TrafficScheduler.kt`.
5. Add unit tests verifying serialization, round-trip parsing, and routing rules.
6. Document the new packet type in [`docs/PROTOCOL.md`](docs/PROTOCOL.md).

---

## 6. Pull Request Process

1. **Fork and Branch**: Create a feature branch off `main` with a descriptive name:
   ```bash
   git checkout -b feature/dynamic-route-metric
   # or
   git checkout -b fix/ble-reassembly-timeout
   ```
2. **Commit Messages**: Use concise, conventional commit prefixes:
   - `feat:` New capability or feature.
   - `fix:` Bug fix or defect resolution.
   - `refactor:` Code reorganization without behavioral change.
   - `test:` Adding or updating tests.
   - `docs:` Documentation corrections or synchronization.
3. **Pre-Submission Checklist**:
   - [ ] Project builds successfully (`assembleDebug`).
   - [ ] All 118 automated tests pass locally (`.\gradlew.bat test`).
   - [ ] No Android classes imported into `:core`.
   - [ ] Voice logic strictly honors direct 1-hop constraint ($ttl = 1$).
   - [ ] Relevant documentation in `docs/` updated.
4. **Submit PR**: Open a Pull Request against `main` on GitHub detailing:
   - The problem addressed or capability added.
   - Test coverage added and verification results.
   - Any physical device testing performed (device models, Android versions).
