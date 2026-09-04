package com.meshwhisper.app.protocol

import com.meshwhisper.core.protocol.trafficPriority as coreTrafficPriority

/**
 * Typealiases bridging the app module to the shared :core protocol module.
 * Preserves 100% source-compatibility across existing Android components and tests.
 */
typealias PacketType = com.meshwhisper.core.protocol.PacketType
typealias MeshPacket = com.meshwhisper.core.protocol.MeshPacket
typealias TrafficPriority = com.meshwhisper.core.protocol.TrafficPriority
typealias PrioritizedPacket = com.meshwhisper.core.protocol.PrioritizedPacket
typealias MeshTrafficController = com.meshwhisper.core.protocol.MeshTrafficController

val PacketType.trafficPriority: TrafficPriority
    get() = this.coreTrafficPriority
