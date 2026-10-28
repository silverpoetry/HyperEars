package dev.hyperears.integration

import dev.hyperears.protocol.oppo.OppoSettingsCodec
import dev.hyperears.protocol.oppo.OppoWireCodec
import org.junit.Assert.*
import org.junit.Test

class OppoSettingsAdapterTest {
    private fun report(command: Int, vararg p: Int) = OppoWireCodec.packet(command, payload = p.map(Int::toByte).toByteArray())
    @Test fun promptVolumeGatesByReportAndPreservesOtherSettings() {
        val adapter=OppoEarbudAdapter();val request=OppoControlRequest.SetPromptVolume(8)
        assertFalse(adapter.executeControl(request).accepted)
        adapter.receive(report(0x8130,0,5))
        assertEquals(5,adapter.runtimeState().features.get<OppoSettingsFeatureState>()!!.promptVolume)
        val result=adapter.executeControl(request)
        assertTrue(result.accepted);assertArrayEquals(OppoSettingsCodec.queryPromptVolume,result.readback.single())
        assertEquals(5,adapter.runtimeState().features.get<OppoSettingsFeatureState>()!!.promptVolume)
        adapter.receive(report(0x810D,0,0))
        assertEquals(5,adapter.runtimeState().features.get<OppoSettingsFeatureState>()!!.promptVolume)
        assertEquals(request,ControlRequestTransport.decode(ControlRequestTransport.encode(request)))
        adapter.resetProtocolSession();assertFalse(adapter.executeControl(request).accepted)
    }
    @Test fun legacySpatialRequiresQualifiedIdentityAndIndependentReport() {
        fun identity(id:Int)=report(0x8103,0,id and 255,(id ushr 8) and 255,id ushr 16)
        var adapter: EarbudAdapter=OppoEarbudAdapter()
        adapter.receive(report(0x810D,0,1,27,0))
        val request=OppoControlRequest.SetSpatialSound(true,0x06F010)
        assertFalse(adapter.executeControl(request).accepted)
        assertEquals(44,OppoLegacySpatialCatalog.products.size)
        val identified=adapter.receive(identity(0x06F010))
        assertTrue(identified.commands.any { it.contentEquals(OppoSettingsCodec.querySwitches(OppoSettingsCodec.switchIds+0x1B)) })
        adapter=(identified.handshake as HandshakeResult.Replace).adapter
        assertFalse(adapter.executeControl(request).accepted)
        adapter.receive(report(0x810D,0,1,27,0))
        assertTrue(adapter.executeControl(request).accepted)
        assertEquals(false,adapter.runtimeState().features.get<OppoSettingsFeatureState>()!!.spatialSound)
        adapter.receive(report(0x810D,0,0));assertFalse(adapter.executeControl(request).accepted)
        adapter.receive(report(0x810D,0,1,27,1))
        adapter=(adapter.receive(identity(0x067410)).handshake as HandshakeResult.Replace).adapter
        adapter.receive(report(0x810D,0,1,27,1))
        assertFalse(adapter.executeControl(request).accepted)
        assertNull(adapter.runtimeState().features.get<OppoSettingsFeatureState>()!!.spatialSound)
        assertEquals(request,ControlRequestTransport.decode(ControlRequestTransport.encode(request)))
    }
    @Test fun switchesAreIndependentlyGatedWithoutOptimismAndReset() {
        for (toggle in OppoToggle.entries) {
            val adapter = OppoEarbudAdapter()
            val request = OppoControlRequest.SetToggle(toggle, true)
            assertFalse(adapter.executeControl(request).accepted)
            adapter.receive(report(0x810D, 0, 1, toggle.featureId, 0))
            val result = adapter.executeControl(request)
            assertTrue(result.accepted)
            assertFalse(result.stateChanged)
            assertArrayEquals(OppoSettingsCodec.setSwitch(toggle.featureId, true), result.commands.single())
            assertArrayEquals(OppoSettingsCodec.querySwitches(), result.readback.single())
            assertEquals(false, adapter.runtimeState().features.get<OppoSettingsFeatureState>()!!.toggles[toggle])
            assertFalse(adapter.snapshot().capabilities.noiseControl)
            assertEquals(BatterySource.SYSTEM_AGGREGATE, adapter.effectiveBatterySource())
            for (other in OppoToggle.entries.filter { it != toggle }) {
                assertFalse(adapter.executeControl(OppoControlRequest.SetToggle(other, true)).accepted)
            }
            adapter.resetProtocolSession()
            assertNull(adapter.runtimeState().features.get<OppoSettingsFeatureState>())
            assertFalse(adapter.executeControl(request).accepted)
        }
    }
    @Test fun eqSelectionNeedsReportedListAndEditingAlsoNeedsWireIdentityAndNonBuiltinSlot() {
        var adapter: EarbudAdapter = OppoEarbudAdapter()
        adapter.receive(report(0x810F, 0, 9))
        assertFalse(adapter.executeControl(OppoControlRequest.SelectEqualizer(9)).accepted)
        val entries = report(0x8122, 0, 1, 1, 250, 6, 9, 1, 65, 2, 62, 0, 0, 232, 3, 0)
        adapter.receive(entries)
        assertTrue(adapter.executeControl(OppoControlRequest.SelectEqualizer(9)).accepted)
        assertFalse(adapter.executeControl(OppoControlRequest.SelectEqualizer(8)).accepted)
        val request = OppoControlRequest.UpdateEqualizer(9, listOf(-2, 3), OppoEqualizerDefinition(9, "A", -6, 6, listOf(62, 1000)))
        assertFalse(adapter.executeControl(request).accepted)
        adapter = (adapter.receive(report(0x8103, 0, 0x10, 0x74, 6)).handshake as HandshakeResult.Replace).adapter
        val result = adapter.executeControl(request)
        assertTrue(result.accepted)
        assertFalse(result.stateChanged)
        assertEquals(listOf(0, 0), adapter.runtimeState().features.get<OppoSettingsFeatureState>()!!.equalizers!!.single().gains)
        assertArrayEquals(OppoSettingsCodec.queryEqualizers, result.readback.single())
        adapter.receive(report(0x8418, 0, 9))
        assertEquals(listOf(0, 0), adapter.runtimeState().features.get<OppoSettingsFeatureState>()!!.equalizers!!.single().gains)
        adapter.receive(report(0x8103, 0, 0x56, 0x34, 0x12))
        assertFalse(adapter.executeControl(request).accepted)
        adapter.receive(report(0x8122, 0, 0))
        assertFalse(adapter.executeControl(request).accepted)
    }
    @Test fun malformedFramesAcksAndBuiltInEqNeverGrantCustomWrite() {
        var adapter: EarbudAdapter = OppoEarbudAdapter()
        listOf(report(0x810D, 1, 1, 4, 1), report(0x810D, 0, 1, 4, 2),
            report(0x8403, 0, 1), report(0x8418, 0, 9)).forEach(adapter::receive)
        assertNull(adapter.runtimeState().features.get<OppoSettingsFeatureState>())
        adapter = (adapter.receive(report(0x8103, 0, 0x10, 0x74, 6)).handshake as HandshakeResult.Replace).adapter
        adapter.receive(report(0x8122, 0, 1, 1, 250, 6, 7, 1, 65, 1, 62, 0, 0))
        assertFalse(adapter.executeControl(OppoControlRequest.UpdateEqualizer(7, listOf(1), OppoEqualizerDefinition(7, "A", -6, 6, listOf(62)))).accepted)
    }
    @Test fun allRequestsUseTypedTransport() {
        listOf(OppoControlRequest.SetToggle(OppoToggle.GAME_MODE, true), OppoControlRequest.SelectEqualizer(9),
            OppoControlRequest.UpdateEqualizer(9, listOf(-2, 3), OppoEqualizerDefinition(9, "A", -6, 6, listOf(62, 1000)))).forEach {
            assertEquals(it, ControlRequestTransport.decode(ControlRequestTransport.encode(it)))
        }
    }
    @Test fun reducedAndEmptySwitchSnapshotsRevokeOnlyMissingSwitchEvidence() {
        val adapter = OppoEarbudAdapter()
        adapter.receive(report(0x810F, 0, 9))
        adapter.receive(report(0x810D, 0, 2, 4, 1, 6, 0))
        adapter.receive(report(0x810D, 0, 1, 6, 1))
        assertFalse(adapter.executeControl(OppoControlRequest.SetToggle(OppoToggle.AUTO_PLAY_PAUSE, false)).accepted)
        assertTrue(adapter.executeControl(OppoControlRequest.SetToggle(OppoToggle.LOW_LATENCY, false)).accepted)
        adapter.receive(report(0x810D, 0, 1, 6, 2))
        assertEquals(mapOf(OppoToggle.LOW_LATENCY to true), adapter.runtimeState().features.get<OppoSettingsFeatureState>()!!.toggles)
        adapter.receive(report(0x810D, 0, 0))
        assertTrue(adapter.runtimeState().features.get<OppoSettingsFeatureState>()!!.toggles.isEmpty())
        assertEquals(9, adapter.runtimeState().features.get<OppoSettingsFeatureState>()!!.currentEqualizer)
        assertFalse(adapter.executeControl(OppoControlRequest.SetToggle(OppoToggle.LOW_LATENCY, false)).accepted)
    }
    @Test fun firmwareConditionalAndNestedBuiltInSlotsAreNeverWritableAsCustom() {
        val exclusions = mapOf(0x06B810 to setOf(3), 0x065C14 to setOf(3),
            0x062014 to setOf(7), 0x063014 to setOf(7), 0x063414 to setOf(7),
            0x063C10 to setOf(4), 0x063C14 to setOf(3), 0x066810 to setOf(3),
            0x068C10 to setOf(7), 0x06C010 to setOf(7, 8), 0x06BC10 to setOf(8), 0x06E410 to setOf(1))
        exclusions.forEach { (productId, ids) ->
            assertTrue("product=${productId.toString(16)} exclusions=$ids", OppoBuiltInEqIds.byProduct.getValue(productId).containsAll(ids))
            val initial = OppoEarbudAdapter()
            val adapter = (initial.receive(report(0x8103, 0, productId and 255,
                (productId ushr 8) and 255, productId ushr 16)).handshake as HandshakeResult.Replace).adapter
            ids.forEach { id ->
                adapter.receive(report(0x8122, 0, 1, 1, 250, 6, id, 1, 65, 1, 62, 0, 0))
                assertFalse("product=$productId preset=$id", adapter.executeControl(
                    OppoControlRequest.UpdateEqualizer(id, listOf(1), OppoEqualizerDefinition(id, "A", -6, 6, listOf(62)))).accepted)
                assertTrue(adapter.executeControl(OppoControlRequest.SelectEqualizer(id)).accepted)
            }
        }
    }
    @Test fun staleCustomDefinitionIsRejectedByAdapterAndSessionEvenWithSameSlotAndBandCount() {
        val initial = OppoEarbudAdapter()
        val adapter = (initial.receive(report(0x8103, 0, 0x10, 0x74, 6)).handshake as HandshakeResult.Replace).adapter
        adapter.receive(report(0x8122, 0, 1, 1, 250, 6, 9, 1, 65, 1, 62, 0, 0))
        val old = adapter.runtimeState().features.get<OppoSettingsFeatureState>()!!.equalizers!!.single().definition()
        val stale = OppoControlRequest.UpdateEqualizer(9, listOf(2), old)
        assertTrue(adapter.executeControl(stale).accepted)
        adapter.receive(report(0x8122, 0, 1, 1, 250, 6, 9, 1, 65, 1, 125, 0, 0))
        assertFalse(adapter.executeControl(stale).accepted)
        assertTrue(adapter.protocolSession.encode(stale).isEmpty())
        val current = adapter.runtimeState().features.get<OppoSettingsFeatureState>()!!.equalizers!!.single().definition()
        assertTrue(adapter.executeControl(stale.copy(expected = current)).accepted)
        for (wrong in listOf(current.copy(name = "old"), current.copy(minimum = -5), current.copy(maximum = 5))) {
            assertTrue(adapter.protocolSession.encode(stale.copy(expected = wrong)).isEmpty())
        }
    }
}
