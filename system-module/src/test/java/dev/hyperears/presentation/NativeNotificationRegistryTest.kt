package dev.hyperears.presentation

import dev.hyperears.integration.*
import org.junit.Assert.*
import org.junit.Test

class NativeNotificationRegistryTest {
    private val address = "02:00:00:00:00:01"
    private val connection = "connection-1"
    private val aggregate = NativeHeadsetProjection(
        address, "vivo TWS 4", HeadsetFormFactor.TWS, HeadsetArtwork.EARBUDS, true,
        BatteryReading(100, false), BatteryReading(null, false), BatteryReading(null, false),
    )
    private val components = aggregate.copy(singleBattery = false,
        left = BatteryReading(92, true), right = BatteryReading(89, false),
        chargingCase = BatteryReading(72, true))

    @Test
    fun firstClickAndComponentUpdateWorkBeforeAndroidReturnsTheVisiblePost() {
        val registry = NativeNotificationRegistry()
        val first = requireNotNull(registry.prepare(aggregate, connection, null))
        registry.posted(first)
        assertSame(first, registry.forClick(address, first.token))

        // notify() has returned but activeNotifications still reports nothing.
        val update = requireNotNull(registry.prepare(components, connection, null))
        assertEquals(first.token, update.token)
        registry.posted(update)
        assertEquals(components, registry.forClick(address, first.token)?.projection)

        // Observing an older visible revision does not invalidate the shared action identity.
        assertNull(registry.prepare(components, connection, NativeNotificationRegistry.Visible(first.token)))
    }

    @Test
    fun clearedNotificationCanBeRepublishedAndItsClickDoesNotRequireAVisiblePost() {
        val registry = NativeNotificationRegistry()
        val first = requireNotNull(registry.prepare(aggregate, connection, null))
        registry.posted(first)
        assertNull(registry.prepare(aggregate, connection, NativeNotificationRegistry.Visible(first.token)))
        val repost = requireNotNull(registry.prepare(aggregate, connection, null))
        registry.posted(repost)
        assertEquals(first.token, repost.token)
        assertSame(repost, registry.forClick(address, first.token))
    }

    @Test
    fun nativeNotificationIsNeitherReplacedNorCancelled() {
        val registry = NativeNotificationRegistry()
        val native = NativeNotificationRegistry.Visible(null)
        assertNull(registry.prepare(aggregate, connection, native))
        val first = requireNotNull(registry.prepare(aggregate, connection, null))
        registry.posted(first)
        assertNull(registry.prepare(components, connection, native))
        assertNull(registry.retire(address, native))
        assertNull(registry.forClick(address, first.token))
    }

    @Test
    fun unpostedBuildDoesNotAuthorizeClicksOrCleanup() {
        val registry = NativeNotificationRegistry()
        val notPosted = requireNotNull(registry.prepare(aggregate, connection, null))
        assertNull(registry.forClick(address, notPosted.token))
        assertNull(registry.retire(address, null))
    }

    @Test
    fun pendingNotificationIsCancelledOnDisconnectAndOldClickCannotTargetReconnect() {
        val registry = NativeNotificationRegistry()
        val first = requireNotNull(registry.prepare(aggregate, connection, null))
        registry.posted(first)
        assertSame(first, registry.retire(address, null))
        assertNull(registry.forClick(address, first.token))

        val reconnected = requireNotNull(registry.prepare(components, "connection-2", null))
        registry.posted(reconnected)
        assertNotEquals(first.token, reconnected.token)
        assertNull(registry.forClick(address, first.token))
        assertSame(reconnected, registry.forClick(address, reconnected.token))
    }

    @Test
    fun reconnectAndHostRestartCanReplaceAnOlderMarkedPostWithoutTouchingAnotherDevice() {
        val registry = NativeNotificationRegistry()
        val old = NativeNotificationRegistry.Visible("token-from-previous-host")
        val first = requireNotNull(registry.prepare(aggregate, connection, old))
        registry.posted(first)
        val other = requireNotNull(registry.prepare(aggregate.copy(address = "02:00:00:00:00:02"), connection, null))
        registry.posted(other)
        assertNull(registry.forClick(other.projection.address, first.token))
        assertSame(first, registry.retire(address, old))
        assertSame(other, registry.forClick(other.projection.address, other.token))
    }

    @Test
    fun vivoProtocolEvidenceUpdatesTheSameNotificationWithoutInventingOrFreezing100Percent() {
        val adapter = VivoEarbudAdapter()
        val registry = NativeNotificationRegistry()
        fun projection() = requireNotNull(NativeHeadsetProjection.from(EarbudState(
            adapter = adapter.snapshot(), address = address, deviceName = "vivo TWS 4",
            lifecycle = DeviceLifecycle(systemProfile = SystemProfileState.CONNECTED),
            features = adapter.runtimeState().features,
        )))
        adapter.onSystemBatteryChanged(100)
        val first = requireNotNull(registry.prepare(projection(), connection, null))
        registry.posted(first)
        assertTrue(first.projection.singleBattery)
        assertEquals(100, first.projection.left.percent)
        assertNull(first.projection.right.percent)

        // Existing vivo GAIA battery fixture; this is a replay, not a new TWS 4 hardware capture.
        adapter.receive("FF 03 00 05 00 1B 82 07 00 5C 59 48 05".split(' ').map { it.toInt(16).toByte() }.toByteArray())
        val update = requireNotNull(registry.prepare(projection(), connection, NativeNotificationRegistry.Visible(first.token)))
        registry.posted(update)
        assertEquals(first.token, update.token)
        assertFalse(update.projection.singleBattery)
        assertEquals(listOf(92, 89, 72), listOf(update.projection.left.percent,
            update.projection.right.percent, update.projection.chargingCase.percent))
        assertSame(update, registry.forClick(address, first.token))
    }
}
