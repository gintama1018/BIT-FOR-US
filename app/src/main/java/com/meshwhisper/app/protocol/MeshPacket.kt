package com.meshwhisper.app.protocol

/**
 * Typealiases bridging the app module to the shared :core protocol module.
 * Preserves 100% source-compatibility across existing Android components and tests.
 */
typealias PacketType = com.meshwhisper.core.protocol.PacketType
typealias MeshPacket = com.meshwhisper.core.protocol.MeshPacket
