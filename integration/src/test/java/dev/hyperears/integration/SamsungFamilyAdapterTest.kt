package dev.hyperears.integration

import dev.hyperears.protocol.samsung.SamsungBudsCodec
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class SamsungFamilyAdapterTest {
    @Test
    fun retailAndCustomizedNamesAndModelNumbersSelectSpecificCandidates() {
        val models = mapOf(
            "Buds Live" to "SM-R180", "Buds Pro" to "SM-R190", "Buds2" to "SM-R177",
            "Buds2 Pro" to "SM-R510", "Buds FE" to "SM-R400", "Buds Core" to "SM-R410",
            "Buds3" to "SM-R530", "Buds3 Pro" to "SM-R630", "Buds3 FE" to "SM-R420",
            "Buds4" to "SM-R540", "Buds4 Pro" to "SM-R640",
        )
        models.forEach { (name, number) ->
            val expected = "samsung-galaxy-" + name.lowercase().replace(' ', '-')
            listOf("Galaxy $name", "我的 $name", number).forEach { alias ->
                val adapter = EarbudAdapterRegistry.resolve(EarbudIdentity(alias, true))!!
                assertEquals(alias, expected, adapter.id)
                assertFalse(adapter.supportsControl(StandardControlRequest.SetNoiseMode(NoiseMode.ANC)))
                assertFalse(adapter.supportsControl(actions()))
                assertNull(adapter.miLinkCardPresentationId)
            }
        }
    }

    @Test
    fun legacyModelsChooseStandardSppAndDoNotBroadenGenericSppMatching() {
        listOf(SamsungBudsCodec.Model.BUDS_LIVE, SamsungBudsCodec.Model.BUDS_PRO).forEach { model ->
            val endpoint = SamsungBuds2ProAdapter(model).transports.single() as RfcommEndpointSpec.ServiceUuid
            assertEquals(SamsungBuds2ProAdapter.STANDARD_SPP_UUID, endpoint.uuid)
        }
        val unknown = EarbudAdapterRegistry.resolve(EarbudIdentity("Unrelated SPP headset", true,
            serviceUuids = setOf(SamsungBuds2ProAdapter.STANDARD_SPP_UUID)))!!
        assertFalse(unknown is SamsungBuds2ProAdapter)
    }

    @Test
    fun everyKnownModelUnlocksHoldAfterLegalReportAndSubmitsImmediatelyWithoutUnsupportedCycles() {
        SamsungBudsCodec.Model.entries.filter { it != SamsungBudsCodec.Model.UNKNOWN }.forEach { model ->
            val adapter = ready(model)
            assertTrue(model.name, adapter.supportsControl(actions()))
            assertTrue(adapter.supportsControl(StandardControlRequest.SetNoiseMode(NoiseMode.ANC)))
            assertEquals(0L, adapter.controlPolicy(actions()).commandGapMs)
            val result = adapter.executeControl(actions())
            assertTrue(result.accepted)
            assertEquals(0x92, SamsungBudsCodec.parseFrame(result.commands.first())!!.id)
            if (model.commonHoldOnly || model.legacyTouch) assertEquals(1, result.commands.size)
            val feature = adapter.runtimeState().features.get<SamsungBudsSettingsFeatureState>()!!
            assertEquals(SamsungTouchAction.NOISE_CONTROL, feature.touchHoldLeftAction)
            assertEquals(SamsungTouchAction.VOICE_ASSISTANT, feature.displayedLeftAction)
            assertTrue(feature.touchHoldActionsPending)
        }
    }

    @Test
    fun newProfilesRejectUnresearchedOptionalRequestsAndCycles() {
        SamsungBudsCodec.Model.entries.filter { it.commonHoldOnly }.forEach { model ->
            val adapter = ready(model)
            listOf(
                SamsungControlRequest.SetVoiceDetect(true), SamsungControlRequest.SetOutsideDoubleTap(true),
                SamsungControlRequest.SetTouchGesture(SamsungTouchGesture.DOUBLE_TAP, false),
                SamsungControlRequest.SetTouchHoldNoiseCycles(SamsungNoiseCycle.ANC_OFF, SamsungNoiseCycle.ANC_OFF),
                SamsungControlRequest.SetExtraHighAmbient(true), SamsungControlRequest.SetAmbientVolume(2),
                SamsungControlRequest.SetEqualizer(true, 2), SamsungControlRequest.SetSidetone(true),
                SamsungControlRequest.SetSeamlessConnection(true), SamsungControlRequest.SetNoiseControlsWithOneEarbud(true),
            ).forEach { request ->
                assertFalse(model.name + request, adapter.supportsControl(request))
                assertFalse(adapter.executeControl(request).accepted)
            }
        }
    }

    @Test
    fun liveNoiseUsesBooleanCommandAndOnlyItsOwnAckConfirmsIt() {
        val adapter = ready(SamsungBudsCodec.Model.BUDS_LIVE)
        assertFalse(adapter.supportsControl(StandardControlRequest.SetNoiseMode(NoiseMode.TRANSPARENCY)))
        val frame = SamsungBudsCodec.parseFrame(adapter.executeControl(StandardControlRequest.SetNoiseMode(NoiseMode.OFF)).commands.single())!!
        assertEquals(0x98, frame.id)
        assertArrayEquals(byteArrayOf(0), frame.payload)
        adapter.receive(ack(0x78, 0))
        assertEquals(NoiseMode.ANC, adapter.runtimeState().noiseMode)
        adapter.receive(ack(0x98, 0))
        assertEquals(NoiseMode.OFF, adapter.runtimeState().noiseMode)
        adapter.receive(ack(0x98, 2))
        assertEquals(NoiseMode.OFF, adapter.runtimeState().noiseMode)
    }

    @Test
    fun legacyLockAndUnlockEchoUseNonInvertedBoolean() {
        listOf(SamsungBudsCodec.Model.BUDS_LIVE, SamsungBudsCodec.Model.BUDS_PRO).forEach { model ->
            val adapter = ready(model)
            val lock = adapter.executeControl(SamsungControlRequest.SetTouchpadLock(true))
            assertArrayEquals(byteArrayOf(1), SamsungBudsCodec.parseFrame(lock.commands.first())!!.payload)
            adapter.receive(ack(0x90, 1))
            assertTrue(adapter.runtimeState().features.get<SamsungBudsSettingsFeatureState>()!!.touchpadLocked)
            val unlock = adapter.executeControl(SamsungControlRequest.SetTouchpadLock(false))
            assertArrayEquals(byteArrayOf(0), SamsungBudsCodec.parseFrame(unlock.commands.first())!!.payload)
            adapter.receive(ack(0x90, 0))
            assertFalse(adapter.runtimeState().features.get<SamsungBudsSettingsFeatureState>()!!.touchpadLocked)
        }
    }

    @Test
    fun validActionAckConfirmsEachModelAndResetRemovesPrivateFeatures() {
        SamsungBudsCodec.Model.entries.filter { it != SamsungBudsCodec.Model.UNKNOWN }.forEach { model ->
            val adapter = ready(model)
            adapter.executeControl(actions())
            adapter.receive(ack(0x92, 1, 2))
            assertFalse(adapter.runtimeState().features.get<SamsungBudsSettingsFeatureState>()!!.touchHoldActionsPending)
            assertEquals(SamsungTouchAction.VOICE_ASSISTANT,
                adapter.runtimeState().features.get<SamsungBudsSettingsFeatureState>()!!.touchHoldLeftAction)
            adapter.resetProtocolSession()
            assertNull(adapter.runtimeState().features.get<SamsungBudsSettingsFeatureState>())
            assertFalse(adapter.supportsControl(actions()))
        }
    }

    @Test
    fun familyFallbackNeverGuessesHoldFormatOrOldStandardService() {
        val adapter = ready(SamsungBudsCodec.Model.UNKNOWN)
        assertFalse(adapter.supportsControl(actions()))
        assertNull(adapter.miLinkCardPresentationId)
        assertTrue(adapter.supportsControl(StandardControlRequest.SetNoiseMode(NoiseMode.ANC)))
    }

    @Test
    fun samsungControllerPriorityAndUnifiedOwnerAreDeclaredPerModel() {
        SamsungBudsCodec.Model.entries.filter { it != SamsungBudsCodec.Model.UNKNOWN }.forEach { model ->
            val apps = SamsungBuds2ProAdapter(model).controlApps
            assertEquals(ControlAppCatalog.galaxyWearable, apps.first())
            assertEquals(ControlAppCatalog.galaxyBudsUnified, apps.last())
            assertEquals(apps.first(), ControlAppCatalog.activeOwner(apps, apps.map { it.packageName }.toSet()))
            assertEquals(apps.last(), ControlAppCatalog.activeOwner(apps, setOf(apps.last().packageName)))
            if (apps.size == 3) assertEquals(apps[1], ControlAppCatalog.activeOwner(apps, setOf(apps[1].packageName)))
        }
    }

    @Test
    fun privateSettingsFlagsRoundTripAndDoNotPromoteCapabilitiesOnAck() {
        val adapter = ready(SamsungBudsCodec.Model.BUDS4_PRO)
        val original = adapter.runtimeState().features
        assertEquals(original, FeatureStateTransport.decode(FeatureStateTransport.encode(original)))
        adapter.receive(ack(0x79, 1, 1, 0, 1, 0, 1))
        assertFalse(adapter.supportsControl(SamsungControlRequest.SetTouchHoldNoiseCycles(
            SamsungNoiseCycle.ANC_OFF, SamsungNoiseCycle.ANC_OFF)))
    }

    private fun actions() = SamsungControlRequest.SetTouchHoldActions(
        SamsungTouchAction.VOICE_ASSISTANT, SamsungTouchAction.NOISE_CONTROL,
    )
    private fun ack(id: Int, vararg parameters: Int) = SamsungBudsCodec.packet(0x42,
        byteArrayOf(id.toByte()) + parameters.map(Int::toByte).toByteArray())
    private fun ready(model: SamsungBudsCodec.Model) = SamsungBuds2ProAdapter(model).apply {
        receive(SamsungBudsCodec.packet(0x61, ByteArray(46).apply {
            this[0] = 13; this[2] = 80; this[3] = 90; this[7] = 70
            this[10] = if (model.legacyTouch) 0 else 0xBF.toByte()
            this[11] = 0x22; this[12] = 1; this[21] = 0x65
        }))
    }
}
