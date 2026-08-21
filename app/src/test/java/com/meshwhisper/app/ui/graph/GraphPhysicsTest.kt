package com.meshwhisper.app.ui.graph

import com.google.common.truth.Truth.assertThat
import org.junit.Test

class GraphPhysicsTest {

    @Test
    fun testPhysicsSimulationConvergence() {
        val sim = GraphPhysicsSimulation()

        val selfNode = GraphNode(id = 1L, x = 200f, y = 200f, label = "Self", isSelf = true)
        val peerA = GraphNode(id = 2L, x = 201f, y = 201f, label = "Peer A", isSelf = false)
        val peerB = GraphNode(id = 3L, x = 199f, y = 199f, label = "Peer B", isSelf = false)

        val nodes = listOf(selfNode, peerA, peerB)
        val edges = listOf(
            GraphEdge(fromId = 1L, toId = 2L, isDirect = true),
            GraphEdge(fromId = 2L, toId = 3L, isDirect = false)
        )

        // Run 100 simulation steps
        for (i in 0 until 100) {
            sim.step(nodes, edges, width = 400f, height = 400f)
        }

        // Self node stays pinned at center
        assertThat(selfNode.x).isEqualTo(200f)
        assertThat(selfNode.y).isEqualTo(200f)

        // Peer nodes repel from center and settle at stable distance
        val distA = kotlin.math.sqrt((peerA.x - 200f) * (peerA.x - 200f) + (peerA.y - 200f) * (peerA.y - 200f))
        val distB = kotlin.math.sqrt((peerB.x - 200f) * (peerB.x - 200f) + (peerB.y - 200f) * (peerB.y - 200f))

        assertThat(distA).isGreaterThan(50f)
        assertThat(distB).isGreaterThan(50f)
        assertThat(peerA.x.isNaN()).isFalse()
        assertThat(peerB.y.isNaN()).isFalse()
    }
}
