package dev.hyperears.integration

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class ControlAppArbitrationTest {
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
            listOf(ControlAppCatalog.huaweiSmartAudio),
            HuaweiFreebudsPro3Adapter().controlApps,
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
