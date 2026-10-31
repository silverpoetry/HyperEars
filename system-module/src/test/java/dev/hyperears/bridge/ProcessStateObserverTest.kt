package dev.hyperears.bridge

import android.app.Application
import dev.hyperears.integration.DeviceLifecycle
import dev.hyperears.integration.EarbudState
import dev.hyperears.integration.SystemProfileState
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], manifest = Config.NONE, application = Application::class)
class ProcessStateObserverTest {
    @Test fun observersSeeCommittedStateAndIgnoreStaleOrUnsubscribedUpdates() {
        ProcessStateStore.clear()
        val address = "02:00:00:00:00:01"
        val state = EarbudState(address = address, revision = 2,
            lifecycle = DeviceLifecycle(systemProfile = SystemProfileState.CONNECTED))
        val observed = mutableListOf<Long>()
        val stop = ProcessStateStore.observe(address) { accepted ->
            assertEquals("session", ProcessStateStore.sessionToken(address))
            assertEquals(accepted.revision, ProcessStateStore.snapshot(address).revision)
            observed += accepted.revision
        }
        try {
            ProcessStateStore.accept(ModuleContract.stateChanged(state, "session", ModuleContract.MILINK_PACKAGE))
            ProcessStateStore.accept(ModuleContract.stateChanged(state.copy(revision = 1), "session", ModuleContract.MILINK_PACKAGE))
            assertEquals(listOf(2L), observed)
            stop()
            ProcessStateStore.accept(ModuleContract.stateChanged(state.copy(revision = 3), "session", ModuleContract.MILINK_PACKAGE))
            assertEquals(listOf(2L), observed)
        } finally { stop(); ProcessStateStore.clear() }
    }
}
