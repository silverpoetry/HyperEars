package dev.hyperears.integration

import dev.hyperears.protocol.moondrop.MoondropZzzAngelsWireCodec
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * PILL adapter tests. Byte vectors are taken from the 2026-11-12 btsnoop capture of the
 * official MOONDROP application talking to a MOONDROP PILL (firmware 1.6.0) over SPP DLCI 2.
 * The capture contains 151 GAIA frames across three sessions and zero noise commands.
 */
class MoondropPillAdapterTest {
    @Test
    fun exactNameSelectsTheModel() {
        val adapter = requireNotNull(
            EarbudAdapterRegistry.resolve(
                EarbudIdentity(
                    deviceName = "MOONDROP PILL",
                    standardHeadset = true,
                ),
            ),
        )
        assertTrue(adapter is MoondropPillAdapter)
        assertEquals(MoondropPillAdapter.ID, adapter.id)
        assertEquals(AdapterResolution.EXACT_MATCH, adapter.resolution)
    }

    @Test
    fun brandFamilyStillClaimsOtherMoondropNames() {
        val adapter = requireNotNull(
            EarbudAdapterRegistry.resolve(
                EarbudIdentity(
                    deviceName = "MOONDROP Something",
                    standardHeadset = true,
                ),
            ),
        )
        assertTrue(adapter is MoondropEarbudAdapter)
        assertFalse(adapter is MoondropPillAdapter)
    }

    /** The collaboration edition must keep resolving to its own revalidated adapter. */
    @Test
    fun zzzAngelsIdentityIsNotClaimedByPill() {
        for (name in listOf("ZZZ-ANGELS-OWS", "MOONDROP ZZZ-ANGELS-OWS")) {
            val adapter = requireNotNull(
                EarbudAdapterRegistry.resolve(
                    EarbudIdentity(deviceName = name, standardHeadset = true),
                ),
            )
            assertTrue(adapter is MoondropZzzAngelsAdapter)
            assertFalse(adapter is MoondropPillAdapter)
        }
    }

    @Test
    fun matchingRejectsNativeSystemEarbudsAndNonHeadsets() {
        val adapter = MoondropPillAdapter()
        assertFalse(
            adapter.matches(
                EarbudIdentity(
                    deviceName = "MOONDROP PILL",
                    standardHeadset = true,
                    nativeSystemEarbud = true,
                ),
            ),
        )
        assertFalse(
            adapter.matches(
                EarbudIdentity(
                    deviceName = "MOONDROP PILL",
                    standardHeadset = false,
                ),
            ),
        )
    }

    @Test
    fun arbitraryPillNamesAndSharedSppCannotSelectTheModel() {
        val adapter = MoondropPillAdapter()
        for (name in listOf("PILL", "Pill Speaker", "MOONDROP Pillow", "Other headset")) {
            assertFalse(adapter.matches(EarbudIdentity(
                deviceName = name,
                standardHeadset = true,
                serviceUuids = setOf(MoondropPillAdapter.STANDARD_SPP_UUID),
            )))
        }
    }

    @Test
    fun declaresTheSppTransportAndLockedInitialCapabilities() {
        val adapter = MoondropPillAdapter()
        assertEquals(
            listOf(MoondropPillAdapter.STANDARD_SPP_UUID),
            adapter.transports.map { transport ->
                when (transport) {
                    is RfcommEndpointSpec.ServiceUuid -> transport.uuid
                    else -> error("unexpected transport $transport")
                }
            },
        )
        assertEquals(TransportReadiness.PROTOCOL_HANDSHAKE, adapter.transportReadiness)
        assertEquals(BatterySource.SYSTEM_AGGREGATE, adapter.effectiveBatterySource())
        assertFalse(adapter.effectiveCapabilities().noiseControl)
        assertTrue(adapter.effectiveSupportedNoiseModes().isEmpty())
        assertEquals(listOf(ControlAppCatalog.moondrop), adapter.controlApps)
    }

    @Test
    fun privateBatteryOpensOnlyAfterALegalResponse() {
        val adapter = MoondropPillAdapter()
        val handshake = adapter.beginHandshake()
        assertTrue(
            handshake.commands.single().contentEquals(MoondropZzzAngelsWireCodec.handshake),
        )
        assertEquals(HandshakeResult.AwaitingEvidence, handshake.handshake)
        assertEquals(BatterySource.SYSTEM_AGGREGATE, adapter.snapshot().batterySource)

        val accepted = adapter.receive(CAPTURED_HANDSHAKE_RESPONSE.hexBytes())
        assertEquals(HandshakeResult.Ready, accepted.handshake)
        assertEquals(
            listOf(MoondropZzzAngelsWireCodec.queryBattery.toList()),
            accepted.commands.map(ByteArray::toList),
        )
        assertEquals(BatterySource.SYSTEM_AGGREGATE, adapter.snapshot().batterySource)

        adapter.receive(CAPTURED_BATTERY_RESPONSE.hexBytes())
        assertEquals(BatterySource.PRIVATE_PROTOCOL, adapter.snapshot().batterySource)
    }

    /** Captured handshake request/response are byte-identical to the ZZZ-ANGELS-OWS family. */
    @Test
    fun capturedHandshakeMatchesTheFamilyCodec() {
        assertTrue(
            MoondropZzzAngelsWireCodec.handshake.contentEquals(
                "FF 01 00 00 00 0A 03 00".hexBytes(),
            ),
        )
        val adapter = MoondropPillAdapter()
        assertEquals(
            HandshakeResult.Ready,
            adapter.receive(CAPTURED_HANDSHAKE_RESPONSE.hexBytes()).handshake,
        )
    }

    /**
     * Captured exchange: supported components `01 02` (no charging case), then
     * `01 64 02 50` = left 100%, right 80%, matching the vendor application's display.
     */
    @Test
    fun capturedSessionFramesUnlockPrivateBattery() {
        val adapter = readyAdapter()
        val result = adapter.receive(CAPTURED_BATTERY_RESPONSE.hexBytes())
        assertTrue(result.stateChanged)
        assertEquals(BatterySource.PRIVATE_PROTOCOL, adapter.effectiveBatterySource())
        assertEquals(100, adapter.runtimeState().battery.left.percent)
        assertEquals(80, adapter.runtimeState().battery.right.percent)
        assertNull(adapter.runtimeState().battery.case.percent)
    }

    /** The supported-component response (`1D 1B 00`) is not a battery reading. */
    @Test
    fun supportedComponentResponseDoesNotOpenBattery() {
        val adapter = readyAdapter()
        val result = adapter.receive(CAPTURED_COMPONENT_RESPONSE.hexBytes())
        assertFalse(result.stateChanged)
        assertEquals(BatterySource.SYSTEM_AGGREGATE, adapter.effectiveBatterySource())
    }

    /** The firmware-version response (`1D 01 05` = "1.6.0") carries no battery state. */
    @Test
    fun firmwareVersionFrameIsIgnored() {
        val adapter = readyAdapter()
        val result = adapter.receive(CAPTURED_FIRMWARE_RESPONSE.hexBytes())
        assertFalse(result.stateChanged)
        assertEquals(BatterySource.SYSTEM_AGGREGATE, adapter.effectiveBatterySource())
    }

    @Test
    fun noiseControlNeverOpensOnThisModel() {
        val adapter = readyAdapter()
        adapter.receive(CAPTURED_BATTERY_RESPONSE.hexBytes())

        // No noise cancellation exists; a family noise frame must not open any control.
        val familyModeFrame = MoondropZzzAngelsWireCodec.frame(
            command = 0x1D,
            subcommand = 0x41,
            opcode = 0x03,
            parameters = byteArrayOf(0x01),
        )
        val result = adapter.receive(familyModeFrame)
        assertFalse(result.stateChanged)
        assertFalse(adapter.snapshot().capabilities.noiseControl)
        assertTrue(adapter.effectiveSupportedNoiseModes().isEmpty())

        val control = adapter.executeControl(
            StandardControlRequest.SetNoiseMode(NoiseMode.ANC),
        )
        assertFalse(control.accepted)
    }

    @Test
    fun handshakeRejectsErrorStatusAndAcceptsOtherLegalVersions() {
        val tolerant = MoondropPillAdapter()
        val otherVersion = tolerant.receive(
            MoondropZzzAngelsWireCodec.frame(
                command = 0x0A,
                subcommand = 0x83,
                opcode = 0x00,
                parameters = byteArrayOf(0, 3, 2, 5),
            ),
        )
        assertEquals(HandshakeResult.Ready, otherVersion.handshake)

        val rejecting = MoondropPillAdapter()
        val failed = rejecting.receive(
            MoondropZzzAngelsWireCodec.frame(
                command = 0x0A,
                subcommand = 0x83,
                opcode = 0x00,
                parameters = byteArrayOf(2, 4, 3, 1),
            ),
        )
        assertEquals(HandshakeResult.Rejected, failed.handshake)
    }

    @Test
    fun batteryReportsMergeComponentWise() {
        val adapter = readyAdapter()
        adapter.receive(batteryFrame(91, 76))
        adapter.receive(MoondropZzzAngelsWireCodec.frame(
            command = 0x1D, subcommand = 0x1B, opcode = 0x01,
            parameters = byteArrayOf(2, 70),
        ))
        assertEquals(91, adapter.runtimeState().battery.left.percent)
        assertEquals(70, adapter.runtimeState().battery.right.percent)
        assertNull(adapter.runtimeState().battery.case.percent)
    }

    /**
     * PILL reports two components, but the shared codec keeps the family's `03` case
     * tolerance. A case reading is only shown if the device actually reports one.
     */
    @Test
    fun familyCaseComponentIsAcceptedOnlyWhenReported() {
        val adapter = readyAdapter()
        adapter.receive(MoondropZzzAngelsWireCodec.frame(
            command = 0x1D, subcommand = 0x1B, opcode = 0x01,
            parameters = byteArrayOf(1, 60, 2, 70, 3, 88),
        ))
        assertEquals(60, adapter.runtimeState().battery.left.percent)
        assertEquals(70, adapter.runtimeState().battery.right.percent)
        assertEquals(88, adapter.runtimeState().battery.case.percent)
    }

    @Test
    fun explicitUnreadableBudsClearStaleValues() {
        val adapter = readyAdapter()
        adapter.receive(batteryFrame(91, 76))
        val cleared = adapter.receive(batteryFrame(null, null))
        assertTrue(cleared.stateChanged)
        assertNull(adapter.runtimeState().battery.left.percent)
        assertNull(adapter.runtimeState().battery.right.percent)
        assertTrue(cleared.effects.isEmpty())
    }

    @Test
    fun allUnknownReportClearsAllPreviousComponents() {
        val adapter = readyAdapter()
        adapter.receive(batteryFrame(91, 76))
        assertTrue(adapter.receive(batteryFrame(null, null)).stateChanged)
        assertEquals(EarbudBattery(), adapter.runtimeState().battery)
        assertFalse(adapter.snapshot().capabilities.noiseControl)
    }

    @Test
    fun batteryBeforeHandshakeAndUnverifiedPushAreIgnored() {
        val adapter = MoondropPillAdapter()
        assertFalse(adapter.receive(batteryFrame(91, 76)).stateChanged)
        assertEquals(BatterySource.SYSTEM_AGGREGATE, adapter.effectiveBatterySource())
        adapter.receive(CAPTURED_HANDSHAKE_RESPONSE.hexBytes())
        for ((sub, opcode) in listOf(0x1A to 0x81, 0x81 to 0x01)) {
            val result = adapter.receive(MoondropZzzAngelsWireCodec.frame(
                command = 0x1D, subcommand = sub, opcode = opcode,
                parameters = byteArrayOf(1, 60, 2, 70),
            ))
            assertFalse(result.stateChanged)
        }
        assertEquals(BatterySource.SYSTEM_AGGREGATE, adapter.effectiveBatterySource())
    }

    @Test
    fun partialFirstBatteryIsHeldForBoundedFollowUpReads() {
        val adapter = readyAdapter()
        MoondropZzzAngelsBatteryPolicy.BOOTSTRAP_DELAYS_MS.forEach { delayMs ->
            val result = adapter.receive(batteryFrame(null, 70))
            assertFalse(result.stateChanged)
            assertEquals(BatterySource.SYSTEM_AGGREGATE, adapter.effectiveBatterySource())
            val followUp = result.effects.single() as AdapterEffect.RequestState
            assertEquals(BatteryFeatureState.FEATURE_ID, followUp.featureId)
            assertEquals(delayMs, followUp.delayMs)
            assertEquals(
                listOf(MoondropZzzAngelsWireCodec.queryBattery.toList()),
                adapter.queryState(followUp.featureId).map(ByteArray::toList),
            )
        }

        val committed = adapter.receive(batteryFrame(null, 70))
        assertTrue(committed.stateChanged)
        assertEquals(BatterySource.PRIVATE_PROTOCOL, adapter.effectiveBatterySource())
    }

    @Test
    fun refreshAndFeatureQueriesOnlyAskForBattery() {
        val adapter = readyAdapter()
        val refresh = adapter.executeControl(StandardControlRequest.Refresh)
        assertTrue(refresh.accepted)
        assertEquals(
            listOf(MoondropZzzAngelsWireCodec.queryBattery.toList()),
            refresh.commands.map(ByteArray::toList),
        )
        assertEquals(
            listOf(MoondropZzzAngelsWireCodec.queryBattery.toList()),
            adapter.queryState(BatteryFeatureState.FEATURE_ID).map(ByteArray::toList),
        )
        assertTrue(adapter.queryState(NoiseModeFeatureState.FEATURE_ID).isEmpty())
    }

    @Test
    fun aKnownModelStaysDormantInsteadOfSwitchingBrands() {
        assertEquals(
            InitialProtocolFailureResolution.KeepDormant,
            MoondropPillAdapter().onInitialProtocolUnavailable(),
        )
    }

    @Test
    fun reconnectResetsComponentCacheAndFirstReportPolicy() {
        val adapter = readyAdapter()
        adapter.receive(batteryFrame(91, 76))
        adapter.resetProtocolSession()
        adapter.receive(CAPTURED_HANDSHAKE_RESPONSE.hexBytes())
        val partial = adapter.receive(batteryFrame(null, 70))
        assertFalse(partial.stateChanged)
        assertEquals(BatterySource.SYSTEM_AGGREGATE, adapter.effectiveBatterySource())
        assertTrue(partial.effects.single() is AdapterEffect.RequestState)
    }

    private fun readyAdapter(): MoondropPillAdapter =
        MoondropPillAdapter().also { adapter ->
            val result = adapter.receive(CAPTURED_HANDSHAKE_RESPONSE.hexBytes())
            assertEquals(HandshakeResult.Ready, result.handshake)
        }

    /** PILL answers with two `<id> <value>` pairs; `00` encodes an unreadable bud. */
    private fun batteryFrame(left: Int?, right: Int?): ByteArray =
        MoondropZzzAngelsWireCodec.frame(
            command = 0x1D,
            subcommand = 0x1B,
            opcode = 0x01,
            parameters = byteArrayOf(
                1,
                (left ?: 0).toByte(),
                2,
                (right ?: 0).toByte(),
            ),
        )

    private fun String.hexBytes(): ByteArray =
        split(" ").map { it.toInt(16).toByte() }.toByteArray()

    private companion object {
        // 2026-11-12 capture, MOONDROP PILL, firmware 1.6.0, SPP DLCI 2.
        const val CAPTURED_HANDSHAKE_RESPONSE = "FF 04 00 04 00 0A 83 00 00 04 03 01"
        const val CAPTURED_COMPONENT_RESPONSE = "FF 04 00 02 00 1D 1B 00 01 02"
        const val CAPTURED_BATTERY_RESPONSE = "FF 04 00 04 00 1D 1B 01 01 64 02 50"
        const val CAPTURED_FIRMWARE_RESPONSE = "FF 04 00 05 00 1D 01 05 31 2E 36 2E 30"
    }
}