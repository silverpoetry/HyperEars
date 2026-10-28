package dev.hyperears.runtime

import android.app.Application
import android.bluetooth.BluetoothManager
import android.content.Context
import android.os.Binder
import dev.hyperears.bridge.AppleNativeAdmissionContract as Contract
import dev.hyperears.integration.*
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], manifest = Config.NONE, application = Application::class)
class AppleNativeManagerTest {
    private val context: Context get() = RuntimeEnvironment.getApplication()
    private val address = "02:00:00:00:00:01"
    private val identity = EarbudIdentity("AirPods", true, serviceUuids = setOf(AppleAirPodsAdapter.AAP_SERVICE_UUID))
    private fun request(): Contract.Request = shadowOf(RuntimeEnvironment.getApplication()).broadcastIntents
        .last { it.action == Contract.REQUEST }.let { requireNotNull(Contract.read(it)) }
    @Test fun activeAppleControllersNeverOverrideNativeOwnershipOrUnknownDecision() {
        val device = context.getSystemService(BluetoothManager::class.java).adapter.getRemoteDevice(address)
        EarbudConnectionManager(context).use { manager ->
            manager.updateExternalControlEnabled(true)
            manager.updateControlAppPresence(setOf(ControlAppCatalog.andPods.packageName, ControlAppCatalog.librePods.packageName))
            manager.onPhysicalConnected(address)
            assertFalse(manager.observeDevice(device, identity))
            val pending = request()
            assertTrue(manager.snapshots().isEmpty())
            manager.acceptAppleNativeDecision(pending, false, 1)
            val delegated = manager.snapshots().single().state
            assertEquals(ControlOwnership.EXTERNAL_APP, delegated.lifecycle.controlOwnership)
            assertEquals(ControlAppCatalog.andPods, delegated.lifecycle.externalControlApp)
            assertFalse(delegated.privateChannelConnected)
            assertFalse(delegated.privateProtocolRequired)
            manager.acceptAppleNativeDecision(pending, true, 2)
            assertFalse(manager.snapshots().single().state.sessionActive)
            manager.updateControlAppPresence(emptySet())
            manager.updateExternalControlEnabled(false)
            assertFalse(manager.registerDevice(device, identity, AppleAirPodsAdapter()))
            assertFalse(manager.snapshots().single().state.sessionActive)
        }
    }

    @Test fun allRegistrationPathsRemainPendingUntilCurrentAuthenticatedEpochDeclines() {
        val device = context.getSystemService(BluetoothManager::class.java).adapter.getRemoteDevice(address)
        EarbudConnectionManager(context).use { manager ->
            manager.onPhysicalConnected(address)
            assertFalse(manager.observeDevice(device, identity))
            val pending = request()
            assertFalse(manager.registerDevice(device, identity, AppleAirPodsAdapter()))
            manager.updateDisabledAdapters(setOf(AppleAirPodsProAdapter.ID))
            manager.setModulePaused(true)
            manager.setModulePaused(false)
            assertTrue(manager.snapshots().isEmpty())
            manager.acceptAppleNativeDecision(pending.copy(producer = Binder()), false, 1)
            manager.acceptAppleNativeDecision(pending.copy(connection = pending.connection.copy(id = "stale")), false, 2)
            assertTrue(manager.snapshots().isEmpty())
            // Disable private adapters to exercise the real manager/session lifecycle without any socket.
            manager.updateDisabledAdapters(setOf(AppleAirPodsAdapter.ID, AppleAirPodsProAdapter.ID, AppleAirPodsMaxAdapter.ID))
            manager.acceptAppleNativeDecision(pending, false, 3)
            assertTrue(manager.snapshots().single().state.sessionActive)
            assertFalse(manager.registerDevice(device, identity.copy(deviceName = "Other", serviceUuids = emptySet()), AppleAirPodsAdapter()))
            assertFalse(manager.registerDevice(device, identity.copy(standardHeadset = false), AppleAirPodsAdapter()))
            manager.observeDevice(device, identity)
            manager.acceptAppleNativeDecision(pending, true, 4)
            assertFalse(manager.snapshots().single().state.sessionActive)
            assertFalse(manager.registerDevice(device, identity, AppleAirPodsAdapter()))
            manager.acceptAppleNativeDecision(pending, false, 3)
            assertFalse(manager.snapshots().single().state.sessionActive)
            manager.acceptAppleNativeDecision(pending, null, 6)
            manager.acceptAppleNativeDecision(pending, false, 5)
            assertFalse(manager.snapshots().single().state.sessionActive)
        }
    }
    @Test fun disconnectAndNewProducerOrTokenNeverReuseBluetoothSideDecline() {
        val device = context.getSystemService(BluetoothManager::class.java).adapter.getRemoteDevice(address)
        EarbudConnectionManager(context).use { manager ->
            manager.onPhysicalConnected(address)
            manager.observeDevice(device, identity)
            val old = request()
            manager.unregisterDevice(device)
            manager.onPhysicalConnected(address)
            manager.observeDevice(device, identity)
            assertNotEquals(old.connection.id, request().connection.id)
            manager.acceptAppleNativeDecision(old, false, 5)
            assertTrue(manager.snapshots().isEmpty())
        }
    }
    @Test fun failedRequestSubmissionCanRetryOnTheNextExistingEvent() {
        var attempts = 0
        val wrapped = object : android.content.ContextWrapper(context) {
            override fun getApplicationContext(): Context = this
            override fun sendBroadcast(intent: android.content.Intent, receiverPermission: String?, options: android.os.Bundle?) {
                if (intent.action == Contract.REQUEST && ++attempts == 1) throw IllegalStateException("temporary send failure")
                super.sendBroadcast(intent, receiverPermission, options)
            }
        }
        val device = context.getSystemService(BluetoothManager::class.java).adapter.getRemoteDevice(address)
        EarbudConnectionManager(wrapped).use { manager ->
            manager.onPhysicalConnected(address)
            assertFalse(manager.observeDevice(device, identity))
            assertEquals(1, attempts)
            assertFalse(manager.observeDevice(device, identity))
            assertEquals(2, attempts)
            assertEquals(address, request().address)
        }
    }

    @Test fun connectionObservedWhilePausedStillRequiresCurrentDecisionOnResume() {
        val device = context.getSystemService(BluetoothManager::class.java).adapter.getRemoteDevice(address)
        EarbudConnectionManager(context).use { manager ->
            manager.setModulePaused(true)
            manager.onPhysicalConnected(address)
            manager.updateDisabledAdapters(setOf(AppleAirPodsAdapter.ID, AppleAirPodsProAdapter.ID, AppleAirPodsMaxAdapter.ID))
            manager.observeDevice(device, identity)
            val pending = request()
            manager.acceptAppleNativeDecision(pending, false, 1)
            assertTrue(manager.snapshots().isEmpty())
            manager.setModulePaused(false)
            assertTrue(manager.snapshots().single().state.sessionActive)
        }
    }

    @Test fun earlyAndBetweenConnectionAddressOnlyClaimsCannotStrandAppleAdmission() {
        val device = context.getSystemService(BluetoothManager::class.java).adapter.getRemoteDevice(address)
        EarbudConnectionManager(context).use { manager ->
            manager.updateDisabledAdapters(setOf(AppleAirPodsAdapter.ID, AppleAirPodsProAdapter.ID, AppleAirPodsMaxAdapter.ID))
            manager.claimSystemOwnership(address)
            manager.onPhysicalConnected(address)
            manager.observeDevice(device, identity)
            val first = request()
            manager.acceptAppleNativeDecision(first, false, 1)
            assertTrue(manager.snapshots().single().state.sessionActive)
            assertTrue(manager.snapshots().single().presentation!!.connectionScopedOwnership)
            manager.unregisterDevice(device)
            assertTrue(manager.snapshots().single().presentation!!.connectionScopedOwnership)
            manager.claimSystemOwnership(address)
            manager.onPhysicalConnected(address)
            manager.observeDevice(device, identity)
            val second = request()
            assertNotEquals(first.connection, second.connection)
            manager.acceptAppleNativeDecision(second, false, 2)
            assertTrue(manager.snapshots().single().state.sessionActive)
        }
    }

    @Test fun nativeHostRestartClosesFallbackAndRetiresPriorReplyGeneration() {
        val device = context.getSystemService(BluetoothManager::class.java).adapter.getRemoteDevice(address)
        EarbudConnectionManager(context).use { manager ->
            manager.onPhysicalConnected(address)
            manager.updateDisabledAdapters(setOf(AppleAirPodsAdapter.ID, AppleAirPodsProAdapter.ID, AppleAirPodsMaxAdapter.ID))
            manager.observeDevice(device, identity)
            val old = request()
            manager.acceptAppleNativeDecision(old, false, 1)
            assertTrue(manager.snapshots().single().state.sessionActive)
            manager.requestAppleNativeDecisions()
            assertFalse(manager.snapshots().single().state.sessionActive)
            val fresh = request()
            assertEquals(old.connection, fresh.connection)
            assertNotEquals(old.query, fresh.query)
            manager.acceptAppleNativeDecision(old, false, 100)
            assertFalse(manager.snapshots().single().state.sessionActive)
            manager.acceptAppleNativeDecision(fresh, false, 101)
            assertTrue(manager.snapshots().single().state.sessionActive)
        }
    }

    @Test fun nameOnlyDeclineDoesNotAuthorizePrivateAdapterOrXiaomiReservation() {
        val device = context.getSystemService(BluetoothManager::class.java).adapter.getRemoteDevice(address)
        EarbudConnectionManager(context).use { manager ->
            manager.onPhysicalConnected(address)
            val nameOnly = identity.copy(serviceUuids = emptySet())
            manager.observeDevice(device, nameOnly)
            manager.acceptAppleNativeDecision(request(), false, 1)
            assertFalse(manager.registerDevice(device, nameOnly, AppleAirPodsAdapter()))
            assertFalse(manager.registerDevice(device, identity.copy(nativeSystemEarbud = true), AppleAirPodsAdapter()))
            assertTrue(manager.snapshots().isEmpty())
        }
    }
}
