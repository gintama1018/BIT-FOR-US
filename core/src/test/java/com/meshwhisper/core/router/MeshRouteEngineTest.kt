package com.meshwhisper.core.router

import com.google.common.truth.Truth.assertThat
import org.junit.Test

class MeshRouteEngineTest {

    private val nodeA = 0xAAAA0001L
    private val nodeB = 0xBBBB0002L
    private val nodeC = 0xCCCC0003L
    private val nodeD = 0xDDDD0004L
    private val nodeE = 0xEEEE0005L

    @Test
    fun testDirectNeighborResolution() {
        val engine = MeshRouteEngine(localNodeId = nodeA)
        engine.updateDirectNeighbors(setOf(nodeB, nodeC))

        val resultB = engine.resolveRoute(nodeB)
        assertThat(resultB).isInstanceOf(RouteLookupResult.Direct::class.java)
        assertThat((resultB as RouteLookupResult.Direct).targetNodeId).isEqualTo(nodeB)

        val resultC = engine.resolveRoute(nodeC)
        assertThat(resultC).isInstanceOf(RouteLookupResult.Direct::class.java)
        assertThat((resultC as RouteLookupResult.Direct).targetNodeId).isEqualTo(nodeC)

        // Node D is not direct and no edges exist
        val resultD = engine.resolveRoute(nodeD)
        assertThat(resultD).isEqualTo(RouteLookupResult.Unreachable)
    }

    @Test
    fun testTwoHopLinearPathAToBToC() {
        // A is directly connected to B. B has an edge to C.
        val engine = MeshRouteEngine(localNodeId = nodeA)
        engine.updateDirectNeighbors(setOf(nodeB))
        engine.updateEdges(listOf(
            RouteEdge(fromNode = nodeB, toNode = nodeC, cost = 1, lastSeen = System.currentTimeMillis())
        ))

        val routeToC = engine.resolveRoute(nodeC)
        assertThat(routeToC).isInstanceOf(RouteLookupResult.NextHop::class.java)

        val nextHop = routeToC as RouteLookupResult.NextHop
        assertThat(nextHop.nextHopNodeId).isEqualTo(nodeB)
        assertThat(nextHop.hopCount).isEqualTo(2)
        assertThat(nextHop.path).containsExactly(nodeA, nodeB, nodeC).inOrder()
    }

    @Test
    fun testMultiHopPathAToBToCToD() {
        // Linear chain: A -> B -> C -> D
        val engine = MeshRouteEngine(localNodeId = nodeA)
        val now = System.currentTimeMillis()
        engine.updateDirectNeighbors(setOf(nodeB))
        engine.updateEdges(listOf(
            RouteEdge(fromNode = nodeB, toNode = nodeC, cost = 1, lastSeen = now),
            RouteEdge(fromNode = nodeC, toNode = nodeD, cost = 1, lastSeen = now)
        ))

        val routeToD = engine.resolveRoute(nodeD)
        assertThat(routeToD).isInstanceOf(RouteLookupResult.NextHop::class.java)

        val nextHop = routeToD as RouteLookupResult.NextHop
        assertThat(nextHop.nextHopNodeId).isEqualTo(nodeB)
        assertThat(nextHop.hopCount).isEqualTo(3)
        assertThat(nextHop.path).containsExactly(nodeA, nodeB, nodeC, nodeD).inOrder()
    }

    @Test
    fun testDiamondTopologyShortestPathSelection() {
        // A connects to B and C.
        // Path 1: A -> B -> D (cost: 1 + 1 = 2 hops)
        // Path 2: A -> C -> E -> D (cost: 1 + 1 + 1 = 3 hops)
        val engine = MeshRouteEngine(localNodeId = nodeA)
        val now = System.currentTimeMillis()
        engine.updateDirectNeighbors(setOf(nodeB, nodeC))
        engine.updateEdges(listOf(
            RouteEdge(fromNode = nodeB, toNode = nodeD, cost = 1, lastSeen = now),
            RouteEdge(fromNode = nodeC, toNode = nodeE, cost = 1, lastSeen = now),
            RouteEdge(fromNode = nodeE, toNode = nodeD, cost = 1, lastSeen = now)
        ))

        val routeToD = engine.resolveRoute(nodeD)
        assertThat(routeToD).isInstanceOf(RouteLookupResult.NextHop::class.java)

        val nextHop = routeToD as RouteLookupResult.NextHop
        // Shortest path must be chosen: A -> B -> D
        assertThat(nextHop.nextHopNodeId).isEqualTo(nodeB)
        assertThat(nextHop.hopCount).isEqualTo(2)
        assertThat(nextHop.path).containsExactly(nodeA, nodeB, nodeD).inOrder()
    }

    @Test
    fun testLinkFailureAndAutomaticFailover() {
        // A connects to B and C. Both can reach D.
        // A -> B -> D (2 hops)
        // A -> C -> D (2 hops)
        val engine = MeshRouteEngine(localNodeId = nodeA)
        val now = System.currentTimeMillis()
        engine.updateDirectNeighbors(setOf(nodeB, nodeC))
        engine.updateEdges(listOf(
            RouteEdge(fromNode = nodeB, toNode = nodeD, cost = 1, lastSeen = now),
            RouteEdge(fromNode = nodeC, toNode = nodeD, cost = 2, lastSeen = now) // slightly higher cost initially
        ))

        // Initial resolution chooses B (lower cost path: cost 2 vs 3)
        val initialRoute = engine.resolveRoute(nodeD, now)
        assertThat((initialRoute as RouteLookupResult.NextHop).nextHopNodeId).isEqualTo(nodeB)

        // Transmission to B fails! Mark link A -> B failed.
        engine.markLinkFailed(nodeA, nodeB, penaltyDurationMs = 60_000L)

        // New route resolution MUST failover to C
        val failoverRoute = engine.resolveRoute(nodeD, now + 100)
        assertThat(failoverRoute).isInstanceOf(RouteLookupResult.NextHop::class.java)
        val failoverNextHop = failoverRoute as RouteLookupResult.NextHop
        assertThat(failoverNextHop.nextHopNodeId).isEqualTo(nodeC)
        assertThat(failoverNextHop.path).containsExactly(nodeA, nodeC, nodeD).inOrder()

        // After penalty expires, B can be used again
        val recoveredRoute = engine.resolveRoute(nodeD, now + 61_000L)
        assertThat((recoveredRoute as RouteLookupResult.NextHop).nextHopNodeId).isEqualTo(nodeB)
    }

    @Test
    fun testStaleEdgeExpirationFilter() {
        val engine = MeshRouteEngine(localNodeId = nodeA, maxEdgeAgeMs = 120_000L)
        val now = System.currentTimeMillis()
        engine.updateDirectNeighbors(setOf(nodeB))

        // Edge reported 130 seconds ago (expired)
        engine.updateEdges(listOf(
            RouteEdge(fromNode = nodeB, toNode = nodeC, cost = 1, lastSeen = now - 130_000L)
        ))

        val routeToC = engine.resolveRoute(nodeC, now)
        // Expired edge must be ignored -> Unreachable
        assertThat(routeToC).isEqualTo(RouteLookupResult.Unreachable)

        // Fresh edge update arrives
        engine.updateEdges(listOf(
            RouteEdge(fromNode = nodeB, toNode = nodeC, cost = 1, lastSeen = now - 10_000L)
        ))
        val freshRouteToC = engine.resolveRoute(nodeC, now)
        assertThat(freshRouteToC).isInstanceOf(RouteLookupResult.NextHop::class.java)
        assertThat((freshRouteToC as RouteLookupResult.NextHop).nextHopNodeId).isEqualTo(nodeB)
    }

    @Test
    fun testLoopFreeGuaranteeInCyclicGraph() {
        // Graph with cycle: A -> B -> C -> D -> B
        val engine = MeshRouteEngine(localNodeId = nodeA)
        val now = System.currentTimeMillis()
        engine.updateDirectNeighbors(setOf(nodeB))
        engine.updateEdges(listOf(
            RouteEdge(fromNode = nodeB, toNode = nodeC, cost = 1, lastSeen = now),
            RouteEdge(fromNode = nodeC, toNode = nodeD, cost = 1, lastSeen = now),
            RouteEdge(fromNode = nodeD, toNode = nodeB, cost = 1, lastSeen = now), // Cycle back to B
            RouteEdge(fromNode = nodeD, toNode = nodeE, cost = 1, lastSeen = now)
        ))

        val routeToE = engine.resolveRoute(nodeE, now)
        assertThat(routeToE).isInstanceOf(RouteLookupResult.NextHop::class.java)

        val nextHop = routeToE as RouteLookupResult.NextHop
        assertThat(nextHop.nextHopNodeId).isEqualTo(nodeB)
        // Path must not contain repeating cycles
        assertThat(nextHop.path).containsExactly(nodeA, nodeB, nodeC, nodeD, nodeE).inOrder()
    }

    @Test
    fun testGetAllReachableRoutes() {
        val engine = MeshRouteEngine(localNodeId = nodeA)
        val now = System.currentTimeMillis()
        engine.updateDirectNeighbors(setOf(nodeB))
        engine.updateEdges(listOf(
            RouteEdge(fromNode = nodeB, toNode = nodeC, cost = 1, lastSeen = now)
        ))

        val allRoutes = engine.getAllReachableRoutes(now)
        assertThat(allRoutes.keys).containsExactly(nodeB, nodeC)
        assertThat(allRoutes[nodeB]).isInstanceOf(RouteLookupResult.Direct::class.java)
        assertThat(allRoutes[nodeC]).isInstanceOf(RouteLookupResult.NextHop::class.java)
    }
}
