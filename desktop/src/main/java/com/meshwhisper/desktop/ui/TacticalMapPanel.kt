package com.meshwhisper.desktop.ui

import com.meshwhisper.core.util.PlusCodeHelper
import com.meshwhisper.desktop.db.DesktopDatabase
import com.meshwhisper.desktop.db.DesktopPeer
import com.meshwhisper.desktop.router.DesktopMeshRouter
import java.awt.*
import java.awt.datatransfer.StringSelection
import java.awt.event.MouseAdapter
import java.awt.event.MouseEvent
import java.awt.geom.AffineTransform
import java.awt.geom.RoundRectangle2D
import java.text.SimpleDateFormat
import java.util.*
import javax.swing.*
import javax.swing.border.EmptyBorder
import javax.swing.border.LineBorder
import kotlin.math.*

/**
 * 100% Offline Tactical Map & Emergency Rescue Homing Panel for Desktop Station.
 * Full parity with mobile Campus Radar, Offline Map, and Compass Homing screens.
 */
class TacticalMapPanel(
    private val database: DesktopDatabase,
    private val router: DesktopMeshRouter,
    private val onOpenChat: (peerId: Long) -> Unit
) : JPanel(BorderLayout(14, 0)) {

    // Default Host Station Anchor (Bangalore campus / custom benchmark if no GPS fix)
    private var hostLat = 12.971598
    private var hostLon = 77.594562

    private var selectedPeer: DesktopPeer? = null
    private val mapCanvas = TacticalMapCanvas()
    private val homingPanel = TacticalHomingPanel()

    init {
        background = ModernTheme.BG_MAIN
        border = EmptyBorder(16, 16, 16, 16)

        add(mapCanvas, BorderLayout.CENTER)
        add(homingPanel, BorderLayout.EAST)

        // Periodic refresh of nodes
        javax.swing.Timer(1200) {
            val peers = database.getAllPeers()
            mapCanvas.updatePeers(peers)
            if (selectedPeer != null) {
                val updated = peers.firstOrNull { it.nodeId == selectedPeer?.nodeId }
                if (updated != null) {
                    selectedPeer = updated
                    homingPanel.updateSelectedPeer(updated)
                }
            } else if (peers.isNotEmpty()) {
                selectedPeer = peers.first()
                homingPanel.updateSelectedPeer(selectedPeer)
            }
        }.start()
    }

    inner class TacticalMapCanvas : JPanel() {
        private var peersList = listOf<DesktopPeer>()
        private var zoomScale = 1.0
        private var pulseAlpha = 0f
        private var pulseDirection = 0.05f

        init {
            background = Color(0x0A, 0x0D, 0x12)
            border = LineBorder(ModernTheme.BORDER_COLOR, 1, true)
            cursor = Cursor.getPredefinedCursor(Cursor.CROSSHAIR_CURSOR)

            val animTimer = javax.swing.Timer(30) {
                pulseAlpha += pulseDirection
                if (pulseAlpha >= 1f) {
                    pulseAlpha = 1f
                    pulseDirection = -0.04f
                } else if (pulseAlpha <= 0.2f) {
                    pulseAlpha = 0.2f
                    pulseDirection = 0.04f
                }
                repaint()
            }
            animTimer.start()

            addMouseListener(object : MouseAdapter() {
                override fun mouseClicked(e: MouseEvent) {
                    val clicked = findPeerAt(e.x, e.y)
                    if (clicked != null) {
                        selectedPeer = clicked
                        homingPanel.updateSelectedPeer(clicked)
                        repaint()
                    }
                }
            })
        }

        fun updatePeers(peers: List<DesktopPeer>) {
            peersList = peers
            repaint()
        }

        private fun findPeerAt(mouseX: Int, mouseY: Int): DesktopPeer? {
            val cx = width / 2
            val cy = height / 2

            for (p in peersList) {
                val (px, py) = calculateScreenPos(p, cx, cy)
                val dist = hypot((mouseX - px).toDouble(), (mouseY - py).toDouble())
                if (dist <= 22.0) {
                    return p
                }
            }
            return null
        }

        private fun calculateScreenPos(peer: DesktopPeer, cx: Int, cy: Int): Pair<Int, Int> {
            val metersPerPixel = 1.5 / zoomScale

            if (peer.latitude != 0.0 && peer.longitude != 0.0) {
                val dLat = peer.latitude - hostLat
                val dLon = peer.longitude - hostLon
                val latMeters = dLat * 111320.0
                val lonMeters = dLon * (111320.0 * cos(Math.toRadians(hostLat)))

                val px = cx + (lonMeters / metersPerPixel).toInt()
                val py = cy - (latMeters / metersPerPixel).toInt()
                return Pair(px, py)
            } else {
                // Synthetic polar layout based on node ID and hops
                val seed = (peer.nodeId and 0xFFFF).toDouble()
                val angle = (seed % 360.0) * (PI / 180.0)
                val distMeters = 40.0 + (peer.hops * 45.0)

                val px = cx + (cos(angle) * (distMeters / metersPerPixel)).toInt()
                val py = cy + (sin(angle) * (distMeters / metersPerPixel)).toInt()
                return Pair(px, py)
            }
        }

        override fun paintComponent(g: Graphics) {
            super.paintComponent(g)
            val g2 = g as? Graphics2D ?: return
            g2.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON)
            g2.setRenderingHint(RenderingHints.KEY_TEXT_ANTIALIASING, RenderingHints.VALUE_TEXT_ANTIALIAS_ON)

            val w = width
            val h = height
            val cx = w / 2
            val cy = h / 2

            // 1. Blueprint Grid Background
            g2.color = Color(0x13, 0x1A, 0x24)
            val gridSize = 45
            var x = 0
            while (x < w) {
                g2.drawLine(x, 0, x, h)
                x += gridSize
            }
            var y = 0
            while (y < h) {
                g2.drawLine(0, y, w, y)
                y += gridSize
            }

            // 2. Tactical Concentric Distance Range Rings
            val ringMeters = intArrayOf(25, 50, 100, 200, 400)
            val metersPerPixel = 1.5 / zoomScale

            for (m in ringMeters) {
                val radiusPx = (m / metersPerPixel).toInt()
                g2.color = Color(0x1A, 0x26, 0x36)
                g2.stroke = BasicStroke(1.0f, BasicStroke.CAP_BUTT, BasicStroke.JOIN_MITER, 10.0f, floatArrayOf(4f, 4f), 0f)
                g2.drawOval(cx - radiusPx, cy - radiusPx, radiusPx * 2, radiusPx * 2)

                g2.color = Color(0x4B, 0x5E, 0x76)
                g2.font = ModernTheme.FONT_SMALL
                g2.drawString("${m}m", cx + 6, cy - radiusPx + 14)
            }

            // 3. Coordinate Crosshairs & Cardinal Labels
            g2.stroke = BasicStroke(1.2f)
            g2.color = Color(0x22, 0x30, 0x42)
            g2.drawLine(cx, 0, cx, h)
            g2.drawLine(0, cy, w, cy)

            g2.font = Font("Segoe UI", Font.BOLD, 12)
            g2.color = ModernTheme.PRIMARY_HOVER
            g2.drawString("N (000°)", cx - 22, 22)
            g2.drawString("S (180°)", cx - 20, h - 12)
            g2.drawString("E (090°)", w - 50, cy - 8)
            g2.drawString("W (270°)", 10, cy - 8)

            // 4. Center Host Station Pin (Me)
            g2.color = Color(0xC2, 0x65, 0x2A, 60)
            g2.fillOval(cx - 20, cy - 20, 40, 40)
            g2.color = ModernTheme.PRIMARY_HOVER
            g2.fillOval(cx - 10, cy - 10, 20, 20)
            g2.color = Color.WHITE
            g2.fillOval(cx - 4, cy - 4, 8, 8)

            g2.font = ModernTheme.FONT_BODY_BOLD
            g2.color = ModernTheme.TEXT_MAIN
            g2.drawString("📍 HOST STATION", cx - 50, cy + 28)
            g2.font = ModernTheme.FONT_SMALL
            g2.color = ModernTheme.TEXT_MUTED
            g2.drawString("0x${router.myNodeIdHex.takeLast(4)} • ${router.myAlias}", cx - 42, cy + 42)

            // 5. Draw Discovered Peer Pins
            for (p in peersList) {
                val (px, py) = calculateScreenPos(p, cx, cy)
                val isSelected = (selectedPeer?.nodeId == p.nodeId)
                val isEmergency = (p.locationTrigger == "MANUAL_SOS" || p.locationTrigger == "BATTERY_CRITICAL_5")

                // Tactical Line from Host to Peer
                if (isSelected) {
                    g2.color = if (isEmergency) Color(0xEF, 0x44, 0x44, 180) else Color(0x10, 0xB9, 0x81, 180)
                    g2.stroke = BasicStroke(2.0f, BasicStroke.CAP_ROUND, BasicStroke.JOIN_ROUND, 10.0f, floatArrayOf(6f, 4f), 0f)
                    g2.drawLine(cx, cy, px, py)
                }

                // Node Aura
                val auraColor = if (isEmergency) {
                    Color(0xEF, 0x44, 0x44, (pulseAlpha * 160).toInt())
                } else if (isSelected) {
                    Color(0x10, 0xB9, 0x81, 140)
                } else {
                    Color(0x10, 0xB9, 0x81, 50)
                }
                g2.color = auraColor
                val auraRadius = if (isEmergency) 24 else (if (isSelected) 20 else 14)
                g2.fillOval(px - auraRadius, py - auraRadius, auraRadius * 2, auraRadius * 2)

                // Node Core
                g2.color = if (isEmergency) ModernTheme.SOS else ModernTheme.ONLINE
                g2.fillOval(px - 7, py - 7, 14, 14)
                g2.color = Color.WHITE
                g2.fillOval(px - 3, py - 3, 6, 6)

                // Selection Ring
                if (isSelected) {
                    g2.color = Color.WHITE
                    g2.stroke = BasicStroke(1.8f)
                    g2.drawOval(px - 16, py - 16, 32, 32)
                }

                // Node Label Box
                val label = if (isEmergency) "🚨 ${p.alias}" else p.alias
                g2.font = ModernTheme.FONT_BODY_BOLD
                val fm = g2.fontMetrics
                val textW = fm.stringWidth(label)

                g2.color = Color(0x11, 0x16, 0x20, 220)
                g2.fillRoundRect(px - textW / 2 - 6, py - 28, textW + 12, 18, 6, 6)
                g2.color = if (isEmergency) ModernTheme.SOS else (if (isSelected) ModernTheme.PRIMARY_HOVER else ModernTheme.TEXT_MAIN)
                g2.drawString(label, px - textW / 2, py - 14)

                // Secondary Info (Plus Code or Hops)
                val subText = p.plusCode ?: "0x${String.format("%016X", p.nodeId).takeLast(4)}"
                g2.font = ModernTheme.FONT_SMALL
                val subW = g2.fontMetrics.stringWidth(subText)
                g2.color = ModernTheme.TEXT_MUTED
                g2.drawString(subText, px - subW / 2, py + 22)
            }
        }
    }

    inner class TacticalHomingPanel : JPanel() {
        private val titleLabel = JLabel("Select a peer to track")
        private val compassCanvas = CompassCanvas()

        private val distanceLabel = JLabel("Distance: -- m")
        private val bearingLabel = JLabel("Bearing: --°")
        private val plusCodeLabel = JLabel("Plus Code: --")
        private val coordinatesLabel = JLabel("GPS: --")
        private val batteryLabel = JLabel("Battery: --")
        private val accuracyLabel = JLabel("Accuracy: --")
        private val triggerBadge = JLabel("STATUS: NORMAL")

        init {
            preferredSize = Dimension(340, 0)
            background = ModernTheme.BG_SIDEBAR
            border = BorderFactory.createCompoundBorder(
                LineBorder(ModernTheme.BORDER_COLOR, 1, true),
                EmptyBorder(16, 16, 16, 16)
            )
            layout = BoxLayout(this, BoxLayout.Y_AXIS)

            // Header
            titleLabel.apply {
                font = ModernTheme.FONT_TITLE
                foreground = ModernTheme.TEXT_MAIN
                alignmentX = Component.LEFT_ALIGNMENT
            }
            add(titleLabel)
            add(Box.createVerticalStrut(10))

            triggerBadge.apply {
                font = ModernTheme.FONT_SMALL
                foreground = ModernTheme.ONLINE
                border = BorderFactory.createCompoundBorder(
                    LineBorder(ModernTheme.ONLINE, 1, true),
                    EmptyBorder(2, 8, 2, 8)
                )
                alignmentX = Component.LEFT_ALIGNMENT
            }
            add(triggerBadge)
            add(Box.createVerticalStrut(14))

            // Compass Dial
            compassCanvas.alignmentX = Component.LEFT_ALIGNMENT
            add(compassCanvas)
            add(Box.createVerticalStrut(16))

            // Telemetry Cards
            fun createTelemetryRow(label: JLabel, icon: String): JPanel {
                return JPanel(FlowLayout(FlowLayout.LEFT, 8, 0)).apply {
                    isOpaque = false
                    alignmentX = Component.LEFT_ALIGNMENT
                    val ic = JLabel(icon).apply { font = Font("Segoe UI Emoji", Font.PLAIN, 14) }
                    label.font = ModernTheme.FONT_BODY
                    label.foreground = ModernTheme.TEXT_MAIN
                    add(ic)
                    add(label)
                }
            }

            add(createTelemetryRow(distanceLabel, "📏"))
            add(Box.createVerticalStrut(8))
            add(createTelemetryRow(bearingLabel, "🧭"))
            add(Box.createVerticalStrut(8))
            add(createTelemetryRow(plusCodeLabel, "🏷️"))
            add(Box.createVerticalStrut(8))
            add(createTelemetryRow(coordinatesLabel, "🌐"))
            add(Box.createVerticalStrut(8))
            add(createTelemetryRow(batteryLabel, "🔋"))
            add(Box.createVerticalStrut(8))
            add(createTelemetryRow(accuracyLabel, "🎯"))
            add(Box.createVerticalStrut(20))

            // Action Buttons
            val btnBox = JPanel(GridLayout(2, 1, 0, 8)).apply {
                isOpaque = false
                alignmentX = Component.LEFT_ALIGNMENT
            }

            val copyBtn = ModernButton("📋 Copy Plus Code", ModernTheme.BG_CARD, ModernTheme.BG_CARD_HOVER, ModernTheme.TEXT_MAIN, 8).apply {
                addActionListener {
                    val p = selectedPeer
                    if (p?.plusCode != null) {
                        Toolkit.getDefaultToolkit().systemClipboard.setContents(StringSelection(p.plusCode), null)
                        JOptionPane.showMessageDialog(this@TacticalMapPanel, "Copied Plus Code: ${p.plusCode} to clipboard!")
                    }
                }
            }

            val chatBtn = ModernButton("💬 Message Peer", ModernTheme.PRIMARY, ModernTheme.PRIMARY_HOVER, Color.WHITE, 8).apply {
                addActionListener {
                    val p = selectedPeer
                    if (p != null) {
                        onOpenChat(p.nodeId)
                    }
                }
            }

            btnBox.add(copyBtn)
            btnBox.add(chatBtn)
            add(btnBox)
        }

        fun updateSelectedPeer(peer: DesktopPeer?) {
            if (peer == null) {
                titleLabel.text = "No Peer Selected"
                return
            }

            titleLabel.text = "📍 ${peer.alias} (0x${String.format("%016X", peer.nodeId).takeLast(4)})"

            val isEmergency = (peer.locationTrigger == "MANUAL_SOS" || peer.locationTrigger == "BATTERY_CRITICAL_5")
            if (isEmergency) {
                triggerBadge.text = "🚨 EMERGENCY SOS RESCUE"
                triggerBadge.foreground = ModernTheme.SOS
                triggerBadge.border = LineBorder(ModernTheme.SOS, 1, true)
            } else {
                triggerBadge.text = "STATUS: ONLINE (100% OFFLINE)"
                triggerBadge.foreground = ModernTheme.ONLINE
                triggerBadge.border = LineBorder(ModernTheme.ONLINE, 1, true)
            }

            // Calculate Distance & Bearing
            if (peer.latitude != 0.0 && peer.longitude != 0.0) {
                val dLat = peer.latitude - hostLat
                val dLon = peer.longitude - hostLon
                val latMeters = dLat * 111320.0
                val lonMeters = dLon * (111320.0 * cos(Math.toRadians(hostLat)))
                val dist = hypot(latMeters, lonMeters)

                var deg = Math.toDegrees(atan2(lonMeters, latMeters))
                if (deg < 0) deg += 360.0

                val cardinal = getCardinal(deg)
                distanceLabel.text = "Distance: ${if (dist >= 1000) String.format("%.2f km", dist / 1000.0) else "${dist.toInt()} meters"}"
                bearingLabel.text = "Bearing: ${String.format("%03d° %s", deg.toInt(), cardinal)}"
                compassCanvas.setBearing(deg)

                val pc = peer.plusCode ?: PlusCodeHelper.encode(peer.latitude, peer.longitude)
                plusCodeLabel.text = "Plus Code: $pc"
                coordinatesLabel.text = "GPS: ${String.format(Locale.US, "%.5f, %.5f", peer.latitude, peer.longitude)}"
                accuracyLabel.text = "Accuracy: ±${peer.accuracyMeters}m (Alt: ${peer.altitude.toInt()}m)"
            } else {
                val dist = 40.0 + (peer.hops * 45.0)
                val seed = (peer.nodeId and 0xFFFF).toDouble()
                val deg = seed % 360.0
                distanceLabel.text = "Estimated Dist: ~${dist.toInt()} m"
                bearingLabel.text = "Relative Angle: ${String.format("%03d°", deg.toInt())}"
                compassCanvas.setBearing(deg)
                plusCodeLabel.text = "Plus Code: Radio Est."
                coordinatesLabel.text = "GPS: No fix sent yet"
                accuracyLabel.text = "Hops: ${peer.hops} via Wi-Fi Mesh"
            }

            batteryLabel.text = if (peer.batteryPercent >= 0) "Battery: ${peer.batteryPercent}% ${if (peer.batteryPercent <= 15) "⚠️" else "⚡"}" else "Battery: Unreported"
        }

        private fun getCardinal(deg: Double): String {
            val directions = arrayOf("N", "NE", "E", "SE", "S", "SW", "W", "NW")
            val index = (((deg + 22.5) % 360.0) / 45.0).toInt()
            return directions[index]
        }
    }

    inner class CompassCanvas : JPanel() {
        private var targetBearingDeg = 0.0

        init {
            preferredSize = Dimension(280, 150)
            maximumSize = Dimension(280, 150)
            isOpaque = false
        }

        fun setBearing(deg: Double) {
            targetBearingDeg = deg
            repaint()
        }

        override fun paintComponent(g: Graphics) {
            super.paintComponent(g)
            val g2 = g as? Graphics2D ?: return
            g2.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON)

            val cx = width / 2
            val cy = height / 2
            val radius = 58

            // Dial Background
            g2.color = Color(0x13, 0x1A, 0x24)
            g2.fillOval(cx - radius, cy - radius, radius * 2, radius * 2)
            g2.color = ModernTheme.BORDER_COLOR
            g2.stroke = BasicStroke(1.5f)
            g2.drawOval(cx - radius, cy - radius, radius * 2, radius * 2)

            // Cardinal Letters
            g2.font = Font("Segoe UI", Font.BOLD, 10)
            g2.color = ModernTheme.PRIMARY_HOVER
            g2.drawString("N", cx - 4, cy - radius + 14)
            g2.color = ModernTheme.TEXT_MUTED
            g2.drawString("S", cx - 3, cy + radius - 6)
            g2.drawString("E", cx + radius - 12, cy + 4)
            g2.drawString("W", cx - radius + 5, cy + 4)

            // Target Direction Homing Needle
            val oldTx = g2.transform
            val rot = Math.toRadians(targetBearingDeg)
            g2.rotate(rot, cx.toDouble(), cy.toDouble())

            val needleLength = 46
            // North pointer (Orange/Sahara)
            val xPointsNorth = intArrayOf(cx, cx - 6, cx + 6)
            val yPointsNorth = intArrayOf(cy - needleLength, cy, cy)
            g2.color = ModernTheme.PRIMARY_HOVER
            g2.fillPolygon(xPointsNorth, yPointsNorth, 3)

            // South pointer (Dark Grey)
            val xPointsSouth = intArrayOf(cx, cx - 6, cx + 6)
            val yPointsSouth = intArrayOf(cy + needleLength, cy, cy)
            g2.color = Color(0x3B, 0x48, 0x5C)
            g2.fillPolygon(xPointsSouth, yPointsSouth, 3)

            // Center Pin
            g2.color = Color.WHITE
            g2.fillOval(cx - 4, cy - 4, 8, 8)
            g2.transform = oldTx
        }
    }
}
