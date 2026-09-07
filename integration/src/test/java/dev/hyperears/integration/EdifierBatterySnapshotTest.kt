package dev.hyperears.integration

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class EdifierBatterySnapshotTest {
    @Test
    fun unavailableComponentsReplaceOldReadingsAcrossTwsAdapters() {
        listOf(EdifierEarbudAdapter(), EdifierFitClipUltraAdapter(), EdifierEvoProAdapter()).forEach { adapter ->
            adapter.beginHandshake()
            adapter.receive(report(80, 90, 70, 1))
            assertEquals(80, adapter.runtimeState().battery.left.percent)

            val changed = adapter.receive(report(0, 0, 60, 2))
            assertTrue(changed.stateChanged)
            assertEquals(
                EarbudBattery(case = BatteryReading(60, false)),
                adapter.runtimeState().battery,
            )

            adapter.receive(report(0, 0, 60, 3))
            assertEquals(EarbudBattery(), adapter.runtimeState().battery)

            adapter.receive(report(81, 91, 0, 2))
            val restored = adapter.runtimeState().battery
            assertEquals(81, restored.left.percent)
            assertEquals(91, restored.right.percent)
            assertEquals(0, restored.case.percent)

            adapter.receive(report(255, 90, 60, 1))
            assertEquals(restored, adapter.runtimeState().battery)
        }
    }

    private fun report(left: Int, right: Int, case: Int, state: Int): ByteArray {
        val body = byteArrayOf(0xBB.toByte(), 0xEC.toByte(), 0xF2.toByte(), 0, 6) +
            listOf(3, left, right, case, state, 0x11).map { (it xor 0xA5).toByte() }.toByteArray()
        return body + body.sumOf { it.toInt() and 0xFF }.toByte()
    }
}
