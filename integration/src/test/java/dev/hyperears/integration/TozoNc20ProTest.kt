package dev.hyperears.integration
import org.junit.Assert.*
import org.junit.Test
class TozoNc20ProTest {
    private fun hex(s:String)=s.chunked(2).map{it.toInt(16).toByte()}.toByteArray()
    @Test fun capturedReportsUnlockOnlyTheirOwnCapabilitiesAndAckDoesNotChangeMode() {
        val adapter=TozoNc20ProAdapter()
        assertTrue(adapter.matches(EarbudIdentity("TOZO NC20 Pro",true)))
        assertFalse(adapter.matches(EarbudIdentity("TOZO NC20",true)))
        assertFalse(adapter.matches(EarbudIdentity("TOZO NC20 Pro Box",false)))
        adapter.beginHandshake()
        assertFalse(adapter.effectiveCapabilities().noiseControl)
        adapter.receive(hex("0001060000470000478e"))
        assertFalse(adapter.effectiveCapabilities().noiseControl)
        adapter.receive(hex("0002026464c8"))
        assertEquals(100,adapter.runtimeState().features.get<BatteryFeatureState>()!!.battery.left.percent)
        adapter.receive(hex("0030010202"))
        assertTrue(adapter.effectiveCapabilities().noiseControl)
        assertEquals(NoiseMode.TRANSPARENCY,adapter.runtimeState().features.get<NoiseModeFeatureState>()!!.mode)
        val sent=adapter.executeControl(TozoControlRequest.SetNoise(TozoNoise.ANC))
        assertTrue(sent.accepted);assertArrayEquals(hex("1004010101"),sent.commands.single())
        adapter.receive(hex("1004010000"))
        assertEquals(NoiseMode.TRANSPARENCY,adapter.runtimeState().features.get<NoiseModeFeatureState>()!!.mode)
        adapter.receive(hex("0030010101"))
        assertEquals(NoiseMode.ANC,adapter.runtimeState().features.get<NoiseModeFeatureState>()!!.mode)
        assertFalse(adapter.supportsControl(TozoControlRequest.SetNoise(TozoNoise.CUSTOM)))
        adapter.resetProtocolSession()
        assertNull(adapter.runtimeState().features.get<TozoNc20ProFeatureState>())
        assertFalse(adapter.supportsControl(TozoControlRequest.SetNoise(TozoNoise.ANC)))
    }
    @Test fun advancedModesRemainTypedAndDoNotMasqueradeAsOff() {
        val adapter=TozoNc20ProAdapter();adapter.beginHandshake();adapter.receive(hex("0030010101"));adapter.receive(hex("0030010606"))
        assertEquals(TozoNoise.ADAPTIVE,adapter.runtimeState().features.get<TozoNc20ProFeatureState>()!!.noise)
        assertNull(adapter.runtimeState().features.get<NoiseModeFeatureState>()?.mode)
        val request=TozoControlRequest.SetNoise(TozoNoise.LEISURE)
        assertEquals(request,ControlRequestTransport.decode(ControlRequestTransport.encode(request)))
        assertArrayEquals(hex("1008010101"),adapter.executeControl(request).commands.single())
        val state=adapter.runtimeState().features
        assertEquals(state.get<TozoNc20ProFeatureState>(),FeatureStateTransport.decode(FeatureStateTransport.encode(state))?.get<TozoNc20ProFeatureState>())
    }
    @Test fun malformedAndAcknowledgementOnlyInputNeverUnlockControl() {
        val adapter=TozoNc20ProAdapter();adapter.beginHandshake()
        assertNull(adapter.receive(hex("1004010000")).handshake)
        assertNull(adapter.receive(hex("0030010203")).handshake)
        assertFalse(adapter.effectiveCapabilities().noiseControl)
        assertFalse(adapter.executeControl(TozoControlRequest.SetNoise(TozoNoise.ANC)).accepted)
        assertFalse(adapter.matches(EarbudIdentity("TOZO NC20 Pro",true,nativeSystemEarbud=true)))
        assertEquals(listOf(ControlAppCatalog.tozo),adapter.controlApps)
    }
    @Test fun supportedModesUseOfficialCommandsAndRequestAuthoritativeReadback() {
        val adapter=TozoNc20ProAdapter();adapter.beginHandshake();adapter.receive(hex("0030010202"))
        val writes=mapOf(TozoNoise.OFF to "1004010000",TozoNoise.ANC to "1004010101",
            TozoNoise.TRANSPARENCY to "1005010101",TozoNoise.WIND to "1007010101",
            TozoNoise.LEISURE to "1008010101",TozoNoise.ADAPTIVE to "1011010101")
        writes.forEach { (mode,bytes) ->
            val result=adapter.executeControl(TozoControlRequest.SetNoise(mode))
            assertTrue(result.accepted);assertArrayEquals(hex(bytes),result.commands.single())
            assertArrayEquals(hex("00300000"),result.readback.single())
            assertFalse(result.stateChanged)
        }
    }
    @Test fun eqRequiresReportedBaselineAndDoesNotPublishAcknowledgementAsAppliedState() {
        val adapter=TozoNc20ProAdapter();adapter.beginHandshake()
        val wire=hex("001828140007003200010064000100c8000300900104002003070040060800800c0100001906000032090072")
        adapter.receive(wire)
        val baseline=requireNotNull(adapter.runtimeState().features.get<TozoEqualizerFeatureState>())
        val request=TozoControlRequest.SetEqualizer(baseline,List(10){0})
        assertEquals(request,ControlRequestTransport.decode(ControlRequestTransport.encode(request)))
        assertEquals(baseline,FeatureStateTransport.decode(FeatureStateTransport.encode(adapter.runtimeState().features))!!.get<TozoEqualizerFeatureState>())
        assertFalse(adapter.executeControl(request.copy(gainsTenths=List(10){51})).accepted)
        assertFalse(adapter.executeControl(request.copy(gainsTenths=List(9){0})).accepted)
        val result=adapter.executeControl(request)
        assertTrue(result.accepted);assertEquals(45,result.commands.single().size)
        assertArrayEquals(hex("00180000"),result.readback.single())
        assertEquals(baseline,adapter.runtimeState().features.get<TozoEqualizerFeatureState>())
        adapter.receive(hex("1018010000"))
        assertEquals(baseline,adapter.runtimeState().features.get<TozoEqualizerFeatureState>())
        val updated=dev.hyperears.protocol.tozo.TozoWireCodec.decode(result.commands.single())!!.payload.drop(1).toByteArray()
        adapter.receive(dev.hyperears.protocol.tozo.TozoWireCodec.encode(0x201f,updated))
        assertEquals(List(10){0},adapter.runtimeState().features.get<TozoEqualizerFeatureState>()!!.bands.map{it.gainTenths})
        assertFalse(adapter.executeControl(request).accepted)
        adapter.resetProtocolSession()
        assertNull(adapter.runtimeState().features.get<TozoEqualizerFeatureState>())
        assertFalse(adapter.executeControl(request).accepted)
    }
    @Test fun touchUsesV7PermissionsAndPreservesUneditedSettingsUntilReadback() {
        val adapter=TozoNc20ProAdapter();adapter.beginHandshake()
        val bytes=byteArrayOf(8,3,10,6,99,1,2,4,5,88)
        adapter.receive(dev.hyperears.protocol.tozo.TozoWireCodec.encode(0x0003,bytes))
        val feature=requireNotNull(adapter.runtimeState().features.get<TozoTouchFeatureState>())
        val request=TozoControlRequest.SetTouch(feature,TozoTouchSlot.RIGHT_TRIPLE,TozoTouchAction.NOISE_CYCLE)
        assertEquals(request,ControlRequestTransport.decode(ControlRequestTransport.encode(request)))
        assertEquals(feature,FeatureStateTransport.decode(FeatureStateTransport.encode(adapter.runtimeState().features))!!.get<TozoTouchFeatureState>())
        assertFalse(adapter.executeControl(request.copy(slot=TozoTouchSlot.LEFT_HOLD,action=TozoTouchAction.PLAY_PAUSE)).accepted)
        val result=adapter.executeControl(request)
        assertTrue(result.accepted)
        assertArrayEquals(hex("00030000"),result.readback.single())
        val payload=dev.hyperears.protocol.tozo.TozoWireCodec.decode(result.commands.single())!!.payload
        assertArrayEquals(bytes.copyOf().also{it[7]=8},payload)
        adapter.receive(hex("1003010000"))
        assertEquals(feature.copy(awaitingReadback=true),adapter.runtimeState().features.get<TozoTouchFeatureState>())
        adapter.receive(dev.hyperears.protocol.tozo.TozoWireCodec.encode(0x0003,payload))
        assertFalse(adapter.executeControl(request).accepted)
        adapter.resetProtocolSession()
        assertNull(adapter.runtimeState().features.get<TozoTouchFeatureState>())
    }
    @Test fun dailyControlsRequireReportsAndCrossDependentSettingsAreReadBackTogether() {
        val adapter=TozoNc20ProAdapter();adapter.beginHandshake()
        adapter.receive(hex("0006010000"))
        var daily=requireNotNull(adapter.runtimeState().features.get<TozoDailyFeatureState>())
        assertNotNull(daily.request(TozoDailySetting.GAME,true))
        assertNull(daily.request(TozoDailySetting.WEAR,true))
        adapter.receive(hex("0025010101"))
        daily=adapter.runtimeState().features.get<TozoDailyFeatureState>()!!
        assertNull(daily.request(TozoDailySetting.MULTIPOINT,false))
        adapter.receive(hex("0029010000"))
        daily=adapter.runtimeState().features.get<TozoDailyFeatureState>()!!
        val request=requireNotNull(daily.request(TozoDailySetting.LDAC,true))
        assertEquals(true,request.expectedPeer)
        assertEquals(request,ControlRequestTransport.decode(ControlRequestTransport.encode(request)))
        val result=adapter.executeControl(request)
        assertTrue(result.accepted);assertEquals(2,result.readback.size)
        assertArrayEquals(hex("1029010101"),result.commands.single())
        adapter.receive(hex("1029010000"))
        assertEquals(false,adapter.runtimeState().features.get<TozoDailyFeatureState>()!!.values[TozoDailySetting.LDAC])
        adapter.receive(hex("201c010000"))
        assertFalse(adapter.executeControl(request).accepted)
        adapter.resetProtocolSession()
        assertNull(adapter.runtimeState().features.get<TozoDailyFeatureState>())
    }

    @Test fun consecutiveTouchEditsWaitForWholeConfigurationReadback() {
        val adapter = TozoNc20ProAdapter()
        adapter.beginHandshake()
        val raw = byteArrayOf(8,3,10,6,99,1,2,4,5,88)
        fun report(value: ByteArray) = adapter.receive(dev.hyperears.protocol.tozo.TozoWireCodec.encode(0x0003, value))
        report(raw)
        val baseline = requireNotNull(adapter.runtimeState().features.get<TozoTouchFeatureState>())
        val first = TozoControlRequest.SetTouch(baseline, TozoTouchSlot.LEFT_SINGLE, TozoTouchAction.NONE)
        val second = TozoControlRequest.SetTouch(baseline, TozoTouchSlot.RIGHT_DOUBLE, TozoTouchAction.NOISE_CYCLE)
        assertTrue(adapter.executeControl(first).accepted)
        assertTrue(adapter.runtimeState().features.get<TozoTouchFeatureState>()!!.awaitingReadback)
        assertFalse(adapter.executeControl(second).accepted)
        assertEquals(listOf(AdapterEffect.RequestState(TozoTouchFeatureState.ID, 2_000L)), adapter.controlWritten(first))
        // An ACK and a bounded retry do not make the old vector safe to write again.
        adapter.receive(hex("1003010000"))
        assertFalse(adapter.supportsControl(second))
        assertArrayEquals(hex("00030000"), adapter.queryState(TozoTouchFeatureState.ID).single())
        assertFalse(adapter.executeControl(second).accepted)
        val applied = raw.copyOf().also { it[0] = 0 }
        report(applied)
        val fresh = requireNotNull(adapter.runtimeState().features.get<TozoTouchFeatureState>())
        assertFalse(fresh.awaitingReadback)
        assertTrue(adapter.queryState(TozoTouchFeatureState.ID).isEmpty())
        assertFalse(adapter.executeControl(second).accepted)
        val next = adapter.executeControl(second.copy(expected=fresh))
        assertTrue(next.accepted)
        val payload = dev.hyperears.protocol.tozo.TozoWireCodec.decode(next.commands.single())!!.payload
        assertEquals(0, payload[0].toInt())
        assertEquals(8, payload[6].toInt())
        // Failed-channel recovery resets the reservation, but still needs a new report.
        adapter.resetProtocolSession()
        assertFalse(adapter.executeControl(first).accepted)
        adapter.beginHandshake()
        report(raw)
        assertTrue(adapter.executeControl(first).accepted)
        // A full report of the unchanged value also releases a failed device-side update.
        report(raw)
        assertFalse(adapter.runtimeState().features.get<TozoTouchFeatureState>()!!.awaitingReadback)
        assertTrue(adapter.executeControl(second).accepted)
    }
}
