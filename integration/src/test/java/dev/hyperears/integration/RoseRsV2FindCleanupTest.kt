package dev.hyperears.integration

import dev.hyperears.protocol.rose.RoseRsV2IdentityCodec.Model
import org.junit.Assert.*
import org.junit.Test

class RoseRsV2FindCleanupTest {
    private fun adapter(model: Model): EarbudAdapter {
        if (model.existingAdapterId == null) return RoseRsV2Adapter(model)
        val prefix = model.prefix.chunked(2).map { it.toInt(16) }
        return requireNotNull(EarbudAdapterRegistry.resolve(EarbudIdentity(model.label, true,
            manufacturerData = mapOf((prefix[0] or (prefix[1] shl 8)) to prefix.drop(2)))))
    }
    private fun report(type: Int, value: Int): ByteArray {
        val body = byteArrayOf(0xDD.toByte(), 0, 2, type.toByte(), value.toByte())
        return body + byteArrayOf(body.sumOf { it.toInt() and 255 }.toByte(), 0xAA.toByte())
    }
    private fun start(a: EarbudAdapter): AdapterEffect.ScheduleProtocolCleanup {
        val request = RoseRsV2ControlRequest.Find(3)
        assertTrue(a.executeControl(request).accepted)
        return a.controlWritten(request).filterIsInstance<AdapterEffect.ScheduleProtocolCleanup>().single()
    }
    @Test fun everyQualifiedFindProfileOwnsOnlyWrittenActionsIncludingOldAdapterIds() {
        for (model in Model.entries.filter { it.findSupported }) {
            val a = adapter(model)
            assertEquals(model.id, a.id)
            a.receive(report(0x2F, 3))
            assertTrue(a.prepareProtocolRelease().isEmpty())
            assertTrue(a.executeControl(RoseRsV2ControlRequest.Find(3)).accepted)
            assertTrue(a.prepareProtocolRelease().isEmpty())
            val lease = start(a)
            assertEquals(30_000L, lease.delayMs)
            assertTrue(a.expireProtocolWorkflow(lease.key, lease.lease + 1).isEmpty())
            val stop = a.expireProtocolWorkflow(lease.key, lease.lease).single()
            assertArrayEquals(byteArrayOf(0x2F, 4), stop.copyOfRange(3, stop.size - 2))
            assertTrue(a.prepareProtocolRelease().isEmpty())
            assertEquals(true, a.runtimeState().features.get<RoseRsV2SettingsFeatureState>()!!.findLeft)
        }
    }
    @Test fun unrelatedMergedReportsCannotCancelAnOwnedDeadlineWithCachedStoppedValues() {
        val a = adapter(Model.CERAMICS_MK2)
        a.receive(report(0x2F, 4))
        val lease = start(a)
        assertFalse(a.receive(report(0x0E, 1)).effects.any { it is AdapterEffect.CancelProtocolCleanup })
        assertEquals(1, a.expireProtocolWorkflow(lease.key, lease.lease).size)
        start(a)
        a.receive(report(0x2F, 3))
        assertTrue(a.receive(report(0x2F, 4)).effects.any { it is AdapterEffect.CancelProtocolCleanup })
        assertTrue(a.prepareProtocolRelease().isEmpty())
    }
    @Test fun explicitStopResetReplacementAndGracefulReleaseConsumeOnlyTheirLease() {
        val a = adapter(Model.FURINA_SOURCE)
        a.receive(report(0x2F, 4))
        val first = start(a); val second = start(a)
        assertTrue(a.expireProtocolWorkflow(first.key, first.lease).isEmpty())
        a.controlWritten(RoseRsV2ControlRequest.Find(4))
        assertTrue(a.expireProtocolWorkflow(second.key, second.lease).isEmpty())
        start(a)
        assertEquals(1, a.prepareProtocolRelease().size)
        assertTrue(a.prepareProtocolRelease().isEmpty())
        val old = start(a); a.resetProtocolSession()
        assertTrue(a.expireProtocolWorkflow(old.key, old.lease).isEmpty())
        a.receive(report(0x2F, 4)); val fresh = start(a)
        assertNotEquals(old.lease, fresh.lease)
        assertTrue(a.expireProtocolWorkflow(old.key, old.lease).isEmpty())
    }
    @Test fun unsupportedAndNameOnlyProfilesNeverGainFindOwnership() {
        val devices = listOf(adapter(Model.MOKA), RoseBudsFeelMk2Adapter(), FurinaEndlessAdapter())
        for (a in devices) {
            a.receive(report(0x2F, 3))
            assertFalse(a.executeControl(RoseRsV2ControlRequest.Find(3)).accepted)
            assertTrue(a.prepareProtocolRelease().isEmpty())
        }
    }
}
