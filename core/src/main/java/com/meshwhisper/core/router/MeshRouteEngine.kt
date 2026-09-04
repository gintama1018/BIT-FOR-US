package com.meshwhisper.core.router

import java.util.PriorityQueue
import java.util.concurrent.ConcurrentHashMap

/**
 * Edge representing a directional link between two mesh nodes.
 *
 * @param fromNode Origin node ID.
 * @param toNode Target node ID.
 * @param cost Link weight (default 1, higher values represent higher latency or lower RSSI).
 * @param lastSeen Timestamp (epoch ms) when this edge was last gossiped/verified.
 */
data class RouteEdge(
    val fromNode: Long,
    val toNode: Long,
    val cost: Int = 1,
    val lastSeen: Long = System.currentTimeMillis()
)

/**
 * Result of a route lookup for a destination node.
 */
sealed class RouteLookupResult {
    /**
     * Destination is an active direct radio neighbor (1-hop BLE/Wi-Fi connection).
     */
    data class Direct(val targetNodeId: Long) : RouteLookupResult()

    /**
     * Destination is reachable via multi-hop relay.
     *
     * @param nextHopNodeId The immediate next node on the shortest path.
     * @param hopCount Total number of hops to reach the destination (>= 2).
     * @param path Full sequence of node IDs [localNodeId, nextHopNodeId, ..., destinationNodeId].
     */
    data class NextHop(
        val nextHopNodeId: Long,
        val hopCount: Int,
        val path: List<Long>
    ) : RouteLookupResult()

    /**
     * No path to destination is known in the topology graph.
     */
    object Unreachable : RouteLookupResult()
}

/**
 * Deterministic graph routing engine for mesh networking.
 *
 * Computes shortest, loop-free paths using Dijkstra's algorithm over active direct
 * connections and verified gossiped topology edges.
 *
 * Supports:
 * - Dynamic direct neighbor tracking (BLE GATT & Wi-Fi Direct).
 * - Freshness-based edge filtering (drops stale edges older than [maxEdgeAgeMs]).
 * - Dynamic link failure penalization (failed links are quarantined for [failedLinkPenaltyMs] to force failover).
 * - Guaranteed acyclic, loop-free path generation.
 */
class MeshRouteEngine(
    val localNodeId: Long,
    val maxEdgeAgeMs: Long = 120_000L, // 2 minutes
    val failedLinkPenaltyMs: Long = 60_000L // 1 minute penalty on transmission failure
) {
    // Active direct neighbors (GATT connected or TCP session active)
    private val directNeighbors = ConcurrentHashMap.newKeySet<Long>()

    // Gossiped directed edges from other nodes: key is "fromNode:toNode"
    private val topologyEdges = ConcurrentHashMap<String, RouteEdge>()

    // Quarantined / failed links: key is "fromNode:toNode" -> expiresAt timestamp
    private val failedLinks = ConcurrentHashMap<String, Long>()

    /**
     * Updates the set of currently connected 1-hop radio neighbors.
     */
    fun updateDirectNeighbors(activeNeighbors: Set<Long>) {
        directNeighbors.clear()
        directNeighbors.addAll(activeNeighbors.filter { it != localNodeId && it != 0L })
    }

    /**
     * Adds or updates gossiped topology edges reported by peers.
     */
    fun updateEdges(edges: List<RouteEdge>) {
        val now = System.currentTimeMillis()
        for (edge in edges) {
            if (edge.fromNode == edge.toNode) continue // Ignore self-loops
            val key = "${edge.fromNode}:${edge.toNode}"
            topologyEdges[key] = edge.copy(lastSeen = if (edge.lastSeen > 0) edge.lastSeen else now)
        }
    }

    /**
     * Records a direct link transmission failure (e.g. GATT write failed, peer disconnected).
     * Quarantines this edge temporarily so the route engine will compute an alternative path.
     */
    fun markLinkFailed(fromNode: Long, toNode: Long, penaltyDurationMs: Long? = null) {
        val penalty = penaltyDurationMs ?: failedLinkPenaltyMs
        val key = "$fromNode:$toNode"
        failedLinks[key] = System.currentTimeMillis() + penalty
    }

    /**
     * Manually clears the failure quarantine for a link.
     */
    fun clearLinkFailure(fromNode: Long, toNode: Long) {
        failedLinks.remove("$fromNode:$toNode")
    }

    /**
     * Returns true if a link is currently penalized/quarantined.
     */
    fun isLinkFailed(fromNode: Long, toNode: Long, now: Long = System.currentTimeMillis()): Boolean {
        val key = "$fromNode:$toNode"
        val expiry = failedLinks[key] ?: return false
        if (now >= expiry) {
            failedLinks.remove(key)
            return false
        }
        return true
    }

    /**
     * Prunes expired edges and cleared link failures.
     */
    fun pruneStaleEntries(now: Long = System.currentTimeMillis()) {
        val cutoff = now - maxEdgeAgeMs
        topologyEdges.entries.removeIf { it.value.lastSeen < cutoff }
        failedLinks.entries.removeIf { it.value <= now }
    }

    /**
     * Resolves the optimal next hop to reach [destinationNodeId].
     *
     * @return [RouteLookupResult.Direct] if destination is directly connected,
     *         [RouteLookupResult.NextHop] if a multi-hop path exists,
     *         [RouteLookupResult.Unreachable] if no route is found.
     */
    @Synchronized
    fun resolveRoute(destinationNodeId: Long, now: Long = System.currentTimeMillis()): RouteLookupResult {
        if (destinationNodeId == localNodeId) {
            return RouteLookupResult.Direct(localNodeId)
        }

        // Direct neighbor check (if link is not penalized)
        if (directNeighbors.contains(destinationNodeId) && !isLinkFailed(localNodeId, destinationNodeId, now)) {
            return RouteLookupResult.Direct(destinationNodeId)
        }

        // Build active adjacency list
        val cutoff = now - maxEdgeAgeMs
        val graph = mutableMapOf<Long, MutableList<Pair<Long, Int>>>()

        // 1. Add direct neighbor edges
        for (neighbor in directNeighbors) {
            if (!isLinkFailed(localNodeId, neighbor, now)) {
                graph.getOrPut(localNodeId) { mutableListOf() }.add(Pair(neighbor, 1))
            }
        }

        // 2. Add gossiped edges (filtered by freshness and link failure)
        for ((key, edge) in topologyEdges) {
            if (edge.lastSeen >= cutoff && !isLinkFailed(edge.fromNode, edge.toNode, now)) {
                graph.getOrPut(edge.fromNode) { mutableListOf() }.add(Pair(edge.toNode, edge.cost))
            }
        }

        // Dijkstra's Shortest Path
        val distances = mutableMapOf<Long, Int>()
        val previous = mutableMapOf<Long, Long>()
        val pq = PriorityQueue<Pair<Long, Int>>(compareBy { it.second })

        distances[localNodeId] = 0
        pq.add(Pair(localNodeId, 0))

        while (pq.isNotEmpty()) {
            val (u, d) = pq.poll()
            if (d > (distances[u] ?: Int.MAX_VALUE)) continue
            if (u == destinationNodeId) break // Found optimal path to target

            val neighbors = graph[u] ?: emptyList()
            for ((v, weight) in neighbors) {
                val alt = d + weight
                if (alt < (distances[v] ?: Int.MAX_VALUE)) {
                    distances[v] = alt
                    previous[v] = u
                    pq.add(Pair(v, alt))
                }
            }
        }

        if (!distances.containsKey(destinationNodeId)) {
            return RouteLookupResult.Unreachable
        }

        // Reconstruct path backwards: destinationNodeId -> ... -> localNodeId
        val path = mutableListOf<Long>()
        var curr: Long? = destinationNodeId
        while (curr != null) {
            path.add(0, curr)
            curr = previous[curr]
            if (path.size > 50) { // Cycle safeguard
                return RouteLookupResult.Unreachable
            }
        }

        if (path.size < 2 || path.first() != localNodeId) {
            return RouteLookupResult.Unreachable
        }

        val nextHop = path[1]
        val hopCount = path.size - 1

        return if (hopCount == 1) {
            RouteLookupResult.Direct(destinationNodeId)
        } else {
            RouteLookupResult.NextHop(
                nextHopNodeId = nextHop,
                hopCount = hopCount,
                path = path
            )
        }
    }

    /**
     * Resolves all currently reachable routes from the local node.
     */
    @Synchronized
    fun getAllReachableRoutes(now: Long = System.currentTimeMillis()): Map<Long, RouteLookupResult> {
        val targets = mutableSetOf<Long>()
        targets.addAll(directNeighbors)
        for (edge in topologyEdges.values) {
            if (edge.lastSeen >= now - maxEdgeAgeMs) {
                targets.add(edge.toNode)
            }
        }
        targets.remove(localNodeId)

        val result = mutableMapOf<Long, RouteLookupResult>()
        for (target in targets) {
            result[target] = resolveRoute(target, now)
        }
        return result
    }
}
