package dev.hyperears.integration

import dev.hyperears.protocol.samsung.SamsungBudsCodec
import org.junit.Assert.*
import org.junit.Test

class OwnedFindCleanupTest {
    @Test fun coralOnlyWrittenLocalFindArmsDeadlineAndExpiryIsOnce() {
        val adapter = RoseCoralAdapter()
        adapter.receive(RoseCoralAdapterTest.report(2, 0x2F, 3))
        assertTrue(adapter.prepareProtocolRelease().isEmpty()) // A reported ring is not ours.
        val start = RoseCoralControlRequest.SetFind(RoseCoralFindTarget.BOTH)
        assertTrue(adapter.executeControl(start).accepted)
        assertTrue(adapter.prepareProtocolRelease().isEmpty()) // Encoding is not a completed write.
        val effect = adapter.controlWritten(start).single() as AdapterEffect.ScheduleProtocolCleanup
        assertEquals(30_000L, effect.delayMs)
        assertTrue(adapter.expireProtocolWorkflow(effect.key, effect.lease + 1).isEmpty())
        val command = adapter.expireProtocolWorkflow(effect.key, effect.lease).single()
        assertArrayEquals(byteArrayOf(0x2F, 4), command.copyOfRange(3, command.size - 2))
        assertTrue(adapter.expireProtocolWorkflow(effect.key, effect.lease).isEmpty())
        assertEquals(RoseCoralFindFeatureState(true, true), adapter.runtimeState().features.get<RoseCoralFindFeatureState>())
    }

    @Test fun coralNewRequestInvalidatesOldDeadlineAndStopReportCancels() {
        val adapter = RoseCoralAdapter()
        adapter.receive(RoseCoralAdapterTest.report(2, 0x2F, 4))
        val request = RoseCoralControlRequest.SetFind(RoseCoralFindTarget.LEFT)
        fun start(): AdapterEffect.ScheduleProtocolCleanup {
            assertTrue(adapter.executeControl(request).accepted)
            return adapter.controlWritten(request).single() as AdapterEffect.ScheduleProtocolCleanup
        }
        val first = start(); val second = start()
        assertTrue(adapter.expireProtocolWorkflow(first.key, first.lease).isEmpty())
        val stopped = adapter.receive(RoseCoralAdapterTest.report(2, 0x2F, 4))
        assertTrue(AdapterEffect.CancelProtocolCleanup(second.key) in stopped.effects)
        assertTrue(adapter.prepareProtocolRelease().isEmpty())
        start()
        assertEquals(1, adapter.prepareProtocolRelease().size)
        assertTrue(adapter.prepareProtocolRelease().isEmpty())
        val final = start(); adapter.resetProtocolSession()
        assertTrue(adapter.expireProtocolWorkflow(final.key, final.lease).isEmpty())
    }

    @Test fun samsungRingingIsIndependentFromFitDeadlineAndNeverStoppedWithoutLocalWrite() {
        val adapter = SamsungBuds2ProAdapter()
        val payload = ByteArray(50).apply { this[0] = 13; this[2] = 80; this[3] = 70; this[9] = 3 }
        adapter.receive(SamsungBudsCodec.packet(0x61, payload))
        assertTrue(adapter.prepareProtocolRelease().isEmpty())
        val start = SamsungDailyControlRequest.Find(true)
        assertTrue(adapter.executeControl(start).accepted)
        val effect = adapter.controlWritten(start).filterIsInstance<AdapterEffect.ScheduleProtocolCleanup>().single()
        assertEquals("samsung.find", effect.key)
        assertTrue(adapter.expireProtocolWorkflow("samsung.fit", effect.lease).isEmpty())
        val stop = adapter.expireProtocolWorkflow(effect.key, effect.lease).single()
        assertEquals(0xA1, SamsungBudsCodec.parseFrame(stop)!!.id)
        assertNull(adapter.runtimeState().features.get<SamsungDailySettingsFeatureState>()!!.findStopped)
        assertTrue(adapter.prepareProtocolRelease().isEmpty())
        adapter.executeControl(start); adapter.controlWritten(start)
        adapter.receive(SamsungBudsCodec.packet(0xA1))
        assertTrue(adapter.prepareProtocolRelease().isEmpty())
        adapter.executeControl(start); adapter.controlWritten(start)
        assertEquals(1, adapter.prepareProtocolRelease().size)
        assertTrue(adapter.prepareProtocolRelease().isEmpty())
    }
}
