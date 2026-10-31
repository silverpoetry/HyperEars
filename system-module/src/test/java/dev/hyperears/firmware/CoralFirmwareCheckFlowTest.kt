package dev.hyperears.firmware

import org.junit.Assert.*
import org.junit.Test

class CoralFirmwareCheckFlowTest {
    @Test fun duplicateRequestsAreCoalescedAndClosedOwnerReceivesNothing() {
        val worker = mutableListOf<() -> Unit>()
        val ui = mutableListOf<() -> Unit>()
        var callbacks = 0
        var token: CoralFirmwareCancellation? = null
        val flow = CoralFirmwareCheckFlow(worker::add, ui::add) { _, _, cancellation ->
            token = cancellation
            error("offline")
        }
        assertTrue(flow.start(50, "zh", { true }) { callbacks++ })
        assertFalse(flow.start(50, "zh", { true }) { callbacks++ })
        worker.single().invoke()
        flow.close()
        ui.single().invoke()
        assertEquals(0, callbacks)
        assertThrows(java.util.concurrent.CancellationException::class.java) { token!!.check() }
        assertFalse(flow.start(50, "zh", { true }) { callbacks++ })
    }

    @Test fun changedFirmwareDiscardsStaleResultAndAllowsNewExplicitCheck() {
        val worker = mutableListOf<() -> Unit>()
        var callbacks = 0
        val flow = CoralFirmwareCheckFlow(worker::add, { it() }) { _, _, _ -> error("offline") }
        flow.start(50, "zh", { false }) { callbacks++ }
        worker.removeAt(0).invoke()
        assertEquals(0, callbacks)
        assertFalse(flow.busy)
        flow.start(51, "zh", { true }) { assertTrue(it.isFailure); callbacks++ }
        worker.removeAt(0).invoke()
        assertEquals(1, callbacks)
    }
}
