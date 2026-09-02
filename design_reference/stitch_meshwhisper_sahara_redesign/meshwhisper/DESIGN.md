---
name: MeshWhisper
colors:
  surface: '#fff8ef'
  surface-dim: '#dfd9d0'
  surface-bright: '#fff8ef'
  surface-container-lowest: '#ffffff'
  surface-container-low: '#f9f3ea'
  surface-container: '#f3ede4'
  surface-container-high: '#ede7de'
  surface-container-highest: '#e8e2d9'
  on-surface: '#1d1b16'
  on-surface-variant: '#554339'
  inverse-surface: '#33302a'
  inverse-on-surface: '#f6f0e7'
  outline: '#887368'
  outline-variant: '#dbc1b5'
  surface-tint: '#99460a'
  primary: '#964407'
  on-primary: '#ffffff'
  primary-container: '#b65c21'
  on-primary-container: '#fffbff'
  inverse-primary: '#ffb68e'
  secondary: '#974544'
  on-secondary: '#ffffff'
  secondary-container: '#fe9794'
  on-secondary-container: '#782c2d'
  tertiary: '#006480'
  on-tertiary: '#ffffff'
  tertiary-container: '#007ea1'
  on-tertiary-container: '#fbfdff'
  error: '#ba1a1a'
  on-error: '#ffffff'
  error-container: '#ffdad6'
  on-error-container: '#93000a'
  primary-fixed: '#ffdbca'
  primary-fixed-dim: '#ffb68e'
  on-primary-fixed: '#331200'
  on-primary-fixed-variant: '#773300'
  secondary-fixed: '#ffdad8'
  secondary-fixed-dim: '#ffb3b0'
  on-secondary-fixed: '#3f0308'
  on-secondary-fixed-variant: '#792e2f'
  tertiary-fixed: '#bce9ff'
  tertiary-fixed-dim: '#70d2fa'
  on-tertiary-fixed: '#001f2a'
  on-tertiary-fixed-variant: '#004d63'
  background: '#fff8ef'
  on-background: '#1d1b16'
  surface-variant: '#e8e2d9'
typography:
  display:
    fontFamily: EB Garamond
    fontSize: 48px
    fontWeight: '500'
    lineHeight: 56px
    letterSpacing: -0.02em
  headline-lg:
    fontFamily: EB Garamond
    fontSize: 32px
    fontWeight: '500'
    lineHeight: 40px
  headline-lg-mobile:
    fontFamily: EB Garamond
    fontSize: 28px
    fontWeight: '500'
    lineHeight: 34px
  headline-md:
    fontFamily: EB Garamond
    fontSize: 24px
    fontWeight: '500'
    lineHeight: 32px
  title-lg:
    fontFamily: Manrope
    fontSize: 20px
    fontWeight: '600'
    lineHeight: 28px
    letterSpacing: 0.01em
  body-lg:
    fontFamily: Manrope
    fontSize: 16px
    fontWeight: '400'
    lineHeight: 24px
  body-md:
    fontFamily: Manrope
    fontSize: 14px
    fontWeight: '400'
    lineHeight: 20px
  label-lg:
    fontFamily: Manrope
    fontSize: 12px
    fontWeight: '600'
    lineHeight: 16px
    letterSpacing: 0.05em
  label-md:
    fontFamily: Manrope
    fontSize: 11px
    fontWeight: '500'
    lineHeight: 16px
rounded:
  sm: 0.25rem
  DEFAULT: 0.5rem
  md: 0.75rem
  lg: 1rem
  xl: 1.5rem
  full: 9999px
spacing:
  unit: 8px
  container-padding: 24px
  gutter: 16px
  stack-sm: 12px
  stack-md: 24px
  stack-lg: 40px
---

## Brand & Style

The design system is rooted in the "Sahara / Warm Minimalist" aesthetic, prioritizing calm technology over frantic emergency interfaces. It is designed to instill a sense of resilience and premium craftsmanship during critical moments when connectivity is lost.

The visual direction blends **Modern Minimalism** with **Editorial Sophistication**. It avoids the clinical coldness of typical utility apps, instead using high-quality typography and tactile warmth to build trust. Layouts are characterized by expansive whitespace, a deliberate lack of visual noise, and a focus on human-centric communication. The interface should feel like a well-printed journal—reliable, timeless, and clear.

## Colors

This design system utilizes a "Warm Linen" foundation to reduce eye strain and provide a soft, organic feel compared to pure white. 

- **Primary (Burnt Sienna):** Reserved for primary actions, active states, and critical mesh status indicators.
- **Secondary (Dusty Rose):** Used for soft accents, categorized alerts, and secondary interactive elements.
- **Surface & Background:** The background is a solid #FAF5EE. Surface containers use a slightly darker tint or subtle borders to distinguish themselves without relying on heavy shadows.
- **Typography (Warm Charcoal):** A deep, warm grey (#2D2A26) is used for text to maintain high contrast while remaining softer than pure black.

## Typography

The typography system follows an editorial logic, pairing the classical elegance of **EB Garamond** for headers with the technical precision of **Manrope** for functional UI elements.

- **Headlines:** Use EB Garamond for all major page titles and section headers to evoke a sense of calm authority.
- **Body & UI:** Manrope is used for all functional text, messages, and labels to ensure maximum legibility at small sizes, especially in low-light or stressful environments.
- **Letter Spacing:** Labels use slightly increased tracking to enhance clarity and provide a premium, modern feel.

## Layout & Spacing

The design system adheres to a strict 8px grid, but favors generous, "breathable" margins to maintain the minimalist aesthetic.

- **Grid Model:** A 4-column grid for mobile and 8-column for tablet. 
- **Margins:** Standard screen padding is set to 24px to push content away from the edges, creating a centered, focused feel.
- **Vertical Rhythm:** Large vertical gaps (Stack LG) are used between distinct sections (e.g., separating the mesh status from the message list) to prevent visual clutter.
- **Edge-to-Edge:** Layouts should utilize Android's edge-to-edge drawing, with the bottom navigation bar and status bar remaining transparent or semi-translucent.

## Elevation & Depth

This design system avoids traditional heavy shadows. Instead, it uses **Tonal Layering** and **Subtle Outlines** to communicate hierarchy.

- **Surface Levels:** The primary background is the lowest level. Cards and containers use a subtle 1px border (#E8E2D9) rather than a shadow to define their boundaries.
- **Soft Elevation:** Only the most critical floating elements (like an SOS button or a primary Action Button) may use a very soft, diffused shadow: `Blur: 20px, Y: 4px, Opacity: 0.05, Color: #C2652A`.
- **Active States:** Pressed or active states are indicated by a slight shift in background tone (e.g., moving from #FAF5EE to #F2EDE4) or a subtle inner stroke.

## Shapes

The shape language is "Rounded," striking a balance between the organic nature of the Sahara theme and the structural requirements of a professional tool.

- **Cards & Buttons:** Use a 16px (1rem) corner radius for a soft but defined appearance.
- **Input Fields:** Follow the card radius (16px) to maintain consistency.
- **Badges/Chips:** Use fully pill-shaped (rounded-full) geometry for network status and connectivity indicators.
- **Selection Controls:** Checkboxes and radio buttons should feel more "editorial"—radio buttons use thick strokes, and checkboxes have a slightly larger radius than standard Android defaults.

## Components

### Message Cards (Editorial Style)
Messages are not displayed in chat bubbles. Instead, they are presented as card-style "entries" with a subtle bottom border or soft background. The sender's name uses `label-lg`, and the timestamp is tucked discreetly in the corner in `label-md`.

### Bottom Navigation
A compact, elegant bar containing five sections: Mesh, Direct, Radar, Logs, and Identity. Icons are thin-stroke (1.5px) and use the Primary color only when active. The container has a soft blur backdrop and a thin top-border.

### Network Radar
A sophisticated visualization showing mesh topology. Nodes are represented by clean circles. The "self" node is a Primary Burnt Sienna circle, while other nodes are Warm Charcoal with thin connecting lines.

### SOS Treatment
The emergency button is the only element that breaks the muted palette. It uses a soft, pulsating Dusty Rose glow. The interaction requires a long-press (3 seconds) to prevent accidental triggers, accompanied by a haptic feedback loop.

### Status Badges
Network strength and battery indicators are rendered as minimalist linear bars or dots, avoiding chunky iconography. They use Primary for "Connected" and Secondary for "Searching."

### Input Fields
Inputs are large with 24px horizontal padding. The label resides inside the container and floats upward when active, using Manrope Medium for maximum clarity.