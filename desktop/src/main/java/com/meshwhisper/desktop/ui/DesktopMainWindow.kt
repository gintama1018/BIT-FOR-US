package com.meshwhisper.desktop.ui

import com.meshwhisper.core.crypto.PureCryptoEngine
import com.meshwhisper.core.protocol.MeshPacket
import com.meshwhisper.desktop.db.DesktopDatabase
import com.meshwhisper.desktop.db.DesktopMessage
import com.meshwhisper.desktop.db.DesktopPeer
import com.meshwhisper.desktop.router.DesktopMeshRouter
import com.meshwhisper.desktop.wifi.DesktopWifiEngine
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.launch
import java.awt.*
import java.awt.event.WindowAdapter
import java.awt.event.WindowEvent
import java.io.File
import java.text.SimpleDateFormat
import java.util.*
import javax.imageio.ImageIO
import javax.swing.*
import javax.swing.border.EmptyBorder
import javax.swing.border.LineBorder

/**
 * Modern High-Grade Dark/Tactical GUI for MeshWhisper Desktop Station.
 */
class DesktopMainWindow(
    private val router: DesktopMeshRouter,
    private val database: DesktopDatabase,
    private val wifiEngine: DesktopWifiEngine
) : JFrame("MeshWhisper — Offline Hybrid Mesh Station") {

    private val scope = CoroutineScope(Dispatchers.Default + SupervisorJob())
    private val timeFormat = SimpleDateFormat("HH:mm", Locale.getDefault())

    // UI State
    private val statusBadgeLabel = JLabel("⚡ 0 LAN PEERS • OFFLINE MESH READY")
    private val cardLayout = CardLayout()
    private val contentDeck = JPanel(cardLayout)

    // Navigation state
    private val navButtons = mutableListOf<JButton>()

    // Public Mesh Tab
    private val publicMessagesPanel = JPanel().apply {
        layout = BoxLayout(this, BoxLayout.Y_AXIS)
        background = ModernTheme.BG_MAIN
    }
    private val publicScrollPane: JScrollPane
    private val publicInputField = ModernTextField("Broadcast message to all nearby nodes...")

    // Direct Chats Tab
    private val peerListModel = DefaultListModel<DesktopPeer>()
    private val peerJList = JList(peerListModel)
    private var selectedPeer: DesktopPeer? = null
    private val dmChatTitleLabel = JLabel("Select a peer to start encrypted chat")
    private val dmMessagesPanel = JPanel().apply {
        layout = BoxLayout(this, BoxLayout.Y_AXIS)
        background = ModernTheme.BG_MAIN
    }
    private val dmScrollPane: JScrollPane
    private val dmInputField = ModernTextField("Type an encrypted private message...")

    // Radar Canvas
    private val radarCanvas = AnimatedRadarCanvas(database, router.myNodeId)

    // Inspector Logs
    private val logsTextArea = JTextArea().apply {
        isEditable = false
        font = ModernTheme.FONT_MONO
        background = Color(0x0C, 0x0E, 0x12)
        foreground = Color(0x34, 0xD3, 0x99)
        border = EmptyBorder(12, 12, 12, 12)
    }

    init {
        defaultCloseOperation = EXIT_ON_CLOSE
        setSize(1180, 780)
        minimumSize = Dimension(960, 620)
        setLocationRelativeTo(null)
        contentPane.background = ModernTheme.BG_MAIN
        contentPane.layout = BorderLayout()

        // 1. Top Header
        val headerPanel = buildHeaderPanel()
        contentPane.add(headerPanel, BorderLayout.NORTH)

        // 2. Main Body (Left Sidebar Nav + Center Deck)
        val bodyPanel = JPanel(BorderLayout()).apply {
            background = ModernTheme.BG_MAIN
        }

        val sidebarNav = buildSidebarNav()
        bodyPanel.add(sidebarNav, BorderLayout.WEST)

        // ScrollPanes with custom UI
        publicScrollPane = JScrollPane(publicMessagesPanel).apply {
            border = null
            verticalScrollBar.ui = ModernScrollBarUI()
            verticalScrollBar.unitIncrement = 16
            background = ModernTheme.BG_MAIN
            viewport.background = ModernTheme.BG_MAIN
        }

        dmScrollPane = JScrollPane(dmMessagesPanel).apply {
            border = null
            verticalScrollBar.ui = ModernScrollBarUI()
            verticalScrollBar.unitIncrement = 16
            background = ModernTheme.BG_MAIN
            viewport.background = ModernTheme.BG_MAIN
        }

        // Add Deck Tabs
        contentDeck.background = ModernTheme.BG_MAIN
        contentDeck.add(buildPublicMeshTab(), "PUBLIC")
        contentDeck.add(buildDirectChatsTab(), "DM")
        contentDeck.add(buildRadarTab(), "RADAR")
        contentDeck.add(buildInspectorTab(), "INSPECTOR")
        contentDeck.add(buildSettingsTab(), "SETTINGS")

        bodyPanel.add(contentDeck, BorderLayout.CENTER)
        contentPane.add(bodyPanel, BorderLayout.CENTER)

        // Initial Data Load
        refreshPublicMessages()
        refreshPeersList()
        refreshLogs()

        // Start Background Listeners
        startBackgroundListeners()

        addWindowListener(object : WindowAdapter() {
            override fun windowClosing(e: WindowEvent?) {
                router.stop()
            }
        })
    }

    private fun buildHeaderPanel(): JPanel {
        val panel = JPanel(BorderLayout()).apply {
            background = ModernTheme.BG_SIDEBAR
            border = BorderFactory.createCompoundBorder(
                BorderFactory.createMatteBorder(0, 0, 1, 0, ModernTheme.BORDER_COLOR),
                EmptyBorder(12, 20, 12, 20)
            )
        }

        val leftBox = JPanel(FlowLayout(FlowLayout.LEFT, 12, 0)).apply { isOpaque = false }
        val logoLabel = JLabel("🛡️").apply {
            font = Font("Segoe UI Emoji", Font.PLAIN, 24)
        }
        val appTitle = JLabel("MeshWhisper").apply {
            font = ModernTheme.FONT_APP_TITLE
            foreground = ModernTheme.TEXT_MAIN
        }
        val stationBadge = JLabel("0x${router.myNodeIdHex.takeLast(4)} • ${router.myAlias}").apply {
            font = ModernTheme.FONT_SMALL
            foreground = ModernTheme.TEXT_MUTED
            border = BorderFactory.createCompoundBorder(
                LineBorder(ModernTheme.BORDER_COLOR, 1, true),
                EmptyBorder(2, 8, 2, 8)
            )
        }
        leftBox.add(logoLabel)
        leftBox.add(appTitle)
        leftBox.add(stationBadge)
        panel.add(leftBox, BorderLayout.WEST)

        val rightBox = JPanel(FlowLayout(FlowLayout.RIGHT, 12, 0)).apply { isOpaque = false }
        statusBadgeLabel.apply {
            font = ModernTheme.FONT_BODY_BOLD
            foreground = ModernTheme.ONLINE
            border = BorderFactory.createCompoundBorder(
                LineBorder(Color(0x10, 0xB9, 0x81, 100), 1, true),
                EmptyBorder(5, 12, 5, 12)
            )
        }
        rightBox.add(statusBadgeLabel)

        val sosBtn = ModernButton(
            text = "🚨 SEND SOS",
            bgColor = ModernTheme.SOS,
            hoverColor = Color(0xDC, 0x26, 0x26),
            cornerRadius = 8
        ).apply {
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
        rightBox.add(sosBtn)

        panel.add(rightBox, BorderLayout.EAST)
        return panel
    }

    private fun buildSidebarNav(): JPanel {
        val sidebar = JPanel().apply {
            layout = BoxLayout(this, BoxLayout.Y_AXIS)
            preferredSize = Dimension(220, 0)
            background = ModernTheme.BG_SIDEBAR
            border = BorderFactory.createCompoundBorder(
                BorderFactory.createMatteBorder(0, 0, 0, 1, ModernTheme.BORDER_COLOR),
                EmptyBorder(16, 12, 16, 12)
            )
        }

        fun createNavBtn(title: String, icon: String, tabKey: String): JButton {
            val btn = JButton("$icon  $title").apply {
                font = ModernTheme.FONT_BODY_BOLD
                foreground = ModernTheme.TEXT_MUTED
                background = ModernTheme.BG_SIDEBAR
                isContentAreaFilled = false
                isFocusPainted = false
                horizontalAlignment = SwingConstants.LEFT
                cursor = Cursor.getPredefinedCursor(Cursor.HAND_CURSOR)
                border = EmptyBorder(10, 14, 10, 14)
                maximumSize = Dimension(196, 42)
                alignmentX = Component.LEFT_ALIGNMENT

                addActionListener {
                    cardLayout.show(contentDeck, tabKey)
                    for (b in navButtons) {
                        b.foreground = ModernTheme.TEXT_MUTED
                        b.isOpaque = false
                    }
                    foreground = ModernTheme.TEXT_MAIN
                    repaint()
                }
            }
            navButtons.add(btn)
            return btn
        }

        val btnPublic = createNavBtn("Public Mesh", "💬", "PUBLIC").apply {
            foreground = ModernTheme.TEXT_MAIN
        }
        val btnDm = createNavBtn("Direct Chats", "🔒", "DM")
        val btnRadar = createNavBtn("Mesh Radar", "📡", "RADAR")
        val btnInspector = createNavBtn("Packet Stream", "📋", "INSPECTOR")
        val btnSettings = createNavBtn("Station Vault", "⚙️", "SETTINGS")

        sidebar.add(btnPublic)
        sidebar.add(Box.createVerticalStrut(6))
        sidebar.add(btnDm)
        sidebar.add(Box.createVerticalStrut(6))
        sidebar.add(btnRadar)
        sidebar.add(Box.createVerticalStrut(6))
        sidebar.add(btnInspector)
        sidebar.add(Box.createVerticalStrut(6))
        sidebar.add(btnSettings)
        sidebar.add(Box.createVerticalGlue())

        return sidebar
    }

    private fun buildPublicMeshTab(): JPanel {
        val tab = JPanel(BorderLayout()).apply {
            background = ModernTheme.BG_MAIN
            border = EmptyBorder(14, 20, 14, 20)
        }

        tab.add(publicScrollPane, BorderLayout.CENTER)

        val bottomBox = JPanel(BorderLayout(10, 0)).apply {
            background = ModernTheme.BG_MAIN
            border = EmptyBorder(12, 0, 0, 0)
        }

        val attachBtn = ModernButton("📎 Attach", ModernTheme.BG_CARD, ModernTheme.BG_CARD_HOVER, ModernTheme.TEXT_MAIN, 10).apply {
            addActionListener {
                val chooser = JFileChooser()
                if (chooser.showOpenDialog(this@DesktopMainWindow) == JFileChooser.APPROVE_OPTION) {
                    val file = chooser.selectedFile
                    val type = if (file.name.endsWith(".jpg", true) || file.name.endsWith(".png", true)) "IMAGE" else "FILE"
                    router.mediaManager.sendMediaFile(MeshPacket.BROADCAST_RECIPIENT_ID, file, type, "")
                    refreshPublicMessages()
                }
            }
        }
        bottomBox.add(attachBtn, BorderLayout.WEST)

        publicInputField.addActionListener { sendPublicChat() }
        bottomBox.add(publicInputField, BorderLayout.CENTER)

        val sendBtn = ModernButton("Broadcast 🚀", ModernTheme.PRIMARY, ModernTheme.PRIMARY_HOVER, Color.WHITE, 10).apply {
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

    private fun buildDirectChatsTab(): JPanel {
        val tab = JPanel(BorderLayout(16, 0)).apply {
            background = ModernTheme.BG_MAIN
            border = EmptyBorder(14, 20, 14, 20)
        }

        // Left Peer List Panel
        val leftPanel = JPanel(BorderLayout()).apply {
            preferredSize = Dimension(280, 0)
            background = ModernTheme.BG_SIDEBAR
            border = BorderFactory.createCompoundBorder(
                LineBorder(ModernTheme.BORDER_COLOR, 1, true),
                EmptyBorder(8, 8, 8, 8)
            )
        }

        val header = JLabel("  Discovered Peers").apply {
            font = ModernTheme.FONT_TITLE
            foreground = ModernTheme.TEXT_MAIN
            border = EmptyBorder(8, 8, 8, 8)
        }
        leftPanel.add(header, BorderLayout.NORTH)

        peerJList.apply {
            background = ModernTheme.BG_SIDEBAR
            selectionMode = ListSelectionModel.SINGLE_SELECTION
            setCellRenderer { _, value, _, isSelected, _ ->
                JPanel(BorderLayout(8, 0)).apply {
                    background = if (isSelected) ModernTheme.BG_CARD_HOVER else ModernTheme.BG_SIDEBAR
                    border = BorderFactory.createCompoundBorder(
                        EmptyBorder(4, 4, 4, 4),
                        EmptyBorder(8, 10, 8, 10)
                    )

                    val isConnected = wifiEngine.isPeerConnected(value.nodeId)
                    val statusDot = if (isConnected) "🟢 " else "📡 "
                    val title = JLabel("$statusDot${value.alias}").apply {
                        font = ModernTheme.FONT_BODY_BOLD
                        foreground = ModernTheme.TEXT_MAIN
                    }
                    val sub = JLabel("0x${String.format("%016X", value.nodeId).takeLast(6)} • ${value.hops} hop(s)").apply {
                        font = ModernTheme.FONT_SMALL
                        foreground = ModernTheme.TEXT_MUTED
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
        leftPanel.add(JScrollPane(peerJList).apply {
            border = null
            verticalScrollBar.ui = ModernScrollBarUI()
            viewport.background = ModernTheme.BG_SIDEBAR
        }, BorderLayout.CENTER)
        tab.add(leftPanel, BorderLayout.WEST)

        // Right Conversation Panel
        val rightPanel = JPanel(BorderLayout()).apply {
            background = ModernTheme.BG_MAIN
        }

        dmChatTitleLabel.apply {
            font = ModernTheme.FONT_TITLE
            foreground = ModernTheme.TEXT_MAIN
            border = EmptyBorder(0, 0, 12, 0)
        }
        rightPanel.add(dmChatTitleLabel, BorderLayout.NORTH)
        rightPanel.add(dmScrollPane, BorderLayout.CENTER)

        val dmBottomBox = JPanel(BorderLayout(10, 0)).apply {
            background = ModernTheme.BG_MAIN
            border = EmptyBorder(12, 0, 0, 0)
        }

        val dmAttachBtn = ModernButton("📎 Attach", ModernTheme.BG_CARD, ModernTheme.BG_CARD_HOVER, ModernTheme.TEXT_MAIN, 10).apply {
            addActionListener {
                val peer = selectedPeer ?: return@addActionListener
                val chooser = JFileChooser()
                if (chooser.showOpenDialog(this@DesktopMainWindow) == JFileChooser.APPROVE_OPTION) {
                    val file = chooser.selectedFile
                    val type = if (file.name.endsWith(".jpg", true) || file.name.endsWith(".png", true)) "IMAGE" else "FILE"
                    router.mediaManager.sendMediaFile(peer.nodeId, file, type, "")
                    refreshDmMessages()
                }
            }
        }
        dmBottomBox.add(dmAttachBtn, BorderLayout.WEST)

        dmInputField.addActionListener { sendDirectChat() }
        dmBottomBox.add(dmInputField, BorderLayout.CENTER)

        val dmSendBtn = ModernButton("Send DM 🔒", ModernTheme.PRIMARY, ModernTheme.PRIMARY_HOVER, Color.WHITE, 10).apply {
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
            val isLan = wifiEngine.isPeerConnected(peer.nodeId)
            val status = if (isLan) "🟢 Active LAN Socket (TCP Direct)" else "📡 Mesh Relay (${peer.hops} hops)"
            dmChatTitleLabel.text = "🔒 Encrypted Chat with ${peer.alias} (0x${String.format("%016X", peer.nodeId).takeLast(6)}) — $status"
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

    private fun buildRadarTab(): JPanel {
        val tab = JPanel(BorderLayout(0, 12)).apply {
            background = ModernTheme.BG_MAIN
            border = EmptyBorder(14, 20, 14, 20)
        }

        val topInfo = JLabel("📡 Dynamic Topology Radar • Real-time Multi-hop Force-Directed Network Graph").apply {
            font = ModernTheme.FONT_TITLE
            foreground = ModernTheme.TEXT_MAIN
        }
        tab.add(topInfo, BorderLayout.NORTH)
        tab.add(radarCanvas, BorderLayout.CENTER)
        return tab
    }

    private fun buildInspectorTab(): JPanel {
        val tab = JPanel(BorderLayout(0, 12)).apply {
            background = ModernTheme.BG_MAIN
            border = EmptyBorder(14, 20, 14, 20)
        }

        val topBar = JPanel(BorderLayout()).apply { background = ModernTheme.BG_MAIN }
        val title = JLabel("📋 Live Binary Datagram Stream & Diagnostic Inspector").apply {
            font = ModernTheme.FONT_TITLE
            foreground = ModernTheme.TEXT_MAIN
        }
        val refreshBtn = ModernButton("Refresh Stream", ModernTheme.BG_CARD, ModernTheme.BG_CARD_HOVER, ModernTheme.TEXT_MAIN, 8).apply {
            addActionListener { refreshLogs() }
        }
        topBar.add(title, BorderLayout.WEST)
        topBar.add(refreshBtn, BorderLayout.EAST)
        tab.add(topBar, BorderLayout.NORTH)

        tab.add(JScrollPane(logsTextArea).apply {
            border = LineBorder(ModernTheme.BORDER_COLOR, 1, true)
            verticalScrollBar.ui = ModernScrollBarUI()
        }, BorderLayout.CENTER)
        return tab
    }

    private fun buildSettingsTab(): JPanel {
        val tab = JPanel().apply {
            layout = BoxLayout(this, BoxLayout.Y_AXIS)
            background = ModernTheme.BG_MAIN
            border = EmptyBorder(24, 32, 24, 32)
        }

        fun createCard(title: String, content: JComponent): JPanel {
            return JPanel(BorderLayout(0, 12)).apply {
                background = ModernTheme.BG_CARD
                border = BorderFactory.createCompoundBorder(
                    LineBorder(ModernTheme.BORDER_COLOR, 1, true),
                    EmptyBorder(18, 20, 18, 20)
                )
                val lbl = JLabel(title).apply {
                    font = ModernTheme.FONT_TITLE
                    foreground = ModernTheme.PRIMARY_HOVER
                }
                add(lbl, BorderLayout.NORTH)
                add(content, BorderLayout.CENTER)
                maximumSize = Dimension(720, 160)
                alignmentX = Component.LEFT_ALIGNMENT
            }
        }

        val identityBox = JPanel(GridLayout(3, 2, 10, 10)).apply { isOpaque = false }
        identityBox.add(JLabel("Node ID (64-bit Hex):").apply { font = ModernTheme.FONT_BODY_BOLD; foreground = ModernTheme.TEXT_MAIN })
        identityBox.add(JLabel("0x${router.myNodeIdHex}").apply { font = ModernTheme.FONT_MONO; foreground = ModernTheme.TEXT_MUTED })
        identityBox.add(JLabel("Public Key Fingerprint:").apply { font = ModernTheme.FONT_BODY_BOLD; foreground = ModernTheme.TEXT_MAIN })
        identityBox.add(JLabel(PureCryptoEngine.generateFingerprint(router.myPublicKey)).apply { font = ModernTheme.FONT_MONO; foreground = ModernTheme.TEXT_MUTED })
        identityBox.add(JLabel("Key Storage Vault:").apply { font = ModernTheme.FONT_BODY_BOLD; foreground = ModernTheme.TEXT_MAIN })
        identityBox.add(JLabel("~/.meshwhisper/identity.vault (PBKDF2-HMAC-SHA256)").apply { font = ModernTheme.FONT_MONO; foreground = ModernTheme.TEXT_MUTED })

        tab.add(createCard("🔐 Cryptographic Identity & Hardware Vault", identityBox))
        tab.add(Box.createVerticalStrut(18))

        val aliasBox = JPanel(FlowLayout(FlowLayout.LEFT, 12, 0)).apply { isOpaque = false }
        val aliasField = ModernTextField(router.myAlias, 10).apply {
            text = router.myAlias
            preferredSize = Dimension(280, 38)
        }
        val saveAliasBtn = ModernButton("Save Alias", ModernTheme.PRIMARY, ModernTheme.PRIMARY_HOVER, Color.WHITE, 10).apply {
            addActionListener {
                val newAlias = aliasField.text.trim()
                if (newAlias.isNotEmpty()) {
                    router.updateAlias(newAlias)
                    JOptionPane.showMessageDialog(this@DesktopMainWindow, "Station alias updated to: $newAlias")
                }
            }
        }
        aliasBox.add(aliasField)
        aliasBox.add(saveAliasBtn)

        tab.add(createCard("🏷️ Station Display Name", aliasBox))
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
                        "🚨 EMERGENCY SOS ALERT FROM 0x${String.format("%016X", sosMsg.senderNodeId)}:\n\n${sosMsg.text}",
                        "🚨 CRITICAL MESH ALERT",
                        JOptionPane.ERROR_MESSAGE
                    )
                }
            }
        }

        // Periodic Status and Peer Refresh
        javax.swing.Timer(1500) {
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
            publicMessagesPanel.add(Box.createVerticalStrut(10))
        }

        publicMessagesPanel.revalidate()
        publicMessagesPanel.repaint()

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
            dmMessagesPanel.add(Box.createVerticalStrut(10))
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
            maximumSize = Dimension(Short.MAX_VALUE.toInt(), 340)
        }

        val card = JPanel(BorderLayout(0, 6)).apply {
            background = when {
                msg.isEmergencySos -> ModernTheme.SOS_BG
                isMe -> ModernTheme.BUBBLE_ME
                else -> ModernTheme.BUBBLE_PEER
            }
            border = BorderFactory.createCompoundBorder(
                LineBorder(if (msg.isEmergencySos) ModernTheme.SOS else ModernTheme.BORDER_COLOR, if (msg.isEmergencySos) 2 else 1, true),
                EmptyBorder(10, 14, 10, 14)
            )
        }

        val senderName = when {
            isMe -> "Me (0x${router.myNodeIdHex.takeLast(4)})"
            msg.isEmergencySos -> "🚨 EMERGENCY SOS — 0x${String.format("%016X", msg.senderNodeId).takeLast(6)}"
            else -> "Peer 0x${String.format("%016X", msg.senderNodeId).takeLast(6)}"
        }

        val headerLabel = JLabel("$senderName  •  ${timeFormat.format(Date(msg.timestamp * 1000L))}").apply {
            font = ModernTheme.FONT_SMALL
            foreground = if (msg.isEmergencySos) ModernTheme.SOS else ModernTheme.TEXT_MUTED
        }
        val textLabel = JLabel("<html><body style='width: 420px;'>${msg.text}</body></html>").apply {
            font = ModernTheme.FONT_BODY
            foreground = ModernTheme.TEXT_MAIN
        }

        card.add(headerLabel, BorderLayout.NORTH)
        card.add(textLabel, BorderLayout.CENTER)

        // Render Media Attachment / Image Preview / Voice Note
        if (!msg.mediaUri.isNullOrBlank()) {
            val mediaFile = File(msg.mediaUri)
            if (mediaFile.exists()) {
                val mediaPanel = JPanel().apply {
                    layout = BoxLayout(this, BoxLayout.Y_AXIS)
                    isOpaque = false
                    border = EmptyBorder(6, 0, 0, 0)
                }

                if (msg.mediaType == "IMAGE") {
                    try {
                        val origImg = ImageIO.read(mediaFile)
                        if (origImg != null) {
                            val maxW = 320
                            val maxH = 220
                            val scale = minOf(maxW.toDouble() / origImg.width, maxH.toDouble() / origImg.height, 1.0)
                            val scaledW = (origImg.width * scale).toInt()
                            val scaledH = (origImg.height * scale).toInt()
                            val scaledImg = origImg.getScaledInstance(scaledW, scaledH, Image.SCALE_SMOOTH)
                            val imgLabel = JLabel(ImageIcon(scaledImg)).apply {
                                cursor = Cursor.getPredefinedCursor(Cursor.HAND_CURSOR)
                                border = LineBorder(ModernTheme.BORDER_COLOR, 1, true)
                                addMouseListener(object : java.awt.event.MouseAdapter() {
                                    override fun mouseClicked(e: java.awt.event.MouseEvent?) {
                                        try { Desktop.getDesktop().open(mediaFile) } catch (_: Exception) {}
                                    }
                                })
                            }
                            mediaPanel.add(imgLabel)
                            mediaPanel.add(Box.createVerticalStrut(6))
                        }
                    } catch (_: Exception) {}
                }

                val actionLabel = when (msg.mediaType) {
                    "IMAGE" -> "🔍 View Full Image (${mediaFile.name})"
                    "VOICE" -> "▶️ Play Voice Recording (${mediaFile.name})"
                    else -> "📂 Open Received File (${mediaFile.name})"
                }

                val openBtn = ModernButton(actionLabel, ModernTheme.BG_CARD, ModernTheme.BG_CARD_HOVER, ModernTheme.TEXT_MAIN, 6).apply {
                    font = ModernTheme.FONT_SMALL
                    addActionListener {
                        try {
                            if (Desktop.isDesktopSupported()) {
                                Desktop.getDesktop().open(mediaFile)
                            }
                        } catch (e: Exception) {
                            JOptionPane.showMessageDialog(this@DesktopMainWindow, "File saved at: ${mediaFile.absolutePath}")
                        }
                    }
                }
                mediaPanel.add(openBtn)
                card.add(mediaPanel, BorderLayout.SOUTH)
            }
        }

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
 * Animated Tactical Radar with Sweep Beam and Glowing Node Constellation.
 */
class AnimatedRadarCanvas(
    private val database: DesktopDatabase,
    private val myNodeId: Long
) : JPanel() {

    private var sweepAngle = 0.0

    init {
        background = Color(0x0E, 0x11, 0x16)
        border = LineBorder(ModernTheme.BORDER_COLOR, 1, true)

        // 60 FPS Sweep Animation
        val timer = javax.swing.Timer(25) {
            sweepAngle += 0.04
            if (sweepAngle > Math.PI * 2) {
                sweepAngle = 0.0
            }
            repaint()
        }
        timer.start()
    }

    override fun paintComponent(g: Graphics) {
        super.paintComponent(g)
        val g2 = g as? Graphics2D ?: return
        g2.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON)

        val w = width
        val h = height
        val centerX = w / 2
        val centerY = h / 2

        // 1. Draw Radar Range Rings
        g2.color = Color(0x1F, 0x27, 0x33)
        g2.drawOval(centerX - 90, centerY - 90, 180, 180)
        g2.drawOval(centerX - 180, centerY - 180, 360, 360)
        g2.drawOval(centerX - 270, centerY - 270, 540, 540)

        // 2. Draw Crosshairs
        g2.color = Color(0x18, 0x20, 0x2C)
        g2.drawLine(centerX, centerY - 280, centerX, centerY + 280)
        g2.drawLine(centerX - 280, centerY, centerX + 280, centerY)

        // 3. Draw Radar Sweep Beam
        val sweepRadius = 280.0
        val sweepX = centerX + (Math.cos(sweepAngle) * sweepRadius).toInt()
        val sweepY = centerY + (Math.sin(sweepAngle) * sweepRadius).toInt()
        g2.color = Color(0x10, 0xB9, 0x81, 100)
        g2.stroke = BasicStroke(2.0f)
        g2.drawLine(centerX, centerY, sweepX, sweepY)

        // 4. Draw Center Node (Me / Host Station)
        g2.color = Color(0xC2, 0x65, 0x2A, 100)
        g2.fillOval(centerX - 18, centerY - 18, 36, 36)
        g2.color = ModernTheme.PRIMARY_HOVER
        g2.fillOval(centerX - 10, centerY - 10, 20, 20)
        g2.color = ModernTheme.TEXT_MAIN
        g2.font = ModernTheme.FONT_BODY_BOLD
        g2.drawString("Me (Host Station)", centerX - 55, centerY - 24)

        val peers = database.getAllPeers()
        if (peers.isEmpty()) {
            g2.color = ModernTheme.TEXT_MUTED
            g2.font = ModernTheme.FONT_BODY
            g2.drawString("Listening for remote Wi-Fi / BLE mesh nodes...", centerX - 140, centerY + 70)
            return
        }

        // 5. Draw Orbiting Peer Nodes & Edges
        val angleStep = (2 * Math.PI) / peers.size
        for ((idx, peer) in peers.withIndex()) {
            val angle = idx * angleStep
            val radius = 150.0 + (peer.hops * 45.0)
            val px = (centerX + Math.cos(angle) * radius).toInt()
            val py = (centerY + Math.sin(angle) * radius).toInt()

            // Edge Link
            g2.color = Color(0x10, 0xB9, 0x81, 140)
            g2.stroke = BasicStroke(2.0f)
            g2.drawLine(centerX, centerY, px, py)

            // Node Glow & Pin
            g2.color = Color(0x10, 0xB9, 0x81, 60)
            g2.fillOval(px - 14, py - 14, 28, 28)
            g2.color = ModernTheme.ONLINE
            g2.fillOval(px - 7, py - 7, 14, 14)

            // Label
            g2.color = ModernTheme.TEXT_MAIN
            g2.font = ModernTheme.FONT_BODY_BOLD
            g2.drawString("${peer.alias} (0x${String.format("%016X", peer.nodeId).takeLast(4)})", px + 16, py + 5)
        }
    }
}
