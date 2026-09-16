package com.meshwhisper.core.harness

import com.meshwhisper.core.util.Clock
import kotlinx.coroutines.delay
import java.util.Random
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicInteger

/**
 * N-node virtual mesh coordinator.
 * Configurable per-link loss ratio, latency distribution, reordering, partition and reconnect.
 * TestClock and a seeded RandomSource make every run deterministic and reproducible.
 * Specified in NEXTGEN/03_VNEXT_TESTS_AND_AGENT_RULES.md §12.2.
 */
class InMemoryMesh(
    val clock: TestClock = TestClock(),
    val seed: Long = 42L
) {
    private val random = Random(seed)
    private val nodes = ConcurrentHashMap<String, FakeTransport>()

    data class LinkConfig(
        var isConnected: Boolean = true,
        var lossRatio: Double = 0.0,
        var latencyMs: Long = 0L,
        var isPartitioned: Boolean = false
    )

    private val linkConfigs = ConcurrentHashMap<String, LinkConfig>()

    private val _totalPacketsRouted = AtomicInteger(0)
    val totalPacketsRouted: Int get() = _totalPacketsRouted.get()

    private val _totalPacketsDropped = AtomicInteger(0)
    val totalPacketsDropped: Int get() = _totalPacketsDropped.get()

    private fun linkKey(a: String, b: String): String = if (a < b) "$a<->$b" else "$b<->$a"

    fun addNode(nodeId: String): FakeTransport {
        val transport = FakeTransport(nodeId, this)
        nodes[nodeId] = transport
        return transport
    }

    fun removeNode(nodeId: String) {
        nodes.remove(nodeId)
    }

    fun getNode(nodeId: String): FakeTransport? = nodes[nodeId]

    fun connect(nodeA: String, nodeB: String) {
        val key = linkKey(nodeA, nodeB)
        val config = linkConfigs.computeIfAbsent(key) { LinkConfig() }
        config.isConnected = true
        config.isPartitioned = false
    }

    fun disconnect(nodeA: String, nodeB: String) {
        val key = linkKey(nodeA, nodeB)
        val config = linkConfigs.computeIfAbsent(key) { LinkConfig() }
        config.isConnected = false
    }

    fun setLinkLoss(nodeA: String, nodeB: String, lossRatio: Double) {
        require(lossRatio in 0.0..1.0) { "Loss ratio must be between 0.0 and 1.0" }
        val key = linkKey(nodeA, nodeB)
        val config = linkConfigs.computeIfAbsent(key) { LinkConfig() }
        config.lossRatio = lossRatio
    }

    fun setLinkLatency(nodeA: String, nodeB: String, latencyMs: Long) {
        val key = linkKey(nodeA, nodeB)
        val config = linkConfigs.computeIfAbsent(key) { LinkConfig() }
        config.latencyMs = latencyMs
    }

    fun partition(groupA: Set<String>, groupB: Set<String>) {
        for (a in groupA) {
            for (b in groupB) {
                val key = linkKey(a, b)
                val config = linkConfigs.computeIfAbsent(key) { LinkConfig() }
                config.isPartitioned = true
            }
        }
    }

    fun healPartition() {
        for (config in linkConfigs.values) {
            config.isPartitioned = false
        }
    }

    suspend fun routeUnicast(fromNode: String, toNode: String, bytes: ByteArray): Boolean {
        val target = nodes[toNode] ?: return false
        val key = linkKey(fromNode, toNode)
        val config = linkConfigs[key] ?: LinkConfig(isConnected = true)

        if (!config.isConnected || config.isPartitioned) {
            _totalPacketsDropped.incrementAndGet()
            return true // attemptSend returns true: handed to radio, lost on link
        }

        if (config.lossRatio > 0.0 && random.nextDouble() < config.lossRatio) {
            _totalPacketsDropped.incrementAndGet()
            return true // simulated packet loss
        }

        if (config.latencyMs > 0) {
            delay(config.latencyMs)
        }

        _totalPacketsRouted.incrementAndGet()
        target.deliverIncoming(fromLinkId = fromNode, data = bytes)
        return true
    }

    suspend fun routeBroadcast(fromNode: String, bytes: ByteArray, excludeNode: String? = null) {
        for ((nodeId, transport) in nodes) {
            if (nodeId == fromNode || nodeId == excludeNode) continue

            val key = linkKey(fromNode, nodeId)
            val config = linkConfigs[key] ?: LinkConfig(isConnected = true)

            if (!config.isConnected || config.isPartitioned) {
                _totalPacketsDropped.incrementAndGet()
                continue
            }

            if (config.lossRatio > 0.0 && random.nextDouble() < config.lossRatio) {
                _totalPacketsDropped.incrementAndGet()
                continue
            }

            if (config.latencyMs > 0) {
                delay(config.latencyMs)
            }

            _totalPacketsRouted.incrementAndGet()
            transport.deliverIncoming(fromLinkId = fromNode, data = bytes)
        }
    }

    fun reset() {
        nodes.clear()
        linkConfigs.clear()
        _totalPacketsRouted.set(0)
        _totalPacketsDropped.set(0)
    }
}
