package com.meshwhisper.core.router

import com.google.common.truth.Truth.assertThat
import com.meshwhisper.core.harness.TestClock
import com.meshwhisper.core.protocol.NeighborEntry
import org.junit.Before
import org.junit.Test

/**
 * Normative regression test suite for Phase P5 Routing and Topology.
 * Specified in NEXTGEN/03_VNEXT_TESTS_AND_AGENT_RULES.md §12.1 Section E.
 */
class RouteEngineP5Test {

    private lateinit var clock: TestClock
    private val localNodeId = 0xAAAA0001L
    private val nodeB = 0xBBBB0002L
    private val nodeC = 0xCCCC0003L
    private val nodeD = 0xDDDD0004L
    private val victimNode = 0xEEEE0005L
    private val malloryNode = 0x66660006L

    @Before
    fun setUp() {
        clock = TestClock(1_000_000L)
    }

    /**
     * T-ROUTE-01 (S-4): Unilateral edge claim "I link to victim" -> edge STAGED, never routed.
     */
    @Test
    fun testTROUTE01_unilateralEdgeClaimIsStagedAndNeverRouted() {
        val engine = MeshRouteEngine(localNodeId = localNodeId, clock = clock)
        engine.updateDirectNeighbors(setOf(malloryNode))

        val now = clock.nowMillis()
        // Mallory unilaterally claims an edge to victimNode. victimNode has made NO reciprocal claim.
        engine.updateEdges(listOf(
            RouteEdge(fromNode = malloryNode, toNode = victimNode, cost = 1, lastSeen = now)
        ))

        // Assert edge is STAGED
        assertThat(engine.getEdgeState(malloryNode, victimNode, now)).isEqualTo(EdgeState.STAGED)

        // Route lookup for victimNode MUST be Unreachable (unilateral edge is not routable)
        val route = engine.resolveRoute(victimNode, now)
        assertThat(route).isEqualTo(RouteLookupResult.Unreachable)
    }

    /**
     * T-ROUTE-02: Reciprocity: both endpoints assert within 90 s -> CONFIRMED, routable.
     */
    @Test
    fun testTROUTE02_reciprocalAssertionsWithin90sBecomeConfirmedAndRoutable() {
        val engine = MeshRouteEngine(localNodeId = localNodeId, clock = clock)
        engine.updateDirectNeighbors(setOf(nodeB))

        val now = clock.nowMillis()
        // Both nodeB and nodeC assert each other within 90s
        engine.updateEdges(listOf(
            RouteEdge(fromNode = nodeB, toNode = nodeC, cost = 1, lastSeen = now),
            RouteEdge(fromNode = nodeC, toNode = nodeB, cost = 1, lastSeen = now + 10_000L)
        ))

        val checkTime = now + 20_000L
        assertThat(engine.getEdgeState(nodeB, nodeC, checkTime)).isEqualTo(EdgeState.CONFIRMED)

        // Route resolution to nodeC MUST succeed via nodeB
        val route = engine.resolveRoute(nodeC, checkTime)
        assertThat(route).isInstanceOf(RouteLookupResult.NextHop::class.java)
        val nextHop = route as RouteLookupResult.NextHop
        assertThat(nextHop.nextHopNodeId).isEqualTo(nodeB)
        assertThat(nextHop.hopCount).isEqualTo(2)
        assertThat(nextHop.path).containsExactly(localNodeId, nodeB, nodeC).inOrder()
    }

    /**
     * T-ROUTE-03: Expiry: one side goes stale -> back to STAGED, evicted at 180 s.
     */
    @Test
    fun testTROUTE03_staleAssertionRevertsToStagedAndEvictsAt180s() {
        val engine = MeshRouteEngine(localNodeId = localNodeId, clock = clock)
        engine.updateDirectNeighbors(setOf(nodeB))

        val t0 = clock.nowMillis()
        engine.updateEdges(listOf(
            RouteEdge(fromNode = nodeB, toNode = nodeC, cost = 1, lastSeen = t0),
            RouteEdge(fromNode = nodeC, toNode = nodeB, cost = 1, lastSeen = t0)
        ))

        // At t0: CONFIRMED
        assertThat(engine.getEdgeState(nodeB, nodeC, t0)).isEqualTo(EdgeState.CONFIRMED)

        // Advance clock by 95 seconds: nodeB's claim is now 95s old (> 90s EDGE_FRESHNESS_MS)
        // Refresh only nodeC -> nodeB
        val t1 = t0 + 95_000L
        clock.advanceSeconds(95L)
        engine.updateEdges(listOf(
            RouteEdge(fromNode = nodeC, toNode = nodeB, cost = 1, lastSeen = t1)
        ))

        // One side is stale (>90s) -> Edge drops back to STAGED and is not routable
        assertThat(engine.getEdgeState(nodeB, nodeC, t1)).isEqualTo(EdgeState.STAGED)
        assertThat(engine.resolveRoute(nodeC, t1)).isEqualTo(RouteLookupResult.Unreachable)

        // Advance clock past 180s without refresh from nodeB
        val t2 = t0 + 190_000L
        clock.advanceSeconds(95L)
        engine.pruneStaleEntries(t2)

        // nodeB -> nodeC (190s old) must be completely evicted
        assertThat(engine.getDirectedClaimsCount()).isEqualTo(1) // only nodeC->nodeB survives (was refreshed at t1)
    }

    /**
     * T-ROUTE-04: Poisoning: 512 origins x 16 edges flood -> origin map capped at 512, LRU.
     */
    @Test
    fun testTROUTE04_topologyFloodOriginMapCappedAt512LRU() {
        val engine = MeshRouteEngine(localNodeId = localNodeId, clock = clock)

        // Inject 700 origins, each advertising 16 neighbors
        for (i in 1..700) {
            val originId = 0x10000000L + i
            val neighbors = (1..16).map { neighborIdx ->
                NeighborEntry(
                    nodeId64 = 0x20000000L + neighborIdx,
                    linkQuality = 80.toByte()
                )
            }
            engine.updateOriginNeighbors(originId, neighbors, clock.nowMillis())
            clock.advanceSeconds(1L)
        }

        // Must be capped at exactly MAX_TOPOLOGY_ORIGINS = 512
        assertThat(engine.getTrackedOriginsCount()).isAtMost(MeshRouteEngine.MAX_TOPOLOGY_ORIGINS)
        assertThat(engine.getTrackedOriginsCount()).isEqualTo(512)
    }

    /**
     * T-ROUTE-05: Greyhole: authenticated relay drops 100% over 20 messages -> cost escalates, alternate route selected.
     */
    @Test
    fun testTROUTE05_greyholeRelayCostEscalatesAndSelectsAlternateRoute() {
        val engine = MeshRouteEngine(localNodeId = localNodeId, clock = clock)
        val now = clock.nowMillis()

        // Two paths to destination nodeD:
        // Path 1: localNodeId -> nodeB -> nodeD (initial base cost: 1 + 1 = 2)
        // Path 2: localNodeId -> nodeC -> nodeD (initial base cost: 1 + 3 = 4)
        engine.updateDirectNeighbors(setOf(nodeB, nodeC))

        engine.updateEdges(listOf(
            RouteEdge(fromNode = nodeB, toNode = nodeD, cost = 1, lastSeen = now),
            RouteEdge(fromNode = nodeD, toNode = nodeB, cost = 1, lastSeen = now),
            RouteEdge(fromNode = nodeC, toNode = nodeD, cost = 3, lastSeen = now),
            RouteEdge(fromNode = nodeD, toNode = nodeC, cost = 3, lastSeen = now)
        ))

        // Initial route must prefer Path 1 (lower cost via nodeB)
        val initialRoute = engine.resolveRoute(nodeD, now)
        assertThat(initialRoute).isInstanceOf(RouteLookupResult.NextHop::class.java)
        assertThat((initialRoute as RouteLookupResult.NextHop).nextHopNodeId).isEqualTo(nodeB)

        // Relay nodeB acts as a greyhole, dropping 100% over 20 attempted messages
        for (i in 1..20) {
            engine.recordDrop(localNodeId, nodeB)
        }

        // Route resolution MUST escalate cost for nodeB and fail over to alternate route via nodeC
        val alternateRoute = engine.resolveRoute(nodeD, now)
        assertThat(alternateRoute).isInstanceOf(RouteLookupResult.NextHop::class.java)
        val altHop = alternateRoute as RouteLookupResult.NextHop
        assertThat(altHop.nextHopNodeId).isEqualTo(nodeC)
        assertThat(altHop.path).containsExactly(localNodeId, nodeC, nodeD).inOrder()
    }

    /**
     * T-ROUTE-06 (S-21): Prune 30 s timer -> stale topology edges disappear.
     */
    @Test
    fun testTROUTE06_pruneStaleEntriesCleansEvictedEdges() {
        val engine = MeshRouteEngine(localNodeId = localNodeId, clock = clock)
        val now = clock.nowMillis()

        // Add edge fresh, and another edge that is already 185s old (> 180s EDGE_EVICT_MS)
        engine.updateEdges(listOf(
            RouteEdge(fromNode = nodeB, toNode = nodeC, cost = 1, lastSeen = now),
            RouteEdge(fromNode = nodeC, toNode = nodeD, cost = 1, lastSeen = now - 185_000L)
        ))

        assertThat(engine.getDirectedClaimsCount()).isEqualTo(2)

        // Run 30s prune
        engine.pruneStaleEntries(now)

        // The edge older than 180s must be completely removed
        assertThat(engine.getDirectedClaimsCount()).isEqualTo(1)
    }

    /**
     * T-ROUTE-07: Route cache: 10 000 packets through a 512-node graph ->
     * Dijkstra runs <= number of topology change events, not per packet.
     */
    @Test
    fun testTROUTE07_routeCachePreventsDijkstraExecutionPerPacket() {
        val engine = MeshRouteEngine(localNodeId = localNodeId, clock = clock)
        val now = clock.nowMillis()

        engine.updateDirectNeighbors(setOf(nodeB))
        engine.updateEdges(listOf(
            RouteEdge(fromNode = nodeB, toNode = nodeC, cost = 1, lastSeen = now),
            RouteEdge(fromNode = nodeC, toNode = nodeB, cost = 1, lastSeen = now),
            RouteEdge(fromNode = nodeC, toNode = nodeD, cost = 1, lastSeen = now),
            RouteEdge(fromNode = nodeD, toNode = nodeC, cost = 1, lastSeen = now)
        ))

        // Initial state before lookups: Dijkstra has not run yet
        assertThat(engine.dijkstraExecutionCount).isEqualTo(0)

        // Forward 10,000 packets towards nodeD with NO topology changes
        for (i in 1..10_000) {
            val route = engine.resolveRoute(nodeD, now)
            assertThat((route as RouteLookupResult.NextHop).nextHopNodeId).isEqualTo(nodeB)
        }

        // Dijkstra MUST run at most once (for the initial cache population), NOT 10,000 times!
        assertThat(engine.dijkstraExecutionCount).isEqualTo(1)
        assertThat(engine.dijkstraExecutionCount).isAtMost(1)
    }

    /**
     * T-ROUTE-08: Cyclic topology -> path acyclic, <= 50 hops, terminates.
     */
    @Test
    fun testTROUTE08_cyclicTopologyProducesAcyclicPathUnder50Hops() {
        val engine = MeshRouteEngine(localNodeId = localNodeId, clock = clock)
        val now = clock.nowMillis()

        // Create cyclic graph: local -> B -> C -> D -> B (cycle) and D -> victimNode
        engine.updateDirectNeighbors(setOf(nodeB))
        engine.updateEdges(listOf(
            RouteEdge(fromNode = nodeB, toNode = nodeC, cost = 1, lastSeen = now),
            RouteEdge(fromNode = nodeC, toNode = nodeB, cost = 1, lastSeen = now),

            RouteEdge(fromNode = nodeC, toNode = nodeD, cost = 1, lastSeen = now),
            RouteEdge(fromNode = nodeD, toNode = nodeC, cost = 1, lastSeen = now),

            RouteEdge(fromNode = nodeD, toNode = nodeB, cost = 1, lastSeen = now),
            RouteEdge(fromNode = nodeB, toNode = nodeD, cost = 1, lastSeen = now),

            RouteEdge(fromNode = nodeD, toNode = victimNode, cost = 1, lastSeen = now),
            RouteEdge(fromNode = victimNode, toNode = nodeD, cost = 1, lastSeen = now)
        ))

        val route = engine.resolveRoute(victimNode, now)
        assertThat(route).isInstanceOf(RouteLookupResult.NextHop::class.java)

        val nextHop = route as RouteLookupResult.NextHop
        assertThat(nextHop.path.size).isAtMost(MeshRouteEngine.MAX_ROUTE_HOPS)
        // Path must have no duplicate nodes (acyclic)
        val uniqueNodes = nextHop.path.toSet()
        assertThat(uniqueNodes.size).isEqualTo(nextHop.path.size)
        assertThat(nextHop.path).containsExactly(localNodeId, nodeB, nodeD, victimNode).inOrder()
    }
}
