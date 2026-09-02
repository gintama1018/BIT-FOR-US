package com.meshwhisper.desktop.ui

import com.meshwhisper.desktop.db.DesktopDatabase
import com.meshwhisper.desktop.db.DesktopMessage
import com.meshwhisper.desktop.db.DesktopPeer
import com.meshwhisper.desktop.router.DesktopMeshRouter
import com.meshwhisper.desktop.wifi.DesktopWifiEngine
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.launch
import java.awt.*
import java.awt.event.WindowAdapter
import java.awt.event.WindowEvent
import java.text.SimpleDateFormat
import java.util.*
import javax.swing.*
import javax.swing.border.EmptyBorder
import javax.swing.border.LineBorder

/**
 * Modern Sahara-Themed Desktop Graphical User Interface (GUI) for MeshWhisper.
 * Provides a rich multi-tab experience (Public Mesh, Direct Chats, Mesh Radar, Inspector, Settings).
 */
class DesktopMainWindow(
    private val router: DesktopMeshRouter,
    private val database: DesktopDatabase,
    private val wifiEngine: DesktopWifiEngine
) : JFrame("MeshWhisper — Offline Hybrid Mesh Station") {

    companion object {
        val COLOR_BG = Color(0xFA, 0xF5, 0xEE)
        val COLOR_SURFACE = Color(0xF3, 0xEC, 0xE0)
        val COLOR_CARD = Color(0xFF, 0xFF, 0xFF)
        val COLOR_PRIMARY = Color(0xC2, 0x65, 0x2A)
        val COLOR_PRIMARY_HOVER = Color(0xA9, 0x54, 0x1E)
        val COLOR_ACCENT = Color(0xD9, 0x77, 0x24)
        val COLOR_TEXT_PRIMARY = Color(0x2B, 0x24, 0x1E)
        val COLOR_TEXT_MUTED = Color(0x76, 0x6B, 0x61)
        val COLOR_SOS = Color(0xDC, 0x26, 0x26)
        val COLOR_SUCCESS = Color(0x16, 0xA3, 0x4A)
        val COLOR_BORDER = Color(0xE5, 0xDC, 0xCE)

        val FONT_TITLE = Font("Segoe UI", Font.BOLD, 18)
        val FONT_HEADER = Font("Segoe UI", Font.BOLD, 14)
        val FONT_BODY = Font("Segoe UI", Font.PLAIN, 13)
        val FONT_BODY_BOLD = Font("Segoe UI", Font.BOLD, 13)
        val FONT_MONO = Font("Consolas", Font.PLAIN, 12)
        val FONT_SMALL = Font("Segoe UI", Font.PLAIN, 11)
    }

    private val scope = CoroutineScope(Dispatchers.Main)
    private val timeFormat = SimpleDateFormat("HH:mm:ss", Locale.getDefault())

    // UI Components
    private val statusBadgeLabel = JLabel("⚡ 0 LAN PEERS • OFFLINE MESH READY")
    private val publicMessagesPanel = JPanel().apply {
        layout = BoxLayout(this, BoxLayout.Y_AXIS)
        background = COLOR_BG
    }
    private val publicScrollPane: JScrollPane
    private val publicInputField = JTextField()

    // Direct Chats UI
    private val peerListModel = DefaultListModel<DesktopPeer>()
    private val peerJList = JList(peerListModel)
    private var selectedPeer: DesktopPeer? = null
    private val dmChatTitleLabel = JLabel("Select a peer to start encrypted chat")
    private val dmMessagesPanel = JPanel().apply {
        layout = BoxLayout(this, BoxLayout.Y_AXIS)
        background = COLOR_BG
    }
    private val dmScrollPane: JScrollPane
    private val dmInputField = JTextField()

    // Inspector Logs UI
    private val logsTextArea = JTextArea().apply {
        isEditable = false
        font = FONT_MONO
        background = Color(0x1E, 0x1E, 0x1E)
        foreground = Color(0xD4, 0xD4, 0xD4)
    }

    // Radar Canvas
    private val radarCanvas = TopologyRadarCanvas(database, router.myNodeId)

    init {
        defaultCloseOperation = EXIT_ON_CLOSE
        setSize(1100, 750)
        minimumSize = Dimension(900, 600)
        setLocationRelativeTo(null)
        contentPane.background = COLOR_BG
        contentPane.layout = BorderLayout()

        // 1. Build Header Bar
        val headerPanel = createHeaderPanel()
        contentPane.add(headerPanel, BorderLayout.NORTH)

        // 2. Build Tabbed Center Pane
        val tabbedPane = JTabbedPane().apply {
            font = FONT_HEADER
            background = COLOR_SURFACE
            foreground = COLOR_TEXT_PRIMARY
        }

        publicScrollPane = JScrollPane(publicMessagesPanel).apply {
            border = null
            verticalScrollBar.unitIncrement = 16
        }
        val publicTab = createPublicMeshTab()
        tabbedPane.addTab("  💬 Public Mesh  ", publicTab)

        dmScrollPane = JScrollPane(dmMessagesPanel).apply {
            border = null
            verticalScrollBar.unitIncrement = 16
        }
        val dmTab = createDirectChatsTab()
        tabbedPane.addTab("  🔒 Direct Chats  ", dmTab)

        val radarTab = createRadarTab()
        tabbedPane.addTab("  📡 Mesh Radar  ", radarTab)

        val inspectorTab = createInspectorTab()
        tabbedPane.addTab("  📋 Packet Inspector  ", inspectorTab)

        val settingsTab = createSettingsTab()
        tabbedPane.addTab("  ⚙️ Settings & Identity  ", settingsTab)

        contentPane.add(tabbedPane, BorderLayout.CENTER)

        // Initial Data Load
        refreshPublicMessages()
        refreshPeersList()
        refreshLogs()

        // Background Listeners
        startBackgroundListeners()

        addWindowListener(object : WindowAdapter() {
            override fun windowClosing(e: WindowEvent?) {
                router.stop()
            }
        })
    }

    private fun createHeaderPanel(): JPanel {
        val panel = JPanel(BorderLayout()).apply {
            background = COLOR_SURFACE
            border = EmptyBorder(12, 16, 12, 16)
        }

        val leftBox = JPanel(FlowLayout(FlowLayout.LEFT, 12, 0)).apply {
            isOpaque = false
        }

        val logoIcon = JLabel("🛡️").apply {
            font = Font("Segoe UI Emoji", Font.PLAIN, 24)
        }
        leftBox.add(logoIcon)

        val titleBox = JPanel().apply {
            layout = BoxLayout(this, BoxLayout.Y_AXIS)
            isOpaque = false
        }
        val appTitle = JLabel("MeshWhisper Desktop Station").apply {
            font = FONT_TITLE
            foreground = COLOR_TEXT_PRIMARY
        }
        val subTitle = JLabel("Node: 0x${router.myNodeIdHex} (${router.myAlias})").apply {
            font = FONT_SMALL
            foreground = COLOR_TEXT_MUTED
        }
        titleBox.add(appTitle)
        titleBox.add(subTitle)
        leftBox.add(titleBox)

        panel.add(leftBox, BorderLayout.WEST)

        val rightBox = JPanel(FlowLayout(FlowLayout.RIGHT, 12, 0)).apply {
            isOpaque = false
        }

        statusBadgeLabel.apply {
            font = FONT_BODY_BOLD
            foreground = COLOR_PRIMARY
            border = LineBorder(COLOR_PRIMARY, 1, true)
            border = BorderFactory.createCompoundBorder(
                border,
                EmptyBorder(4, 10, 4, 10)
            )
        }
        rightBox.add(statusBadgeLabel)

        val sosButton = JButton("🚨 SEND SOS").apply {
            font = FONT_BODY_BOLD
            foreground = Color.WHITE
            background = COLOR_SOS
            isFocusPainted = false
            cursor = Cursor.getPredefinedCursor(Cursor.HAND_CURSOR)
            border = EmptyBorder(8, 16, 8, 16)
            addActionListener {
                val emergencyText = JOptionPane.showInputDialog(
                    this@DesktopMainWindow,
                    "Enter urgent emergency message to broadcast across all mesh nodes:",
                    "🚨 Broadcast SOS Emergency Alert",
                    JOptionPane.WARNING_MESSAGE
                )
                if (!emergencyText.isNullOrBlank()) {
                    router.sendPublicMessage(emergencyText.trim(), isSos = true)
                    refreshPublicMessages()
                }
            }
        }
        rightBox.add(sosButton)

        panel.add(rightBox, BorderLayout.EAST)
        return panel
    }

    private fun createPublicMeshTab(): JPanel {
        val tab = JPanel(BorderLayout()).apply {
            background = COLOR_BG
            border = EmptyBorder(12, 16, 12, 16)
        }

        tab.add(publicScrollPane, BorderLayout.CENTER)

        val bottomBox = JPanel(BorderLayout(10, 0)).apply {
            background = COLOR_BG
            border = EmptyBorder(10, 0, 0, 0)
        }

        publicInputField.apply {
            font = FONT_BODY
            border = BorderFactory.createCompoundBorder(
                LineBorder(COLOR_BORDER, 1, true),
                EmptyBorder(10, 12, 10, 12)
            )
            addActionListener { sendPublicChat() }
        }
        bottomBox.add(publicInputField, BorderLayout.CENTER)

        val sendBtn = JButton("Broadcast").apply {
            font = FONT_BODY_BOLD
            foreground = Color.WHITE
            background = COLOR_PRIMARY
            isFocusPainted = false
            cursor = Cursor.getPredefinedCursor(Cursor.HAND_CURSOR)
            border = EmptyBorder(10, 20, 10, 20)
            addActionListener { sendPublicChat() }
        }
        bottomBox.add(sendBtn, BorderLayout.EAST)

        tab.add(bottomBox, BorderLayout.SOUTH)
        return tab
    }

    private fun sendPublicChat() {
        val text = publicInputField.text.trim()
        if (text.isNotEmpty()) {
            router.sendPublicMessage(text, isSos = false)
            publicInputField.text = ""
            refreshPublicMessages()
        }
    }

    private fun createDirectChatsTab(): JPanel {
        val tab = JPanel(BorderLayout(16, 0)).apply {
            background = COLOR_BG
            border = EmptyBorder(12, 16, 12, 16)
        }

        // Left Peer Sidebar
        val leftPanel = JPanel(BorderLayout()).apply {
            preferredSize = Dimension(280, 0)
            background = COLOR_SURFACE
            border = LineBorder(COLOR_BORDER, 1, true)
        }

        val peerListHeader = JLabel("  Peers on Mesh").apply {
            font = FONT_HEADER
            foreground = COLOR_TEXT_PRIMARY
            border = EmptyBorder(10, 10, 10, 10)
        }
        leftPanel.add(peerListHeader, BorderLayout.NORTH)

        peerJList.apply {
            background = COLOR_CARD
            selectionMode = ListSelectionModel.SINGLE_SELECTION
            setCellRenderer { _, value, _, isSelected, _ ->
                JPanel(BorderLayout()).apply {
                    background = if (isSelected) COLOR_SURFACE else COLOR_CARD
                    border = EmptyBorder(8, 10, 8, 10)

                    val isConnected = wifiEngine.isPeerConnected(value.nodeId)
                    val statusDot = if (isConnected) "⚡ " else "📡 "
                    val title = JLabel("$statusDot${value.alias}").apply {
                        font = FONT_BODY_BOLD
                        foreground = COLOR_TEXT_PRIMARY
                    }
                    val sub = JLabel("0x${String.format("%016X", value.nodeId).takeLast(6)} • ${value.hops} hop(s)").apply {
                        font = FONT_SMALL
                        foreground = COLOR_TEXT_MUTED
                    }
                    add(title, BorderLayout.NORTH)
                    add(sub, BorderLayout.SOUTH)
                }
            }
            addListSelectionListener {
                selectedPeer = selectedValue
                updateDmChatHeader()
                refreshDmMessages()
            }
        }
        leftPanel.add(JScrollPane(peerJList).apply { border = null }, BorderLayout.CENTER)
        tab.add(leftPanel, BorderLayout.WEST)

        // Right Chat Pane
        val rightPanel = JPanel(BorderLayout()).apply {
            background = COLOR_BG
        }

        dmChatTitleLabel.apply {
            font = FONT_HEADER
            foreground = COLOR_TEXT_PRIMARY
            border = EmptyBorder(0, 0, 10, 0)
        }
        rightPanel.add(dmChatTitleLabel, BorderLayout.NORTH)
        rightPanel.add(dmScrollPane, BorderLayout.CENTER)

        val dmBottomBox = JPanel(BorderLayout(10, 0)).apply {
            background = COLOR_BG
            border = EmptyBorder(10, 0, 0, 0)
        }
        dmInputField.apply {
            font = FONT_BODY
            border = BorderFactory.createCompoundBorder(
                LineBorder(COLOR_BORDER, 1, true),
                EmptyBorder(10, 12, 10, 12)
            )
            addActionListener { sendDirectChat() }
        }
        dmBottomBox.add(dmInputField, BorderLayout.CENTER)

        val dmSendBtn = JButton("Send Encrypted DM").apply {
            font = FONT_BODY_BOLD
            foreground = Color.WHITE
            background = COLOR_PRIMARY
            isFocusPainted = false
            cursor = Cursor.getPredefinedCursor(Cursor.HAND_CURSOR)
            border = EmptyBorder(10, 20, 10, 20)
            addActionListener { sendDirectChat() }
        }
        dmBottomBox.add(dmSendBtn, BorderLayout.EAST)
        rightPanel.add(dmBottomBox, BorderLayout.SOUTH)

        tab.add(rightPanel, BorderLayout.CENTER)
        return tab
    }

    private fun updateDmChatHeader() {
        val peer = selectedPeer
        if (peer != null) {
            val status = if (wifiEngine.isPeerConnected(peer.nodeId)) "Active on LAN (Direct TCP)" else "Mesh Relay (${peer.hops} hops)"
            dmChatTitleLabel.text = "🔒 Chat with ${peer.alias} (0x${String.format("%016X", peer.nodeId)}) — $status"
        } else {
            dmChatTitleLabel.text = "Select a peer to start encrypted chat"
        }
    }

    private fun sendDirectChat() {
        val peer = selectedPeer ?: return
        val text = dmInputField.text.trim()
        if (text.isNotEmpty()) {
            router.sendDirectMessage(peer.nodeId, text)
            dmInputField.text = ""
            refreshDmMessages()
        }
    }

    private fun createRadarTab(): JPanel {
        val tab = JPanel(BorderLayout(0, 10)).apply {
            background = COLOR_BG
            border = EmptyBorder(12, 16, 12, 16)
        }

        val topInfo = JLabel("Live Mesh Topology Radar (Auto-updating force-directed network graph)").apply {
            font = FONT_HEADER
            foreground = COLOR_TEXT_PRIMARY
        }
        tab.add(topInfo, BorderLayout.NORTH)
        tab.add(radarCanvas, BorderLayout.CENTER)
        return tab
    }

    private fun createInspectorTab(): JPanel {
        val tab = JPanel(BorderLayout(0, 10)).apply {
            background = COLOR_BG
            border = EmptyBorder(12, 16, 12, 16)
        }

        val topBar = JPanel(BorderLayout()).apply {
            background = COLOR_BG
        }
        val title = JLabel("Live Binary Packet Stream & Diagnostic Inspector").apply {
            font = FONT_HEADER
            foreground = COLOR_TEXT_PRIMARY
        }
        val refreshBtn = JButton("Refresh Logs").apply {
            font = FONT_SMALL
            addActionListener { refreshLogs() }
        }
        topBar.add(title, BorderLayout.WEST)
        topBar.add(refreshBtn, BorderLayout.EAST)
        tab.add(topBar, BorderLayout.NORTH)

        tab.add(JScrollPane(logsTextArea).apply { border = LineBorder(COLOR_BORDER, 1) }, BorderLayout.CENTER)
        return tab
    }

    private fun createSettingsTab(): JPanel {
        val tab = JPanel().apply {
            layout = BoxLayout(this, BoxLayout.Y_AXIS)
            background = COLOR_BG
            border = EmptyBorder(24, 32, 24, 32)
        }

        fun createSection(title: String, content: JComponent): JPanel {
            return JPanel(BorderLayout(0, 8)).apply {
                background = COLOR_CARD
                border = BorderFactory.createCompoundBorder(
                    LineBorder(COLOR_BORDER, 1, true),
                    EmptyBorder(16, 16, 16, 16)
                )
                val lbl = JLabel(title).apply {
                    font = FONT_HEADER
                    foreground = COLOR_PRIMARY
                }
                add(lbl, BorderLayout.NORTH)
                add(content, BorderLayout.CENTER)
                maximumSize = Dimension(700, 140)
                alignmentX = Component.LEFT_ALIGNMENT
            }
        }

        // 1. Node Identity Card
        val identityBox = JPanel(GridLayout(3, 2, 8, 8)).apply { isOpaque = false }
        identityBox.add(JLabel("Node ID (64-bit Hex):").apply { font = FONT_BODY_BOLD })
        identityBox.add(JLabel("0x${router.myNodeIdHex}").apply { font = FONT_MONO })
        identityBox.add(JLabel("Public Key Fingerprint:").apply { font = FONT_BODY_BOLD })
        identityBox.add(JLabel(com.meshwhisper.core.crypto.PureCryptoEngine.generateFingerprint(router.myPublicKey)).apply { font = FONT_MONO })
        identityBox.add(JLabel("Key Storage Vault:").apply { font = FONT_BODY_BOLD })
        identityBox.add(JLabel("~/.meshwhisper/identity.vault (PBKDF2-HMAC-SHA256)").apply { font = FONT_MONO })

        tab.add(createSection("Cryptographic Identity", identityBox))
        tab.add(Box.createVerticalStrut(16))

        // 2. Alias Editor Card
        val aliasBox = JPanel(FlowLayout(FlowLayout.LEFT, 10, 0)).apply { isOpaque = false }
        val aliasField = JTextField(router.myAlias, 20).apply { font = FONT_BODY }
        val saveAliasBtn = JButton("Save New Alias").apply {
            font = FONT_BODY_BOLD
            foreground = Color.WHITE
            background = COLOR_PRIMARY
            addActionListener {
                val newAlias = aliasField.text.trim()
                if (newAlias.isNotEmpty()) {
                    router.updateAlias(newAlias)
                    JOptionPane.showMessageDialog(this@DesktopMainWindow, "Alias updated to: $newAlias")
                }
            }
        }
        aliasBox.add(aliasField)
        aliasBox.add(saveAliasBtn)

        tab.add(createSection("Station Alias", aliasBox))
        return tab
    }

    private fun startBackgroundListeners() {
        // Collect live incoming messages
        scope.launch {
            router.incomingMessages.collectLatest {
                SwingUtilities.invokeLater {
                    refreshPublicMessages()
                    refreshDmMessages()
                    refreshLogs()
                }
            }
        }

        // Collect SOS alerts with popup
        scope.launch {
            router.sosAlerts.collectLatest { sosMsg ->
                SwingUtilities.invokeLater {
                    JOptionPane.showMessageDialog(
                        this@DesktopMainWindow,
                        "🚨 EMERGENCY SOS FROM 0x${String.format("%016X", sosMsg.senderNodeId)}:\n\n${sosMsg.text}",
                        "🚨 CRITICAL MESH ALERT",
                        JOptionPane.ERROR_MESSAGE
                    )
                }
            }
        }

        // Periodic Status and Peer Refresh
        javax.swing.Timer(2000) {
            val count = wifiEngine.connectedPeersCount.value
            statusBadgeLabel.text = "⚡ $count LAN PEER(S) • DUAL-RADIO READY"
            refreshPeersList()
            radarCanvas.repaint()
        }.start()
    }

    private fun refreshPublicMessages() {
        publicMessagesPanel.removeAll()
        val messages = database.getPublicAndSosMessages()

        for (msg in messages) {
            val bubble = createMessageBubble(msg)
            publicMessagesPanel.add(bubble)
            publicMessagesPanel.add(Box.createVerticalStrut(8))
        }

        publicMessagesPanel.revalidate()
        publicMessagesPanel.repaint()

        // Auto-scroll to bottom
        SwingUtilities.invokeLater {
            val vertical = publicScrollPane.verticalScrollBar
            vertical.value = vertical.maximum
        }
    }

    private fun refreshDmMessages() {
        dmMessagesPanel.removeAll()
        val peer = selectedPeer ?: return
        val messages = database.getDirectConversation(peer.nodeId, router.myNodeId)

        for (msg in messages) {
            val bubble = createMessageBubble(msg)
            dmMessagesPanel.add(bubble)
            dmMessagesPanel.add(Box.createVerticalStrut(8))
        }

        dmMessagesPanel.revalidate()
        dmMessagesPanel.repaint()

        SwingUtilities.invokeLater {
            val vertical = dmScrollPane.verticalScrollBar
            vertical.value = vertical.maximum
        }
    }

    private fun createMessageBubble(msg: DesktopMessage): JPanel {
        val isMe = !msg.isIncoming
        val panel = JPanel(BorderLayout()).apply {
            isOpaque = false
            maximumSize = Dimension(Short.MAX_VALUE.toInt(), 75)
        }

        val card = JPanel(BorderLayout(0, 4)).apply {
            background = when {
                msg.isEmergencySos -> Color(0xFF, 0xEB, 0xEB)
                isMe -> Color(0xE8, 0xF5, 0xE9)
                else -> COLOR_CARD
            }
            border = BorderFactory.createCompoundBorder(
                LineBorder(if (msg.isEmergencySos) COLOR_SOS else COLOR_BORDER, if (msg.isEmergencySos) 2 else 1, true),
                EmptyBorder(8, 12, 8, 12)
            )
        }

        val senderName = when {
            isMe -> "Me (0x${router.myNodeIdHex.takeLast(4)})"
            msg.isEmergencySos -> "🚨 EMERGENCY SOS — 0x${String.format("%016X", msg.senderNodeId).takeLast(6)}"
            else -> "Peer 0x${String.format("%016X", msg.senderNodeId).takeLast(6)}"
        }

        val headerLabel = JLabel("$senderName  •  ${timeFormat.format(Date(msg.timestamp * 1000L))}").apply {
            font = FONT_SMALL
            foreground = if (msg.isEmergencySos) COLOR_SOS else COLOR_TEXT_MUTED
        }
        val textLabel = JLabel("<html><body style='width: 450px;'>${msg.text}</body></html>").apply {
            font = FONT_BODY
            foreground = COLOR_TEXT_PRIMARY
        }

        card.add(headerLabel, BorderLayout.NORTH)
        card.add(textLabel, BorderLayout.CENTER)

        if (isMe) {
            panel.add(card, BorderLayout.EAST)
        } else {
            panel.add(card, BorderLayout.WEST)
        }

        return panel
    }

    private fun refreshPeersList() {
        val peers = database.getAllPeers()
        peerListModel.clear()
        for (p in peers) {
            peerListModel.addElement(p)
        }
    }

    private fun refreshLogs() {
        val logs = database.getRecentPacketLogs(80)
        val sb = StringBuilder()
        for (l in logs) {
            val time = timeFormat.format(Date(l.timestamp))
            sb.append("[$time] [${l.direction.padEnd(5)}] [${l.type.padEnd(16)}] (${l.sizeBytes}B) ${l.info}\n")
        }
        logsTextArea.text = sb.toString()
    }
}

/**
 * Custom Radar Canvas for drawing dynamic force-directed mesh network links.
 */
class TopologyRadarCanvas(
    private val database: DesktopDatabase,
    private val myNodeId: Long
) : JPanel() {

    init {
        background = DesktopMainWindow.COLOR_CARD
        border = LineBorder(DesktopMainWindow.COLOR_BORDER, 1, true)
    }

    override fun paintComponent(g: Graphics) {
        super.paintComponent(g)
        val g2 = g as? Graphics2D ?: return
        g2.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON)

        val w = width
        val h = height
        val centerX = w / 2
        val centerY = h / 2

        // Draw Radar concentric range circles
        g2.color = Color(0xF3, 0xEC, 0xE0)
        g2.drawOval(centerX - 100, centerY - 100, 200, 200)
        g2.drawOval(centerX - 200, centerY - 200, 400, 400)
        g2.drawOval(centerX - 300, centerY - 300, 600, 600)

        // Draw My Node (Center)
        g2.color = DesktopMainWindow.COLOR_PRIMARY
        g2.fillOval(centerX - 14, centerY - 14, 28, 28)
        g2.color = DesktopMainWindow.COLOR_TEXT_PRIMARY
        g2.font = DesktopMainWindow.FONT_BODY_BOLD
        g2.drawString("Me (Host Station)", centerX - 50, centerY - 20)

        val peers = database.getAllPeers()
        if (peers.isEmpty()) {
            g2.color = DesktopMainWindow.COLOR_TEXT_MUTED
            g2.font = DesktopMainWindow.FONT_BODY
            g2.drawString("No remote mesh nodes in range yet. Listening for UDP discovery...", centerX - 180, centerY + 80)
            return
        }

        // Draw Peer Nodes in circular constellation
        val angleStep = (2 * Math.PI) / peers.size
        for ((idx, peer) in peers.withIndex()) {
            val angle = idx * angleStep
            val radius = 160.0 + (peer.hops * 40.0)
            val px = (centerX + Math.cos(angle) * radius).toInt()
            val py = (centerY + Math.sin(angle) * radius).toInt()

            // Draw Edge Link
            g2.color = Color(0xC2, 0x65, 0x2A, 120)
            g2.stroke = BasicStroke(2.0f)
            g2.drawLine(centerX, centerY, px, py)

            // Draw Node Circle
            g2.color = DesktopMainWindow.COLOR_ACCENT
            g2.fillOval(px - 10, py - 10, 20, 20)

            // Label
            g2.color = DesktopMainWindow.COLOR_TEXT_PRIMARY
            g2.font = DesktopMainWindow.FONT_BODY
            g2.drawString("${peer.alias} (0x${String.format("%016X", peer.nodeId).takeLast(4)})", px + 14, py + 5)
        }
    }
}
