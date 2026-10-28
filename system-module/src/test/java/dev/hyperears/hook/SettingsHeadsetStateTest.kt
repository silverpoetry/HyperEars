package dev.hyperears.hook

import android.os.Binder
import android.os.IBinder
import dev.hyperears.bridge.*
import dev.hyperears.integration.*
import dev.hyperears.presentation.*
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], manifest = Config.NONE)
class SettingsHeadsetStateTest {
    private val address = "00:11:22:33:44:55"
    private val producer = Binder()
    private val first = HeadsetConnection("first", 100)
    private fun state(revision: Long = 1) = EarbudState(address = address, revision = revision,
        lifecycle = DeviceLifecycle(systemProfile = SystemProfileState.CONNECTED))
    private fun intent(state: EarbudState = state(), token: String = "token", connection: HeadsetConnection = first,
        admission: NativeAdmission = NativeAdmission.HYPEREARS, binder: IBinder = producer) =
        NativePresentationContract.attach(ModuleContract.stateChanged(state, token, ModuleContract.SETTINGS_PACKAGE),
            NativePresentationMetadata(connection, admission), binder)
    @Test fun requiresProducerMetadataAndExplicitAdmission() {
        val cache = SettingsHeadsetState()
        assertFalse(cache.accept(ModuleContract.stateChanged(state(), "token", ModuleContract.SETTINGS_PACKAGE)))
        assertNull(cache.record(address))
        assertTrue(cache.accept(intent(admission = NativeAdmission.UNKNOWN)))
        assertNull(cache.record(address))
        assertTrue(cache.accept(intent(state(2))))
        assertNotNull(cache.record(address.lowercase()))
        assertNull(cache.record("another"))
    }
    @Test fun nativeOwnershipSticksOnlyForItsPhysicalConnection() {
        val cache = SettingsHeadsetState()
        cache.accept(intent()); assertNotNull(cache.record(address))
        cache.accept(intent(state(2), admission = NativeAdmission.SYSTEM)); assertNull(cache.record(address))
        cache.accept(intent(state(3))); assertNull(cache.record(address))
        cache.accept(intent(state(4), "new", HeadsetConnection("next", 200))); assertNotNull(cache.record(address))
        assertFalse(cache.accept(intent(state(100), "old-late"))); assertEquals("new", cache.record(address)?.token)
    }
    @Test fun staleRevisionSessionExternalOwnerAndDisconnectNeverAuthorize() {
        val cache = SettingsHeadsetState()
        cache.accept(intent(state(5)))
        assertFalse(cache.accept(intent(state(4))))
        cache.accept(intent(state(6), "new"))
        assertFalse(cache.accept(intent(state(10))))
        cache.accept(intent(state(7).copy(lifecycle = state().lifecycle.copy(controlOwnership = ControlOwnership.EXTERNAL_APP, externalControlApp = ControlAppSpec("test", "Test"))), "new"))
        assertNull(cache.record(address))
        cache.accept(intent(state(8).copy(lifecycle = DeviceLifecycle()), "new"))
        assertNull(cache.record(address))
        cache.clear(); assertNull(cache.record(address))
    }
    @Test fun producerDeathRevokesAlreadyAcceptedRecords() {
        var alive = true
        val binder = object : IBinder by producer { override fun isBinderAlive() = alive }
        val cache = SettingsHeadsetState()
        cache.accept(intent(binder = binder)); assertNotNull(cache.record(address))
        alive = false
        assertNull(cache.record(address)); assertFalse(cache.accept(intent(state(2), binder = binder)))
    }
    @Test fun advancedEnvironmentCannotOutliveTheFragmentsAddress() {
        val record = SettingsHeadsetState.Record(state(), "token", NativePresentationMetadata(first, NativeAdmission.HYPEREARS), producer)
        var page = address
        fun accepted(target: String = address) = settingsPageRecord(address, target, { page }, { record })
        assertSame(record, accepted())
        assertNull(accepted("another"))
        page = "another"
        assertNull(accepted())
        assertNull(settingsPageRecord(address, address, { error("detached fragment") }, { record }))
        assertNull(settingsPageRecord(address, address, { address }, { record.copy(state = state().copy(address = "another")) }))
    }
    @Test fun settingsIsAnExplicitStateReplyConsumer() {
        assertTrue(ModuleContract.SETTINGS_PACKAGE in ModuleContract.stateConsumerPackages)
        val request = ModuleContract.requestState(ModuleContract.SETTINGS_PACKAGE)
        assertEquals(ModuleContract.SETTINGS_PACKAGE, with(ModuleContract) { request.readReplyPackage() })
    }
}
