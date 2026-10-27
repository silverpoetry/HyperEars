package dev.hyperears.integration

import dev.hyperears.protocol.moondrop.MoondropZzzAngelsWireCodec
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class MoondropZzzAngelsAdapterTest {
    @Test
    fun exactNameSelectsTheModel() {
        val adapter = requireNotNull(
            EarbudAdapterRegistry.resolve(
                EarbudIdentity(
                    deviceName = "ZZZ-ANGELS-OWS",
                    standardHeadset = true,
                ),
            ),
        )
        assertTrue(adapter is MoondropZzzAngelsAdapter)
        assertEquals(MoondropZzzAngelsAdapter.ID, adapter.id)

        val qualified = requireNotNull(
            EarbudAdapterRegistry.resolve(
                EarbudIdentity(
                    deviceName = "MOONDROP ZZZ-ANGELS-OWS",
                    standardHeadset = true,
                ),
            ),
        )
        assertTrue(qualified is MoondropZzzAngelsAdapter)
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
        assertFalse(adapter is MoondropZzzAngelsAdapter)
    }

    @Test
    fun matchingRejectsNativeSystemEarbudsNonHeadsetsAndTheLeaIdentity() {
        val adapter = MoondropZzzAngelsAdapter()
        assertFalse(
            adapter.matches(
                EarbudIdentity(
                    deviceName = "ZZZ-ANGELS-OWS",
                    standardHeadset = true,
                    nativeSystemEarbud = true,
                ),
            ),
        )
        assertFalse(
            adapter.matches(
                EarbudIdentity(
                    deviceName = "ZZZ-ANGELS-OWS",
                    standardHeadset = false,
                ),
            ),
        )
        // The earbuds also broadcast an unusable LE Audio identity; the vendor application
        // cannot connect to it either, so this adapter must never claim it.
        assertFalse(
            adapter.matches(
                EarbudIdentity(
                    deviceName = "ZZZ-ANGELS-OWS-LEA",
                    standardHeadset = true,
                ),
            ),
        )
    }

    @Test
    fun arbitraryZzzAngelNamesAndSharedSppCannotSelectTheModel() {
        val adapter = MoondropZzzAngelsAdapter()
        for (name in listOf("ZZZ Angel Speaker", "ZZZ-ANGELS-OWS-LEA", "Other headset")) {
            assertFalse(adapter.matches(EarbudIdentity(
                deviceName = name,
                standardHeadset = true,
                serviceUuids = setOf(MoondropZzzAngelsAdapter.STANDARD_SPP_UUID),
            )))
        }
    }

    @Test
    fun declaresTheSppTransportAndLockedInitialCapabilities() {
        val adapter = MoondropZzzAngelsAdapter()
        assertEquals(
            listOf(MoondropZzzAngelsAdapter.STANDARD_SPP_UUID),
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
    }

    @Test
    fun privateBatteryOpensOnlyAfterALegalResponse() {
        val adapter = MoondropZzzAngelsAdapter()
        val handshake = adapter.beginHandshake()
        assertTrue(
            handshake.commands.single().contentEquals(MoondropZzzAngelsWireCodec.handshake),
        )
        assertEquals(HandshakeResult.AwaitingEvidence, handshake.handshake)
        assertEquals(BatterySource.SYSTEM_AGGREGATE, adapter.snapshot().batterySource)

        val accepted = adapter.receive(handshakeFrame())
        assertEquals(HandshakeResult.Ready, accepted.handshake)
        assertEquals(
            listOf(MoondropZzzAngelsWireCodec.queryBattery.toList()),
            accepted.commands.map(ByteArray::toList),
        )
        assertEquals(BatterySource.SYSTEM_AGGREGATE, adapter.snapshot().batterySource)

        adapter.receive(batteryFrame(91, 76, 88))
        assertEquals(BatterySource.PRIVATE_PROTOCOL, adapter.snapshot().batterySource)
    }

    @Test
    fun capturedSessionFramesUnlockPrivateBattery() {
        // btsnoop of the official application against firmware 1.2.0.
        val adapter = readyAdapter()
        val result = adapter.receive("FF 04 00 04 00 1D 1B 01 01 3C 02 64".hexBytes())
        assertTrue(result.stateChanged)
        assertEquals(BatterySource.PRIVATE_PROTOCOL, adapter.effectiveBatterySource())
        assertEquals(60, adapter.runtimeState().battery.left.percent)
        assertEquals(100, adapter.runtimeState().battery.right.percent)
        assertNull(adapter.runtimeState().battery.case.percent)
    }

    @Test
    fun noiseControlNeverOpensOnThisModel() {
        val adapter = readyAdapter()
        adapter.receive(batteryFrame(91, 76, 88))

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
        val tolerant = MoondropZzzAngelsAdapter()
        val otherVersion = tolerant.receive(
            MoondropZzzAngelsWireCodec.frame(
                command = 0x0A,
                subcommand = 0x83,
                opcode = 0x00,
                parameters = byteArrayOf(0, 3, 2, 5),
            ),
        )
        assertEquals(HandshakeResult.Ready, otherVersion.handshake)

        val rejecting = MoondropZzzAngelsAdapter()
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
        adapter.receive(batteryFrame(91, 76, 88))
        adapter.receive(MoondropZzzAngelsWireCodec.frame(
            command = 0x1D, subcommand = 0x1B, opcode = 0x01,
            parameters = byteArrayOf(2, 70),
        ))
        assertEquals(91, adapter.runtimeState().battery.left.percent)
        assertEquals(70, adapter.runtimeState().battery.right.percent)
        assertEquals(88, adapter.runtimeState().battery.case.percent)
    }

    @Test
    fun explicitUnreadableBudsClearStaleValuesWhileKeepingValidCase() {
        val adapter = readyAdapter()
        adapter.receive(batteryFrame(91, 76, 88))
        val cleared = adapter.receive(batteryFrame(null, null, 42))
        assertTrue(cleared.stateChanged)
        assertNull(adapter.runtimeState().battery.left.percent)
        assertNull(adapter.runtimeState().battery.right.percent)
        assertEquals(42, adapter.runtimeState().battery.case.percent)
        assertTrue(cleared.effects.isEmpty())
    }

    @Test
    fun allUnknownReportClearsAllPreviousComponents() {
        val adapter = readyAdapter()
        adapter.receive(batteryFrame(91, 76, 88))
        assertTrue(adapter.receive(batteryFrame(null, null, null)).stateChanged)
        assertEquals(EarbudBattery(), adapter.runtimeState().battery)
        assertFalse(adapter.snapshot().capabilities.noiseControl)
    }

    @Test
    fun batteryBeforeHandshakeAndUnverifiedPushAreIgnored() {
        val adapter = MoondropZzzAngelsAdapter()
        assertFalse(adapter.receive(batteryFrame(91, 76, null)).stateChanged)
        assertEquals(BatterySource.SYSTEM_AGGREGATE, adapter.effectiveBatterySource())
        adapter.receive(handshakeFrame())
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
    fun caseOnlyReportStaysProvisionalUntilBothBudsArrive() {
        val adapter = readyAdapter()
        val first = adapter.receive(batteryFrame(null, null, 88))
        assertFalse(first.stateChanged)
        assertEquals(BatterySource.SYSTEM_AGGREGATE, adapter.effectiveBatterySource())
        val followUp = first.effects.single() as AdapterEffect.RequestState
        assertEquals(BatteryFeatureState.FEATURE_ID, followUp.featureId)

        val committed = adapter.receive(batteryFrame(60, 70, 88))
        assertTrue(committed.stateChanged)
        assertEquals(BatterySource.PRIVATE_PROTOCOL, adapter.effectiveBatterySource())
        assertEquals(88, adapter.runtimeState().battery.case.percent)
    }

    @Test
    fun partialFirstBatteryIsHeldForBoundedFollowUpReads() {
        val adapter = readyAdapter()
        MoondropZzzAngelsBatteryPolicy.BOOTSTRAP_DELAYS_MS.forEach { delayMs ->
            val result = adapter.receive(batteryFrame(null, 70, null))
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

        val committed = adapter.receive(batteryFrame(null, 70, null))
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
            MoondropZzzAngelsAdapter().onInitialProtocolUnavailable(),
        )
    }

    private fun readyAdapter(): MoondropZzzAngelsAdapter =
        MoondropZzzAngelsAdapter().also { adapter ->
            val result = adapter.receive(handshakeFrame())
            assertEquals(HandshakeResult.Ready, result.handshake)
        }

    @Test
    fun reconnectResetsComponentCacheAndFirstReportPolicy() {
        val adapter = readyAdapter()
        adapter.receive(batteryFrame(91, 76, 88))
        adapter.resetProtocolSession()
        adapter.receive(handshakeFrame())
        val partial = adapter.receive(batteryFrame(null, 70, null))
        assertFalse(partial.stateChanged)
        assertEquals(BatterySource.SYSTEM_AGGREGATE, adapter.effectiveBatterySource())
        assertTrue(partial.effects.single() is AdapterEffect.RequestState)
    }

    private fun handshakeFrame(): ByteArray = MoondropZzzAngelsWireCodec.frame(
        command = 0x0A,
        subcommand = 0x83,
        opcode = 0x00,
        parameters = byteArrayOf(0, 4, 3, 1),
    )

    private fun batteryFrame(left: Int?, right: Int?, case: Int?): ByteArray =
        MoondropZzzAngelsWireCodec.frame(
            command = 0x1D,
            subcommand = 0x1B,
            opcode = 0x01,
            parameters = byteArrayOf(
                1,
                (left ?: 0).toByte(),
                2,
                (right ?: 0).toByte(),
                3,
                (case ?: 0xFF).toByte(),
            ),
        )

    private fun String.hexBytes(): ByteArray =
        split(" ").map { it.toInt(16).toByte() }.toByteArray()
}
