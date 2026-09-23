# MESHWHISPER / BIT FOR US — UI STRUCTURE SPECIFICATION

**Source:** Extracted directly from shipped Compose code (`app/src/main/java/com/meshwhisper/app/ui/**`), cross-checked against `design_reference/stitch_meshwhisper_sahara_redesign/*/screen.png` mockups.  
**Design Reference:** Sahara: Warm Minimalism Design System v2 ([`DESIGN_.md`](file:///c:/Users/hp/Downloads/BIT%20FOR%20US/DESIGN_.md))

---

## Navigation & Screen Legend
- **[TL]** Top-Left | **[TC]** Top-Center | **[TR]** Top-Right
- **[CL]** Center-Left | **[C]** Center | **[CR]** Center-Right
- **[BL]** Bottom-Left | **[BC]** Bottom-Center | **[BR]** Bottom-Right
- **(D)** Opens Dialog | **(S)** Opens Bottom Sheet / Modal Panel | **(N)** Navigates Screen | **(T)** Toggles Local State

---

## App Shell — Navigation Frame
**File:** [`ui/MainScreen.kt`](file:///c:/Users/hp/Downloads/BIT%20FOR%20US/app/src/main/java/com/meshwhisper/app/ui/MainScreen.kt)

### Structure
- Full-screen Scaffold, background = `SaharaBackground` (`#FFF8EF`)
- Content area: Active tab screen, OR `DirectChatDetailScreen` full-screen when a chat thread is open
- Bottom bar: `FloatingBottomNavigationPill`, hidden when a chat thread is open

### Bottom Nav — FloatingBottomNavigationPill
- **Component:** [`ui/components/FloatingNavBar.kt`](file:///c:/Users/hp/Downloads/BIT%20FOR%20US/app/src/main/java/com/meshwhisper/app/ui/components/FloatingNavBar.kt)
- **Position:** [BC], floating capsule, 280–380dp wide, 20dp horizontal / 10dp vertical margin from screen edge.
- **5 Tabs (SpaceEvenly, left to right):**
  1. `PUBLIC` — icon `CellTower` — label "Mesh" — (N) Public Mesh Screen
  2. `DIRECT` — icon `Lock` — label "Direct" — (N) Direct Chats Screen
  3. `RADAR` — icon `NetworkCheck` — label "Radar" — (N) Mesh Radar Screen
  4. `INSPECTOR` — icon `DataObject` — label "Logs" — (N) Packet Inspector Screen
  5. `SETTINGS` — icon `Settings` — label "Identity" — (N) Identity Settings Screen
- **Selected Tab:** Icon inside a 32dp filled `SaharaPrimary` circle, 17dp white icon, bold 9sp uppercase label below in `SaharaPrimary`.
- **Unselected Tab:** 20dp muted icon only, no visible label (blank placeholder keeps row height constant).
- **Default Launch Tab:** `PUBLIC`.

### Back Navigation Behavior
- Chat thread open $\to$ closes thread, returns to previous active tab
- Any tab other than `PUBLIC` $\to$ jumps to `PUBLIC` tab
- On `PUBLIC` tab, no thread open $\to$ exits app

---

## Screen 0 — App Lock / Biometric Unlock
**Files:** [`ui/BiometricUnlockActivity.kt`](file:///c:/Users/hp/Downloads/BIT%20FOR%20US/app/src/main/java/com/meshwhisper/app/ui/BiometricUnlockActivity.kt), [`ui/MainActivity.kt`](file:///c:/Users/hp/Downloads/BIT%20FOR%20US/app/src/main/java/com/meshwhisper/app/ui/MainActivity.kt)

- **Trigger:** On cold launch, and after backgrounding past lock timeout.
- **Layout:**
  - [C] App icon / logo
  - [C] "MeshWhisper Locked" headline (EB Garamond)
  - [C] Biometric prompt trigger — (T) invokes Android `BiometricPrompt`
  - [BC] Fallback: Device PIN/pattern entry if biometrics fail or unavailable

---

## Screen 1 — Public Mesh (NavTab.PUBLIC)
**File:** [`ui/screens/PublicMeshScreen.kt`](file:///c:/Users/hp/Downloads/BIT%20FOR%20US/app/src/main/java/com/meshwhisper/app/ui/screens/PublicMeshScreen.kt)  
**Mockup Reference:** `2._public_mesh`, `13._sos_emergency_flow`

- **Top Bar (`SaharaTopAppBar`):**
  - [TL] Signal status icon (`SignalCellularAlt`) — (T) re-announces presence on mesh
  - [TC] Title "Public Mesh" (EB Garamond `headlineLarge`), connected-peer badge next to it
  - [TR] Emergency icon (`Emergency`, `SaharaError` tint) — (D) opens SOS Broadcast dialog
- **Channel Badge Card:**
  - [CL] Channel icon (`Lock` if private, `CellTower` if open) + channel name + subtype label
  - [CR] "Tune" text + chevron-right icon $\to$ (D) Channel Configuration dialog
- **Feed Filters:** All, Alerts, Updates, Help, Resources
- **Active SOS Card:** Pinned at top if active (`SaharaSosCard`) with "Acknowledge" button
- **Broadcast Composer:**
  - [BL] `+` attachment button $\to$ (S) media/voice attach menu
  - [BC] Text input field: "Describe emergency / needs (optional)"
  - [BR] Send button (paper-plane, circular `SaharaPrimary`)
- **Voice Recording State (Broadcast):**
  - Pulsing red dot + live timer "00:07 / 00:30" + "Broadcasting voice..." (30-second cap)

---

## Screen 2 — Direct Chats (List) (NavTab.DIRECT)
**File:** [`ui/screens/DirectChatsScreen.kt`](file:///c:/Users/hp/Downloads/BIT%20FOR%20US/app/src/main/java/com/meshwhisper/app/ui/screens/DirectChatsScreen.kt)  
**Mockup Reference:** `3._direct_messages`

- **Top Header:**
  - [TL] Signal status icon (`SignalCellularAlt`, 38dp circular) $\to$ (T) re-announces presence
  - [TR] Two icons side-by-side:
    - QR scan icon (`QrCodeScanner`) $\to$ (S) opens `CameraQrScanner`
    - Search icon (`Search` $\leftrightarrow$ `Close`) $\to$ (T) expands/collapses search field
- **Search Field:** OutlinedTextField, "Search alias or node hex..."
- **Conversation List:**
  - Rows: Avatar + Display Name + Trust Badge + Last message preview + Timestamp + Unread indicator
  - Tap row $\to$ (N) opens `DirectChatDetailScreen`

---

## Screen 3 — Direct Chat Detail (Thread)
**File:** [`ui/screens/DirectChatDetailScreen.kt`](file:///c:/Users/hp/Downloads/BIT%20FOR%20US/app/src/main/java/com/meshwhisper/app/ui/screens/DirectChatDetailScreen.kt)  
**Mockup Reference:** `4._direct_chat`

- **Header Row:**
  - [TL] Back arrow (`ArrowBack`) $\to$ (N) closes thread
  - [CL] Peer avatar (38dp)
  - [C] Peer display name (bold) + trust badge + connection-quality line
  - [TR-1] Voice Call icon (`Call`) $\to$ 1-hop voice call (disabled if not direct 1-hop or conflicted/blocked)
  - [TR-2] Safety/Verify icon $\to$ (D) Safety Number verification dialog (`Shield` / `VerifiedUser` / `Warning`)
- **Message Thread:**
  - Day dividers ("YESTERDAY", "TODAY")
  - Incoming bubbles: left-aligned, white fill (`SaharaSurfaceContainerLowest`)
  - Outgoing bubbles: right-aligned, tan fill (`SaharaPrimaryFixed`) + timestamp + ticks + "Delivered • N hops"
- **Composer:**
  - [BL] `+` attach button $\to$ (S) media/camera/voice attach menu
  - [BC] Text input field
  - [BR] Send button / Long-press voice note recorder

---

## Screen 4 — Mesh Radar (NavTab.RADAR)
**File:** [`ui/screens/MeshRadarScreen.kt`](file:///c:/Users/hp/Downloads/BIT%20FOR%20US/app/src/main/java/com/meshwhisper/app/ui/screens/MeshRadarScreen.kt)  
**Mockup Reference:** `6._mesh_radar`, `7._mesh_topology`

- **Top Bar:** Title "Mesh Radar"
- **Radar Canvas:**
  - Concentric range rings, perimeter zone markers
  - Peer nodes plotted radially by signal/hop distance
  - Node tap $\to$ info popup with "Hail Peer" ping button and direct chat navigation

---

## Screen 5 — Packet Inspector (NavTab.INSPECTOR)
**File:** [`ui/screens/PacketInspectorScreen.kt`](file:///c:/Users/hp/Downloads/BIT%20FOR%20US/app/src/main/java/com/meshwhisper/app/ui/screens/PacketInspectorScreen.kt)  
**Mockup Reference:** `10._packet_inspector`

- **Top Bar:** Title "Packet Inspector" / "Logs"
- **Filter Chips:** All, Messages, Routing, System
- **Log List:** Recent packet logs (newest first), packet type badges (`MESH`, `ROUTE`, `SOS`, `ALERT`), sender/recipient hex IDs, hops, bytes

---

## Screen 6 — Identity & Settings (NavTab.SETTINGS)
**File:** [`ui/screens/IdentitySettingsScreen.kt`](file:///c:/Users/hp/Downloads/BIT%20FOR%20US/app/src/main/java/com/meshwhisper/app/ui/screens/IdentitySettingsScreen.kt)  
**Mockup Reference:** `11._identity_settings`, `12._identity_verification`

- **Top Bar:** Title "Identity"
- **Profile Card:** Avatar, Field User alias, hex ID, inline edit icon
- **Sections:** Device Name, Mesh Identity, Privacy & Security, Bluetooth, App Settings, About
- **Safety / Import Actions:**
  - Import Peer Link Dialog with Camera QR Scanner and manual link paste
- **Emergency Panic Wipe:** Dedicated destructive action with explicit confirmation dialog

---

## Screen 7 — SOS Emergency Broadcast
**Component / Dialog:** Triggered from Public Mesh [TR] emergency icon  
**Mockup Reference:** `13._sos_emergency_flow`

- Full-screen / modal broadcast flow
- Concentric pulsing red alert rings
- Live status: "Broadcasting... Your SOS is being relayed through the mesh."
- Live duration timer: "00:12"
- Prominent "Stop Broadcast" action button (`SaharaError` solid fill)
