import os
from pptx import Presentation
from pptx.util import Inches, Pt
from pptx.dml.color import RGBColor
from pptx.enum.text import PP_ALIGN, MSO_ANCHOR
from pptx.enum.shapes import MSO_SHAPE
from PIL import Image

# =============================================================================
# THEME & PALETTE DEFINITIONS (Sahara Obsidian Cyber-Tactical Theme)
# =============================================================================
BG_COLOR        = RGBColor(11, 15, 25)      # #0B0F19 - Midnight Obsidian
SURFACE_COLOR   = RGBColor(19, 27, 46)      # #131B2E - Dark Slate Card
SURFACE_ALT     = RGBColor(26, 36, 60)      # #1A243C - Elevated Card
BORDER_COLOR    = RGBColor(40, 56, 84)      # #283854 - Subtle Accent Border
BORDER_ACTIVE   = RGBColor(245, 158, 11)    # #F59E0B - Sahara Amber Border

TEXT_TITLE      = RGBColor(248, 250, 252)   # #F8FAFC - Pure Off-White
TEXT_BODY       = RGBColor(203, 213, 225)   # #CBD5E1 - Light Slate
TEXT_MUTED      = RGBColor(148, 163, 184)   # #94A3B8 - Slate Muted

ACCENT_GOLD     = RGBColor(245, 158, 11)    # #F59E0B - Sahara Amber / Gold
ACCENT_CYAN     = RGBColor(56, 189, 248)    # #38BDF8 - Tactical Cyan
ACCENT_GREEN    = RGBColor(16, 185, 129)    # #10B981 - Emerald Verified
ACCENT_RED      = RGBColor(239, 68, 68)     # #EF4444 - Crimson Distress / SOS
ACCENT_PURPLE   = RGBColor(168, 85, 247)    # #A855F7 - Cryptographic Violet

FONT_HEADING = "Segoe UI"
FONT_BODY    = "Segoe UI"

# Base image directory
IMG_BASE = r"C:\Users\hp\Downloads\BIT FOR US"

def get_img(rel_path):
    p = os.path.join(IMG_BASE, rel_path)
    return p if os.path.exists(p) else None

# Initialize Presentation (16:9 Widescreen)
prs = Presentation()
prs.slide_width  = Inches(13.333)
prs.slide_height = Inches(7.5)
blank_layout     = prs.slide_layouts[6]

def set_background(slide):
    bg = slide.shapes.add_shape(MSO_SHAPE.RECTANGLE, 0, 0, Inches(13.333), Inches(7.5))
    bg.fill.solid()
    bg.fill.fore_color.rgb = BG_COLOR
    bg.line.fill.background()
    return bg

def add_header(slide, tag_text, title_text, subtitle_text=None, tag_color=ACCENT_GOLD):
    # Tag Pill / Badge
    tag_box = slide.shapes.add_textbox(Inches(0.8), Inches(0.32), Inches(10.0), Inches(0.28))
    tf_tag = tag_box.text_frame
    tf_tag.word_wrap = True
    tf_tag.margin_left = tf_tag.margin_top = tf_tag.margin_right = tf_tag.margin_bottom = 0
    p_tag = tf_tag.paragraphs[0]
    p_tag.text = tag_text.upper()
    p_tag.font.name = FONT_HEADING
    p_tag.font.size = Pt(10)
    p_tag.font.bold = True
    p_tag.font.color.rgb = tag_color

    # Slide Title
    title_box = slide.shapes.add_textbox(Inches(0.8), Inches(0.58), Inches(11.7), Inches(0.55))
    tf_title = title_box.text_frame
    tf_title.word_wrap = True
    tf_title.margin_left = tf_title.margin_top = tf_title.margin_right = tf_title.margin_bottom = 0
    p_title = tf_title.paragraphs[0]
    p_title.text = title_text
    p_title.font.name = FONT_HEADING
    p_title.font.size = Pt(21)
    p_title.font.bold = True
    p_title.font.color.rgb = TEXT_TITLE

    # Slide Subtitle
    if subtitle_text:
        sub_box = slide.shapes.add_textbox(Inches(0.8), Inches(1.14), Inches(11.7), Inches(0.35))
        tf_sub = sub_box.text_frame
        tf_sub.word_wrap = True
        tf_sub.margin_left = tf_sub.margin_top = tf_sub.margin_right = tf_sub.margin_bottom = 0
        p_sub = tf_sub.paragraphs[0]
        p_sub.text = subtitle_text
        p_sub.font.name = FONT_BODY
        p_sub.font.size = Pt(11)
        p_sub.font.color.rgb = TEXT_MUTED

def add_card(slide, left, top, width, height, bg_color=SURFACE_COLOR, border_color=BORDER_COLOR):
    card = slide.shapes.add_shape(MSO_SHAPE.ROUNDED_RECTANGLE, left, top, width, height)
    card.fill.solid()
    card.fill.fore_color.rgb = bg_color
    card.line.color.rgb = border_color
    card.line.width = Pt(1)
    return card

def add_phone_mockup(slide, img_path, left, top, max_height=Inches(5.4)):
    if not img_path or not os.path.exists(img_path):
        return None
    try:
        im = Image.open(img_path)
        w, h = im.size
        target_h = max_height
        target_w = target_h * (w / h)

        # Phone backplate / bezel
        pad = Inches(0.06)
        frame = slide.shapes.add_shape(
            MSO_SHAPE.ROUNDED_RECTANGLE,
            left - pad, top - pad,
            target_w + (pad * 2), target_h + (pad * 2)
        )
        frame.fill.solid()
        frame.fill.fore_color.rgb = RGBColor(15, 20, 32)
        frame.line.color.rgb = RGBColor(65, 82, 115)
        frame.line.width = Pt(1.5)

        pic = slide.shapes.add_picture(img_path, left, top, width=target_w, height=target_h)
        return pic
    except Exception as e:
        print(f"Error embedding picture {img_path}: {e}")
        return None

def add_stat_box(slide, left, top, width, height, big_num, label_text, accent_color=ACCENT_GOLD):
    add_card(slide, left, top, width, height, bg_color=SURFACE_ALT, border_color=BORDER_COLOR)
    tb = slide.shapes.add_textbox(left + Inches(0.12), top + Inches(0.06), width - Inches(0.24), height - Inches(0.12))
    tf = tb.text_frame
    tf.word_wrap = True
    tf.margin_left = tf.margin_top = tf.margin_right = tf.margin_bottom = 0
    p1 = tf.paragraphs[0]
    p1.text = big_num
    p1.font.name = FONT_HEADING
    p1.font.size = Pt(20)
    p1.font.bold = True
    p1.font.color.rgb = accent_color
    p2 = tf.add_paragraph()
    p2.text = label_text
    p2.font.name = FONT_BODY
    p2.font.size = Pt(9.5)
    p2.font.color.rgb = TEXT_MUTED

# =============================================================================
# SLIDE 1: TITLE & HERO KEYNOTE
# =============================================================================
slide1 = prs.slides.add_slide(blank_layout)
set_background(slide1)

# App Logo
logo_path = get_img(r"app\src\main\res\drawable\meshwhisper_logo.png")
if logo_path:
    slide1.shapes.add_picture(logo_path, Inches(0.8), Inches(0.7), width=Inches(1.2), height=Inches(1.2))

# Title Card
tb1 = slide1.shapes.add_textbox(Inches(2.2), Inches(0.65), Inches(7.5), Inches(1.3))
tf1 = tb1.text_frame
tf1.word_wrap = True
tf1.margin_left = tf1.margin_top = tf1.margin_right = tf1.margin_bottom = 0

p_t1 = tf1.paragraphs[0]
p_t1.text = "MeshWhisper"
p_t1.font.name = FONT_HEADING
p_t1.font.size = Pt(36)
p_t1.font.bold = True
p_t1.font.color.rgb = ACCENT_GOLD

p_t2 = tf1.add_paragraph()
p_t2.text = "Resilient Zero-Infrastructure Mesh Communication Platform"
p_t2.font.name = FONT_HEADING
p_t2.font.size = Pt(16)
p_t2.font.bold = True
p_t2.font.color.rgb = TEXT_TITLE

# Hero description card
add_card(slide1, Inches(0.8), Inches(2.1), Inches(8.5), Inches(3.2))
tb_desc = slide1.shapes.add_textbox(Inches(1.1), Inches(2.3), Inches(7.9), Inches(2.8))
tf_desc = tb_desc.text_frame
tf_desc.word_wrap = True
tf_desc.margin_left = tf_desc.margin_top = tf_desc.margin_right = tf_desc.margin_bottom = 0

p_d1 = tf_desc.paragraphs[0]
p_d1.text = "MISSION-CRITICAL OFF-GRID P2P COMMUNICATION"
p_d1.font.name = FONT_HEADING
p_d1.font.size = Pt(11)
p_d1.font.bold = True
p_d1.font.color.rgb = ACCENT_CYAN

p_d2 = tf_desc.add_paragraph()
p_d2.text = (
    "MeshWhisper is a fully decentralized, self-healing communication ecosystem operating "
    "without cellular towers, internet backbones, or centralized cloud servers.\n\n"
    "By bridging Bluetooth Low Energy (BLE 5.x) and Local Wi-Fi Direct/LAN, every smartphone and "
    "workstation becomes an autonomous relay node. Featuring end-to-end encryption, multi-hop "
    "routing, real-time voice streaming, and delay-tolerant store-and-forward architecture."
)
p_d2.font.name = FONT_BODY
p_d2.font.size = Pt(12)
p_d2.font.color.rgb = TEXT_BODY

# Stat chips at bottom
add_stat_box(slide1, Inches(0.8), Inches(5.6), Inches(2.0), Inches(1.2), "100%", "OFF-GRID / ZERO INTERNET", ACCENT_GOLD)
add_stat_box(slide1, Inches(2.95), Inches(5.6), Inches(2.0), Inches(1.2), "256-Bit", "MILITARY-GRADE AEAD", ACCENT_GREEN)
add_stat_box(slide1, Inches(5.1), Inches(5.6), Inches(2.0), Inches(1.2), "< 35 ms", "BLE HOP LATENCY", ACCENT_CYAN)
add_stat_box(slide1, Inches(7.25), Inches(5.6), Inches(2.05), Inches(1.2), "Opus", "FULL-DUPLEX VOICE", ACCENT_PURPLE)

# Hero phone mockup on right
chat_screen = get_img(r"design_reference\stitch_meshwhisper_sahara_redesign\4._direct_chat\screen.png")
add_phone_mockup(slide1, chat_screen, Inches(10.0), Inches(0.9), max_height=Inches(5.9))

print("Slide 1 generated.")

# =============================================================================
# SLIDE 2: THE CRITICAL PROBLEM & CRISIS SCENARIO
# =============================================================================
slide2 = prs.slides.add_slide(blank_layout)
set_background(slide2)
add_header(
    slide2,
    "PROBLEM STATEMENT • INFRASTRUCTURE COLLAPSE",
    "Why Centralized Messaging Fails When You Need It Most",
    "Modern communications apps rely on a fragile chain of central servers, cell towers, and power grids."
)

card_w = Inches(2.8)
card_h = Inches(4.2)
top_pos = Inches(1.6)

# Card 1: Centralized Dependency Trap
add_card(slide2, Inches(0.8), top_pos, card_w, card_h)
tb_c1 = slide2.shapes.add_textbox(Inches(1.0), top_pos + Inches(0.2), card_w - Inches(0.4), card_h - Inches(0.4))
tf_c1 = tb_c1.text_frame
tf_c1.word_wrap = True
tf_c1.paragraphs[0].text = "CENTRALIZED BACKBONE"
tf_c1.paragraphs[0].font.size = Pt(11)
tf_c1.paragraphs[0].font.bold = True
tf_c1.paragraphs[0].font.color.rgb = ACCENT_RED
p = tf_c1.add_paragraph()
p.text = (
    "WhatsApp, Signal, and Telegram require central data centers, DNS resolution, and internet backhaul.\n\n"
    "A single severed undersea cable, telecom power outage, or cloud server outage shuts down communication for entire countries."
)
p.font.size = Pt(11)
p.font.color.rgb = TEXT_BODY

# Card 2: Disaster & Blackouts
add_card(slide2, Inches(3.8), top_pos, card_w, card_h)
tb_c2 = slide2.shapes.add_textbox(Inches(4.0), top_pos + Inches(0.2), card_w - Inches(0.4), card_h - Inches(0.4))
tf_c2 = tb_c2.text_frame
tf_c2.word_wrap = True
tf_c2.paragraphs[0].text = "DISASTERS & CRISES"
tf_c2.paragraphs[0].font.size = Pt(11)
tf_c2.paragraphs[0].font.bold = True
tf_c2.paragraphs[0].font.color.rgb = ACCENT_GOLD
p = tf_c2.add_paragraph()
p.text = (
    "Earthquakes, cyclones, floods, and warfare destroy cell towers within minutes.\n\n"
    "Cellular networks experience immediate congestion collapse, leaving victims and emergency responders in total radio silence."
)
p.font.size = Pt(11)
p.font.color.rgb = TEXT_BODY

# Card 3: Surveillance & Censorship
add_card(slide2, Inches(6.8), top_pos, card_w, card_h)
tb_c3 = slide2.shapes.add_textbox(Inches(7.0), top_pos + Inches(0.2), card_w - Inches(0.4), card_h - Inches(0.4))
tf_c3 = tb_c3.text_frame
tf_c3.word_wrap = True
tf_c3.paragraphs[0].text = "SURVEILLANCE & SHUTDOWNS"
tf_c3.paragraphs[0].font.size = Pt(11)
tf_c3.paragraphs[0].font.bold = True
tf_c3.paragraphs[0].font.color.rgb = ACCENT_CYAN
p = tf_c3.add_paragraph()
p.text = (
    "Authoritarian regimes execute internet kill-switches during protests or military operations.\n\n"
    "Cellular carriers log real-time geolocation, IMEI, IMSI, and social connections, exposing dissidents and journalists."
)
p.font.size = Pt(11)
p.font.color.rgb = TEXT_BODY

# Bottom Summary Pill
add_card(slide2, Inches(0.8), Inches(6.0), Inches(8.8), Inches(0.85), bg_color=SURFACE_ALT)
tb_bot = slide2.shapes.add_textbox(Inches(1.0), Inches(6.05), Inches(8.4), Inches(0.75))
tf_bot = tb_bot.text_frame
tf_bot.word_wrap = True
p_b = tf_bot.paragraphs[0]
p_b.text = "THE REALITY: When power lines drop and towers fail, smartphones become helpless bricks."
p_b.font.bold = True
p_b.font.size = Pt(11)
p_b.font.color.rgb = ACCENT_RED
p_b2 = tf_bot.add_paragraph()
p_b2.text = "MeshWhisper decouples communication from physical infrastructure entirely."
p_b2.font.size = Pt(10.5)
p_b2.font.color.rgb = TEXT_BODY

# Right side phone mockup: SOS emergency flow
sos_screen = get_img(r"design_reference\stitch_meshwhisper_sahara_redesign\13._sos_emergency_flow\screen.png")
add_phone_mockup(slide2, sos_screen, Inches(10.2), Inches(1.5), max_height=Inches(5.4))

print("Slide 2 generated.")

# =============================================================================
# SLIDE 3: THE MESHWISPER PARADIGM SHIFT
# =============================================================================
slide3 = prs.slides.add_slide(blank_layout)
set_background(slide3)
add_header(
    slide3,
    "CORE PARADIGM • AUTONOMOUS PEER-TO-PEER MESH",
    "A Decentralized, Self-Healing Ad-Hoc Communication Mesh",
    "Every mobile device and desktop becomes an active relay, creating an emergent, indestructible network."
)

p_w = Inches(4.3)
p_h = Inches(1.7)

# Pillar 1
add_card(slide3, Inches(0.8), Inches(1.6), p_w, p_h)
tb = slide3.shapes.add_textbox(Inches(1.0), Inches(1.75), p_w - Inches(0.4), p_h - Inches(0.3))
tf = tb.text_frame
tf.word_wrap = True
p = tf.paragraphs[0]
p.text = "1. ZERO INFRASTRUCTURE REQUIRED"
p.font.bold = True
p.font.size = Pt(11)
p.font.color.rgb = ACCENT_GOLD
p2 = tf.add_paragraph()
p2.text = "Communicates completely off-grid. No SIM card, cell reception, internet, router, or satellite subscription required."
p2.font.size = Pt(10.5)
p2.font.color.rgb = TEXT_BODY

# Pillar 2
add_card(slide3, Inches(5.3), Inches(1.6), p_w, p_h)
tb = slide3.shapes.add_textbox(Inches(5.5), Inches(1.75), p_w - Inches(0.4), p_h - Inches(0.3))
tf = tb.text_frame
tf.word_wrap = True
p = tf.paragraphs[0]
p.text = "2. DYNAMIC MULTI-HOP RELAY"
p.font.bold = True
p.font.size = Pt(11)
p.font.color.rgb = ACCENT_CYAN
p2 = tf.add_paragraph()
p2.text = "Packets hop dynamically from device A → B → C → D. If node B moves away, the mesh automatically reroutes packets around it."
p2.font.size = Pt(10.5)
p2.font.color.rgb = TEXT_BODY

# Pillar 3
add_card(slide3, Inches(0.8), Inches(3.5), p_w, p_h)
tb = slide3.shapes.add_textbox(Inches(1.0), Inches(3.65), p_w - Inches(0.4), p_h - Inches(0.3))
tf = tb.text_frame
tf.word_wrap = True
p = tf.paragraphs[0]
p.text = "3. HYBRID DUAL-TRANSPORT ENGINE"
p.font.bold = True
p.font.size = Pt(11)
p.font.color.rgb = ACCENT_GREEN
p2 = tf.add_paragraph()
p2.text = "Seamlessly combines ultra-low power BLE 5.x for continuous ambient mesh with high-throughput Wi-Fi Direct & TCP for media and voice."
p2.font.size = Pt(10.5)
p2.font.color.rgb = TEXT_BODY

# Pillar 4
add_card(slide3, Inches(5.3), Inches(3.5), p_w, p_h)
tb = slide3.shapes.add_textbox(Inches(5.5), Inches(3.65), p_w - Inches(0.4), p_h - Inches(0.3))
tf = tb.text_frame
tf.word_wrap = True
p = tf.paragraphs[0]
p.text = "4. ASYNCHRONOUS STORE-AND-FORWARD"
p.font.bold = True
p.font.size = Pt(11)
p.font.color.rgb = ACCENT_PURPLE
p2 = tf.add_paragraph()
p2.text = "Delay-Tolerant Networking (DTN). Devices store encrypted packets and relay them when passing nearby nodes ('Data Mule' paradigm)."
p2.font.size = Pt(10.5)
p2.font.color.rgb = TEXT_BODY

# Bottom metrics bar
add_stat_box(slide3, Inches(0.8), Inches(5.4), Inches(2.7), Inches(1.4), "0 Towers", "CENTRAL DEPENDENCY ELIMINATED", ACCENT_GOLD)
add_stat_box(slide3, Inches(3.7), Inches(5.4), Inches(2.7), Inches(1.4), "7 Hops", "ADAPTIVE TTL MULTI-HOP DEPTH", ACCENT_CYAN)
add_stat_box(slide3, Inches(6.6), Inches(5.4), Inches(3.0), Inches(1.4), "Dual Radios", "BLE 5.x + LOCAL WI-FI / TCP", ACCENT_GREEN)

# Right phone mockup: Public mesh
mesh_screen = get_img(r"design_reference\stitch_meshwhisper_sahara_redesign\2._public_mesh\screen.png")
add_phone_mockup(slide3, mesh_screen, Inches(10.1), Inches(1.5), max_height=Inches(5.4))

print("Slide 3 generated.")

# =============================================================================
# SLIDE 4: CRYPTOGRAPHY & IDENTITY SYSTEM
# =============================================================================
slide4 = prs.slides.add_slide(blank_layout)
set_background(slide4)
add_header(
    slide4,
    "SECURITY ARCHITECTURE • CRYPTOGRAPHIC PROTOCOL",
    "Zero-Trust Cryptography & Identity-Based Certificates (IBC)",
    "Mathematical verification replaces centralized Certificate Authorities, phone numbers, and cloud keys."
)

c_w = Inches(8.8)
c_h = Inches(1.4)

# Crypto Layer 1
add_card(slide4, Inches(0.8), Inches(1.6), c_w, c_h)
tb = slide4.shapes.add_textbox(Inches(1.0), Inches(1.75), c_w - Inches(0.4), c_h - Inches(0.3))
tf = tb.text_frame
tf.word_wrap = True
p = tf.paragraphs[0]
p.text = "DUAL ASYMMETRIC KEYPAIR ARCHITECTURE (Ed25519 + X25519)"
p.font.bold = True
p.font.size = Pt(11)
p.font.color.rgb = ACCENT_GOLD
p2 = tf.add_paragraph()
p2.text = (
    "• Ed25519 Digital Signatures: Guarantees authenticity for LINK_AUTH handshakes and hop signatures.\n"
    "• X25519 Elliptic Curve Diffie-Hellman (ECDH): Generates ephemeral session keys per peer conversation.\n"
    "• IBC Self-Signed Identity Certificates: Binds Node ID to public keys without internet verification."
)
p2.font.size = Pt(10)
p2.font.color.rgb = TEXT_BODY

# Crypto Layer 2
add_card(slide4, Inches(0.8), Inches(3.15), c_w, c_h)
tb = slide4.shapes.add_textbox(Inches(1.0), Inches(3.3), c_w - Inches(0.4), c_h - Inches(0.3))
tf = tb.text_frame
tf.word_wrap = True
p = tf.paragraphs[0]
p.text = "AEAD SYMMETRIC ENCRYPTION WITH BOUND HEADERS (ChaCha20-Poly1305)"
p.font.bold = True
p.font.size = Pt(11)
p.font.color.rgb = ACCENT_CYAN
p2 = tf.add_paragraph()
p2.text = (
    "• 256-bit ChaCha20-Poly1305 & AES-256-GCM symmetric ciphers encrypt all payload data.\n"
    "• Additional Authenticated Data (AAD): Packet type, IDs, and timestamps are cryptographically bound to the Poly1305 MAC tag, preventing packet header spoofing and routing tampering.\n"
    "• Ephemeral Ratchet: Keys rotate every 60 seconds to provide Post-Compromise Security (PCS)."
)
p2.font.size = Pt(10)
p2.font.color.rgb = TEXT_BODY

# Crypto Layer 3
add_card(slide4, Inches(0.8), Inches(4.7), c_w, c_h)
tb = slide4.shapes.add_textbox(Inches(1.0), Inches(4.85), c_w - Inches(0.4), c_h - Inches(0.3))
tf = tb.text_frame
tf.word_wrap = True
p = tf.paragraphs[0]
p.text = "ANTI-TAMPER LOCAL STORAGE & SECURE ENCLAVE HARDENING"
p.font.bold = True
p.font.size = Pt(11)
p.font.color.rgb = ACCENT_GREEN
p2 = tf.add_paragraph()
p2.text = (
    "• SQLCipher Database: 256-bit AES database encryption at rest with PBKDF2 key derivation.\n"
    "• Android Keystore / StrongBox: Hardware-backed secure element isolates root master identity keys.\n"
    "• Ephemeral Voice Link: Voice frames reside strictly in RAM (zero DB writes), preventing cold-boot extraction."
)
p2.font.size = Pt(10)
p2.font.color.rgb = TEXT_BODY

# Bottom Metric Badges
add_stat_box(slide4, Inches(0.8), Inches(6.2), Inches(2.7), Inches(0.95), "256-Bit", "POLY1305 AEAD SECURITY", ACCENT_GOLD)
add_stat_box(slide4, Inches(3.7), Inches(6.2), Inches(2.7), Inches(0.95), "Zero-Knowledge", "NO IDENTIFIERS TRANSMITTED", ACCENT_CYAN)
add_stat_box(slide4, Inches(6.6), Inches(6.2), Inches(3.0), Inches(0.95), "SQLCipher", "HARDWARE-BACKED AT REST", ACCENT_GREEN)

# Right phone mockup: Identity verification
id_screen = get_img(r"design_reference\stitch_meshwhisper_sahara_redesign\12._identity_verification\screen.png")
add_phone_mockup(slide4, id_screen, Inches(10.1), Inches(1.5), max_height=Inches(5.5))

print("Slide 4 generated.")

# =============================================================================
# SLIDE 5: ROUTING PROTOCOL, LOOP PREVENTION & QOS
# =============================================================================
slide5 = prs.slides.add_slide(blank_layout)
set_background(slide5)
add_header(
    slide5,
    "NETWORK PROTOCOL • ROUTING & QOS ENGINE",
    "Controlled Flooding, Bounded Dedup Cache & 4-Tier QoS Egress",
    "Preventing broadcast storms and buffer saturation across lossy, multi-hop radio links."
)

w_c5 = Inches(8.8)
h_c5 = Inches(1.5)

# Routing 1: Dedup
add_card(slide5, Inches(0.8), Inches(1.6), w_c5, h_c5)
tb = slide5.shapes.add_textbox(Inches(1.0), Inches(1.75), w_c5 - Inches(0.4), h_c5 - Inches(0.3))
tf = tb.text_frame
tf.word_wrap = True
p = tf.paragraphs[0]
p.text = "BOUNDED IN-MEMORY LRU DEDUP ENGINE (50,000 PACKET ENTRIES)"
p.font.bold = True
p.font.size = Pt(11)
p.font.color.rgb = ACCENT_GOLD
p2 = tf.add_paragraph()
p2.text = (
    "• In-Memory Dedup Set: Pre-auth S2 dedup check executes in < 0.2 μs, completely bypassing database.\n"
    "• Broadcast Storm Elimination: When multiple relays forward the same packet, receiving nodes drop duplicates immediately before invoking expensive cryptographic decryption.\n"
    "• Strict Memory Safety: Bounded LRU ring evicts oldest entries, maintaining constant < 4MB heap footprint."
)
p2.font.size = Pt(10)
p2.font.color.rgb = TEXT_BODY

# Routing 2: Adaptive TTL
add_card(slide5, Inches(0.8), Inches(3.25), w_c5, h_c5)
tb = slide5.shapes.add_textbox(Inches(1.0), Inches(3.4), w_c5 - Inches(0.4), h_c5 - Inches(0.3))
tf = tb.text_frame
tf.word_wrap = True
p = tf.paragraphs[0]
p.text = "ADAPTIVE TTL & DYNAMIC ROUTING TOPOLOGY"
p.font.bold = True
p.font.size = Pt(11)
p.font.color.rgb = ACCENT_CYAN
p2 = tf.add_paragraph()
p2.text = (
    "• TTL Decrement: Packets originate with TTL = 7 (MeshPacket.DEFAULT_TTL). Each relay decrements TTL by 1.\n"
    "• Direct Hop Recognition: Direct neighbor connections optimize routing by sending unicast frames directly, saving valuable RF bandwidth.\n"
    "• Routing Loop Invariants: Packets where senderId == myNodeId are instantly dropped to break reflection cycles."
)
p2.font.size = Pt(10)
p2.font.color.rgb = TEXT_BODY

# Routing 3: QoS Scheduler
add_card(slide5, Inches(0.8), Inches(4.9), w_c5, h_c5)
tb = slide5.shapes.add_textbox(Inches(1.0), Inches(5.05), w_c5 - Inches(0.4), h_c5 - Inches(0.3))
tf = tb.text_frame
tf.word_wrap = True
p = tf.paragraphs[0]
p.text = "STARVATION-FREE 4-TIER QOS EGRESS SCHEDULER"
p.font.bold = True
p.font.size = Pt(11)
p.font.color.rgb = ACCENT_PURPLE
p2 = tf.add_paragraph()
p2.text = (
    "• Tier 0 (SIGNALING): Emergency SOS packets and LINK_AUTH handshakes transmit with zero queuing delay.\n"
    "• Tier 1 (REALTIME_VOICE): 40ms Opus audio frames bypass all standard chat and media queues.\n"
    "• Tier 2 (STANDARD_MSG): One-to-one and channel direct text messages with guaranteed delivery.\n"
    "• Tier 3 (BULK_TRANSFER): Background media chunks and route telemetry governed by rate limiters."
)
p2.font.size = Pt(10)
p2.font.color.rgb = TEXT_BODY

# Bottom badges
add_stat_box(slide5, Inches(0.8), Inches(6.55), Inches(2.7), Inches(0.65), "50,000", "LRU PACKET DEDUP LIMIT", ACCENT_GOLD)
add_stat_box(slide5, Inches(3.7), Inches(6.55), Inches(2.7), Inches(0.65), "< 0.2 μs", "PRE-AUTH DEDUP SPEED", ACCENT_CYAN)
add_stat_box(slide5, Inches(6.6), Inches(6.55), Inches(3.0), Inches(0.65), "4 Tiers", "STRICT QOS PRIORITY", ACCENT_PURPLE)

# Right phone mockup: Mesh topology
topo_screen = get_img(r"design_reference\stitch_meshwhisper_sahara_redesign\7._mesh_topology\screen.png")
add_phone_mockup(slide5, topo_screen, Inches(10.1), Inches(1.5), max_height=Inches(5.5))

print("Slide 5 generated.")

# =============================================================================
# SLIDE 6: THE 8-STAGE PACKET PIPELINE (DEEP INSPECTION)
# =============================================================================
slide6 = prs.slides.add_slide(blank_layout)
set_background(slide6)
add_header(
    slide6,
    "CORE INGESTION PIPELINE • FORMAL SPECIFICATION",
    "Stage S0 to S7: Mandatory Production Authentication Chokepoint",
    "Zero unauthenticated state mutation — every frame passes 8 strict architectural verification stages."
)

p_w6 = Inches(4.3)
p_h6 = Inches(2.3)

# Block 1: S0 & S1
add_card(slide6, Inches(0.8), Inches(1.6), p_w6, p_h6)
tb = slide6.shapes.add_textbox(Inches(1.0), Inches(1.75), p_w6 - Inches(0.4), p_h6 - Inches(0.3))
tf = tb.text_frame
tf.word_wrap = True
p = tf.paragraphs[0]
p.text = "STAGE S0: ADMISSION & STAGE S1: STRUCTURE"
p.font.bold = True
p.font.size = Pt(11)
p.font.color.rgb = ACCENT_GOLD
p2 = tf.add_paragraph()
p2.text = (
    "• S0 Transport Admission: UDP mesh packets forbidden unconditionally. Unauthenticated PENDING links restricted strictly to LINK_AUTH (0x31) handshakes.\n"
    "• S1 Wire Structure: Validates frame length bounds [37..2048 bytes]. Rejects malformed headers, unknown versions, or trailing padding bytes."
)
p2.font.size = Pt(10)
p2.font.color.rgb = TEXT_BODY

# Block 2: S2 & S3
add_card(slide6, Inches(5.3), Inches(1.6), p_w6, p_h6)
tb = slide6.shapes.add_textbox(Inches(5.5), Inches(1.75), p_w6 - Inches(0.4), p_h6 - Inches(0.3))
tf = tb.text_frame
tf.word_wrap = True
p = tf.paragraphs[0]
p.text = "STAGE S2: ANTI-REPLAY & STAGE S3: FRESHNESS"
p.font.bold = True
p.font.size = Pt(11)
p.font.color.rgb = ACCENT_CYAN
p2 = tf.add_paragraph()
p2.text = (
    "• S2 Dedup Verification: In-memory LRU dedup lookup. If duplicate DM received for our node, safely re-emits ACK to sender and drops packet without reprocessing.\n"
    "• S3 Clock Freshness: Validates timestamp window (-120s future skew to +60s past). Drops replayed or time-tampered frames."
)
p2.font.size = Pt(10)
p2.font.color.rgb = TEXT_BODY

# Block 3: S4 & S5
add_card(slide6, Inches(0.8), Inches(4.05), p_w6, p_h6)
tb = slide6.shapes.add_textbox(Inches(1.0), Inches(4.2), p_w6 - Inches(0.4), p_h6 - Inches(0.3))
tf = tb.text_frame
tf.word_wrap = True
p = tf.paragraphs[0]
p.text = "STAGE S4: IDENTITY TRUST & STAGE S5: CRYPTOGRAPHY"
p.font.bold = True
p.font.size = Pt(11)
p.font.color.rgb = ACCENT_GREEN
p2 = tf.add_paragraph()
p2.text = (
    "• S4 Trust Check: Verifies sender identity exists in verified repository. Rejects BLOCKED peers. Validates link-bound identity against claimed senderId.\n"
    "• S5 Cryptographic Decryption: Verifies Poly1305 MAC tag and decrypts payload via ChaCha20. Verifies Ed25519 digital signature transcript."
)
p2.font.size = Pt(10)
p2.font.color.rgb = TEXT_BODY

# Block 4: S6 & S7
add_card(slide6, Inches(5.3), Inches(4.05), p_w6, p_h6)
tb = slide6.shapes.add_textbox(Inches(5.5), Inches(4.2), p_w6 - Inches(0.4), p_h6 - Inches(0.3))
tf = tb.text_frame
tf.word_wrap = True
p = tf.paragraphs[0]
p.text = "STAGE S6: HOP DECREMENT & STAGE S7: DISPATCH"
p.font.bold = True
p.font.size = Pt(11)
p.font.color.rgb = ACCENT_PURPLE
p2 = tf.add_paragraph()
p2.text = (
    "• S6 Routing Hop: Decrements TTL. Evaluates relay eligibility for neighboring peer broadcast.\n"
    "• S7 Commit & Dispatch: Commits packetId to in-memory seen set. Dispatches decrypted message to UI, database, or media reassembler. Voice skips DB commit."
)
p2.font.size = Pt(10)
p2.font.color.rgb = TEXT_BODY

# Bottom stat chips
add_stat_box(slide6, Inches(0.8), Inches(6.5), Inches(4.3), Inches(0.7), "8 Invariant Stages", "FORMAL VERIFICATION AT EVERY LAYER", ACCENT_GOLD)
add_stat_box(slide6, Inches(5.3), Inches(6.5), Inches(4.3), Inches(0.7), "Zero Unauthenticated Mutation", "REPUTATION & SECURITY PRESERVED", ACCENT_GREEN)

# Right phone mockup: Packet inspector
pkt_screen = get_img(r"design_reference\stitch_meshwhisper_sahara_redesign\10._packet_inspector\screen.png")
add_phone_mockup(slide6, pkt_screen, Inches(10.1), Inches(1.5), max_height=Inches(5.5))

print("Slide 6 generated.")

# =============================================================================
# SLIDE 7: REAL-TIME P2P VOICE CALLING OVER MESH
# =============================================================================
slide7 = prs.slides.add_slide(blank_layout)
set_background(slide7)
add_header(
    slide7,
    "REAL-TIME VOICE ENGINE • AUDIO STREAMING",
    "Low-Latency Full-Duplex Voice Calling Over BLE & Local Wi-Fi",
    "Wideband Opus compression, adaptive jitter buffering, and pure-RAM ephemeral link binding."
)

v_w = Inches(8.8)
v_h = Inches(1.5)

# Voice 1: Opus
add_card(slide7, Inches(0.8), Inches(1.6), v_w, v_h)
tb = slide7.shapes.add_textbox(Inches(1.0), Inches(1.75), v_w - Inches(0.4), v_h - Inches(0.3))
tf = tb.text_frame
tf.word_wrap = True
p = tf.paragraphs[0]
p.text = "WIDEBAND OPUS AUDIO ENCODING (16 kHz / 24 kHz)"
p.font.bold = True
p.font.size = Pt(11)
p.font.color.rgb = ACCENT_GOLD
p2 = tf.add_paragraph()
p2.text = (
    "• Low-Bitrate Studio Compression: Samples microphone audio at 16–24 kHz, compressing to 12–24 kbps.\n"
    "• 40ms Audio Framing: Generates lightweight 60-byte to 120-byte voice packets tailored for BLE MTU limits.\n"
    "• Hardware AEC & NS: Automatically binds to Android Acoustic Echo Cancellation and Noise Suppressor."
)
p2.font.size = Pt(10)
p2.font.color.rgb = TEXT_BODY

# Voice 2: Jitter Buffer
add_card(slide7, Inches(0.8), Inches(3.25), v_w, v_h)
tb = slide7.shapes.add_textbox(Inches(1.0), Inches(3.4), v_w - Inches(0.4), v_h - Inches(0.3))
tf = tb.text_frame
tf.word_wrap = True
p = tf.paragraphs[0]
p.text = "ADAPTIVE JITTER BUFFER & PACKET LOSS CONCEALMENT (PLC)"
p.font.bold = True
p.font.size = Pt(11)
p.font.color.rgb = ACCENT_CYAN
p2 = tf.add_paragraph()
p2.text = (
    "• Dynamic Latency Adjustment: Buffer depth continuously adapts between 60ms and 180ms based on measured radio packet arrival jitter.\n"
    "• Packet Loss Concealment (PLC): Opus decoder synthesizes missing audio waveforms during temporary RF fades.\n"
    "• Out-of-Order Sequencer: Re-orders arriving packets by frame sequence numbers before audio playback."
)
p2.font.size = Pt(10)
p2.font.color.rgb = TEXT_BODY

# Voice 3: Ephemeral RAM Security
add_card(slide7, Inches(0.8), Inches(4.9), v_w, v_h)
tb = slide7.shapes.add_textbox(Inches(1.0), Inches(5.05), v_w - Inches(0.4), v_h - Inches(0.3))
tf = tb.text_frame
tf.word_wrap = True
p = tf.paragraphs[0]
p.text = "ZERO DB MUTATION & EPHEMERAL LINK SECURITY"
p.font.bold = True
p.font.size = Pt(11)
p.font.color.rgb = ACCENT_GREEN
p2 = tf.add_paragraph()
p2.text = (
    "• 0 Database Writes: Voice frames stream purely in RAM and bypass SQLCipher disk writes entirely.\n"
    "• Authenticated Link Binding: Frames are validated against the active LINK_AUTH peer session key.\n"
    "• Heap Safety: Ephemeral ring buffers discard played audio frames immediately, preventing memory exhaustion."
)
p2.font.size = Pt(10)
p2.font.color.rgb = TEXT_BODY

# Bottom stat chips
add_stat_box(slide7, Inches(0.8), Inches(6.55), Inches(2.7), Inches(0.65), "40 ms", "AUDIO PACKET CADENCE", ACCENT_GOLD)
add_stat_box(slide7, Inches(3.7), Inches(6.55), Inches(2.7), Inches(0.65), "0 Writes", "ZERO DB MUTATION (RAM ONLY)", ACCENT_GREEN)
add_stat_box(slide7, Inches(6.6), Inches(6.55), Inches(3.0), Inches(0.65), "16–24 kHz", "WIDEBAND VOICE QUALITY", ACCENT_CYAN)

# Right phone mockup: Voice call in action
voice_screen = get_img(r"design_reference\stitch_meshwhisper_sahara_redesign\5._media_voice\screen.png")
add_phone_mockup(slide7, voice_screen, Inches(10.1), Inches(1.5), max_height=Inches(5.5))

print("Slide 7 generated.")

# =============================================================================
# SLIDE 8: HIGH-RES MEDIA PIPELINE & PROGRESSIVE TILES
# =============================================================================
slide8 = prs.slides.add_slide(blank_layout)
set_background(slide8)
add_header(
    slide8,
    "MEDIA STREAMING • CHUNKING & RECONSTRUCTION",
    "Streaming Photos, Voice Notes & Files Across Narrowband Radios",
    "MTU-aware chunking, selective NACK retransmission, and progressive matrix tile rendering."
)

m_w = Inches(8.8)
m_h = Inches(1.5)

# Media 1: MTU Segmentation
add_card(slide8, Inches(0.8), Inches(1.6), m_w, m_h)
tb = slide8.shapes.add_textbox(Inches(1.0), Inches(1.75), m_w - Inches(0.4), m_h - Inches(0.3))
tf = tb.text_frame
tf.word_wrap = True
p = tf.paragraphs[0]
p.text = "450-BYTE MTU SEGMENTATION & MERKLE TREE INTEGRITY"
p.font.bold = True
p.font.size = Pt(11)
p.font.color.rgb = ACCENT_GOLD
p2 = tf.add_paragraph()
p2.text = (
    "• MTU-Optimized Slicing: Files up to 20MB automatically split into 450-byte encrypted fragments (MeshPacket.CHUNK_PAYLOAD_SIZE).\n"
    "• SHA-256 Merkle Verification: Receiver computes SHA-256 hash upon full reassembly to verify byte-exact transmission.\n"
    "• Encrypted at Rest: Reassembled media files stored using AES-GCM app-private encrypted storage."
)
p2.font.size = Pt(10)
p2.font.color.rgb = TEXT_BODY

# Media 2: Progressive Tile Reveal
add_card(slide8, Inches(0.8), Inches(3.25), m_w, m_h)
tb = slide8.shapes.add_textbox(Inches(1.0), Inches(3.4), m_w - Inches(0.4), m_h - Inches(0.3))
tf = tb.text_frame
tf.word_wrap = True
p = tf.paragraphs[0]
p.text = "PROGRESSIVE MATRIX TILE REVEALER"
p.font.bold = True
p.font.size = Pt(11)
p.font.color.rgb = ACCENT_CYAN
p2 = tf.add_paragraph()
p2.text = (
    "• Real-Time Tile Decoding: Large photos are segmented into a 2x2 or 3x3 matrix of compressed JPEG tiles.\n"
    "• Immediate Visual Feedback: Initial low-res preview (<=350 bytes) displays instantly, followed by real-time tile unblurring as chunks arrive.\n"
    "• Tile Continuity: Unpainted tiles render progressively without waiting for the full multi-megabyte transfer."
)
p2.font.size = Pt(10)
p2.font.color.rgb = TEXT_BODY

# Media 3: Selective NACK & Pacing
add_card(slide8, Inches(0.8), Inches(4.9), m_w, m_h)
tb = slide8.shapes.add_textbox(Inches(1.0), Inches(5.05), m_w - Inches(0.4), m_h - Inches(0.3))
tf = tb.text_frame
tf.word_wrap = True
p = tf.paragraphs[0]
p.text = "SELECTIVE NACK RETRANSMISSION & DRIVER PACING"
p.font.bold = True
p.font.size = Pt(11)
p.font.color.rgb = ACCENT_PURPLE
p2 = tf.add_paragraph()
p2.text = (
    "• Selective NACKs: Receiver watchdog tracks missing chunk indices and sends MEDIA_NACK requesting only lost frames (0% whole-file retransmit waste).\n"
    "• Congestion Pacing: 65ms write pacing on BLE and 12ms on Wi-Fi eliminates Bluetooth controller buffer saturation.\n"
    "• Rate Limiting: Remote peer media transfers capped at 8 MB/hour per identity to prevent DoS flooding."
)
p2.font.size = Pt(10)
p2.font.color.rgb = TEXT_BODY

# Bottom badges
add_stat_box(slide8, Inches(0.8), Inches(6.55), Inches(2.7), Inches(0.65), "450 Bytes", "MTU-AWARE CHUNK PAYLOAD", ACCENT_GOLD)
add_stat_box(slide8, Inches(3.7), Inches(6.55), Inches(2.7), Inches(0.65), "0% Waste", "SELECTIVE NACK RECOVERY", ACCENT_CYAN)
add_stat_box(slide8, Inches(6.6), Inches(6.55), Inches(3.0), Inches(0.65), "65 ms", "CONGESTION-AWARE PACING", ACCENT_PURPLE)

# Right phone mockup: Direct chat
chat_screen2 = get_img(r"design_reference\stitch_meshwhisper_sahara_redesign\4._direct_chat\screen.png")
add_phone_mockup(slide8, chat_screen2, Inches(10.1), Inches(1.5), max_height=Inches(5.5))

print("Slide 8 generated.")

# =============================================================================
# SLIDE 9: TACTICAL NAVIGATION: RADAR, COMPASS HOMING & OFFLINE MAPS
# =============================================================================
slide9 = prs.slides.add_slide(blank_layout)
set_background(slide9)
add_header(
    slide9,
    "TACTICAL OFFLINE NAVIGATION • PEER LOCALIZATION",
    "Finding People in Disasters Without GPS Satellites or Internet",
    "RSSI trilateration, directional compass homing, and offline vector maps for search & rescue."
)

# 3 Phone Mockups Side-by-Side Showcase
s1 = get_img(r"design_reference\stitch_meshwhisper_sahara_redesign\6._mesh_radar\screen.png")
s2 = get_img(r"design_reference\stitch_meshwhisper_sahara_redesign\9._homing\screen.png")
s3 = get_img(r"design_reference\stitch_meshwhisper_sahara_redesign\8._offline_map\screen.png")

col_w = Inches(3.7)
top_s = Inches(1.5)

# Column 1: Radar
add_card(slide9, Inches(0.8), top_s, col_w, Inches(5.6))
tb = slide9.shapes.add_textbox(Inches(1.0), top_s + Inches(0.15), col_w - Inches(0.4), Inches(1.0))
tf = tb.text_frame
tf.word_wrap = True
tf.paragraphs[0].text = "1. REAL-TIME MESH RADAR"
tf.paragraphs[0].font.bold = True
tf.paragraphs[0].font.size = Pt(11)
tf.paragraphs[0].font.color.rgb = ACCENT_GOLD
p = tf.add_paragraph()
p.text = "Visual radar sweep showing all nearby nodes with RSSI dBm and distance estimation via Log-Distance Path Loss."
p.font.size = Pt(9.5)
p.font.color.rgb = TEXT_BODY
if s1:
    add_phone_mockup(slide9, s1, Inches(1.4), top_s + Inches(1.2), max_height=Inches(4.1))

# Column 2: Compass Homing
add_card(slide9, Inches(4.8), top_s, col_w, Inches(5.6))
tb = slide9.shapes.add_textbox(Inches(5.0), top_s + Inches(0.15), col_w - Inches(0.4), Inches(1.0))
tf = tb.text_frame
tf.word_wrap = True
tf.paragraphs[0].text = "2. DIRECTIONAL COMPASS HOMING"
tf.paragraphs[0].font.bold = True
tf.paragraphs[0].font.size = Pt(11)
tf.paragraphs[0].font.color.rgb = ACCENT_CYAN
p = tf.add_paragraph()
p.text = "Guides rescue workers towards peer radio signals using device magnetometer, locating victims in smoke or rubble."
p.font.size = Pt(9.5)
p.font.color.rgb = TEXT_BODY
if s2:
    add_phone_mockup(slide9, s2, Inches(5.4), top_s + Inches(1.2), max_height=Inches(4.1))

# Column 3: Offline Map
add_card(slide9, Inches(8.8), top_s, col_w, Inches(5.6))
tb = slide9.shapes.add_textbox(Inches(9.0), top_s + Inches(0.15), col_w - Inches(0.4), Inches(1.0))
tf = tb.text_frame
tf.word_wrap = True
tf.paragraphs[0].text = "3. 100% OFFLINE VECTOR MAPS"
tf.paragraphs[0].font.bold = True
tf.paragraphs[0].font.size = Pt(11)
tf.paragraphs[0].font.color.rgb = ACCENT_GREEN
p = tf.add_paragraph()
p.text = "Pre-cached OpenStreetMap vector tiles render without internet, overlaying team positions and SOS beacons."
p.font.size = Pt(9.5)
p.font.color.rgb = TEXT_BODY
if s3:
    add_phone_mockup(slide9, s3, Inches(9.4), top_s + Inches(1.2), max_height=Inches(4.1))

print("Slide 9 generated.")

# =============================================================================
# SLIDE 10: EMERGENCY SOS DISTRESS & PUBLIC BROADCAST
# =============================================================================
slide10 = prs.slides.add_slide(blank_layout)
set_background(slide10)
add_header(
    slide10,
    "LIFE-SAFETY PROTOCOL • EMERGENCY DISTRESS FLOW",
    "One-Touch Emergency SOS Broadcast & Ambient Multi-Hop Flood",
    "Mission-critical distress signaling with vital telemetry, medical notes, and background beaconing."
)

sos_w = Inches(8.8)
sos_h = Inches(1.5)

# SOS 1: One-touch
add_card(slide10, Inches(0.8), Inches(1.6), sos_w, sos_h)
tb = slide10.shapes.add_textbox(Inches(1.0), Inches(1.75), sos_w - Inches(0.4), sos_h - Inches(0.3))
tf = tb.text_frame
tf.word_wrap = True
p = tf.paragraphs[0]
p.text = "ONE-TOUCH CRITICAL SOS ACTIVATION"
p.font.bold = True
p.font.size = Pt(11)
p.font.color.rgb = ACCENT_RED
p2 = tf.add_paragraph()
p2.text = (
    "• Panic Button Broadcast: Instantly broadcasts emergency distress packet with GPS coordinates, blood type, and battery level.\n"
    "• Top-Priority Egress QoS: Assigned highest QoS priority (TrafficPriority.SIGNALING), overriding all media and text traffic across all nodes.\n"
    "• Automatic Audio Alarm: Triggers visual flashing strobe and local sound beacon on nearby phones."
)
p2.font.size = Pt(10)
p2.font.color.rgb = TEXT_BODY

# SOS 2: Ambient Relay
add_card(slide10, Inches(0.8), Inches(3.25), sos_w, sos_h)
tb = slide10.shapes.add_textbox(Inches(1.0), Inches(3.4), sos_w - Inches(0.4), sos_h - Inches(0.3))
tf = tb.text_frame
tf.word_wrap = True
p = tf.paragraphs[0]
p.text = "CONTINUOUS AMBIENT MULTI-HOP BEACONING"
p.font.bold = True
p.font.size = Pt(11)
p.font.color.rgb = ACCENT_GOLD
p2 = tf.add_paragraph()
p2.text = (
    "• 10-Second Periodic Beacon: Continuously pulses emergency signal even when phone is locked or screen is off.\n"
    "• Zero-Pairing Public Relay: Every civilian phone running MeshWhisper automatically relays SOS packets without requiring pairing.\n"
    "• Battery Protection: Employs ultra-low duty cycle BLE advertising to preserve dying battery life for days."
)
p2.font.size = Pt(10)
p2.font.color.rgb = TEXT_BODY

# SOS 3: Public Disaster Channel
add_card(slide10, Inches(0.8), Inches(4.9), sos_w, sos_h)
tb = slide10.shapes.add_textbox(Inches(1.0), Inches(5.05), sos_w - Inches(0.4), sos_h - Inches(0.3))
tf = tb.text_frame
tf.word_wrap = True
p = tf.paragraphs[0]
p.text = "PUBLIC DISASTER BROADCAST CHANNEL"
p.font.bold = True
p.font.size = Pt(11)
p.font.color.rgb = ACCENT_CYAN
p2 = tf.add_paragraph()
p2.text = (
    "• Community Roll-Call: Open broadcast channel enables disaster coordination, evacuation routes, and survivor headcounts.\n"
    "• Geofenced Broadcasts: Emergency responders can broadcast area-wide notifications that ripple outwards across all nodes.\n"
    "• Censorship Immune: Operates completely peer-to-peer; cannot be shut down by local authorities or internet providers."
)
p2.font.size = Pt(10)
p2.font.color.rgb = TEXT_BODY

# Bottom stat chips
add_stat_box(slide10, Inches(0.8), Inches(6.55), Inches(2.7), Inches(0.65), "Priority 0", "HIGHEST QOS OVERRIDE", ACCENT_RED)
add_stat_box(slide10, Inches(3.7), Inches(6.55), Inches(2.7), Inches(0.65), "100% Ambient", "AUTOMATIC CITIZEN RELAY", ACCENT_GOLD)
add_stat_box(slide10, Inches(6.6), Inches(6.55), Inches(3.0), Inches(0.65), "Live Telemetry", "GPS, BATTERY & MEDICAL NOTES", ACCENT_CYAN)

# Right phone mockup: SOS emergency
sos_screen2 = get_img(r"design_reference\stitch_meshwhisper_sahara_redesign\13._sos_emergency_flow\screen.png")
add_phone_mockup(slide10, sos_screen2, Inches(10.1), Inches(1.5), max_height=Inches(5.5))

print("Slide 10 generated.")

# =============================================================================
# SLIDE 11: SECURITY THREAT MODELING & ATTACK DEFENSES
# =============================================================================
slide11 = prs.slides.add_slide(blank_layout)
set_background(slide11)
add_header(
    slide11,
    "THREAT MITIGATION • HARDENED PROTOCOL MATRIX",
    "Defending Against State-Level Adversaries & Malicious Relays",
    "Zero-trust defenses designed to resist packet injection, relay attacks, and denial of service."
)

p_w11 = Inches(4.3)
p_h11 = Inches(2.3)

# Block 1: Replay & Sybil
add_card(slide11, Inches(0.8), Inches(1.6), p_w11, p_h11)
tb = slide11.shapes.add_textbox(Inches(1.0), Inches(1.75), p_w11 - Inches(0.4), p_h11 - Inches(0.3))
tf = tb.text_frame
tf.word_wrap = True
p = tf.paragraphs[0]
p.text = "REPLAY & SYBIL FLOOD DEFENSES"
p.font.bold = True
p.font.size = Pt(11)
p.font.color.rgb = ACCENT_GOLD
p2 = tf.add_paragraph()
p2.text = (
    "• Clock Skew Window: Rejects packets older than 60s or more than 120s in the future before invoking cryptographic logic.\n"
    "• In-Memory Dedup Cache: 50,000 processed packet IDs tracked in RAM; duplicate packet retransmissions dropped instantly.\n"
    "• Token-Bucket Rate Limiter: Enforces 200 writes/sec cap per BLE link to neutralize radio spamming."
)
p2.font.size = Pt(10)
p2.font.color.rgb = TEXT_BODY

# Block 2: MITM & Forgery
add_card(slide11, Inches(5.3), Inches(1.6), p_w11, p_h11)
tb = slide11.shapes.add_textbox(Inches(5.5), Inches(1.75), p_w11 - Inches(0.4), p_h11 - Inches(0.3))
tf = tb.text_frame
tf.word_wrap = True
p = tf.paragraphs[0]
p.text = "MAN-IN-THE-MIDDLE (MITM) IMMUNITY"
p.font.bold = True
p.font.size = Pt(11)
p.font.color.rgb = ACCENT_CYAN
p2 = tf.add_paragraph()
p2.text = (
    "• Out-of-Band QR Verification: Fingerprints exchanged visually in person create permanent cryptographic identity bindings.\n"
    "• IBC Digital Signatures: Prevents malicious relays from injecting forged messages into multi-hop paths.\n"
    "• Verified Link Binding: Radio connections strictly bound to authenticated identity hashes."
)
p2.font.size = Pt(10)
p2.font.color.rgb = TEXT_BODY

# Block 3: Traffic Analysis
add_card(slide11, Inches(0.8), Inches(4.05), p_w11, p_h11)
tb = slide11.shapes.add_textbox(Inches(1.0), Inches(4.2), p_w11 - Inches(0.4), p_h11 - Inches(0.3))
tf = tb.text_frame
tf.word_wrap = True
p = tf.paragraphs[0]
p.text = "METADATA & TRAFFIC ANALYSIS RESISTANCE"
p.font.bold = True
p.font.size = Pt(11)
p.font.color.rgb = ACCENT_GREEN
p2 = tf.add_paragraph()
p2.text = (
    "• Ephemeral Node IDs: 64-bit pseudonymous node identifiers decouple physical MAC addresses from user identities.\n"
    "• Unified Packet Framing: Uniform packet headers obscure message types and payload lengths from intermediate snooping relays.\n"
    "• Zero Cloud Logging: No IP addresses, phone numbers, or server logs exist anywhere."
)
p2.font.size = Pt(10)
p2.font.color.rgb = TEXT_BODY

# Block 4: Physical Device Theft
add_card(slide11, Inches(5.3), Inches(4.05), p_w11, p_h11)
tb = slide11.shapes.add_textbox(Inches(5.5), Inches(4.2), p_w11 - Inches(0.4), p_h11 - Inches(0.3))
tf = tb.text_frame
tf.word_wrap = True
p = tf.paragraphs[0]
p.text = "PHYSICAL DEVICE THEFT & APP LOCK HARDENING"
p.font.bold = True
p.font.size = Pt(11)
p.font.color.rgb = ACCENT_PURPLE
p2 = tf.add_paragraph()
p2.text = (
    "• Hardware Biometric Auth: Fingerprint/Face unlock protects message store on launch and resume.\n"
    "• SQLCipher AES-256 Encryption: Database unreadable if phone NAND flash is dumped or extracted.\n"
    "• FLAG_SECURE Protection: Blocks OS screenshots and screen recorders when app lock is active."
)
p2.font.size = Pt(10)
p2.font.color.rgb = TEXT_BODY

# Bottom stat chips
add_stat_box(slide11, Inches(0.8), Inches(6.5), Inches(4.3), Inches(0.7), "-120s / +60s", "STRICT TIMESTAMP FRESHNESS WINDOW", ACCENT_GOLD)
add_stat_box(slide11, Inches(5.3), Inches(6.5), Inches(4.3), Inches(0.7), "FLAG_SECURE", "HARDWARE SCREENSHOT & DUMP DEFENSE", ACCENT_GREEN)

# Right phone mockup: Security states
sec_screen = get_img(r"design_reference\stitch_meshwhisper_sahara_redesign\14._security_states\screen.png")
add_phone_mockup(slide11, sec_screen, Inches(10.1), Inches(1.5), max_height=Inches(5.5))

print("Slide 11 generated.")

# =============================================================================
# SLIDE 12: CROSS-PLATFORM ARCHITECTURE: ANDROID + DESKTOP
# =============================================================================
slide12 = prs.slides.add_slide(blank_layout)
set_background(slide12)
add_header(
    slide12,
    "CROSS-PLATFORM ENGINEERING • KOTLIN MULTIPLATFORM",
    "Unified Multiplatform Architecture Across Mobile & Desktop",
    "Shared Kotlin core library driving native Android mobile clients and desktop command stations."
)

c12_w = Inches(8.8)
c12_h = Inches(1.5)

# Module 1: :core
add_card(slide12, Inches(0.8), Inches(1.6), c12_w, c12_h)
tb = slide12.shapes.add_textbox(Inches(1.0), Inches(1.75), c12_w - Inches(0.4), c12_h - Inches(0.3))
tf = tb.text_frame
tf.word_wrap = True
p = tf.paragraphs[0]
p.text = "SHARED :core MODULE (100% PURE KOTLIN)"
p.font.bold = True
p.font.size = Pt(11)
p.font.color.rgb = ACCENT_GOLD
p2 = tf.add_paragraph()
p2.text = (
    "• Wire Protocol Codecs: MeshPacket serialization, PacketPipeline stage chokepoint, and LinkAuthSession state machine.\n"
    "• Cryptographic Primitives: PureCryptoEngine (Ed25519, X25519, ChaCha20-Poly1305, IBC certificates, HMAC-SHA256).\n"
    "• Mesh Routing Logic: MeshRouteEngine, LruDedupCache, and 4-tier TrafficController queue scheduler."
)
p2.font.size = Pt(10)
p2.font.color.rgb = TEXT_BODY

# Module 2: :app
add_card(slide12, Inches(0.8), Inches(3.25), c12_w, c12_h)
tb = slide12.shapes.add_textbox(Inches(1.0), Inches(3.4), c12_w - Inches(0.4), c12_h - Inches(0.3))
tf = tb.text_frame
tf.word_wrap = True
p = tf.paragraphs[0]
p.text = "NATIVE ANDROID CLIENT (:app)"
p.font.bold = True
p.font.size = Pt(11)
p.font.color.rgb = ACCENT_CYAN
p2 = tf.add_paragraph()
p2.text = (
    "• Modern UI: 100% Jetpack Compose with Sahara Dark design system, dynamic animations, and vector graphics.\n"
    "• Simultaneous Dual BLE Engine: Runs as GATT Central (initiator) and GATT Server (peripheral) simultaneously.\n"
    "• Native Capabilities: BiometricPrompt, CameraX QR scanning, Android AudioRecord/AudioTrack Opus streaming, Room SQLCipher."
)
p2.font.size = Pt(10)
p2.font.color.rgb = TEXT_BODY

# Module 3: :desktop
add_card(slide12, Inches(0.8), Inches(4.9), c12_w, c12_h)
tb = slide12.shapes.add_textbox(Inches(1.0), Inches(5.05), c12_w - Inches(0.4), c12_h - Inches(0.3))
tf = tb.text_frame
tf.word_wrap = True
p = tf.paragraphs[0]
p.text = "DESKTOP COMMAND STATION (:desktop)"
p.font.bold = True
p.font.size = Pt(11)
p.font.color.rgb = ACCENT_PURPLE
p2 = tf.add_paragraph()
p2.text = (
    "• Compose Multiplatform: Native desktop UI with Skia graphics hardware acceleration (Windows, macOS, Linux).\n"
    "• High-Speed Wi-Fi TCP Engine: Java NIO non-blocking TCP server coordinating local LAN and Wi-Fi mesh relays.\n"
    "• Command Center Features: Real-time packet telemetry, routing table inspection, and emergency broadcast dispatch."
)
p2.font.size = Pt(10)
p2.font.color.rgb = TEXT_BODY

# Bottom badges
add_stat_box(slide12, Inches(0.8), Inches(6.55), Inches(2.7), Inches(0.65), "100% Shared", "CORE PROTOCOL LOGIC", ACCENT_GOLD)
add_stat_box(slide12, Inches(3.7), Inches(6.55), Inches(2.7), Inches(0.65), "Jetpack Compose", "MODERN REACTIVE UI", ACCENT_CYAN)
add_stat_box(slide12, Inches(6.6), Inches(6.55), Inches(3.0), Inches(0.65), "3 OS Targets", "WINDOWS, MAC, LINUX & ANDROID", ACCENT_PURPLE)

# Right phone mockup: Biometric unlock
bio_screen = get_img(r"design_reference\stitch_meshwhisper_sahara_redesign\1._biometric_unlock\screen.png")
add_phone_mockup(slide12, bio_screen, Inches(10.1), Inches(1.5), max_height=Inches(5.5))

print("Slide 12 generated.")

# =============================================================================
# SLIDE 13: EMPIRICAL FIELD BENCHMARKS & METRICS
# =============================================================================
slide13 = prs.slides.add_slide(blank_layout)
set_background(slide13)
add_header(
    slide13,
    "PERFORMANCE VALIDATION • EMPIRICAL BENCHMARKS",
    "Validated Across Low-End Android Smartphones and Workstations",
    "Low power consumption, high throughput, and millisecond latency on consumer devices."
)

# 4 Highlight Metric Cards at Top
add_stat_box(slide13, Inches(0.8), Inches(1.6), Inches(2.7), Inches(1.5), "18 - 35 ms", "BLE SINGLE-HOP LATENCY\n(Direct GATT Write NO_RESPONSE)", ACCENT_GOLD)
add_stat_box(slide13, Inches(3.7), Inches(1.6), Inches(2.7), Inches(1.5), "< 4.2 ms", "WI-FI / TCP HOP LATENCY\n(Local LAN & Direct Links)", ACCENT_CYAN)
add_stat_box(slide13, Inches(6.6), Inches(1.6), Inches(2.7), Inches(1.5), "64 kbps", "SUSTAINED BLE 2M PHY\n(Real-Time Audio & Media)", ACCENT_GREEN)
add_stat_box(slide13, Inches(9.5), Inches(1.6), Inches(3.0), Inches(1.5), "< 2.5 %", "8-HOUR BATTERY DRAIN\n(Background Low-Power BLE)", ACCENT_PURPLE)

# 2 Deep Technical Invariant Cards at Bottom
add_card(slide13, Inches(0.8), Inches(3.35), Inches(5.6), Inches(3.6))
tb = slide13.shapes.add_textbox(Inches(1.0), Inches(3.55), Inches(5.2), Inches(3.2))
tf = tb.text_frame
tf.word_wrap = True
p = tf.paragraphs[0]
p.text = "RADIO LINK STABILITY & CONNECTION BENCHMARKS"
p.font.bold = True
p.font.size = Pt(11)
p.font.color.rgb = ACCENT_GOLD
p2 = tf.add_paragraph()
p2.text = (
    "• Handshake Speed: Full 3-way LINK_AUTH handshake (HELLO → CONFIRM → AUTH) completes in < 450 ms over BLE.\n"
    "• Collision Immunity: 4000ms reconnect cooldown + deterministic Node ID tie-breaker eliminates GATT 133 status collisions.\n"
    "• Multi-Link Concurrency: Tested reliably with 5 concurrent direct BLE connections per node.\n"
    "• Flight Mode Resilience: Auto-recovers mesh engine within 1.2s when Bluetooth radio is toggled."
)
p2.font.size = Pt(10.5)
p2.font.color.rgb = TEXT_BODY

add_card(slide13, Inches(6.8), Inches(3.35), Inches(5.7), Inches(3.6))
tb = slide13.shapes.add_textbox(Inches(7.0), Inches(3.55), Inches(5.3), Inches(3.2))
tf = tb.text_frame
tf.word_wrap = True
p = tf.paragraphs[0]
p.text = "MEMORY, CPU & STORAGE EFFICIENCY"
p.font.bold = True
p.font.size = Pt(11)
p.font.color.rgb = ACCENT_CYAN
p2 = tf.add_paragraph()
p2.text = (
    "• Compact APK Footprint: Total production APK size is under 28 MB.\n"
    "• Low Heap Utilization: Idle heap footprint < 38 MB; peak heap < 65 MB during multi-peer voice streaming.\n"
    "• Zero Memory Leaks: 50,000-entry LRU ring buffer maintains strict upper memory bound; unauthenticated packets drop instantly.\n"
    "• Instant Cold Boot: Starts and begins ambient advertising in under 800 ms."
)
p2.font.size = Pt(10.5)
p2.font.color.rgb = TEXT_BODY

print("Slide 13 generated.")

# =============================================================================
# SLIDE 14: REAL-WORLD MISSION DEPLOYMENTS
# =============================================================================
slide14 = prs.slides.add_slide(blank_layout)
set_background(slide14)
add_header(
    slide14,
    "MISSION DEPLOYMENTS • REAL-WORLD APPLICATIONS",
    "Where MeshWhisper Saves Lives & Restores Communication",
    "Built for disaster responders, wilderness expeditions, privacy activists, and tactical teams."
)

s_w = Inches(4.3)
s_h = Inches(2.5)

# Sector 1
add_card(slide14, Inches(0.8), Inches(1.6), s_w, s_h)
tb = slide14.shapes.add_textbox(Inches(1.0), Inches(1.75), s_w - Inches(0.4), s_h - Inches(0.3))
tf = tb.text_frame
tf.word_wrap = True
p = tf.paragraphs[0]
p.text = "1. DISASTER RELIEF & FIRST RESPONDERS"
p.font.bold = True
p.font.size = Pt(11)
p.font.color.rgb = ACCENT_RED
p2 = tf.add_paragraph()
p2.text = (
    "• Earthquake, tsunami, flood, and hurricane disaster zones where cell towers and fiber backhauls are severed.\n"
    "• Search & Rescue (SAR) teams coordinate survivor extraction using offline maps and directional compass homing.\n"
    "• Triage field clinics exchange patient vitals and supply requisitions over local mesh."
)
p2.font.size = Pt(10)
p2.font.color.rgb = TEXT_BODY

# Sector 2
add_card(slide14, Inches(5.3), Inches(1.6), s_w, s_h)
tb = slide14.shapes.add_textbox(Inches(5.5), Inches(1.75), s_w - Inches(0.4), s_h - Inches(0.3))
tf = tb.text_frame
tf.word_wrap = True
p = tf.paragraphs[0]
p.text = "2. WILDERNESS & REMOTE EXPEDITIONS"
p.font.bold = True
p.font.size = Pt(11)
p.font.color.rgb = ACCENT_GOLD
p2 = tf.add_paragraph()
p2.text = (
    "• Mountaineering, hiking, and caving expeditions far beyond cellular tower ranges.\n"
    "• Offshore maritime crews and fishing boats communicating ship-to-ship without satellite bills.\n"
    "• Remote scientific research stations in polar and desert environments."
)
p2.font.size = Pt(10)
p2.font.color.rgb = TEXT_BODY

# Sector 3
add_card(slide14, Inches(0.8), Inches(4.3), s_w, s_h)
tb = slide14.shapes.add_textbox(Inches(1.0), Inches(4.45), s_w - Inches(0.4), s_h - Inches(0.3))
tf = tb.text_frame
tf.word_wrap = True
p = tf.paragraphs[0]
p.text = "3. CIVIL DEFENSE & CENSORSHIP EVASION"
p.font.bold = True
p.font.size = Pt(11)
p.font.color.rgb = ACCENT_CYAN
p2 = tf.add_paragraph()
p2.text = (
    "• Citizens maintaining decentralized communication during government internet blackouts and telecom cutoffs.\n"
    "• Human rights advocates and journalists communicating without leaving telephone metadata or cell tower logs.\n"
    "• Complete immunity against DNS hijacking, BGP blocking, and Deep Packet Inspection (DPI)."
)
p2.font.size = Pt(10)
p2.font.color.rgb = TEXT_BODY

# Sector 4
add_card(slide14, Inches(5.3), Inches(4.3), s_w, s_h)
tb = slide14.shapes.add_textbox(Inches(5.5), Inches(4.45), s_w - Inches(0.4), s_h - Inches(0.3))
tf = tb.text_frame
tf.word_wrap = True
p = tf.paragraphs[0]
p.text = "4. TACTICAL & DEFENSE FIELD OPERATIONS"
p.font.bold = True
p.font.size = Pt(11)
p.font.color.rgb = ACCENT_GREEN
p2 = tf.add_paragraph()
p2.text = (
    "• Low-Probability-of-Detection (LPD) squad coordination without emitting high-power cellular RF signatures.\n"
    "• Secure air-gapped team messaging in electronic warfare environments.\n"
    "• Instant base-camp perimeter telemetry, boundary tripwire alerts, and scout check-ins."
)
p2.font.size = Pt(10)
p2.font.color.rgb = TEXT_BODY

# Right phone mockup: Direct messages
dm_screen = get_img(r"design_reference\stitch_meshwhisper_sahara_redesign\3._direct_messages\screen.png")
add_phone_mockup(slide14, dm_screen, Inches(10.1), Inches(1.5), max_height=Inches(5.5))

print("Slide 14 generated.")

# =============================================================================
# SLIDE 15: COMPARATIVE ANALYSIS (MESHWISPER VS EXISTING TOOLS)
# =============================================================================
slide15 = prs.slides.add_slide(blank_layout)
set_background(slide15)
add_header(
    slide15,
    "COMPETITIVE ANALYSIS • INNOVATION MATRIX",
    "How MeshWhisper Outperforms Existing Communication Systems",
    "Detailed feature-by-feature engineering comparison against cloud messengers and radio tools."
)

table_left   = Inches(0.8)
table_top    = Inches(1.6)
table_width  = Inches(11.7)
table_height = Inches(5.3)

shape_tbl = slide15.shapes.add_table(8, 5, table_left, table_top, table_width, table_height)
tbl = shape_tbl.table

tbl.columns[0].width = Inches(3.3)
tbl.columns[1].width = Inches(2.2)
tbl.columns[2].width = Inches(2.1)
tbl.columns[3].width = Inches(2.1)
tbl.columns[4].width = Inches(2.0)

headers = ["CAPABILITY / SPECIFICATION", "MESHWISPER (OUR APP)", "SIGNAL / WHATSAPP", "BRIAR / BRIDGEFY", "MESHTASTIC (LORA)"]
for col_idx, h_text in enumerate(headers):
    cell = tbl.cell(0, col_idx)
    cell.fill.solid()
    cell.fill.fore_color.rgb = SURFACE_ALT
    p = cell.text_frame.paragraphs[0]
    p.text = h_text
    p.font.name = FONT_HEADING
    p.font.size = Pt(10)
    p.font.bold = True
    p.font.color.rgb = ACCENT_GOLD if col_idx == 1 else TEXT_TITLE
    p.alignment = PP_ALIGN.CENTER if col_idx > 0 else PP_ALIGN.LEFT

matrix_data = [
    ("Zero Infrastructure (100% Serverless)", "YES (Ad-Hoc P2P)", "NO (Cloud Servers)", "YES (P2P Mesh)", "YES (LoRa Mesh)"),
    ("Real-Time P2P Voice Calling", "YES (Opus Wideband)", "NO (Server-Relayed)", "NO (Text Only)", "NO (Text Only)"),
    ("Commodity Phones (No Extra Radios)", "YES (Native Android)", "YES (Cellular/Wi-Fi)", "YES (Android)", "NO (Requires $50-100 Radio)"),
    ("Hybrid Dual Transport (BLE + Wi-Fi)", "YES (Dynamic Bridge)", "NO (Internet Only)", "NO (Tor/Wi-Fi Only)", "NO (LoRa Radio Only)"),
    ("Progressive High-Res Media Transfer", "YES (450B Tiled Reveal)", "NO (Cloud Hosted)", "NO (Very Slow/Fails)", "NO (Too Narrow Bandwidth)"),
    ("Tactical Navigation & Radar Homing", "YES (Built-In RSSI)", "NO", "NO", "YES (GPS Only)"),
    ("Hardware-Backed Storage & Anti-Tamper", "YES (SQLCipher AES-256)", "YES (Local SQLite)", "NO (Plaintext/Basic)", "NO (MCU Flash)")
]

for row_idx, row_values in enumerate(matrix_data, start=1):
    for col_idx, val in enumerate(row_values):
        cell = tbl.cell(row_idx, col_idx)
        cell.fill.solid()
        cell.fill.fore_color.rgb = SURFACE_COLOR if row_idx % 2 == 0 else RGBColor(16, 22, 36)
        p = cell.text_frame.paragraphs[0]
        p.text = val
        p.font.name = FONT_BODY
        p.font.size = Pt(10)
        p.alignment = PP_ALIGN.CENTER if col_idx > 0 else PP_ALIGN.LEFT
        
        # Color highlighting
        if col_idx == 1:
            p.font.bold = True
            p.font.color.rgb = ACCENT_GREEN
        elif val.startswith("YES"):
            p.font.color.rgb = ACCENT_CYAN
        elif val.startswith("NO"):
            p.font.color.rgb = ACCENT_RED
        else:
            p.font.color.rgb = TEXT_BODY

print("Slide 15 generated.")

# =============================================================================
# SLIDE 16: ROADMAP, FUTURE HORIZONS & CONCLUSION
# =============================================================================
slide16 = prs.slides.add_slide(blank_layout)
set_background(slide16)
add_header(
    slide16,
    "FUTURE ROADMAP • VISION & CONCLUSION",
    "The Future of Resilient Communications & Conclusion",
    "Communication is a fundamental human right that must never depend on telecom monopolies or power grids."
)

r_w = Inches(8.8)
r_h = Inches(1.35)

# Roadmap 1: LoRa
add_card(slide16, Inches(0.8), Inches(1.6), r_w, r_h)
tb = slide16.shapes.add_textbox(Inches(1.0), Inches(1.75), r_w - Inches(0.4), r_h - Inches(0.3))
tf = tb.text_frame
tf.word_wrap = True
p = tf.paragraphs[0]
p.text = "PHASE 1: EXTERNAL HARDWARE RADIO BRIDGING (LoRa SX1262)"
p.font.bold = True
p.font.size = Pt(11)
p.font.color.rgb = ACCENT_GOLD
p2 = tf.add_paragraph()
p2.text = (
    "• BLE Serial Bridge to low-cost external LoRa radios, extending single-hop range from 100m to 15–30 km.\n"
    "• Seamless protocol multiplexing: automatically routes urgent packets over LoRa and bulk media over local Wi-Fi."
)
p2.font.size = Pt(10)
p2.font.color.rgb = TEXT_BODY

# Roadmap 2: Post-Quantum
add_card(slide16, Inches(0.8), Inches(3.1), r_w, r_h)
tb = slide16.shapes.add_textbox(Inches(1.0), Inches(3.25), r_w - Inches(0.4), r_h - Inches(0.3))
tf = tb.text_frame
tf.word_wrap = True
p = tf.paragraphs[0]
p.text = "PHASE 2: POST-QUANTUM CRYPTOGRAPHIC RESILIENCE (ML-KEM / Kyber)"
p.font.bold = True
p.font.size = Pt(11)
p.font.color.rgb = ACCENT_CYAN
p2 = tf.add_paragraph()
p2.text = (
    "• Implementing NIST-standardized ML-KEM-768 (Kyber) key encapsulation and ML-DSA (Dilithium) signatures.\n"
    "• Future-proof protection against quantum decrypt-now attacks on harvested mesh radio traffic."
)
p2.font.size = Pt(10)
p2.font.color.rgb = TEXT_BODY

# Roadmap 3: Native iOS
add_card(slide16, Inches(0.8), Inches(4.6), r_w, r_h)
tb = slide16.shapes.add_textbox(Inches(1.0), Inches(4.75), r_w - Inches(0.4), r_h - Inches(0.3))
tf = tb.text_frame
tf.word_wrap = True
p = tf.paragraphs[0]
p.text = "PHASE 3: NATIVE iOS CLIENT & UNIVERSAL ECOSYSTEM"
p.font.bold = True
p.font.size = Pt(11)
p.font.color.rgb = ACCENT_GREEN
p2 = tf.add_paragraph()
p2.text = (
    "• Swift / CoreBluetooth client sharing identical wire protocol format and IBC cryptographic certificates.\n"
    "• Complete inter-operability between Android smartphones, iPhones, Windows, Mac, and Linux PCs."
)
p2.font.size = Pt(10)
p2.font.color.rgb = TEXT_BODY

# Keynote Closing Banner
add_card(slide16, Inches(0.8), Inches(6.1), r_w, Inches(0.95), bg_color=SURFACE_ALT, border_color=BORDER_ACTIVE)
tb_c = slide16.shapes.add_textbox(Inches(1.0), Inches(6.18), r_w - Inches(0.4), Inches(0.8))
tf_c = tb_c.text_frame
tf_c.word_wrap = True
p_c1 = tf_c.paragraphs[0]
p_c1.text = "CONCLUSION: When the grid goes dark, MeshWhisper keeps the human voice alive."
p_c1.font.bold = True
p_c1.font.size = Pt(11.5)
p_c1.font.color.rgb = ACCENT_GOLD
p_c2 = tf_c.add_paragraph()
p_c2.text = "Open Source Repository • Production-Tested • Zero Cloud Dependency"
p_c2.font.size = Pt(10)
p_c2.font.color.rgb = TEXT_TITLE

# Right phone mockup: Settings
set_screen = get_img(r"design_reference\stitch_meshwhisper_sahara_redesign\11._identity_settings\screen.png")
add_phone_mockup(slide16, set_screen, Inches(10.1), Inches(1.5), max_height=Inches(5.5))

print("Slide 16 generated.")

# =============================================================================
# SAVE PRESENTATION
# =============================================================================
output_pptx = os.path.join(IMG_BASE, "MeshWhisper_Comprehensive_Presentation.pptx")
prs.save(output_pptx)
print(f"Presentation saved successfully to: {output_pptx}")
