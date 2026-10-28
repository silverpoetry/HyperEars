package dev.hyperears.integration

import dev.hyperears.protocol.rose.RoseRsV2IdentityCodec as Identity
import org.junit.Assert.*
import org.junit.Test

class RoseRsV2AdapterTest {
    @Test fun everyHeadProfileUsesReportedChargeSlotNotLeftAndNeverFillsBudTelemetry() {
        for(model in Identity.Model.entries.filter { it.aggregateBattery }) {
            val a=RoseRsV2Adapter(model)
            a.receive(frame(12,70,60,50 or 128))
            assertEquals(model.name,50,a.runtimeState().battery.overall.percent)
            assertTrue(a.runtimeState().battery.overall.charging)
            assertNull(a.runtimeState().battery.left.percent);assertNull(a.runtimeState().battery.case.percent)
            a.receive(frame(12,70,60,255))
            assertNull(a.runtimeState().battery.overall.percent)
        }
    }
    @Test fun opalAndMokaDoNotInheritNeighborModesAndHscGainDoesNotGrantHearing() {
        val opal=RoseRsV2Adapter(Identity.Model.OPAL);opal.receive(frame(9,1))
        assertEquals(setOf(NoiseMode.ANC,NoiseMode.OFF,NoiseMode.TRANSPARENCY),opal.snapshot().supportedNoiseModes)
        assertFalse(opal.executeControl(StandardControlRequest.SetNoiseMode(NoiseMode.WIND)).accepted)
        val moka=RoseRsV2Adapter(Identity.Model.MOKA);moka.receive(frame(0x2F,1)+frame(7,1))
        assertFalse(moka.executeControl(RoseRsV2ControlRequest.Find(1)).accepted)
        assertFalse(moka.executeControl(RoseRsV2ControlRequest.SetTouchEnabled(true)).accepted)
        val hsc=RoseRsV2Adapter(Identity.Model.CERAMICS_U_HSC_2710);hsc.receive(frame(0x45,0)+frame(0x46,1))
        assertTrue(hsc.executeControl(RoseRsV2ControlRequest.SetGain(Identity.Model.CERAMICS_U_HSC_2710.name,1)).accepted)
        assertFalse(hsc.executeControl(RoseRsV2ControlRequest.SetHearingProtection(Identity.Model.CERAMICS_U_HSC_2710.name,1)).accepted)
        hsc.resetProtocolSession();assertFalse(hsc.executeControl(RoseRsV2ControlRequest.SetGain(Identity.Model.CERAMICS_U_HSC_2710.name,1)).accepted)
    }
    @Test fun qualifiedGainAndProtectionUseReadbackAndResetRatherThanOptimism() {
        for (model in Identity.Model.entries) {
            val a=RoseRsV2Adapter(model)
            val gain=RoseRsV2ControlRequest.SetGain(model.name,2)
            val protection=RoseRsV2ControlRequest.SetHearingProtection(model.name,6)
            assertFalse(a.executeControl(gain).accepted)
            a.receive(frame(0x45,0)+frame(0x46,1))
            assertEquals(model.gainSupported,a.executeControl(gain).accepted)
            assertEquals(model.hearingProtectionSupported,a.executeControl(protection).accepted)
            if(model.gainAndProtection) {
                assertArrayEquals(byteArrayOf(0x45,2),payload(a.executeControl(gain).commands.single()))
                assertArrayEquals(byteArrayOf(0xFA.toByte(),0x46),payload(a.executeControl(protection).readback.single()))
                assertEquals(0,a.runtimeState().features.get<RoseRsV2SettingsFeatureState>()!!.gain)
                assertEquals(1,a.runtimeState().features.get<RoseRsV2SettingsFeatureState>()!!.hearingProtection)
                assertFalse(a.executeControl(gain.copy(expectedModel="other")).accepted)
                assertEquals(protection,ControlRequestTransport.decode(ControlRequestTransport.encode(protection)))
                a.receive(frame(0x45,2)+frame(0x46,6))
                assertEquals(2,a.runtimeState().features.get<RoseRsV2SettingsFeatureState>()!!.gain)
                assertEquals(6,a.runtimeState().features.get<RoseRsV2SettingsFeatureState>()!!.hearingProtection)
            }
            a.resetProtocolSession();assertFalse(a.executeControl(gain).accepted)
            assertFalse(a.executeControl(protection).accepted)
        }
    }
    @Test fun newBatteryTopologiesNeverInventCaseOrTwoBudsForSwift() {
        val swift=RoseRsV2Adapter(Identity.Model.SWIFT2)
        swift.receive(frame(12,70,60,50))
        assertEquals(50,swift.runtimeState().battery.overall.percent)
        assertNull(swift.runtimeState().battery.left.percent)
        assertNull(swift.runtimeState().battery.case.percent)
        assertEquals(HeadsetFormFactor.HEADPHONES,swift.formFactor)
        for(m in Identity.Model.entries.filter { it.name.startsWith("LUMINOUS_") }) {
            val a=RoseRsV2Adapter(m);a.receive(frame(12,70,60,50))
            assertEquals(70,a.runtimeState().battery.left.percent)
            assertEquals(60,a.runtimeState().battery.right.percent)
            assertNull(a.runtimeState().battery.case.percent)
        }
    }
    @Test fun automaticManufacturerSelectionKeepsVariantsDistinctAndNoStaticFeatures() {
        Identity.Model.entries.forEach { model ->
            val identity=identity(model)
            val resolved=EarbudAdapterRegistry.resolve(identity)!!
            assertEquals(model.id,resolved.id)
            assertNull(resolved.miLinkCardPresentationId)
            assertFalse(resolved.supportsControl(RoseRsV2ControlRequest.SetEqualizer(0)))
            assertFalse(RoseRsV2Adapter(model).matches(identity.copy(manufacturerData=emptyMap())))
            assertFalse(RoseRsV2Adapter(model).matches(identity.copy(nativeSystemEarbud=true)))
        }
    }
    @Test fun reportQualifiedRequestsReadBackWithoutOptimisticPublication() {
        Identity.Model.entries.forEach { model ->
            val a=RoseRsV2Adapter(model)
            assertFalse(a.executeControl(RoseRsV2ControlRequest.SetGame(true)).accepted)
            a.receive(frame(0x0E,0));assertTrue(a.executeControl(RoseRsV2ControlRequest.SetGame(true)).accepted)
            assertEquals(false,a.runtimeState().features.get<RoseRsV2SettingsFeatureState>()!!.game)
            a.receive(frame(0x0E,1));assertEquals(true,a.runtimeState().features.get<RoseRsV2SettingsFeatureState>()!!.game)
            a.resetProtocolSession();assertFalse(a.executeControl(RoseRsV2ControlRequest.SetGame(true)).accepted)
        }
    }
    @Test fun eqLabelsUseSourceSemanticValuesAndFindReceiveOnlyCodesAreNotWrites() {
        val a=RoseRsV2Adapter(Identity.Model.ASTRO_X);a.receive(frame(0x2A,0))
        val pop=a.executeControl(RoseRsV2ControlRequest.SetEqualizer(0))
        assertArrayEquals(byteArrayOf(0x2A,1),payload(pop.commands.single()))
        assertArrayEquals(byteArrayOf(0xFA.toByte(),0x2A),payload(pop.readback.single()))
        assertEquals(1,a.runtimeState().features.get<RoseRsV2SettingsFeatureState>()!!.eq)
        assertArrayEquals(byteArrayOf(0x2A,2),payload(a.executeControl(RoseRsV2ControlRequest.SetEqualizer(2)).commands.single()))
        a.receive(frame(0x2F,4));assertFalse(a.executeControl(RoseRsV2ControlRequest.Find(5)).accepted)
        assertTrue(a.executeControl(RoseRsV2ControlRequest.Find(1)).accepted)
        val encoded=ControlRequestTransport.encode(RoseRsV2ControlRequest.SetTouch(17,3))
        assertEquals(RoseRsV2ControlRequest.SetTouch(17,3),ControlRequestTransport.decode(encoded))
    }
    @Test fun sourceDistinctNoiseTouchAndAudioProfilesRejectWrongDialect() {
        val speak=RoseRsV2Adapter(Identity.Model.SPEAKFEEL2);speak.receive(frame(7,1));speak.receive(frame(9,1))
        assertFalse(speak.executeControl(RoseRsV2ControlRequest.SetTouchEnabled(true)).accepted)
        assertFalse(speak.supportsControl(StandardControlRequest.SetNoiseMode(NoiseMode.ANC)))
        val open=RoseRsV2Adapter(Identity.Model.OPENFEEL_L);open.receive(frame(0x2B,0))
        assertTrue(open.executeControl(RoseRsV2ControlRequest.SetAudio(1)).accepted)
        assertFalse(open.executeControl(RoseRsV2ControlRequest.SetAudio(2)).accepted)
        val ceramics=RoseRsV2Adapter(Identity.Model.CERAMICS_MK2_5736);ceramics.receive(frame(0x2B,0));ceramics.receive(frame(9,4))
        assertFalse(ceramics.executeControl(RoseRsV2ControlRequest.SetAudio(0)).accepted)
        assertTrue(ceramics.supportsControl(StandardControlRequest.SetNoiseMode(NoiseMode.WIND)))
    }
    private fun identity(model:Identity.Model):EarbudIdentity {
        val bytes=model.prefix.chunked(2).map{it.toInt(16)}
        return EarbudIdentity("Renamed headset",true,manufacturerData=mapOf((bytes[0] or (bytes[1] shl 8)) to bytes.drop(2)))
    }
    private fun payload(bytes:ByteArray)=bytes.copyOfRange(3,bytes.size-2)
    private fun frame(type:Int,vararg values:Int):ByteArray {
        val p=byteArrayOf(0xDD.toByte(),0,(values.size+1).toByte(),type.toByte())+values.map(Int::toByte)
        return p+byteArrayOf(p.sumOf{it.toInt() and 255}.toByte(),0xAA.toByte())
    }
    @Test fun conflictingOtherRoseProtocolDoesNotSelectRsV2() {
        val identity = EarbudIdentity("ROSE unclassified", true, manufacturerData = mapOf(
            0x0e0b to listOf(10,12), 0x02f1 to listOf(3)))
        val model = dev.hyperears.protocol.rose.RoseRsV2IdentityCodec.Model.CERAMICS_MK2
        assertFalse(RoseRsV2Adapter(model).matches(identity))
        assertNotEquals(model.id, EarbudAdapterRegistry.resolve(identity)?.id)
    }

}
