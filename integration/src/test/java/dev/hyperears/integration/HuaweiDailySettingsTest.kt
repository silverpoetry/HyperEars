package dev.hyperears.integration

import dev.hyperears.protocol.huawei.HuaweiFreebudsSppCodec as Wire
import org.junit.Assert.*
import org.junit.Test

class HuaweiDailySettingsTest {
    private fun report(command: Int, vararg fields: Pair<Int, ByteArray>) = Wire.packet(command, fields.toList())
    private fun field(value: Int) = byteArrayOf(value.toByte())
    @Test fun perModelReadProbesNeverGrantCapabilities() {
        val adapters = HuaweiDailyModelCatalog.models.map(::HuaweiDailyModelAdapter) +
            listOf(HuaweiFreebudsPro3Adapter(), HuaweiFreebuds5iAdapter(), HuaweiFreeClip2Adapter())
        adapters.forEach { adapter ->
            assertNull(adapter.snapshot().presentationId)
            assertFalse(adapter.supportsControl(HuaweiDailyControlRequest.SetWearDetection(true)))
            assertFalse(adapter.snapshot().capabilities.noiseControl)
        }
        val studio = adapters.single { it.id == "huawei-freebuds-studio" }
        val commands = studio.beginHandshake().commands.map { Wire.parseFrame(it)!!.command }
        assertTrue(0x2B11 in commands); assertFalse(0x2B4A in commands)
        val lace = adapters.single { it.id == "huawei-freelace-pro2" }
        assertFalse(lace.beginHandshake().commands.any { Wire.parseFrame(it)!!.command == 0x2B11 })
    }
    @Test fun pro4AndFreeClipCandidatesStayWithinIndependentReportBoundaries() {
        for (id in listOf("huawei-freebuds-pro4", "huawei-freeclip")) {
            val adapter = HuaweiDailyModelAdapter(HuaweiDailyModelCatalog.models.single { it.id == id })
            val commands = adapter.beginHandshake().commands.map { Wire.parseFrame(it)!!.command }
            assertTrue(0x2B4A in commands)
            assertFalse(adapter.supportsControl(HuaweiDailyControlRequest.SetWearDetection(true)))
            adapter.receive(report(0x2B11, 1 to field(0)))
            assertTrue(adapter.supportsControl(HuaweiDailyControlRequest.SetWearDetection(true)))
            assertEquals(false, adapter.runtimeState().features.get<HuaweiDailySettingsFeatureState>()!!.wearDetection)
            adapter.receive(report(0x2B2A, 1 to byteArrayOf(1, 2)))
            assertFalse(adapter.snapshot().capabilities.noiseControl)
            assertNull(adapter.runtimeState().features.get<HuaweiAncLevelFeatureState>())
            adapter.receive(report(0x2B2A, 1 to byteArrayOf(2, 1)))
            if (id == "huawei-freeclip") {
                assertFalse(0x2B2A in commands); assertFalse(0x2B19 in commands)
                assertFalse(adapter.snapshot().capabilities.noiseControl)
                adapter.receive(report(0x2B17, 1 to field(10), 2 to field(10)))
                adapter.receive(report(0x2B19, 1 to field(1)))
                assertFalse(adapter.supportsControl(HuaweiDailyControlRequest.SetGesture(HuaweiGestureKind.HOLD, HuaweiGestureSide.LEFT, 10)))
                assertFalse(adapter.supportsControl(HuaweiDailyControlRequest.SetGesture(HuaweiGestureKind.CYCLE, HuaweiGestureSide.LEFT, 1)))
                assertTrue(adapter.queryState(NoiseModeFeatureState.FEATURE_ID).isEmpty())
            } else {
                assertTrue(adapter.snapshot().capabilities.noiseControl)
                assertEquals(setOf(NoiseMode.ANC, NoiseMode.OFF), adapter.snapshot().supportedNoiseModes)
                adapter.receive(report(0x2B19, 1 to field(1), 2 to field(1)))
                assertTrue(adapter.supportsControl(HuaweiDailyControlRequest.SetGesture(HuaweiGestureKind.CYCLE, HuaweiGestureSide.LEFT, 1)))
                assertFalse(adapter.supportsControl(HuaweiDailyControlRequest.SetGesture(HuaweiGestureKind.CYCLE, HuaweiGestureSide.LEFT, 2)))
            }
            adapter.resetProtocolSession()
            assertFalse(adapter.snapshot().capabilities.noiseControl)
            assertFalse(adapter.supportsControl(HuaweiDailyControlRequest.SetWearDetection(true)))
        }
    }
    @Test fun independentReportGatesReadbackAndReset() {
        val adapter = HuaweiFreebuds5iAdapter()
        val request = HuaweiDailyControlRequest.SetWearDetection(false)
        val bytes = report(0x2B11, 1 to field(1))
        bytes.forEach { adapter.receive(byteArrayOf(it)) }
        assertTrue(adapter.supportsControl(request))
        assertFalse(adapter.supportsControl(HuaweiDailyControlRequest.SetMultipoint(true)))
        assertEquals(HuaweiDailyPresentationIds.SETTINGS, adapter.snapshot().presentationId)
        val result = adapter.executeControl(request)
        assertTrue(result.accepted)
        assertEquals(0x2B10, Wire.parseFrame(result.commands.single())!!.command)
        assertEquals(0x2B11, Wire.parseFrame(result.readback.single())!!.command)
        assertEquals(true, adapter.runtimeState().features.get<HuaweiDailySettingsFeatureState>()!!.wearDetection)
        adapter.receive(report(0x2B10, 1 to field(0)))
        assertEquals(true, adapter.runtimeState().features.get<HuaweiDailySettingsFeatureState>()!!.wearDetection)
        adapter.receive(report(0x2B11, 1 to field(0)))
        assertEquals(false, adapter.runtimeState().features.get<HuaweiDailySettingsFeatureState>()!!.wearDetection)
        adapter.resetProtocolSession()
        assertFalse(adapter.supportsControl(request)); assertTrue(adapter.protocolSession.encode(request).isEmpty())
        assertNull(adapter.snapshot().presentationId)
    }
    @Test fun exactSourceProfilesPreventCrossModelWritesAndPreserveAncGaps() {
        val clip = HuaweiFreeClip2Adapter()
        clip.receive(report(0x2B4A, 1 to field(1), 2 to field(10), 3 to byteArrayOf(1, 2, 3, 9, 10)))
        assertTrue(clip.supportsControl(HuaweiDailyControlRequest.SetEqualizer(10)))
        assertFalse(clip.supportsControl(HuaweiDailyControlRequest.SetEqualizer(2)))
        assertFalse(clip.snapshot().capabilities.noiseControl)
        assertFalse(clip.beginHandshake().commands.any { Wire.parseFrame(it)!!.command == 0x2B19 })
        val family = HuaweiFreebudsFamilyAdapter()
        family.receive(report(0x2B11, 1 to field(1)))
        assertNull(family.runtimeState().features.get<HuaweiDailySettingsFeatureState>())
        val five = HuaweiFreebuds5iAdapter()
        five.receive(report(0x2B2A, 1 to byteArrayOf(2, 1)))
        assertNull(five.runtimeState().features.get<HuaweiAncLevelFeatureState>())
    }
    @Test fun gestureOptionsIntersectReportsAndPartialUpdatesRevokeMissingSides() {
        val adapter = HuaweiFreebuds5iAdapter()
        adapter.receive(report(0x0120, 1 to field(1), 2 to field(2), 3 to byteArrayOf(1, 2), 4 to field(-1)))
        assertTrue(adapter.supportsControl(HuaweiDailyControlRequest.SetGesture(HuaweiGestureKind.DOUBLE, HuaweiGestureSide.RIGHT, 1)))
        assertFalse(adapter.supportsControl(HuaweiDailyControlRequest.SetGesture(HuaweiGestureKind.DOUBLE, HuaweiGestureSide.RIGHT, 7)))
        adapter.receive(report(0x0120, 1 to field(1), 3 to byteArrayOf(1, 2)))
        assertFalse(adapter.supportsControl(HuaweiDailyControlRequest.SetGesture(HuaweiGestureKind.DOUBLE, HuaweiGestureSide.RIGHT, 1)))
        adapter.receive(report(0x0120, 1 to field(42), 3 to byteArrayOf(42)))
        assertEquals(1, adapter.runtimeState().features.get<HuaweiDailySettingsFeatureState>()!!.gestures.single().left)
    }
    @Test fun studioAndFreelaceDoNotInventTwsBatteries() {
        HuaweiDailyModelCatalog.models.filter { it.aggregate }.forEach { model ->
            val adapter = HuaweiDailyModelAdapter(model)
            adapter.receive(report(0x0108, 1 to field(70), 2 to byteArrayOf(50, 60, 80)))
            val battery = adapter.runtimeState().battery
            assertEquals(70, battery.overall.percent)
            assertNull(battery.left.percent); assertNull(battery.right.percent); assertNull(battery.case.percent)
            assertEquals(HeadsetFormFactor.HEADPHONES, adapter.snapshot().formFactor)
        }
    }
    @Test fun customEditingRequiresReportedSlotAndTypedIpcRoundTrips() {
        val adapter = HuaweiDailyModelAdapter(HuaweiDailyModelCatalog.models.single { it.id == "huawei-freebuds-6i" })
        val record = byteArrayOf(100, 10) + ByteArray(10) + "Daily".toByteArray().copyOf(24)
        adapter.receive(report(0x2B4A, 1 to field(1), 2 to field(100), 3 to byteArrayOf(1, 2, 3, 9), 5 to field(10), 8 to record))
        val request = HuaweiDailyControlRequest.ManageEqualizer(adapter.runtimeState().features.get<HuaweiDailySettingsFeatureState>()!!.eqInventory!!, HuaweiEqAction.UPDATE, 100, gains = List(10) { 20 })
        assertTrue(adapter.supportsControl(request))
        assertFalse(adapter.supportsControl(request.copy(id = 101)))
        assertFalse(adapter.supportsControl(request.copy(gains = List(10) { 61 })))
        val result = adapter.executeControl(request)
        assertEquals(0x2B49, Wire.parseFrame(result.commands.single())!!.command)
        assertEquals(List(10) { 0 }, adapter.runtimeState().features.get<HuaweiDailySettingsFeatureState>()!!.equalizer!!.custom.single().gains)
        val requests = listOf(request, HuaweiDailyControlRequest.SetEqualizer(1), HuaweiDailyControlRequest.SetWearDetection(true),
            HuaweiDailyControlRequest.SetMultipoint(false), HuaweiDailyControlRequest.SetGesture(HuaweiGestureKind.HOLD, HuaweiGestureSide.LEFT, 10))
        requests.forEach { assertEquals(it, ControlRequestTransport.decode(ControlRequestTransport.encode(it))) }
        val snapshot = adapter.runtimeState().features
        assertEquals(snapshot, FeatureStateTransport.decode(FeatureStateTransport.encode(snapshot)))
    }
    @Test fun staleCustomSlotSnapshotIsRejectedByAdapterSessionAndIpc() {
        val adapter = HuaweiFreebudsPro3Adapter()
        fun eq(name: String, gain: Int = 0) = report(0x2B4A, 1 to field(1), 2 to field(100), 3 to field(1), 5 to field(10),
            8 to (byteArrayOf(100, 10) + ByteArray(10) { gain.toByte() } + name.toByteArray().copyOf(24)))
        adapter.receive(eq("Original"))
        val slot = adapter.runtimeState().features.get<HuaweiDailySettingsFeatureState>()!!.equalizer!!.custom.single()
        val request = HuaweiDailyControlRequest.ManageEqualizer(adapter.runtimeState().features.get<HuaweiDailySettingsFeatureState>()!!.eqInventory!!, HuaweiEqAction.UPDATE, 100, gains = List(10) { 20 })
        assertTrue(adapter.supportsControl(request))
        val restored = ControlRequestTransport.decode(ControlRequestTransport.encode(request))!!
        adapter.receive(eq("Replacement"))
        assertFalse(adapter.executeControl(restored).accepted)
        assertTrue(adapter.protocolSession.encode(restored).isEmpty())
        adapter.receive(eq("Original", 10))
        assertFalse(adapter.executeControl(request).accepted)
    }
    @Test fun validUnsupportedEqSnapshotRevokesOldControlsButMalformedDoesNot() {
        val adapter = HuaweiFreebudsPro3Adapter()
        adapter.receive(report(0x2B4A, 1 to field(1), 2 to field(5), 3 to field(5), 5 to field(10), 8 to byteArrayOf()))
        val old = HuaweiDailyControlRequest.ManageEqualizer(adapter.runtimeState().features.get<HuaweiDailySettingsFeatureState>()!!.eqInventory!!, HuaweiEqAction.SELECT, 5)
        assertTrue(adapter.supportsControl(old))
        adapter.receive(report(0x2B4A, 1 to field(1), 2 to byteArrayOf(6, 7), 3 to field(6)))
        assertTrue(adapter.supportsControl(old))
        adapter.receive(report(0x2B4A, 1 to field(1), 2 to field(6), 3 to field(6), 5 to field(10), 8 to byteArrayOf()))
        assertFalse(adapter.supportsControl(old))
        assertTrue(adapter.protocolSession.encode(old).isEmpty())
        val eq = adapter.runtimeState().features.get<HuaweiDailySettingsFeatureState>()!!.equalizer!!
        assertEquals(6, eq.selected); assertEquals(listOf(6), eq.presets)
    }
    @Test fun aggregateComponentOnlyReportsKeepWorkingSystemBattery() {
        HuaweiDailyModelCatalog.models.filter { it.aggregate }.forEach { model ->
            val adapter = HuaweiDailyModelAdapter(model)
            adapter.onSystemBatteryChanged(75)
            val rejected = adapter.receive(report(0x0108, 2 to byteArrayOf(50, 60, 80)))
            assertNull(rejected.handshake)
            assertEquals(BatterySource.SYSTEM_AGGREGATE, adapter.effectiveBatterySource())
            assertEquals(75, adapter.runtimeState().battery.overall.percent)
            adapter.receive(report(0x0108, 1 to field(65)))
            assertEquals(BatterySource.PRIVATE_PROTOCOL, adapter.effectiveBatterySource())
            assertEquals(65, adapter.runtimeState().battery.overall.percent)
            adapter.receive(report(0x0127, 2 to byteArrayOf(40, 30, 20)))
            assertEquals(65, adapter.runtimeState().battery.overall.percent)
        }
    }

    @Test fun freeLaceUsesCooldownAndMirroredPhysicalButtonsOnlyAfterReports() {
        val adapter = HuaweiDailyModelAdapter(HuaweiDailyModelCatalog.models.single { it.id == "huawei-freelace-pro" })
        val endpoint = adapter.transports.single() as RfcommEndpointSpec.Channel
        assertEquals(16, endpoint.number)
        assertEquals(2000L, endpoint.connectDelayMs)
        assertEquals(HeadsetFormFactor.HEADPHONES, adapter.formFactor)
        adapter.receive(report(0x0108, 2 to byteArrayOf(50, 60, 80)))
        assertEquals(50, adapter.runtimeState().battery.left.percent)
        assertEquals(60, adapter.runtimeState().battery.right.percent)
        val power = HuaweiDailyControlRequest.SetGesture(HuaweiGestureKind.DOUBLE, HuaweiGestureSide.LEFT, 12)
        assertFalse(adapter.supportsControl(power))
        adapter.receive(report(0x0120, 1 to field(-1), 2 to field(-1)))
        assertTrue(adapter.supportsControl(power))
        val session = HuaweiDailySettingsSession(HuaweiDailyProfile.LACE_PRO)
        session.offer(requireNotNull(Wire.parseFrame(report(0x0120, 1 to field(-1)))))
        val write = requireNotNull(Wire.parseFrame(requireNotNull(session.encode(power))))
        assertArrayEquals(field(12), write.params[1])
        assertArrayEquals(field(12), write.params[2])
        assertFalse(adapter.supportsControl(power.copy(side = HuaweiGestureSide.RIGHT)))
        session.offer(requireNotNull(Wire.parseFrame(report(0x2B17, 1 to field(3)))))
        val hold = HuaweiDailyControlRequest.SetGesture(HuaweiGestureKind.HOLD, HuaweiGestureSide.LEFT, 5)
        val holdWrite = requireNotNull(Wire.parseFrame(requireNotNull(session.encode(hold))))
        assertArrayEquals(field(5), holdWrite.params[1]); assertArrayEquals(field(5), holdWrite.params[2])
        assertNull(session.encode(hold.copy(value = 10)))
        adapter.resetProtocolSession()
        assertFalse(adapter.supportsControl(power))
    }

}
