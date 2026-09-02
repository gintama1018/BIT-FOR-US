package com.meshwhisper.desktop

import com.meshwhisper.core.logging.StdoutLogger
import com.meshwhisper.desktop.crypto.DesktopPassphraseKeyStorage
import com.meshwhisper.desktop.db.DesktopDatabase
import com.meshwhisper.desktop.router.DesktopMeshRouter
import com.meshwhisper.desktop.wifi.DesktopWifiEngine
import kotlinx.coroutines.*
import java.util.Scanner

fun main(args: Array<String>) {
    val logger = StdoutLogger
    val keyStorage = DesktopPassphraseKeyStorage()
    val database = DesktopDatabase()
    val wifiEngine = DesktopWifiEngine(logger)
    val router = DesktopMeshRouter(keyStorage, database, wifiEngine, logger)

    println("""
        ===================================================================
        🔥  MeshWhisper Desktop Node (Windows / macOS) — Hybrid Mesh Station
        ===================================================================
        Node ID    : 0x${router.myNodeIdHex} (${router.myNodeId})
        Alias      : ${router.myAlias}
        Transport  : UDP 42425 (Discovery) | TCP 42426 (Data Streaming)
        Security   : X25519 ECDH + HKDF-SHA256 + AES-256-GCM (AEAD)
        Database   : SQLite (~/.meshwhisper/meshwhisper.db)
        ===================================================================
        Commands:
          /broadcast <text>         - Send public broadcast message
          /sos <emergency_text>     - Broadcast urgent SOS emergency alert
          /dm <nodeIdHex> <text>    - Send encrypted direct message to peer
          /peers                    - List discovered mesh peers
          /edges                    - List active topology links (Mesh Radar)
          /status                   - View network & peer status
          /alias <name>             - Change node alias
          /quit                     - Shutdown mesh station
        ===================================================================
    """.trimIndent())

    router.start()

    val scope = CoroutineScope(Dispatchers.Default + SupervisorJob())

    // Listen to incoming messages in background
    scope.launch {
        router.incomingMessages.collect { msg ->
            if (msg.isEmergencySos) {
                println("\n🚨 [EMERGENCY SOS ALERT] From: 0x${String.format("%016X", msg.senderNodeId)} -> ${msg.text}")
            } else if (msg.isChannelBroadcast) {
                println("\n💬 [PUBLIC MESH] 0x${String.format("%016X", msg.senderNodeId)}: ${msg.text}")
            } else {
                println("\n🔒 [DIRECT DM] 0x${String.format("%016X", msg.senderNodeId)}: ${msg.text}")
            }
            print("> ")
        }
    }

    val scanner = Scanner(System.`in`)
    print("> ")

    while (scanner.hasNextLine()) {
        val line = scanner.nextLine().trim()
        if (line.equals("/quit", ignoreCase = true) || line.equals("exit", ignoreCase = true)) {
            break
        }

        when {
            line.startsWith("/broadcast ") -> {
                val text = line.removePrefix("/broadcast ").trim()
                if (text.isNotEmpty()) {
                    router.sendPublicMessage(text, isSos = false)
                    println(">> Broadcast sent.")
                }
            }
            line.startsWith("/sos ") -> {
                val text = line.removePrefix("/sos ").trim()
                if (text.isNotEmpty()) {
                    router.sendPublicMessage(text, isSos = true)
                    println(">> 🚨 SOS Alert broadcasted across all mesh peers!")
                }
            }
            line.startsWith("/dm ") -> {
                val parts = line.removePrefix("/dm ").trim().split(" ", limit = 2)
                if (parts.size == 2) {
                    val targetHex = parts[0].removePrefix("0x").trim()
                    val text = parts[1].trim()
                    try {
                        val targetNodeId = java.lang.Long.parseUnsignedLong(targetHex, 16)
                        val msgId = router.sendDirectMessage(targetNodeId, text)
                        if (msgId != null) {
                            println(">> Encrypted DM sent to 0x$targetHex (Msg ID: $msgId)")
                        } else {
                            println(">> Peer 0x$targetHex not found in local database. Wait for discovery beacon.")
                        }
                    } catch (e: Exception) {
                        println(">> Invalid Node ID hex: $targetHex")
                    }
                } else {
                    println(">> Usage: /dm <nodeIdHex> <message>")
                }
            }
            line == "/peers" -> {
                val peers = database.getAllPeers()
                println("--- Discovered Mesh Peers (${peers.size}) ---")
                if (peers.isEmpty()) {
                    println("No peers discovered yet. Waiting for UDP beacons...")
                } else {
                    for (p in peers) {
                        val active = if (wifiEngine.isPeerConnected(p.nodeId)) "⚡ [LAN TCP]" else "📡 [DISCOVERED]"
                        println("  0x${String.format("%016X", p.nodeId)} | ${p.alias.padEnd(16)} | Hops: ${p.hops} | $active")
                    }
                }
                println("----------------------------------------------")
            }
            line == "/edges" -> {
                val edges = database.getAllTopologyEdges()
                println("--- Active Topology Edges (${edges.size}) ---")
                for (e in edges) {
                    println("  0x${String.format("%016X", e.sourceNodeId)} <---> 0x${String.format("%016X", e.targetNodeId)} (RSSI: ${e.rssi} dBm)")
                }
                println("----------------------------------------------")
            }
            line == "/status" -> {
                println("--- Mesh Station Status ---")
                println("  Node ID       : 0x${router.myNodeIdHex}")
                println("  Alias         : ${router.myAlias}")
                println("  Local IP      : ${wifiEngine.localIpAddress.value ?: "Resolving..."}")
                println("  Active Peers  : ${wifiEngine.connectedPeersCount.value}")
                println("  Wi-Fi Engine  : ${if (wifiEngine.isWifiActive.value) "ACTIVE" else "IDLE"}")
                println("---------------------------")
            }
            line.startsWith("/alias ") -> {
                val newAlias = line.removePrefix("/alias ").trim()
                if (newAlias.isNotEmpty()) {
                    router.updateAlias(newAlias)
                    println(">> Updated alias to: $newAlias")
                }
            }
            line.isNotEmpty() -> {
                println(">> Unknown command. Type /broadcast, /sos, /dm, /peers, /edges, /status, or /quit.")
            }
        }
        print("> ")
    }

    println("Shutting down MeshWhisper Desktop Station...")
    router.stop()
    scope.cancel()
}
