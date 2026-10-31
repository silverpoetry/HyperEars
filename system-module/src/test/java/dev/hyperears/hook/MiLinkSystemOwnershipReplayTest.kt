package dev.hyperears.hook

import android.app.Application
import android.content.ContextWrapper
import android.content.Intent
import android.os.Bundle
import dev.hyperears.bridge.ModuleContract
import dev.hyperears.bridge.ModuleRuntimeGate
import dev.hyperears.bridge.ProcessStateStore
import dev.hyperears.integration.DeviceLifecycle
import dev.hyperears.integration.EarbudState
import dev.hyperears.integration.SystemProfileState
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], manifest = Config.NONE, application = Application::class)
class MiLinkSystemOwnershipReplayTest {
    private lateinit var hook: MiLinkServiceHook
    private lateinit var context: RecordingContext

    @Before
    fun setUp() {
        ProcessStateStore.clear()
        ModuleRuntimeGate.update(false)
        hook = MiLinkServiceHook()
        context = RecordingContext()
        field("context").set(hook, context)
        ownership().claimSystemOwnership(ADDRESS)
    }

    @After
    fun tearDown() {
        ProcessStateStore.clear()
        ModuleRuntimeGate.update(false)
    }

    @Test
    fun survivingMiLinkReplaysClaimForNewBluetoothSessionOnly() {
        ProcessStateStore.accept(stateIntent("old", 1))
        invoke("publishSystemOwnershipClaim", ADDRESS)
        assertEquals(2, context.sent.size)

        accept("old", 2)
        assertEquals(2, context.sent.size)
        accept("new", 1)
        assertEquals(4, context.sent.size)
        accept("new", 2)
        accept("old", 99) // Retired sessions must never cause replay.
        assertEquals(4, context.sent.size)
        assertEquals(
            listOf(ModuleContract.MILINK_PACKAGE, ModuleContract.BLUETOOTH_PACKAGE),
            context.sent.takeLast(2).map { it.`package` },
        )
    }

    @Test
    fun initialAuthoritativeClaimWithoutSessionIsPreservedButInactiveUpdatesDoNotReplay() {
        invoke("publishSystemOwnershipClaim", ADDRESS)
        assertEquals(2, context.sent.size)
        accept("inactive", 1, active = false)
        invoke("handleStateChanged", with(ModuleContract) {
            Intent(ACTION_STATE_CHANGED).putState(EarbudState(address = ADDRESS, revision = 1))
        })
        invoke("handleStateChanged", stateIntent("", 1))
        assertEquals(2, context.sent.size)
        accept("active", 1)
        assertEquals(4, context.sent.size)
    }

    @Test
    fun failedReplayCanRetryOnNextAcceptedUpdate() {
        context.failBluetooth = true
        accept("session", 1)
        assertEquals(1, context.sent.size)
        context.failBluetooth = false
        accept("session", 2)
        assertEquals(3, context.sent.size)
        accept("session", 3)
        invoke("flushPendingSystemOwnershipClaims")
        assertEquals(3, context.sent.size)
    }

    @Test
    fun moduleResetClearsClaimDeliveryHistory() {
        accept("session", 1)
        assertEquals(2, context.sent.size)
        invoke("clearModuleState")
        ownership().claimSystemOwnership(ADDRESS)
        accept("session", 1)
        assertEquals(4, context.sent.size)
    }

    @Test
    fun pendingInitialClaimSurvivesMissingContextAndMissingSession() {
        field("context").set(hook, null)
        invoke("publishSystemOwnershipClaim", ADDRESS)
        assertEquals(0, context.sent.size)
        field("context").set(hook, context)
        invoke("flushPendingSystemOwnershipClaims")
        assertEquals(2, context.sent.size)
        invoke("flushPendingSystemOwnershipClaims")
        assertEquals(2, context.sent.size)
    }

    @Test
    fun pendingReplayDoesNotBroadcastAfterSessionEnds() {
        context.failBluetooth = true
        accept("session", 1)
        assertEquals(1, context.sent.size)
        accept("session", 2, active = false)
        context.failBluetooth = false
        invoke("flushPendingSystemOwnershipClaims")
        assertEquals(1, context.sent.size)
        accept("next", 1)
        assertEquals(3, context.sent.size)
    }

    @Test
    fun contextRecoveryFlushesPendingReplayOnce() {
        field("context").set(hook, null)
        accept("session", 1)
        assertEquals(0, context.sent.size)
        field("context").set(hook, context)
        invoke("flushPendingSystemOwnershipClaims")
        assertEquals(2, context.sent.size)
        accept("session", 2)
        assertEquals(2, context.sent.size)
    }

    private fun accept(token: String, revision: Long, active: Boolean = true) =
        invoke("handleStateChanged", stateIntent(token, revision, active))

    private fun stateIntent(token: String, revision: Long, active: Boolean = true): Intent =
        ModuleContract.stateChanged(
            EarbudState(
                address = ADDRESS,
                revision = revision,
                lifecycle = DeviceLifecycle(systemProfile = if (active) {
                    SystemProfileState.CONNECTED
                } else {
                    SystemProfileState.DISCONNECTED
                }),
            ),
            token,
            ModuleContract.MILINK_PACKAGE,
        )

    private fun ownership() = field("deviceOwnership").get(hook) as MiLinkDeviceOwnershipRegistry

    private fun field(name: String) = MiLinkServiceHook::class.java.getDeclaredField(name)
        .apply { isAccessible = true }

    private fun invoke(name: String, vararg args: Any) {
        MiLinkServiceHook::class.java.declaredMethods.single { it.name == name }
            .apply { isAccessible = true }.invoke(hook, *args)
    }

    private class RecordingContext : ContextWrapper(RuntimeEnvironment.getApplication()) {
        val sent = mutableListOf<Intent>()
        var failBluetooth = false

        override fun sendBroadcast(intent: Intent, receiverPermission: String?, options: Bundle?) {
            if (failBluetooth && intent.`package` == ModuleContract.BLUETOOTH_PACKAGE) {
                error("Bluetooth broadcast delivery failed")
            }
            sent += Intent(intent)
        }
    }

    private companion object {
        const val ADDRESS = "AA:BB:CC:DD:EE:FF"
    }
}
