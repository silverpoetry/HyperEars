package dev.hyperears.hook

import android.app.Application
import android.content.ContextWrapper
import android.content.Intent
import android.os.Binder
import android.os.Bundle
import com.xiaomi.mxbluetoothsdk.service.MxBluetoothService
import dev.hyperears.bridge.*
import dev.hyperears.integration.*
import dev.hyperears.presentation.*
import org.junit.Assert.*
import org.junit.After
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], manifest = Config.NONE, application = Application::class)
class MiLinkAppleOwnershipReconciliationTest {
    private val hook = MiLinkServiceHook()
    private val producer = Binder()
    private val context = RecordingContext()
    private val service = MxBluetoothService()
    private val address = "02:00:00:00:00:01"
    @Before fun setup() {
        ProcessStateStore.clear(); ModuleRuntimeGate.update(false)
        hook.appClassLoader = javaClass.classLoader!!
        hook.packageName = ModuleContract.MILINK_PACKAGE
        field("context").set(hook, context)
        MxBluetoothService.mMxBluetoothService = service
        service.aroundOriginal = { device, original ->
            invoke("invokeNativeHeadsetAdmission", service.javaClass.name, service, device, original) as Int
        }
    }
    @After fun cleanup() { ProcessStateStore.clear(); ModuleRuntimeGate.update(false) }
    @Test fun staleSystemOwnerRequeriesAndRestoresPresentationAndCardLookup() {
        ownership().claimSystemOwnership(address)
        val callback = RuntimeCallback()
        service.mCallbacks += callback
        accept("fresh", 1, "physical-1")
        assertEquals(1, service.queries)
        assertEquals(MiLinkDeviceOwnershipRegistry.Owner.HYPEREARS, ownership().owner(address))
        assertNotNull(invoke("adapterForAddress", address))
        assertEquals(AppleMiLinkPresentationIds.LISTENING,
            (invoke("adapterForAddress", address) as AdapterSnapshot).presentationId)
        assertTrue(context.sent.any { it.action == NativePresentationContract.ACTION_ADMISSION })
        assertTrue(callback.identityUpdates > 0)
        assertTrue(invoke("nativeCardAdapter", invoke("adapterForAddress", address)!!) is AppleListeningMiLinkCardAdapter)
        invoke("handleSystemOwnershipClaim", context,
            ModuleContract.systemOwnershipClaimed(address, ModuleContract.MILINK_PACKAGE),
            ModuleContract.MILINK_PACKAGE, -1)
        assertFalse(ownership().isSystemOwned(address))
    }
    @Test fun freshNativePositiveStaysOwnedAcrossLogicalSessionReplacementButNotNewConnection() {
        service.original = 1
        accept("first", 1, "physical-1")
        assertTrue(ownership().isSystemOwned(address))
        service.original = 0
        accept("replacement", 1, "physical-1")
        assertTrue(ownership().isSystemOwned(address))
        assertEquals(1, service.queries)
        accept("next", 1, "physical-2")
        assertFalse(ownership().isSystemOwned(address))
        assertEquals(2, service.queries)
        val stale = NativePresentationContract.attach(
            ModuleContract.systemOwnershipClaimed(address, ModuleContract.MILINK_PACKAGE),
            metadata("physical-1"), producer)
        invoke("handleSystemOwnershipClaim", context, stale, ModuleContract.MILINK_PACKAGE, -1)
        assertFalse(ownership().isSystemOwned(address))
        val current = NativePresentationContract.attach(
            ModuleContract.systemOwnershipClaimed(address, ModuleContract.MILINK_PACKAGE),
            metadata("physical-2"), producer)
        invoke("handleSystemOwnershipClaim", context, current, ModuleContract.MILINK_PACKAGE, -1)
        assertTrue(ownership().isSystemOwned(address))
    }
    @Test fun retiredPhysicalProducerCannotReplaceCurrentAcceptedState() {
        accept("old", 1, "physical-1")
        accept("new", 1, "physical-2", source = Binder())
        accept("unseen-late-token", 99, "physical-1")
        assertEquals("new", ProcessStateStore.sessionToken(address))
        assertEquals(2, service.queries)
        assertFalse(ownership().isSystemOwned(address))
    }

    @Test fun originalNativeResultCrossingPhysicalReplacementCannotClaimTheNewConnection() {
        accept("old", 1, "physical-1")
        val device = context.getSystemService(android.bluetooth.BluetoothManager::class.java).adapter.getRemoteDevice(address)
        val original: () -> Any? = { accept("next", 1, "physical-2"); 1 }
        assertEquals(1, invoke("invokeNativeHeadsetAdmission", service.javaClass.name, service, device, original))
        assertFalse(ownership().isSystemOwned(address))
        assertEquals("next", ProcessStateStore.sessionToken(address))
    }

    @Test fun scopedPositiveBeforeFirstLocalStateSurvivesMatchingReplay() {
        val positive = NativePresentationContract.attach(
            ModuleContract.systemOwnershipClaimed(address, ModuleContract.MILINK_PACKAGE),
            metadata("physical-1"), producer)
        invoke("handleSystemOwnershipClaim", context, positive, ModuleContract.MILINK_PACKAGE, -1)
        service.mBluetoothHeadsetService = null
        accept("first", 1, "physical-1")
        assertTrue(ownership().isSystemOwned(address))
        assertEquals(0, service.queries)
        assertNull(invoke("adapterForAddress", address))
    }

    @Test fun staleScopeCannotCommitOwnershipOrDeliveryAfterPhysicalRebinding() {
        val old = MiLinkDeviceOwnershipRegistry.ConnectionScope(producer, metadata("physical-1").connection)
        val fresh = MiLinkDeviceOwnershipRegistry.ConnectionScope(producer, metadata("physical-2").connection)
        ownership().bindConnectionScope(address, old)
        ownership().bindAcceptedConnectionScope(address, fresh) { true }
        var delivered = false
        assertNull(ownership().withCurrentConnectionScope(address, old) {
            ownership().claimSystemOwnership(address)
            delivered = true
        })
        assertFalse(delivered)
        assertFalse(ownership().isSystemOwned(address))
    }

    @Test fun standardFallbackKeepsExplicitAppleConnectionScope() {
        ownership().claimSystemOwnership(address)
        accept("standard", 1, "physical-1", StandardEarbudAdapter().snapshot())
        assertEquals(1, service.queries)
        assertFalse(ownership().isSystemOwned(address))
    }
    private fun metadata(connection: String) = NativePresentationMetadata(
        HeadsetConnection(connection, if (connection == "physical-1") 10 else 20),
        connectionScopedOwnership = true)
    private fun accept(token: String, revision: Long, connection: String,
        adapter: AdapterSnapshot = AppleAirPodsAdapter().snapshot().copy(presentationId = AppleMiLinkPresentationIds.LISTENING),
        source: android.os.IBinder = producer) {
        val state = EarbudState(address = address, adapter = adapter, revision = revision,
            lifecycle = DeviceLifecycle(systemProfile = SystemProfileState.CONNECTED))
            .withFeature(BatteryFeatureState(EarbudBattery.fromAggregate(50)))
        invoke("handleStateChanged", NativePresentationContract.attach(
            ModuleContract.stateChanged(state, token, ModuleContract.MILINK_PACKAGE), metadata(connection), source))
    }
    private fun ownership() = field("deviceOwnership").get(hook) as MiLinkDeviceOwnershipRegistry
    private fun field(name: String) = MiLinkServiceHook::class.java.getDeclaredField(name).apply { isAccessible = true }
    private fun invoke(name: String, vararg args: Any): Any? = MiLinkServiceHook::class.java.declaredMethods
        .single { it.name == name }.apply { isAccessible = true }.invoke(hook, *args)
    private class RuntimeCallback {
        var identityUpdates = 0
        @Suppress("UNUSED_PARAMETER")
        fun onDeviceIdUpdate(device: android.bluetooth.BluetoothDevice, id: String) { identityUpdates++ }
    }
    private class RecordingContext : ContextWrapper(RuntimeEnvironment.getApplication()) {
        val sent = mutableListOf<Intent>()
        override fun sendBroadcast(intent: Intent, receiverPermission: String?, options: Bundle?) { sent += Intent(intent) }
        override fun sendBroadcast(intent: Intent) { sent += Intent(intent) }
    }
}
