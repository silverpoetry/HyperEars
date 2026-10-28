package dev.hyperears.integration

import dev.hyperears.protocol.qcy.QcyWireCodec
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class QcyEarbudAdapterTest {
    @Test
    fun registrySelectsExactC50sBeforeTheQcyProtocolFamily() {
        val exact = resolve("QCY Crossky C50S")
        val family = resolve("QCY HT07")

        assertTrue(exact is QcyCrosskyC50sAdapter)
        assertEquals(AdapterResolution.EXACT_MATCH, exact.resolution)
        assertTrue(family is QcyStandardGattAdapter)
    }

    @Test
    fun nativeSystemDeviceNeverEntersTheQcyChain() {
        val adapter = EarbudAdapterRegistry.resolve(
            EarbudIdentity(
                deviceName = "QCY C50S",
                standardHeadset = true,
                nativeSystemEarbud = true,
            ),
        )

        assertEquals(null, adapter)
    }

    @Test
    fun privateCapabilitiesStayClosedUntilTheirOwnReadResponses() {
        val adapter = QcyStandardGattAdapter()

        assertEquals(BatterySource.SYSTEM_AGGREGATE, adapter.snapshot().batterySource)
        assertTrue(adapter.snapshot().capabilities.battery)
        assertFalse(adapter.snapshot().capabilities.noiseControl)
        assertEquals(
            (listOf(QcyWireCodec.queryBattery, QcyWireCodec.queryNoiseMode) + QcyWireCodec.advancedQueries).map(ByteArray::toList),
            adapter.beginHandshake().commands.map(ByteArray::toList),
        )

        val battery = adapter.receive(hex("FF 05 2F 03 D7 4C 40"))
        assertEquals(HandshakeResult.Ready, battery.handshake)
        assertEquals(BatterySource.PRIVATE_PROTOCOL, adapter.snapshot().batterySource)
        assertEquals(87, adapter.runtimeState().battery.left.percent)
        assertEquals(76, adapter.runtimeState().battery.right.percent)
        assertEquals(64, adapter.runtimeState().battery.case.percent)
        assertFalse(adapter.snapshot().capabilities.noiseControl)

        adapter.receive(hex("FF 03 0C 01 03"))
        assertTrue(adapter.snapshot().capabilities.noiseControl)
        assertEquals(
            setOf(NoiseMode.ANC, NoiseMode.OFF, NoiseMode.TRANSPARENCY),
            adapter.snapshot().supportedNoiseModes,
        )
        assertEquals(NoiseMode.TRANSPARENCY, adapter.runtimeState().noiseMode)
    }

    @Test
    fun outdoorReportProjectsToAncWithoutAddingAFourthControl() {
        val adapter = QcyStandardGattAdapter()

        adapter.receive(hex("FF 03 0C 01 02"))

        assertEquals(NoiseMode.ANC, adapter.runtimeState().noiseMode)
        assertEquals(
            setOf(NoiseMode.ANC, NoiseMode.OFF, NoiseMode.TRANSPARENCY),
            adapter.snapshot().supportedNoiseModes,
        )
    }

    @Test
    fun standardNoiseControlWritesOneCommandAndRequestsAuthoritativeReadback() {
        val adapter = QcyStandardGattAdapter()
        adapter.receive(hex("FF 03 0C 01 00"))

        val result = adapter.executeControl(
            StandardControlRequest.SetNoiseMode(NoiseMode.TRANSPARENCY),
        )

        assertTrue(result.accepted)
        assertFalse(result.stateChanged)
        assertEquals(
            listOf(QcyWireCodec.setNoiseMode(QcyWireCodec.NoiseMode.TRANSPARENCY).toList()),
            result.commands.map(ByteArray::toList),
        )
        assertEquals(
            listOf(QcyWireCodec.queryNoiseMode.toList()),
            result.readback.map(ByteArray::toList),
        )
        assertEquals(NoiseMode.OFF, adapter.runtimeState().noiseMode)
    }

    @Test
    fun failedPublicProtocolProbeFallsBackDirectlyToStandardIntegration() {
        val resolution = QcyStandardGattAdapter().resolveInitialProtocolFailure()

        assertTrue(resolution is InitialProtocolFailureResolution.FallbackTo)
        val fallback = (resolution as InitialProtocolFailureResolution.FallbackTo).adapter
        assertEquals(StandardEarbudAdapter.ID, fallback.id)
        assertFalse(fallback.privateProtocolRequired)
    }

    @Test
    fun companionMatcherRequiresAnExactNameOrAddressAssociation() {
        val session = GattPeerIdentity(
            deviceName = "QYCC50S",
            deviceAddress = "AA:BB:CC:DD:EE:FF",
        )
        val bondedCompanion = GattPeerIdentity(
            deviceName = "QYCC50S-APP",
            deviceAddress = "00:11:22:33:44:55",
        )
        val addressLinkedAdvertisement = GattPeerIdentity(
            deviceName = null,
            deviceAddress = "00:11:22:33:44:66",
            manufacturerData = mapOf(
                QcyStandardGattAdapter.QCY_MANUFACTURER_ID to qcyAdvertisement(
                    controlAddress = hex("AA BB CC DD EE FF"),
                ),
            ),
        )
        val unrelatedAdvertisement = GattPeerIdentity(
            deviceName = "Nearby device",
            deviceAddress = "00:11:22:33:44:77",
            manufacturerData = mapOf(
                QcyStandardGattAdapter.QCY_MANUFACTURER_ID to qcyAdvertisement(
                    controlAddress = hex("10 20 30 40 50 60"),
                ),
            ),
        )

        assertTrue(QcyGattPeerMatcher.matches(session, bondedCompanion))
        assertTrue(QcyGattPeerMatcher.matches(session, addressLinkedAdvertisement))
        assertFalse(QcyGattPeerMatcher.matches(session, unrelatedAdvertisement))
    }

    private fun resolve(name: String): EarbudAdapter = requireNotNull(
        EarbudAdapterRegistry.resolve(
            EarbudIdentity(
                deviceName = name,
                standardHeadset = true,
            ),
        ),
    )

    private fun qcyAdvertisement(controlAddress: ByteArray): ByteArray = ByteArray(24).apply {
        this[0] = 0x4D
        this[1] = 0x55
        this[12] = controlAddress[0]
        this[11] = controlAddress[1]
        this[13] = controlAddress[2]
        this[16] = controlAddress[3]
        this[15] = controlAddress[4]
        this[14] = controlAddress[5]
    }

    private fun hex(value: String): ByteArray = value
        .trim()
        .split(Regex("\\s+"))
        .map { it.toInt(16).toByte() }
        .toByteArray()
}
