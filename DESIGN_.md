# MeshWhisper — "Sahara: Warm Minimalism" Design System
**Status:** Formal specification v2 (supersedes root `DESIGN_.md`)  
**Scope:** Applies to Android (`:app`, Jetpack Compose). Desktop (`:desktop`, Java Swing) follows the same tokens where the toolkit allows.  
**Source of truth:** Values below are pulled directly from the shipped code (`ui/theme/Color.kt`, `ui/theme/Type.kt`) — this is not aspirational, it's what's already implemented. Use this file as the single spec judges/teammates can check the app against.

---

## 0. Why this file exists

The previous design doc (`DESIGN_.md`) stated the direction ("Sun-Baked Simplicity") but not the enforceable rules — exact tokens, states, spacing scale, or per-component behavior. That gap is why a judge can't verify "does the shipped app match the design intent" just by reading prose. This version fixes that: every value below is a rule, not a vibe. If a screen doesn't match a rule here, that's a bug to fix, not a style choice.

---

## 1. North Star

**"Sun-Baked Simplicity."** A field radio, not a chat app skin. Warm, editorial, calm — because this app is used in stressful moments (disaster response, search-and-rescue), and a loud, saturated, notification-heavy UI actively works against that context. Every screen should read as trustworthy and quiet, even the emergency ones — urgency comes from color and copy discipline, not from clutter.

**Rules that follow from this:**
- Never use more than one saturated accent color on screen at once outside of the SOS/emergency state.
- Whitespace is a component, not leftover space. When in doubt, add more of it.
- No pure white (`#FFFFFF`) as a large background fill — always the warm linen tone below. Cold neutrals break the "warm" premise immediately and are the single most common way a redesign accidentally drifts off-brand.

---

## 2. Color Tokens

All tokens below exist today in `ui/theme/Color.kt` as `Sahara*` Compose `Color` values. Do not introduce new raw hex values in screen code — add a token here first, then reference it.

### 2.1 Primary — Burnt Sienna (brand, primary actions, links, active states)
| Token | Hex | Usage |
|---|---|---|
| `SaharaPrimary` | `#964407` | Primary buttons, active nav icon fill, links, focused input border, headline text color on light bg |
| `SaharaOnPrimary` | `#FFFFFF` | Text/icons on top of `SaharaPrimary` fills |
| `SaharaPrimaryContainer` | `#B65C21` | Secondary-strength primary fill (e.g. pressed states, deeper accents) |
| `SaharaOnPrimaryContainer` | `#FFFBFF` | Text on `SaharaPrimaryContainer` |
| `SaharaPrimaryFixed` | `#FFDBCA` | Outgoing chat bubble fill |
| `SaharaPrimaryFixedDim` | `#FFB68E` | Outgoing bubble pressed/variant state |

### 2.2 Secondary — Dusty Rose / Terracotta (supporting accents, badges)
| Token | Hex | Usage |
|---|---|---|
| `SaharaSecondary` | `#974544` | Secondary accents, non-critical warning badges |
| `SaharaSecondaryContainer` | `#FE9794` | Soft secondary chip fills |

### 2.3 Tertiary — Teal (informational, "connected/verified" alternate accent)
| Token | Hex | Usage |
|---|---|---|
| `SaharaTertiary` | `#006480` | Reserved for informational states distinct from primary (e.g. "Wi-Fi link" vs "BLE link" differentiation — currently underused, see §7) |

### 2.4 Emergency / Error — Crimson Alert
| Token | Hex | Usage |
|---|---|---|
| `SaharaError` | `#BA1A1A` | SOS button, destructive actions (block/delete/panic wipe), collision/conflict warnings |
| `SaharaErrorContainer` | `#FFDAD6` | Error card backgrounds, cancel-recording button fill |
| `SaharaOnErrorContainer` | `#93000A` | Text on error containers |

This is the **only** color family allowed to visually dominate a screen — reserved exclusively for SOS broadcast, panic wipe, blocked-peer, and node-collision states. If it appears anywhere else, that's a design bug.

### 2.5 Surfaces — Warm Linen Architecture
| Token | Hex | Usage |
|---|---|---|
| `SaharaBackground` | `#FFF8EF` | Screen background (never pure white) |
| `SaharaSurfaceContainerLowest` | `#FFFFFF` | Cards, input fields, elevated sheets — the *only* place true white is allowed, and only as a small elevated surface against the linen background, never as the page background itself |
| `SaharaSurfaceContainerLow` | `#F9F3EA` | Nav pill background, subtle containers |
| `SaharaSurfaceContainer` | `#F3EDE4` | Default card containers |
| `SaharaSurfaceContainerHigh` | `#EDE7DE` | Elevated containers (dialogs) |
| `SaharaSurfaceContainerHighest` | `#E8E2D9` | Highest-elevation containers |
| `SaharaSurfaceDim` | `#DFD9D0` | Dimmed/disabled surface |

### 2.6 Text & Outline
| Token | Hex | Usage |
|---|---|---|
| `SaharaOnSurface` | `#1D1B16` | Primary body/heading text |
| `SaharaOnSurfaceVariant` | `#554339` | Secondary/muted text, timestamps, placeholders |
| `SaharaOutline` | `#887368` | Dividers, icon-only muted state |
| `SaharaOutlineVariant` | `#DBC1B5` | Card borders (thin, warm — never cold gray) |

### 2.7 Status Accents
| Token | Hex | Usage |
|---|---|---|
| `SaharaOnline` | `#10B981` | Peer online/connected dot, delivered-checkmark tint |
| `SaharaWarning` | `#D48A37` | Non-critical warnings (e.g. low battery relay mode) |

### 2.8 Dark mode
**Not currently implemented as a distinct token set** — `DarkBackground`/`DarkSurface` aliases in code currently just point back to the light Sahara tokens. This is a real gap: a field/tactical app used at night or in low light with the screen at max brightness is a genuine operational liability (visibility to hostile observers, battery drain, eye strain). **Action item for next redesign pass:** define true dark-warm tokens (not black — a dark warm charcoal/umber, e.g. background `#1D1712`, surfaces `#2A2119`, keeping `SaharaPrimary` roughly as-is since burnt sienna reads fine on dark) and wire `isSystemInDarkTheme()` to switch `SaharaColorScheme`.

---

## 3. Typography

Editorial serif headlines + geometric sans body — this pairing is the single most distinctive visual signature of the app. Keep it consistent; don't let any screen fall back to system default sans-serif for headings.

- **Headlines/Titles → EB Garamond** (`EBGaramondFamily`), Bold/SemiBold. Google Fonts, loaded via `GoogleFont.Provider`.
- **Body/Labels/UI chrome → Manrope** (`ManropeFamily`), Normal–Bold weights.

| Style | Font | Weight | Size / Line height | Used for |
|---|---|---|---|---|
| `displayLarge` | EB Garamond | Bold | 34sp / 40sp | Splash/onboarding hero text only |
| `displayMedium` | EB Garamond | Bold | 28sp / 34sp | Large section headers |
| `headlineLarge` | EB Garamond | Bold | 24sp / 30sp | Top app bar screen titles ("Public Mesh", "Mesh Radar", etc.) |
| `headlineMedium` | EB Garamond | SemiBold | 20sp / 26sp | Dialog titles |
| `headlineSmall` | EB Garamond | SemiBold | 18sp / 24sp | Card section headers |
| `titleLarge` | EB Garamond | Bold | 18sp / 24sp | Peer display names in chat headers |
| `titleMedium` | Manrope | SemiBold | 15sp | List item titles (chat list names) |
| body text | Manrope | Normal | 14sp | Message bubble text, form field text |
| labels/captions | Manrope | Normal–Bold | 9–12sp | Timestamps, nav pill labels, badges |

**Rule:** never mix — a screen title is always EB Garamond, a button label is always Manrope. Nav pill labels are Manrope Bold 9sp uppercase with 0.6sp letter-spacing (this specific micro-detail is what makes the bottom nav feel "editorial" rather than generic Material — preserve it exactly in any redesign).

---

## 4. Spacing & Sizing Scale

Use multiples of 4dp. Observed/standardized scale from the current implementation:

| Token | Value | Usage |
|---|---|---|
| `space-xs` | 4dp | Icon-to-label gap, chip internal gaps |
| `space-sm` | 8dp | Row item internal spacing |
| `space-md` | 12–14dp | Card internal padding (horizontal), list row padding |
| `space-lg` | 16–18dp | Screen horizontal margins |
| `space-xl` | 20dp | Top header horizontal padding |
| `space-2xl` | 28–32dp | Card padding on spacious/settings screens |

**Touch targets:** every `IconButton` in the shipped code is sized `38–40dp`. **Do not go below 40dp** for any new tappable icon — that's the accessibility floor (Android's own guidance is 48dp; 38–40dp is already tight and should not shrink further in a redesign, only grow).

**Corner radius:** cards `12dp`, dialogs slightly larger, bottom-nav pill `CircleShape` (fully rounded), chat bubbles rounded per standard messaging convention (not yet standardized — pick one radius, e.g. 18dp with a 4dp "tail" corner, and apply everywhere).

---

## 5. Elevation & Shadow

Ultra-soft only — this app should never look like it's floating on Material Design's default heavy shadows.

- Standard card: no shadow, rely on `SaharaOutlineVariant` 1dp border instead.
- Bottom nav pill: `elevation = 8dp`, `ambientColor = 0x1F3A302A`, `spotColor = 0x143A302A` — a warm-tinted shadow, not black. **This is important and easy to get wrong in a redesign:** default Compose/Material shadow color is a cool black; this app's shadows are tinted warm-brown to match the palette. Any new elevated component must use this same warm shadow color pair, never the Material default.

---

## 6. Iconography

- Material Icons (`Icons.Default.*`), filled style throughout — no outlined/rounded variants mixed in. Keep this consistent.
- Icon size inside a 38–40dp touch target: `20–22dp`.
- Status dots (online/connected): `6–10dp` filled circle, `SaharaOnline` or `SaharaError` depending on state, sometimes pulse-animated (infinite `alpha` tween 0.4→1.0 over 600–1000ms) — used for the active-mesh indicator and SOS recording indicator. Reuse this exact pulse pattern for any new "live" indicator rather than inventing a new animation.

---

## 7. Component Specs

### 7.1 `SaharaTopAppBar` (shared across Public Mesh, Radar, Inspector, Identity Settings)
- Height: status-bar padding + ~56dp content row.
- Layout: `[Left IconButton 40dp] — [Center: EB Garamond headlineLarge title + optional Manrope subtitle, weight=1, centered] — [Right action IconButton(s), 40dp each]`.
- Left icon default: signal/status icon (`SignalCellularAlt`), tappable to re-announce presence.
- Right icon default: Emergency icon (`SaharaError` tint) — **only wired on Public Mesh screen today.** Other screens pass a different `actionIcon`/`onActionClick` or omit it. Decide deliberately per-screen rather than leaving it inconsistent.

### 7.2 Bottom Navigation — `FloatingBottomNavigationPill`
- A floating, centered capsule (`min 280dp–max 380dp` wide), not edge-to-edge — this is a deliberate "field device" affordance, keep it.
- 5 tabs, `SpaceEvenly` arrangement, in this exact order (do not reorder without updating `NavTab` and this doc together):
  1. **Public** (icon: `CellTower`) — Public Mesh / SOS broadcast feed
  2. **Direct** (icon: `Lock`) — Direct encrypted chats list
  3. **Radar** (icon: `NetworkCheck`) — Mesh topology visualization
  4. **Logs** (icon: `DataObject`) — Packet Inspector
  5. **Identity** (icon: `Settings`) — Identity/Settings
- **Selected state:** 32dp circular `SaharaPrimary` badge behind a 17dp white icon, plus a 9sp bold uppercase label below in `SaharaPrimary`.
- **Unselected state:** icon only, 20dp, `SaharaOnSurfaceVariant` at 70% alpha, no visible label (transparent placeholder preserves row height so tabs don't jump vertically when selection changes — keep this trick, it's a real polish detail worth preserving).
- Hidden entirely when a Direct Chat thread is open (full-screen chat takes over; back button returns to the tab it came from).

### 7.3 Cards
- Fill: `SaharaSurfaceContainerLowest` (default) or a status-tinted fill (e.g. green-tinted for an active private channel badge, error-tinted for SOS alerts).
- Border: `1dp`, `SaharaOutlineVariant` at 40–60% alpha (or a status color at 50% alpha for tinted cards).
- Radius: `12dp`.
- Internal padding: `14dp` horizontal, `8–10dp` vertical for compact cards; up to `28–32dp` for settings-style cards.

### 7.4 Buttons
- **Primary action** (e.g. "Apply & Activate", "Broadcast Voice Note" send): solid `SaharaPrimary` fill, white text/icon, `8dp` corner radius.
- **Destructive action** (SOS broadcast, panic wipe, unblock/block, cancel recording): solid `SaharaError` fill or `SaharaErrorContainer` at reduced alpha with `SaharaError` icon tint.
- **Secondary/text action** ("Cancel", "Reset to Public"): text-only, `SaharaOnSurfaceVariant` or `SaharaError` for destructive-secondary, no fill.
- **Icon-only buttons**: circular tap target, no visible background unless active/toggled (e.g. active recording cancel button gets a light error-tinted circular background).

### 7.5 Chat bubbles
- Outgoing: `SaharaPrimaryFixed` fill, `SaharaOutlineVariant` border.
- Incoming: `SaharaSurfaceContainerLowest` fill, `SaharaOutlineVariant` border.
- Metadata row below bubble: timestamp + delivery ticks + hop count ("Delivered • 2 hops") in `SaharaOnSurfaceVariant`/`SaharaOnline` small caps — **hop count next to delivery status is a genuinely good, mesh-specific detail from the design mockups; make sure it's actually rendered in the shipped `DirectChatDetailScreen`, not just the mockup — this is exactly the kind of thing a judge would want to see live.**

### 7.6 Input fields
- `OutlinedTextField`, white (`SaharaSurfaceContainerLowest`) fill, `SaharaOutlineVariant` unfocused border, `SaharaPrimary` focused border, `12dp` radius, placeholder text in `SaharaOnSurfaceVariant` at 60% alpha.

---

## 8. Motion

- Standard transition timing: `200ms` tween for color/state changes (nav icon tint, etc.).
- Pulse/live indicators: `600–1000ms` infinite reverse tween, alpha `0.4↔1.0`.
- Collapsible content (search field, etc.): `fadeIn()+expandVertically()` / `fadeOut()+shrinkVertically()`.
- Keep all motion subtle and functional (state changes, live status) — no decorative bounce/spring effects. Matches the "calm field tool" premise.

---

## 9. States checklist (apply to every screen)

For each screen/component, explicitly design and implement:
- **Empty state** (e.g. no messages yet, no peers discovered) — currently present on some screens; audit all 6 for a proper empty-state illustration/copy, not just a blank list.
- **Loading/scanning state** (e.g. BLE scanning in progress before first peer found).
- **Offline/no-peers state** (distinct from "loading" — clearly communicate "0 connected, still listening" vs "actively searching").
- **Error/denied state** (e.g. Bluetooth permission denied, Bluetooth off) — must have a clear, actionable recovery path, not a silent failure.
- **Emergency/SOS state** — the one place `SaharaError` is allowed to dominate.

---

## 10. What to prioritize fixing first (tied to judge feedback)

1. Confirm every implemented screen visually matches its `design_reference/stitch_meshwhisper_sahara_redesign/` mockup — take real device screenshots side-by-side with the mockups and keep both in the repo. Right now only the mockups exist in the submission; the actual rendered app has never been captured.
2. Add real dark-mode tokens (§2.8) — genuinely relevant for a "used in the field, possibly at night" app, and currently just aliased back to light mode.
3. Standardize chat bubble corner radius and hop-count/delivery metadata rendering (§7.5) so it matches the mockup exactly.
4. Decide a deliberate rule for when `SaharaTopAppBar`'s right-side emergency icon appears (§7.1) — right now it's inconsistent screen-to-screen, which reads as unfinished rather than intentional.