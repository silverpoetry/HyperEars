package dev.hyperears.integration

/** Bytes and an optional logical route within one connected transport; no Android handles. */
data class ProtocolPacket(val bytes: ByteArray, val route: String? = null)

/** A declared extra write/notify pair in the transport's service. Null remains the primary route. */
data class GattRouteSpec(
    val id: String,
    val writeCharacteristicUuid: String,
    val notifyCharacteristicUuid: String,
    val writeMode: GattWriteMode = GattWriteMode.WITH_RESPONSE,
) {
    init {
        require(id.isNotBlank())
        require(writeCharacteristicUuid.isNotBlank() && notifyCharacteristicUuid.isNotBlank())
    }
}
