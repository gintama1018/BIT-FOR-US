package com.meshwhisper.desktop.ui

import java.awt.*
import java.awt.geom.RoundRectangle2D
import javax.swing.*
import javax.swing.border.EmptyBorder
import javax.swing.plaf.basic.BasicScrollBarUI

object ModernTheme {
    val BG_MAIN = Color(0x11, 0x13, 0x17)
    val BG_SIDEBAR = Color(0x16, 0x19, 0x20)
    val BG_CARD = Color(0x1E, 0x23, 0x2C)
    val BG_CARD_HOVER = Color(0x26, 0x2C, 0x37)
    val BG_INPUT = Color(0x18, 0x1B, 0x22)
    val BORDER_COLOR = Color(0x2C, 0x33, 0x40)
    val BORDER_FOCUS = Color(0xD9, 0x77, 0x24)

    val PRIMARY = Color(0xC2, 0x65, 0x2A)
    val PRIMARY_HOVER = Color(0xD9, 0x77, 0x24)
    val ACCENT = Color(0xF5, 0x9E, 0x0B)
    val SOS = Color(0xEF, 0x44, 0x44)
    val SOS_BG = Color(0x3B, 0x14, 0x14)
    val ONLINE = Color(0x10, 0xB9, 0x81)

    val TEXT_MAIN = Color(0xF9, 0xFA, 0xFB)
    val TEXT_MUTED = Color(0x9C, 0xA3, 0xAF)
    val TEXT_DARK = Color(0x11, 0x13, 0x17)

    val BUBBLE_ME = Color(0x8C, 0x42, 0x14)
    val BUBBLE_PEER = Color(0x22, 0x27, 0x32)

    val FONT_APP_TITLE = Font("Segoe UI", Font.BOLD, 16)
    val FONT_TITLE = Font("Segoe UI", Font.BOLD, 14)
    val FONT_BODY = Font("Segoe UI", Font.PLAIN, 13)
    val FONT_BODY_BOLD = Font("Segoe UI", Font.BOLD, 13)
    val FONT_SMALL = Font("Segoe UI", Font.PLAIN, 11)
    val FONT_MONO = Font("Consolas", Font.PLAIN, 12)
}

class ModernButton(
    text: String,
    private val bgColor: Color = ModernTheme.PRIMARY,
    private val hoverColor: Color = ModernTheme.PRIMARY_HOVER,
    private val textColor: Color = Color.WHITE,
    private val cornerRadius: Int = 10
) : JButton(text) {

    private var isHovered = false

    init {
        isContentAreaFilled = false
        isFocusPainted = false
        border = EmptyBorder(8, 16, 8, 16)
        font = ModernTheme.FONT_BODY_BOLD
        foreground = textColor
        cursor = Cursor.getPredefinedCursor(Cursor.HAND_CURSOR)

        addMouseListener(object : java.awt.event.MouseAdapter() {
            override fun mouseEntered(e: java.awt.event.MouseEvent?) {
                isHovered = true
                repaint()
            }

            override fun mouseExited(e: java.awt.event.MouseEvent?) {
                isHovered = false
                repaint()
            }
        })
    }

    override fun paintComponent(g: Graphics) {
        val g2 = g.create() as Graphics2D
        g2.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON)
        g2.color = if (isHovered) hoverColor else bgColor
        g2.fill(RoundRectangle2D.Float(0f, 0f, width.toFloat(), height.toFloat(), cornerRadius.toFloat(), cornerRadius.toFloat()))
        g2.dispose()
        super.paintComponent(g)
    }
}

class ModernTextField(
    private val placeholder: String = "",
    private val cornerRadius: Int = 10
) : JTextField() {

    private var isFocused = false

    init {
        isOpaque = false
        font = ModernTheme.FONT_BODY
        foreground = ModernTheme.TEXT_MAIN
        caretColor = ModernTheme.PRIMARY_HOVER
        border = EmptyBorder(10, 14, 10, 14)

        addFocusListener(object : java.awt.event.FocusAdapter() {
            override fun focusGained(e: java.awt.event.FocusEvent?) {
                isFocused = true
                repaint()
            }

            override fun focusLost(e: java.awt.event.FocusEvent?) {
                isFocused = false
                repaint()
            }
        })
    }

    override fun paintComponent(g: Graphics) {
        val g2 = g.create() as Graphics2D
        g2.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON)

        // Background
        g2.color = ModernTheme.BG_INPUT
        g2.fill(RoundRectangle2D.Float(0f, 0f, width.toFloat() - 1, height.toFloat() - 1, cornerRadius.toFloat(), cornerRadius.toFloat()))

        // Border
        g2.color = if (isFocused) ModernTheme.BORDER_FOCUS else ModernTheme.BORDER_COLOR
        g2.stroke = BasicStroke(if (isFocused) 1.5f else 1.0f)
        g2.draw(RoundRectangle2D.Float(0.5f, 0.5f, width.toFloat() - 1.5f, height.toFloat() - 1.5f, cornerRadius.toFloat(), cornerRadius.toFloat()))

        // Placeholder text
        if (text.isEmpty() && !isFocused) {
            g2.color = ModernTheme.TEXT_MUTED
            g2.font = font
            val fm = g2.fontMetrics
            val y = (height - fm.height) / 2 + fm.ascent
            g2.drawString(placeholder, insets.left, y)
        }

        g2.dispose()
        super.paintComponent(g)
    }
}

class ModernScrollBarUI : BasicScrollBarUI() {
    override fun configureScrollBarColors() {
        thumbColor = Color(0x3B, 0x44, 0x54)
        trackColor = ModernTheme.BG_MAIN
    }

    override fun createDecreaseButton(orientation: Int): JButton = createZeroButton()
    override fun createIncreaseButton(orientation: Int): JButton = createZeroButton()

    private fun createZeroButton(): JButton {
        return JButton().apply {
            preferredSize = Dimension(0, 0)
            minimumSize = Dimension(0, 0)
            maximumSize = Dimension(0, 0)
        }
    }

    override fun paintThumb(g: Graphics, c: JComponent, thumbBounds: Rectangle) {
        if (thumbBounds.isEmpty || !scrollbar.isEnabled) return
        val g2 = g.create() as Graphics2D
        g2.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON)
        g2.color = thumbColor
        g2.fillRoundRect(thumbBounds.x + 2, thumbBounds.y + 2, thumbBounds.width - 4, thumbBounds.height - 4, 6, 6)
        g2.dispose()
    }

    override fun paintTrack(g: Graphics, c: JComponent, trackBounds: Rectangle) {
        g.color = trackColor
        g.fillRect(trackBounds.x, trackBounds.y, trackBounds.width, trackBounds.height)
    }
}
