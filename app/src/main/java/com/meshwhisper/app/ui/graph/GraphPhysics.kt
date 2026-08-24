package com.meshwhisper.app.ui.graph

import kotlin.math.max
import kotlin.math.min
import kotlin.math.sqrt

data class GraphNode(
    val id: Long,
    var x: Float,
    var y: Float,
    var vx: Float = 0f,
    var vy: Float = 0f,
    val label: String,
    val isSelf: Boolean
)

data class GraphEdge(
    val fromId: Long,
    val toId: Long,
    val isDirect: Boolean,
    val rssi: Int = 0
)

class GraphPhysicsSimulation(
    val repulsion: Float = 12000f,
    val directSpringLength: Float = 140f,
    val relaySpringLength: Float = 220f,
    val springStrength: Float = 0.04f,
    val centerGravity: Float = 0.02f,
    val damping: Float = 0.82f
) {

    fun step(
        nodes: List<GraphNode>,
        edges: List<GraphEdge>,
        width: Float,
        height: Float,
        dt: Float = 0.03f
    ) {
        if (nodes.isEmpty() || width <= 0f || height <= 0f) return

        val centerX = width / 2f
        val centerY = height / 2f

        // 1. Coulomb Repulsion between all node pairs
        for (i in nodes.indices) {
            val a = nodes[i]
            for (j in i + 1 until nodes.size) {
                val b = nodes[j]

                val dx = b.x - a.x
                val dy = b.y - a.y
                val distSq = dx * dx + dy * dy
                val dist = max(30f, sqrt(distSq))

                val force = repulsion / (dist * dist)
                val fx = (dx / dist) * force
                val fy = (dy / dist) * force

                if (!a.isSelf) {
                    a.vx -= fx
                    a.vy -= fy
                }
                if (!b.isSelf) {
                    b.vx += fx
                    b.vy += fy
                }
            }
        }

        // 2. Hooke's Spring Attraction along Graph Edges (O(1) node map lookup)
        val nodeMap = nodes.associateBy { it.id }
        for (edge in edges) {
            val a = nodeMap[edge.fromId] ?: continue
            val b = nodeMap[edge.toId] ?: continue

            val dx = b.x - a.x
            val dy = b.y - a.y
            val dist = max(1f, sqrt(dx * dx + dy * dy))

            val targetLength = if (edge.isDirect) directSpringLength else relaySpringLength
            val displacement = dist - targetLength
            val force = displacement * springStrength

            val fx = (dx / dist) * force
            val fy = (dy / dist) * force

            if (!a.isSelf) {
                a.vx += fx
                a.vy += fy
            }
            if (!b.isSelf) {
                b.vx -= fx
                b.vy -= fy
            }
        }

        // 3. Center Gravity & Boundary Clamping
        val padding = 40f
        for (node in nodes) {
            if (node.isSelf) {
                // Pin self node at center
                node.x = centerX
                node.y = centerY
                node.vx = 0f
                node.vy = 0f
                continue
            }

            // Pull towards center
            val dx = centerX - node.x
            val dy = centerY - node.y
            node.vx += dx * centerGravity
            node.vy += dy * centerGravity

            // Integrate velocity with damping
            node.vx *= damping
            node.vy *= damping
            node.x += node.vx
            node.y += node.vy

            // Screen boundary clamping
            node.x = max(padding, min(width - padding, node.x))
            node.y = max(padding, min(height - padding, node.y))
        }
    }
}
