package com.meshwhisper.core.router

import com.meshwhisper.core.protocol.NeighborEntry
import com.meshwhisper.core.util.Clock
import com.meshwhisper.core.util.SystemClock
import java.util.PriorityQueue
import java.util.concurrent.ConcurrentHashMap

/**
 * State of a topology edge in the vNext trust model.
 * Specified in NEXTGEN/01_VNEXT_PROTOCOL_FROZEN.md §2.15.
 */
enum class EdgeState {
    /**
     * Single-sided or unreciprocated assertion. Never routable (closes S-4).
     */
    STAGED,

    /**
     * Reciprocally asserted by both endpoints within [MeshRouteEngine.EDGE_FRESHNESS_MS]. Routable.
     */
    CONFIRMED
}

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
 * Implements Phase P5: Routing + Custody requirements:
 * - STAGED / CONFIRMED topology edges (closes S-4).
 * - Reciprocal assertion within EDGE_FRESHNESS_MS (90s).
 * - Stale edge degradation (reverts to STAGED after 90s, evicted after 180s).
 * - Bounded topology state (MAX_TOPOLOGY_ORIGINS = 512, LRU eviction).
 * - Edge set replacement per announce (never indefinitely merged).
 * - Dynamic LinkTrust / greyhole cost escalation.
 * - Route caching (Dijkstra runs only on topology changes, not per packet).
 * - Guaranteed acyclic, loop-free path generation (max 50 hops).
 */
class MeshRouteEngine(
    val localNodeId: Long,
    val maxEdgeAgeMs: Long = EDGE_FRESHNESS_MS,
    val failedLinkPenaltyMs: Long = DEFAULT_FAILED_PENALTY_MS,
    val clock: Clock = SystemClock()
) {
    companion object {
        const val MAX_NEIGHBORS_PER_ANNOUNCE = 16
        const val EDGE_FRESHNESS_MS = 90_000L      // 90 seconds
        const val EDGE_EVICT_MS = 180_000L        // 180 seconds
        const val MAX_TOPOLOGY_ORIGINS = 512       // Max origins tracked
        const val PRUNE_CADENCE_MS = 30_000L      // 30 seconds prune cadence
        const val DEFAULT_FAILED_PENALTY_MS = 60_000L
        const val MAX_ROUTE_HOPS = 50
    }

    // Active direct neighbors (GATT connected or TCP session active)
    private val directNeighbors = ConcurrentHashMap.newKeySet<Long>()

    // Directed claims: "fromNode:toNode" -> RouteEdge
    private val directedClaims = ConcurrentHashMap<String, RouteEdge>()

    // Origins LRU map tracking originNodeId -> set of claimed neighbors
    private val originNeighborSets = LinkedHashMap<Long, Set<Long>>(16, 0.75f, true)

    // Quarantined / failed links: key is "fromNode:toNode" -> expiresAt timestamp
    private val failedLinks = ConcurrentHashMap<String, Long>()

    // LinkTrust / Greyhole drop counter: key is "fromNode:toNode" or "nodeId" -> drop count
    private val linkDropCounts = ConcurrentHashMap<String, Int>()

    // Route cache: destinationNodeId -> RouteLookupResult
    private val routeCache = ConcurrentHashMap<Long, RouteLookupResult>()

    // Instrumentation counter to verify Dijkstra execution frequency (T-ROUTE-07)
    @Volatile
    var dijkstraExecutionCount: Int = 0
        private set

    /**
     * Canonical edge key helper: order nodes so key is canonical for undirected pair.
     */
    private fun canonicalPairKey(a: Long, b: Long): String {
        return if (a <= b) "$a:$b" else "$b:$a"
    }

    private fun directedKey(from: Long, to: Long): String = "$from:$to"

    /**
     * Invalidate cached route lookups on any topology change event.
     */
    @Synchronized
    fun invalidateCache() {
        routeCache.clear()
    }

    /**
     * Updates the set of currently connected 1-hop radio neighbors.
     */
    @Synchronized
    fun updateDirectNeighbors(activeNeighbors: Set<Long>) {
        val filtered = activeNeighbors.filter { it != localNodeId && it != 0L }.toSet()
        if (filtered != directNeighbors) {
            directNeighbors.clear()
            directNeighbors.addAll(filtered)
            invalidateCache()
        }
    }

    /**
     * Wholesale update of an origin's advertised neighbors from an authenticated PEER_ANNOUNCE.
     * Enforces wholesale replacement per announce (never merged) and MAX_TOPOLOGY_ORIGINS = 512.
     */
    @Synchronized
    fun updateOriginNeighbors(
        originNodeId: Long,
        neighbors: List<NeighborEntry>,
        timestampMs: Long = clock.nowMillis()
    ) {
        if (originNodeId == 0L) return

        // 1. Clean up previously claimed edges by this origin that are not in new set
        val oldNeighbors = originNeighborSets[originNodeId] ?: emptySet()
        val newNeighbors = neighbors.map { it.nodeId64 }.filter { it != originNodeId && it != 0L }.toSet()

        for (oldTarget in oldNeighbors) {
            if (!newNeighbors.contains(oldTarget)) {
                directedClaims.remove(directedKey(originNodeId, oldTarget))
            }
        }

        // 2. Insert new claims
        for (neighbor in neighbors) {
            if (neighbor.nodeId64 == originNodeId || neighbor.nodeId64 == 0L) continue
            val cost = maxOf(1, (100 - neighbor.linkQuality.toInt().coerceIn(0, 100)) / 20)
            directedClaims[directedKey(originNodeId, neighbor.nodeId64)] = RouteEdge(
                fromNode = originNodeId,
                toNode = neighbor.nodeId64,
                cost = cost,
                lastSeen = timestampMs
            )
        }

        // 3. Update origin LRU tracking
        originNeighborSets[originNodeId] = newNeighbors
        if (originNeighborSets.size > MAX_TOPOLOGY_ORIGINS) {
            val oldestOrigin = originNeighborSets.keys.iterator().next()
            originNeighborSets.remove(oldestOrigin)?.forEach { target ->
                directedClaims.remove(directedKey(oldestOrigin, target))
            }
        }

        invalidateCache()
    }

    /**
     * Legacy / test helper: Adds or updates directed topology claims.
     */
    @Synchronized
    fun updateEdges(edges: List<RouteEdge>) {
        val now = clock.nowMillis()
        var changed = false
        for (edge in edges) {
            if (edge.fromNode == edge.toNode) continue
            val key = directedKey(edge.fromNode, edge.toNode)
            val updated = edge.copy(lastSeen = if (edge.lastSeen > 0) edge.lastSeen else now)
            val prev = directedClaims.put(key, updated)
            if (prev == null || prev.cost != updated.cost || prev.lastSeen != updated.lastSeen) {
                changed = true
            }

            // Track in origin sets
            val set = originNeighborSets.getOrPut(edge.fromNode) { mutableSetOf() }.toMutableSet()
            set.add(edge.toNode)
            originNeighborSets[edge.fromNode] = set
        }

        // Enforce 512 origins cap
        while (originNeighborSets.size > MAX_TOPOLOGY_ORIGINS) {
            val oldestOrigin = originNeighborSets.keys.iterator().next()
            originNeighborSets.remove(oldestOrigin)?.forEach { target ->
                directedClaims.remove(directedKey(oldestOrigin, target))
            }
            changed = true
        }

        if (changed) {
            invalidateCache()
        }
    }

    /**
     * Checks the confirmation state of an edge between [nodeA] and [nodeB].
     * An edge is CONFIRMED only if both endpoints assert each other within [EDGE_FRESHNESS_MS] (90s).
     * Otherwise it is STAGED (closes S-4).
     */
    fun getEdgeState(nodeA: Long, nodeB: Long, now: Long = clock.nowMillis()): EdgeState {
        if (nodeA == nodeB) return EdgeState.STAGED

        // Check direct link: if one side is localNodeId and the other is an active direct radio neighbor
        val isDirectNeighbor = (nodeA == localNodeId && directNeighbors.contains(nodeB)) ||
                (nodeB == localNodeId && directNeighbors.contains(nodeA))

        val claimAB = directedClaims[directedKey(nodeA, nodeB)]
        val claimBA = directedClaims[directedKey(nodeB, nodeA)]

        val aAssertsB = (claimAB != null && (now - claimAB.lastSeen) <= EDGE_FRESHNESS_MS) ||
                (nodeA == localNodeId && isDirectNeighbor)
        val bAssertsA = (claimBA != null && (now - claimBA.lastSeen) <= EDGE_FRESHNESS_MS) ||
                (nodeB == localNodeId && isDirectNeighbor)

        return if (aAssertsB && bAssertsA) {
            EdgeState.CONFIRMED
        } else {
            EdgeState.STAGED
        }
    }

    /**
     * Records a transmission failure or dropped packet by a relay.
     * Escalates link cost for greyhole routing (T-ROUTE-05).
     */
    @Synchronized
    fun recordDrop(fromNode: Long, toNode: Long) {
        val key = directedKey(fromNode, toNode)
        val count = linkDropCounts.compute(key) { _, v -> (v ?: 0) + 1 } ?: 1
        invalidateCache()
    }

    /**
     * Records a direct link transmission failure. Quarantines this edge temporarily.
     */
    @Synchronized
    fun markLinkFailed(fromNode: Long, toNode: Long, penaltyDurationMs: Long? = null) {
        val penalty = penaltyDurationMs ?: failedLinkPenaltyMs
        val key = directedKey(fromNode, toNode)
        failedLinks[key] = clock.nowMillis() + penalty
        invalidateCache()
    }

    /**
     * Clears failure quarantine and drop counts for a link.
     */
    @Synchronized
    fun clearLinkFailure(fromNode: Long, toNode: Long) {
        val key = directedKey(fromNode, toNode)
        failedLinks.remove(key)
        linkDropCounts.remove(key)
        invalidateCache()
    }

    /**
     * Returns true if a link is currently penalized/quarantined.
     */
    fun isLinkFailed(fromNode: Long, toNode: Long, now: Long = clock.nowMillis()): Boolean {
        val key = directedKey(fromNode, toNode)
        val expiry = failedLinks[key] ?: return false
        if (now >= expiry) {
            failedLinks.remove(key)
            return false
        }
        return true
    }

    /**
     * Prunes stale directed claims and link failures.
     * - Edges unasserted for > [EDGE_EVICT_MS] (180s) are evicted.
     * - Stale quarantine expirations are cleared.
     * - Invalidates route cache if any edge state changed.
     */
    @Synchronized
    fun pruneStaleEntries(now: Long = clock.nowMillis()) {
        val evictCutoff = now - EDGE_EVICT_MS
        var changed = false

        val it = directedClaims.entries.iterator()
        while (it.hasNext()) {
            val entry = it.next()
            if (entry.value.lastSeen < evictCutoff) {
                it.remove()
                changed = true
            }
        }

        // Clean up empty origin sets
        val originIt = originNeighborSets.entries.iterator()
        while (originIt.hasNext()) {
            val (origin, targets) = originIt.next()
            val remainingTargets = targets.filter { target ->
                val claim = directedClaims[directedKey(origin, target)]
                claim != null && claim.lastSeen >= evictCutoff
            }.toSet()
            if (remainingTargets.isEmpty()) {
                originIt.remove()
                changed = true
            } else if (remainingTargets.size != targets.size) {
                originNeighborSets[origin] = remainingTargets
                changed = true
            }
        }

        val failedIt = failedLinks.entries.iterator()
        while (failedIt.hasNext()) {
            val entry = failedIt.next()
            if (entry.value <= now) {
                failedIt.remove()
                changed = true
            }
        }

        if (changed) {
            invalidateCache()
        }
    }

    /**
     * Resolves the optimal next hop to reach [destinationNodeId].
     * Uses route cache when valid, avoiding redundant Dijkstra runs (T-ROUTE-07).
     */
    @Synchronized
    fun resolveRoute(destinationNodeId: Long, now: Long = clock.nowMillis()): RouteLookupResult {
        if (destinationNodeId == localNodeId) {
            return RouteLookupResult.Direct(localNodeId)
        }

        // Direct neighbor fast path: if direct neighbor is active and not quarantined
        if (directNeighbors.contains(destinationNodeId) && !isLinkFailed(localNodeId, destinationNodeId, now)) {
            return RouteLookupResult.Direct(destinationNodeId)
        }

        // Clear expired link penalties (topology state change event)
        if (failedLinks.isNotEmpty()) {
            val it = failedLinks.entries.iterator()
            var anyExpired = false
            while (it.hasNext()) {
                val entry = it.next()
                if (now >= entry.value) {
                    it.remove()
                    anyExpired = true
                }
            }
            if (anyExpired) {
                invalidateCache()
            }
        }

        // Check route cache
        val cached = routeCache[destinationNodeId]
        if (cached != null) {
            return cached
        }

        // Cache miss: Execute Dijkstra and populate cache for all destinations
        executeDijkstra(now)

        return routeCache[destinationNodeId] ?: RouteLookupResult.Unreachable
    }

    /**
     * Executes Dijkstra's algorithm over CONFIRMED edges only, populating [routeCache].
     * Increments [dijkstraExecutionCount].
     */
    private fun executeDijkstra(now: Long) {
        dijkstraExecutionCount++

        // Build adjacency graph of CONFIRMED edges only
        val graph = mutableMapOf<Long, MutableList<Pair<Long, Int>>>()

        // 1. Direct neighbor edges (from localNodeId)
        for (neighbor in directNeighbors) {
            if (!isLinkFailed(localNodeId, neighbor, now)) {
                val dropPenalty = linkDropCounts[directedKey(localNodeId, neighbor)] ?: 0
                val cost = 1 + (dropPenalty * 5)
                graph.getOrPut(localNodeId) { mutableListOf() }.add(Pair(neighbor, cost))
            }
        }

        // 2. Gossiped edges: examine all canonical pairs and include ONLY CONFIRMED edges
        val seenPairs = mutableSetOf<String>()
        for ((key, claim) in directedClaims) {
            val u = claim.fromNode
            val v = claim.toNode
            val pairKey = canonicalPairKey(u, v)
            if (!seenPairs.add(pairKey)) continue

            // Evaluate reciprocal confirmation
            if (getEdgeState(u, v, now) == EdgeState.CONFIRMED) {
                // Add u -> v if not failed
                if (!isLinkFailed(u, v, now)) {
                    val dropPenalty = linkDropCounts[directedKey(u, v)] ?: 0
                    val cost = claim.cost + (dropPenalty * 5)
                    graph.getOrPut(u) { mutableListOf() }.add(Pair(v, cost))
                }
                // Add v -> u if not failed
                if (!isLinkFailed(v, u, now)) {
                    val claimReverse = directedClaims[directedKey(v, u)]
                    val baseCost = claimReverse?.cost ?: claim.cost
                    val dropPenalty = linkDropCounts[directedKey(v, u)] ?: 0
                    val cost = baseCost + (dropPenalty * 5)
                    graph.getOrPut(v) { mutableListOf() }.add(Pair(u, cost))
                }
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

        // Reconstruct paths for all reachable nodes and store in routeCache
        for ((target, _) in distances) {
            if (target == localNodeId) continue

            // Direct check
            if (directNeighbors.contains(target) && !isLinkFailed(localNodeId, target, now)) {
                routeCache[target] = RouteLookupResult.Direct(target)
                continue
            }

            // Path reconstruction with loop & hop safeguard (T-ROUTE-08)
            val path = mutableListOf<Long>()
            val visited = mutableSetOf<Long>()
            var curr: Long? = target
            var hasLoop = false

            while (curr != null) {
                if (!visited.add(curr)) {
                    hasLoop = true
                    break
                }
                path.add(0, curr)
                curr = previous[curr]
                if (path.size > MAX_ROUTE_HOPS) {
                    hasLoop = true
                    break
                }
            }

            if (hasLoop || path.size < 2 || path.first() != localNodeId) {
                // Cycle or broken path -> unreachable
                routeCache[target] = RouteLookupResult.Unreachable
                continue
            }

            val nextHop = path[1]
            val hopCount = path.size - 1

            routeCache[target] = if (hopCount == 1) {
                RouteLookupResult.Direct(target)
            } else {
                RouteLookupResult.NextHop(
                    nextHopNodeId = nextHop,
                    hopCount = hopCount,
                    path = path
                )
            }
        }
    }

    /**
     * Resolves all currently reachable routes from the local node.
     */
    @Synchronized
    fun getAllReachableRoutes(now: Long = clock.nowMillis()): Map<Long, RouteLookupResult> {
        val targets = mutableSetOf<Long>()
        targets.addAll(directNeighbors)
        for (claim in directedClaims.values) {
            if (getEdgeState(claim.fromNode, claim.toNode, now) == EdgeState.CONFIRMED) {
                targets.add(claim.toNode)
                targets.add(claim.fromNode)
            }
        }
        targets.remove(localNodeId)

        val result = mutableMapOf<Long, RouteLookupResult>()
        for (target in targets) {
            val res = resolveRoute(target, now)
            if (res !is RouteLookupResult.Unreachable) {
                result[target] = res
            }
        }
        return result
    }

    /**
     * Count of currently tracked directed claims.
     */
    fun getDirectedClaimsCount(): Int = directedClaims.size

    /**
     * Count of tracked origins.
     */
    fun getTrackedOriginsCount(): Int = originNeighborSets.size
}

