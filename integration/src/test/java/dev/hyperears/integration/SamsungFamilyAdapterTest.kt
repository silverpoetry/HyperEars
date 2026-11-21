package dev.hyperears.integration

import dev.hyperears.protocol.samsung.SamsungBudsCodec
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
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
            listOf("Galaxy $name", "Samsung Galaxy $name", number).forEach { alias ->
                val adapter = EarbudAdapterRegistry.resolve(EarbudIdentity(alias, true))!!
                assertEquals(alias, expected, adapter.id)
                assertFalse(adapter.supportsControl(StandardControlRequest.SetNoiseMode(NoiseMode.ANC)))
                assertNull(adapter.miLinkCardPresentationId)
            }
            val customized = EarbudAdapterRegistry.resolve(EarbudIdentity(
                "我的 $name", true,
                serviceUuids = setOf(SamsungBuds2ProAdapter.BUDS2_PRO_UUID),
            ))!!
            assertEquals(expected, customized.id)
        }
    }

    @Test
    fun genericBudsNamesWithoutSamsungIdentityStayOutsideSamsungCandidates() {
        listOf("CMF Buds Pro", "CMF Buds 2", "Redmi Buds 3 Pro", "Other Buds FE", "我的 Buds2 Pro")
            .forEach { name ->
                val identity = EarbudIdentity(name, true)
                SamsungBudsCodec.Model.entries.forEach { model ->
                    assertFalse(name + model, SamsungBuds2ProAdapter(model).matches(identity))
                }
            }
        val adapter = EarbudAdapterRegistry.resolve(EarbudIdentity("CMF Buds Pro", true))!!
        assertEquals(StandardEarbudAdapter.ID, adapter.id)
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
    fun eachProfileExposesOnlyItsStandardNoiseModesAfterConfirmation() {
        SamsungBudsCodec.Model.entries.forEach { model ->
            val adapter = ready(model)
            val modes = if (model.ambient) setOf(NoiseMode.ANC, NoiseMode.OFF, NoiseMode.TRANSPARENCY)
                else setOf(NoiseMode.ANC, NoiseMode.OFF)
            NoiseMode.entries.forEach { mode ->
                assertEquals(model.name, mode in modes, adapter.supportsControl(StandardControlRequest.SetNoiseMode(mode)))
            }
            assertNull(adapter.miLinkCardPresentationId)
            adapter.resetProtocolSession()
            assertFalse(adapter.supportsControl(StandardControlRequest.SetNoiseMode(NoiseMode.ANC)))
        }
    }

    private fun ack(id: Int, vararg parameters: Int) = SamsungBudsCodec.packet(0x42,
        byteArrayOf(id.toByte()) + parameters.map(Int::toByte).toByteArray())
    private fun ready(model: SamsungBudsCodec.Model) = SamsungBuds2ProAdapter(model).apply {
        val updateId = if (model == SamsungBudsCodec.Model.BUDS_LIVE) 0x9B else 0x77
        receive(SamsungBudsCodec.packet(updateId, byteArrayOf(1)))
    }
}
