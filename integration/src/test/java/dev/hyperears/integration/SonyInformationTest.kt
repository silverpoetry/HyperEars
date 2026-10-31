package dev.hyperears.integration
import dev.hyperears.protocol.sony.SonyHeadphonesWireCodec as Wire
import org.junit.Assert.*
import org.junit.Test
class SonyInformationTest {
    private fun b(vararg v: Int) = v.map(Int::toByte).toByteArray()
    @Test fun informationCrossesProcessTransportWithoutChangingMeaning() {
        val value = SonyInformationFeatureState(modelName = "WH-1000XM6", firmware = "1.2.3", displayFirmware = "2.0.0", color = 3, codec = 0x10, upscalingGeneration = 2, upscalingStatus = 2)
        val restored = FeatureStateTransport.decode(FeatureStateTransport.encode(DeviceFeatureSnapshot(listOf(value))))
        assertEquals(value, restored?.get<SonyInformationFeatureState>())
    }
    @Test fun artworkRequiresReportedModelAndKnownColor() {
        assertNull(SonyInformationFeatureState(modelName = "WH-1000XM6").artwork())
        assertNull(SonyInformationFeatureState(color = 3).artwork())
        assertNull(SonyInformationFeatureState(modelName = "WH-1000XM5", color = 3).artwork())
        assertNull(SonyInformationFeatureState(modelName = "WH-1000XM6", color = 0x25).artwork())
        assertEquals("headset_sony_wh1000xm6_03", SonyInformationFeatureState(modelName = "WH-1000XM6", color = 0x13).artwork()?.headset?.value)
    }
    @Test fun onlyWhXm6AdmitsInformationAndResetClearsIt() {
        for (name in listOf("WH-1000XM6", "WH-1000XM4")) {
            val adapter = requireNotNull(EarbudAdapterRegistry.resolve(EarbudIdentity(deviceName = name, standardHeadset = true)))
            adapter.beginHandshake()
            fun report(table: Wire.MessageType, payload: ByteArray) = adapter.receive(Wire.encode(table, 0, payload))
            report(Wire.MessageType.COMMAND_1, b(1, 0, 3, 0, 0, 0, 0, 0))
            val body = b(5, 2, 3, 49, 46, 50)
            report(Wire.MessageType.COMMAND_2, body)
            assertNull(adapter.runtimeState().features.get<SonyInformationFeatureState>())
            report(Wire.MessageType.COMMAND_1, body)
            val information = adapter.runtimeState().features.get<SonyInformationFeatureState>()
            if (name == "WH-1000XM6") {
                assertEquals("1.2", information?.firmware)
                report(Wire.MessageType.COMMAND_1, b(5, 3, 0x30, 3))
                assertEquals(HeadsetArtwork.HEADPHONES, adapter.artwork)
                report(Wire.MessageType.COMMAND_1, b(5, 1, 11) + "WH-1000XM6".encodeToByteArray())
                assertEquals(HeadsetArtwork.HEADPHONES, adapter.artwork) // malformed declared length
                report(Wire.MessageType.COMMAND_1, b(5, 1, 10) + "WH-1000XM6".encodeToByteArray())
                assertEquals("headset_sony_wh1000xm6_03", adapter.artwork.headset.value)
                report(Wire.MessageType.COMMAND_1, b(0x13, 2, 0x10))
                assertEquals(0x10, adapter.runtimeState().features.get<SonyInformationFeatureState>()?.codec)
                assertEquals("1.2", adapter.runtimeState().features.get<SonyInformationFeatureState>()?.firmware)
            } else assertNull(information)
            adapter.resetProtocolSession()
            assertNull(adapter.runtimeState().features.get<SonyInformationFeatureState>())
            adapter.beginHandshake()
            report(Wire.MessageType.COMMAND_1, b(1, 0, 3, 0, 0, 0, 0, 0))
            report(Wire.MessageType.COMMAND_1, b(0x13, 2, 2))
            assertNull(adapter.runtimeState().features.get<SonyInformationFeatureState>()?.firmware)
            assertNull(adapter.runtimeState().features.get<SonyInformationFeatureState>()?.color)
        }
    }
}
