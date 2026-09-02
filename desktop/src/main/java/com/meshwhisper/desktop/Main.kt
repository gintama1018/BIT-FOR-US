package com.meshwhisper.desktop

import com.meshwhisper.core.logging.StdoutLogger
import com.meshwhisper.desktop.crypto.DesktopPassphraseKeyStorage
import com.meshwhisper.desktop.db.DesktopDatabase
import com.meshwhisper.desktop.router.DesktopMeshRouter
import com.meshwhisper.desktop.ui.DesktopMainWindow
import com.meshwhisper.desktop.wifi.DesktopWifiEngine
import java.awt.GraphicsEnvironment
import javax.swing.SwingUtilities
import javax.swing.UIManager

fun main(args: Array<String>) {
    val logger = StdoutLogger
    val keyStorage = DesktopPassphraseKeyStorage()
    val database = DesktopDatabase()
    val wifiEngine = DesktopWifiEngine(logger)
    val router = DesktopMeshRouter(keyStorage, database, wifiEngine, logger)

    println("""
        ===================================================================
        🛡️  MeshWhisper Desktop Node (Windows / macOS) — Hybrid Mesh Station
        ===================================================================
        Node ID    : 0x${router.myNodeIdHex} (${router.myNodeId})
        Alias      : ${router.myAlias}
        Transport  : UDP 42425 (Discovery) | TCP 42426 (Data Streaming)
        Security   : X25519 ECDH + HKDF-SHA256 + AES-256-GCM (AEAD)
        Database   : SQLite (~/.meshwhisper/meshwhisper.db)
        ===================================================================
    """.trimIndent())

    router.start()

    // Launch Desktop Graphical User Interface (GUI)
    if (!GraphicsEnvironment.isHeadless()) {
        try {
            UIManager.setLookAndFeel(UIManager.getSystemLookAndFeelClassName())
        } catch (_: Exception) {}

        SwingUtilities.invokeLater {
            val mainWindow = DesktopMainWindow(router, database, wifiEngine)
            mainWindow.isVisible = true
        }
    } else {
        println("Running in headless console mode...")
    }
}
