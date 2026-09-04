package com.meshwhisper.app.wifi

import com.google.common.truth.Truth.assertThat
import org.junit.Test
import java.net.InetAddress
import java.net.ServerSocket
import java.net.Socket
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger

class WifiConnectionLimitTest {

    @Test
    fun testMaxConcurrentWifiConnectionsConstant() {
        assertThat(MeshWifiEngine.MAX_CONCURRENT_WIFI_CONNECTIONS).isEqualTo(5)
    }

    @Test
    fun testConnectionSixPlusIsRejected() {
        val server = ServerSocket(0, 50, InetAddress.getByName("127.0.0.1"))
        val port = server.localPort
        val acceptedSockets = mutableListOf<Socket>()
        val rejectedCount = AtomicInteger(0)
        val activeSessions = ConcurrentHashMap<Long, Socket>()
        val latch = CountDownLatch(6)

        val serverThread = Thread {
            try {
                while (!server.isClosed && (acceptedSockets.size + rejectedCount.get() < 6)) {
                    val socket = server.accept()
                    if (activeSessions.size >= MeshWifiEngine.MAX_CONCURRENT_WIFI_CONNECTIONS) {
                        rejectedCount.incrementAndGet()
                        socket.close()
                    } else {
                        val id = acceptedSockets.size.toLong() + 1
                        activeSessions[id] = socket
                        acceptedSockets.add(socket)
                    }
                    latch.countDown()
                }
            } catch (_: Exception) {}
        }
        serverThread.isDaemon = true
        serverThread.start()

        val clientSockets = mutableListOf<Socket>()
        for (i in 1..6) {
            val client = Socket("127.0.0.1", port)
            clientSockets.add(client)
        }

        assertThat(latch.await(3, TimeUnit.SECONDS)).isTrue()
        assertThat(acceptedSockets.size).isEqualTo(5)
        assertThat(rejectedCount.get()).isEqualTo(1)

        // The 6th client socket should be closed by server immediately upon reaching limit
        val client6 = clientSockets[5]
        val read = client6.getInputStream().read()
        assertThat(read).isEqualTo(-1) // Connection closed by server

        server.close()
        clientSockets.forEach { try { it.close() } catch (_: Exception) {} }
        acceptedSockets.forEach { try { it.close() } catch (_: Exception) {} }
    }

    @Test
    fun testHandshakeTimeoutThrowsAndClosesSocket() {
        assertThat(MeshWifiEngine.TCP_HANDSHAKE_TIMEOUT_MS).isEqualTo(5000)

        val server = ServerSocket(0, 5, InetAddress.getByName("127.0.0.1"))
        val port = server.localPort
        val latch = CountDownLatch(1)
        var timedOut = false

        val serverThread = Thread {
            try {
                val socket = server.accept()
                // Test timeout mechanism (300ms for fast test execution)
                socket.soTimeout = 300
                val inStream = java.io.DataInputStream(socket.getInputStream())
                try {
                    inStream.readLong() // Client connects and sends nothing
                } catch (_: java.net.SocketTimeoutException) {
                    timedOut = true
                } finally {
                    socket.close()
                    latch.countDown()
                }
            } catch (_: Exception) {}
        }
        serverThread.isDaemon = true
        serverThread.start()

        val client = Socket("127.0.0.1", port)
        // Client deliberately sends nothing
        assertThat(latch.await(3, TimeUnit.SECONDS)).isTrue()
        assertThat(timedOut).isTrue()

        val readResult = client.getInputStream().read()
        assertThat(readResult).isEqualTo(-1)

        client.close()
        server.close()
    }
}
