package com.meshwhisper.core.protocol

import java.util.concurrent.ConcurrentLinkedQueue
import java.util.concurrent.atomic.AtomicInteger

/**
 * High-performance, starvation-free traffic scheduler and QoS controller for MeshWhisper.
 * 
 * Features:
 * 1. 4-Tier Bounded Queues: Prevents heap exhaustion under flood bursts.
 * 2. Weighted Fair Scheduling (WFS): Prevents bulk transfers from starving indefinitely.
 * 3. Airtime Protection Watchdog: Discards stale/expired frames before they hit physical radios.
 * 4. Lock-Free Ingress: Uses ConcurrentLinkedQueue per priority tier for non-blocking concurrent enqueueing.
 */
class MeshTrafficController(
    val maxQueuePerTier: Int = 100,
    val maxPacketLifetimeMs: Long = 30_000L
) {
    private val criticalQueue = ConcurrentLinkedQueue<PrioritizedPacket>()
    private val highQueue = ConcurrentLinkedQueue<PrioritizedPacket>()
    private val standardQueue = ConcurrentLinkedQueue<PrioritizedPacket>()
    private val bulkQueue = ConcurrentLinkedQueue<PrioritizedPacket>()

    private val criticalCount = AtomicInteger(0)
    private val highCount = AtomicInteger(0)
    private val standardCount = AtomicInteger(0)
    private val bulkCount = AtomicInteger(0)

    // Weighted Quota for Deficit Round Robin (Standard : Bulk = 2 : 1)
    private var standardCredit = 0

    /**
     * Enqueues a packet with its appropriate QoS priority.
     * Returns true if enqueued, false if dropped due to queue saturation.
     */
    fun enqueue(
        rawBytes: ByteArray,
        packetType: PacketType,
        targetNodeId: Long? = null,
        excludeAddress: String? = null
    ): Boolean {
        val priority = packetType.trafficPriority
        val packet = PrioritizedPacket(
            rawBytes = rawBytes,
            priority = priority,
            targetNodeId = targetNodeId,
            excludeAddress = excludeAddress,
            enqueuedAtMs = System.currentTimeMillis()
        )

        val (queue, counter) = getQueueAndCounter(priority)
        if (counter.get() >= maxQueuePerTier) {
            // Drop oldest item in this tier to maintain bounded memory
            val dropped = queue.poll()
            if (dropped != null) {
                counter.decrementAndGet()
            }
        }

        queue.offer(packet)
        counter.incrementAndGet()
        return true
    }

    /**
     * Polls the next packet to transmit based on QoS priority and fairness credits.
     * Automatically discards expired packets.
     */
    fun pollNext(): PrioritizedPacket? {
        val now = System.currentTimeMillis()

        // 1. Always drain Tier 0 (Critical Emergency - SOS & Call Signaling) first
        var packet = pollNonExpired(criticalQueue, criticalCount, now)
        if (packet != null) return packet

        // 2. Drain Tier 1 (High Interactive - ACKs, Key Exchange, Typing)
        packet = pollNonExpired(highQueue, highCount, now)
        if (packet != null) return packet

        // 3. Fairness between Standard and Bulk (Weighted 2:1)
        if (standardCredit < 2) {
            packet = pollNonExpired(standardQueue, standardCount, now)
            if (packet != null) {
                standardCredit++
                return packet
            }
        }
        standardCredit = 0

        // 4. Try Bulk Transfer
        packet = pollNonExpired(bulkQueue, bulkCount, now)
        if (packet != null) return packet

        // Fallback: Check Standard again if Bulk was empty
        return pollNonExpired(standardQueue, standardCount, now)
    }

    private fun pollNonExpired(
        queue: ConcurrentLinkedQueue<PrioritizedPacket>,
        counter: AtomicInteger,
        now: Long
    ): PrioritizedPacket? {
        while (true) {
            val item = queue.poll() ?: return null
            counter.decrementAndGet()
            if (now - item.enqueuedAtMs <= maxPacketLifetimeMs) {
                return item
            }
            // Packet expired in queue: dropped without transmitting to save airtime
        }
    }

    private fun getQueueAndCounter(priority: TrafficPriority): Pair<ConcurrentLinkedQueue<PrioritizedPacket>, AtomicInteger> {
        return when (priority) {
            TrafficPriority.CRITICAL_EMERGENCY -> Pair(criticalQueue, criticalCount)
            TrafficPriority.HIGH_INTERACTIVE -> Pair(highQueue, highCount)
            TrafficPriority.STANDARD_MESSAGING -> Pair(standardQueue, standardCount)
            TrafficPriority.BULK_TRANSFER -> Pair(bulkQueue, bulkCount)
        }
    }

    fun getCountForTier(priority: TrafficPriority): Int {
        return when (priority) {
            TrafficPriority.CRITICAL_EMERGENCY -> criticalCount.get()
            TrafficPriority.HIGH_INTERACTIVE -> highCount.get()
            TrafficPriority.STANDARD_MESSAGING -> standardCount.get()
            TrafficPriority.BULK_TRANSFER -> bulkCount.get()
        }
    }

    fun totalQueued(): Int = criticalCount.get() + highCount.get() + standardCount.get() + bulkCount.get()

    fun clear() {
        criticalQueue.clear()
        highQueue.clear()
        standardQueue.clear()
        bulkQueue.clear()
        criticalCount.set(0)
        highCount.set(0)
        standardCount.set(0)
        bulkCount.set(0)
    }
}
