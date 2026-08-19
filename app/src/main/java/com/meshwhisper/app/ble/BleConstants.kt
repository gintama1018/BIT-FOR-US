package com.meshwhisper.app.ble

import java.util.UUID

object BleConstants {
    val MESH_SERVICE_UUID: UUID = UUID.fromString("0000B170-0000-1000-8000-00805F9B34FB")
    val WRITE_CHAR_UUID: UUID = UUID.fromString("0000B171-0000-1000-8000-00805F9B34FB")
    val NOTIFY_CHAR_UUID: UUID = UUID.fromString("0000B172-0000-1000-8000-00805F9B34FB")
    val CCCD_UUID: UUID = UUID.fromString("00002902-0000-1000-8000-00805F9B34FB")

    const val REQUESTED_MTU = 512
    const val DEFAULT_MTU = 23
    const val GATT_HEADER_SIZE = 3 // ATT opcode (1) + Attribute handle (2)

    // Frame chunking prefixes (for small MTU fragmentation)
    const val FRAME_TYPE_SINGLE: Byte = 0x00
    const val FRAME_TYPE_CHUNK: Byte = 0x01
}
