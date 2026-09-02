---
name: Warm Minimalist Modern
colors:
  surface: '#FFF8EF'
  surface-dim: '#e0d9d3'
  surface-bright: '#fff8f3'
  surface-container-lowest: '#ffffff'
  surface-container-low: '#faf2ec'
  surface-container: '#F3EDE4'
  surface-container-high: '#eee7e1'
  surface-container-highest: '#e8e1db'
  on-surface: '#1e1b17'
  on-surface-variant: '#554339'
  inverse-surface: '#33302c'
  inverse-on-surface: '#f7efe9'
  outline: '#887368'
  outline-variant: '#dbc1b5'
  surface-tint: '#99460a'
  primary: '#733100'
  on-primary: '#ffffff'
  primary-container: '#964407'
  on-primary-container: '#ffc9ad'
  inverse-primary: '#ffb68e'
  secondary: '#974544'
  on-secondary: '#ffffff'
  secondary-container: '#fd9794'
  on-secondary-container: '#772d2d'
  tertiary: '#004b60'
  on-tertiary: '#ffffff'
  tertiary-container: '#006480'
  on-tertiary-container: '#97dfff'
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
  on-secondary-fixed-variant: '#792e2e'
  tertiary-fixed: '#bce9ff'
  tertiary-fixed-dim: '#88d0f0'
  on-tertiary-fixed: '#001f2a'
  on-tertiary-fixed-variant: '#004d63'
  background: '#fff8f3'
  on-background: '#1e1b17'
  surface-variant: '#e8e1db'
  warm-linen: '#FAF5EE'
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
  sm: 0.5rem
  DEFAULT: 1rem
  md: 1.5rem
  lg: 2rem
  xl: 3rem
  full: 9999px
spacing:
  base: 8px
  margin-mobile: 24px
  margin-desktop: 40px
  gutter: 16px
  stack-sm: 12px
  stack-md: 24px
  stack-lg: 40px
---

## Brand & Style

The design system evolves the "Sahara / Warm Minimalist" aesthetic into a more contemporary, fluid expression. It balances the editorial sophistication of a high-end journal with a modern, organic geometry. By moving toward a **Modern Minimalism** infused with **Pill-Shaped** elements, the interface feels softer, more approachable, and highly refined.

The emotional response should be one of "calm resilience"—providing a premium, tactile experience that feels advanced yet human-centric. The visual language is characterized by generous whitespace, a soft color palette, and a distinctive reliance on fully rounded forms that suggest a continuous, uninterrupted flow of information.

## Colors

The palette is anchored in organic, earth-toned hues that evoke a "Sahara" warmth, providing a soft-contrast environment that reduces visual fatigue.

- **Primary (Burnt Sienna):** Used for critical actions, active states, and essential connectivity indicators.
- **Secondary (Dusty Rose):** Applied to nuanced alerts and secondary navigational accents.
- **Neutral (Warm Charcoal):** Used for typography to ensure legibility while maintaining a softer profile than absolute black.
- **Surface Strategy:** The system utilizes `#FAF5EE` (Warm Linen) as the primary canvas. Depth is created through tonal shifts rather than shadows, using slightly darker linen shades for containers and structural elements.

## Typography

Typography follows an editorial hierarchy that pairs classical serif elegance with modern sans-serif utility.

- **Editorial Serifs:** **EB Garamond** is reserved for large display titles and headlines, providing a sense of timeless authority and calm.
- **Functional Sans:** **Manrope** is used for all interface-related text, body copy, and labels. Its geometric but open nature ensures high legibility in stressful or low-light conditions.
- **Refinement:** Labels utilize slightly expanded tracking (0.05em) to improve clarity and contribute to the modern, airy aesthetic.

## Layout & Spacing

The layout model is based on a **Fluid Grid** that emphasizes "breathable" whitespace.

- **Grid Model:** 4 columns for mobile, 8 columns for tablet, and 12 columns for desktop. 
- **Margins & Gutters:** A generous 24px margin on mobile pushes content toward the center, creating focus. Gutters are kept at 16px to maintain a tight relationship between related elements.
- **Vertical Rhythm:** A strict 8px base unit governs all spacing. Use "Stack LG" (40px) to separate major functional blocks, such as a mesh status overview from a message list, to prevent visual clutter.

## Elevation & Depth

This design system avoids traditional heavy shadows in favor of **Tonal Layering** and **Subtle Outlines**.

- **Tonal Layers:** Elevation is communicated by placing elements on surfaces that are slightly lighter or darker than the background.
- **Low-Contrast Outlines:** Containers and cards use a subtle 1px border (`#E8E2D9`) to define boundaries without adding visual weight.
- **Selective Elevation:** Only the most critical floating elements—specifically the SOS action—may use an ambient, highly diffused shadow (`Blur: 20px, Opacity: 0.05, Tinted Primary`) to signify its priority.

## Shapes

The shape language is strictly **Pill-Shaped (Rounded-Full)**. Every interactive and container-based element must utilize maximum corner radii to create a fluid, modern look.

- **Full Rounding:** Buttons, input fields, search bars, and badges must be fully pill-shaped.
- **Large Containers:** Cards and major UI containers use the highest level of roundedness (32px or more) to ensure they harmonize with the smaller pill elements.
- **Consistency:** This geometry extends to selection controls; radio buttons and checkboxes should feel soft and circular, avoiding sharp corners entirely.

## Components

### Buttons & Inputs
Buttons and search bars are strictly pill-shaped. Input fields feature a minimum of 24px horizontal padding to accommodate the deep curves of the pill geometry. Labels float internally to maintain the clean, contained look.

### Message Entries
Avoid traditional "chat bubbles." Messages are presented as editorial cards with fully rounded corners or as entries separated by subtle tonal shifts. This maintains the sophisticated, journal-like feel of the system.

### Navigation
The bottom navigation bar is a floating pill-shaped container with a frosted glass effect (backdrop blur) and a subtle 1px outline. Active states are indicated by the Primary color and a soft, circular background glow.

### Status Indicators
Network strength, battery, and mesh status indicators are rendered as minimalist pill-shaped badges. These use high-contrast text on a Primary or Secondary background to ensure visibility.

### SOS Button
The SOS trigger is a large, circular or pill-shaped element. It features a soft pulsating glow in Dusty Rose and requires a long-press interaction, providing both visual and haptic confirmation of the emergency state.