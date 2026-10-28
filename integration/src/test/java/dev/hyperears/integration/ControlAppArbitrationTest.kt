package dev.hyperears.integration

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class ControlAppArbitrationTest {
    @Test fun appleControllersPreserveAndPodsPriorityWithoutBecomingIdentityEvidence() {
        val controllers = listOf(ControlAppCatalog.andPods, ControlAppCatalog.librePods)
        for (adapter in listOf(AppleAirPodsAdapter(), AppleAirPodsProAdapter(), AppleAirPodsMaxAdapter())) {
            assertEquals(controllers, adapter.controlApps)
            assertEquals(controllers, adapter.snapshot().controlApps)
            assertEquals(ControlAppCatalog.andPods, ControlAppCatalog.activeOwner(adapter.controlApps, controllers.map { it.packageName }.toSet()))
            assertEquals(ControlAppCatalog.librePods, ControlAppCatalog.activeOwner(adapter.controlApps, setOf("me.kavishdevar.librepods")))
            assertNull(ControlAppCatalog.activeOwner(adapter.controlApps, setOf(ControlAppCatalog.heyMelody.packageName)))
            val projection = adapter.snapshot().standardIntegrationProjection()
            assertEquals(controllers, projection.controlApps)
            assertFalse(projection.privateProtocolRequired)
            assertTrue(projection.transportKinds.isEmpty())
            assertTrue(projection.supportedNoiseModes.isEmpty())
            assertNull(projection.presentationId)
        }
        assertEquals("cn.xiaolongonly.andpodsop", ControlAppCatalog.andPods.packageName)
        assertEquals("me.kavishdevar.librepods", ControlAppCatalog.librePods.packageName)
        controllers.forEach { assertEquals(it, ControlAppCatalog.find(it.packageName)) }
        assertFalse(AppleAirPodsAdapter().matches(EarbudIdentity("AndPods", true)))
        assertFalse(AppleAirPodsAdapter().matches(EarbudIdentity("LibrePods", true)))
        assertFalse(AppleAirPodsAdapter().matches(EarbudIdentity("AirPods", true,
            nativeSystemEarbud = true, serviceUuids = setOf(AppleAirPodsAdapter.AAP_SERVICE_UUID))))
        assertTrue(StandardEarbudAdapter().controlApps.isEmpty())
    }

    @Test
    fun originalSamsungManagersParticipateInOrderedOwnershipAndStandardYield() {
        val cases = listOf(
            dev.hyperears.protocol.samsung.SamsungBudsCodec.Model.BUDS to ControlAppCatalog.galaxyBudsManager,
            dev.hyperears.protocol.samsung.SamsungBudsCodec.Model.BUDS_PLUS to ControlAppCatalog.galaxyBudsPlusManager,
        )
        cases.forEach { (model, plugin) ->
            val adapter = SamsungBuds2ProAdapter(model)
            assertEquals(listOf(ControlAppCatalog.galaxyWearable, plugin, ControlAppCatalog.galaxyBudsUnified),adapter.controlApps)
            assertEquals(plugin,ControlAppCatalog.find(plugin.packageName))
            assertEquals(plugin,ControlAppCatalog.activeOwner(adapter.controlApps,setOf(plugin.packageName)))
            assertEquals(plugin,ControlAppCatalog.activeOwner(adapter.controlApps,setOf(plugin.packageName,ControlAppCatalog.galaxyBudsUnified.packageName)))
            assertEquals(ControlAppCatalog.galaxyWearable,ControlAppCatalog.activeOwner(adapter.controlApps,adapter.controlApps.map { it.packageName }.toSet()))
            val other=cases.single { it.first != model }.second
            assertNull(ControlAppCatalog.activeOwner(adapter.controlApps,setOf(other.packageName)))
            adapter.receive(dev.hyperears.protocol.samsung.SamsungBudsCodec.packet(0x61,ByteArray(24).apply {
                this[0]=3; this[2]=80; this[3]=70; this[10]=1; this[11]=if(model.original) 7 else 3; this[13]=0x22
            },model))
            assertTrue(adapter.supportsControl(SamsungDailyControlRequest.SetEqualizer(true,2)))
            val projection=adapter.snapshot().standardIntegrationProjection()
            assertEquals(adapter.id,projection.id); assertEquals(adapter.controlApps,projection.controlApps)
            assertNull(projection.presentationId); assertTrue(projection.transportKinds.isEmpty())
            assertFalse(projection.privateProtocolRequired); assertFalse(projection.capabilities.noiseControl)
            val owned=EarbudState(adapter=projection,lifecycle=DeviceLifecycle(
                systemProfile=SystemProfileState.CONNECTED,privateTransport=PrivateTransportState.NOT_REQUIRED,
                protocolHandshake=ProtocolHandshakeState.NOT_REQUIRED,controlOwnership=ControlOwnership.EXTERNAL_APP,externalControlApp=plugin))
            assertTrue(owned.connected); assertEquals(plugin,owned.lifecycle.externalControlApp)
        }
    }

    @Test
    fun adaptersDeclareOnlyTheirVendorControllerApplications() {
        assertEquals(
            listOf(ControlAppCatalog.vivoEarphones),
            VivoTwsAir3ProAdapter().controlApps,
        )
        assertEquals(
            listOf(
                ControlAppCatalog.heyMelody,
                ControlAppCatalog.oplusWirelessEarphones,
                ControlAppCatalog.colorOsWirelessEarphones,
            ),
            OppoEarbudAdapter().controlApps,
        )
        assertEquals(
            listOf(ControlAppCatalog.sonySoundConnect),
            SonyEarbudAdapter().controlApps,
        )
        assertEquals(
            listOf(ControlAppCatalog.technicsAudioConnect),
            TechnicsEarbudAdapter().controlApps,
        )
        assertEquals(
            listOf(ControlAppCatalog.huaweiSmartAudio, ControlAppCatalog.huaweiAiLife),
            HuaweiFreebudsPro3Adapter().controlApps,
        )
        assertEquals(
            listOf(ControlAppCatalog.moondrop),
            MoondropPuddingAdapter().controlApps,
        )
        assertEquals(
            listOf(ControlAppCatalog.galaxyWearable, ControlAppCatalog.galaxyBuds2ProManager,
                ControlAppCatalog.galaxyBudsUnified),
            SamsungBuds2ProAdapter().controlApps,
        )
        assertEquals(
            listOf(ControlAppCatalog.moondrop),
            MoondropMirageAdapter().controlApps,
        )
        assertTrue(StandardEarbudAdapter().controlApps.isEmpty())
    }

    @Test
    fun activeOwnerPreservesAdapterDeclaredPriority() {
        val candidates = OppoEarbudAdapter().controlApps

        assertEquals(
            ControlAppCatalog.heyMelody,
            ControlAppCatalog.activeOwner(
                candidates,
                setOf(
                    ControlAppCatalog.colorOsWirelessEarphones.packageName,
                    ControlAppCatalog.heyMelody.packageName,
                ),
            ),
        )
        assertNull(ControlAppCatalog.activeOwner(candidates, emptySet()))
    }

    @Test
    fun boseFamiliesAndConcreteModelsInheritTheChinaController() {
        val expected = listOf(
            ControlAppCatalog.bose,
            ControlAppCatalog.boseChina,
            ControlAppCatalog.boseConnect,
        )
        val adapters = listOf(BoseEarbudAdapter(), BoseHeadphonesAdapter()) + BoseBmapModelRegistry.adapters
        adapters.forEach { adapter ->
            assertEquals(adapter.id, expected, adapter.controlApps)
            assertEquals(adapter.id, expected, adapter.snapshot().controlApps)
        }
        assertEquals(ControlAppCatalog.boseChina, ControlAppCatalog.find("com.bose.bosemusic.china"))
        assertNull(ControlAppCatalog.find("com.bose.bosemusic.china.clone"))
    }

    @Test
    fun alternateControllersParticipateWithoutChangingExistingPriority() {
        val cases = listOf(
            BoseHeadphonesAdapter().controlApps to ControlAppCatalog.boseChina,
            EdifierEarbudAdapter().controlApps to ControlAppCatalog.edifierConnex,
            QcyStandardGattAdapter().controlApps to ControlAppCatalog.qcyChina,
            HuaweiFreebudsPro3Adapter().controlApps to ControlAppCatalog.huaweiAiLife,
        )
        cases.forEach { (candidates, alternate) ->
            assertEquals(alternate, ControlAppCatalog.find(alternate.packageName))
            assertEquals(alternate, ControlAppCatalog.activeOwner(candidates, setOf(alternate.packageName)))
            assertEquals(
                candidates.first(),
                ControlAppCatalog.activeOwner(
                    candidates,
                    setOf(candidates.first().packageName, alternate.packageName),
                ),
            )
        }
        assertTrue(StandardEarbudAdapter().controlApps.isEmpty())
    }

    @Test
    fun externalOwnerProjectionKeepsIdentityButRemovesPrivateControls() {
        val original = VivoTwsAir3ProAdapter().snapshot()

        val projection = original.standardIntegrationProjection()

        assertEquals(original.id, projection.id)
        assertEquals(original.formFactor, projection.formFactor)
        assertEquals(original.controlApps, projection.controlApps)
        assertFalse(projection.privateProtocolRequired)
        assertEquals(BatterySource.SYSTEM_AGGREGATE, projection.batterySource)
        assertTrue(projection.capabilities.battery)
        assertTrue(projection.capabilities.audioHandoff)
        assertFalse(projection.capabilities.noiseControl)
        assertTrue(projection.supportedNoiseModes.isEmpty())
        assertNull(projection.presentationId)
        assertTrue(projection.transportKinds.isEmpty())
    }

    @Test
    fun externalAppOwnershipRepresentsAReadyStandardIntegrationSession() {
        val owner = ControlAppCatalog.bose
        val state = EarbudState(
            adapter = BoseEarbudAdapter().snapshot().standardIntegrationProjection(),
            lifecycle = DeviceLifecycle(
                systemProfile = SystemProfileState.CONNECTED,
                privateTransport = PrivateTransportState.NOT_REQUIRED,
                protocolHandshake = ProtocolHandshakeState.NOT_REQUIRED,
                controlOwnership = ControlOwnership.EXTERNAL_APP,
                externalControlApp = owner,
            ),
        )

        assertTrue(state.connected)
        assertFalse(state.privateProtocolRequired)
        assertEquals(owner, state.lifecycle.externalControlApp)
    }
}
