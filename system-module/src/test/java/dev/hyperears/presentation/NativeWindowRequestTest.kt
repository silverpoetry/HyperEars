package dev.hyperears.presentation

import android.os.IBinder
import dev.hyperears.bridge.DeviceStateRegistry
import dev.hyperears.integration.BatteryFeatureState
import dev.hyperears.integration.BatteryReading
import dev.hyperears.integration.BatterySource
import dev.hyperears.integration.DeviceLifecycle
import dev.hyperears.integration.EarbudBattery
import dev.hyperears.integration.EarbudState
import dev.hyperears.integration.StandardEarbudAdapter
import dev.hyperears.integration.SystemProfileState
import dev.hyperears.integration.withFeature
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Test
import java.lang.reflect.Proxy

class NativeWindowRequestTest {
    private val producer = binder()
    private val metadata =
        NativePresentationMetadata(
            HeadsetConnection("connection-1", 100),
            NativeAdmission.HYPEREARS,
        )

    private fun request(
        percent: Int?,
        revision: Long = 1,
    ) = NativeWindowRequest(
        id = "window-1",
        state =
            EarbudState(
                adapter = StandardEarbudAdapter().snapshot(),
                address = "02:00:00:00:00:01",
                revision = revision,
                lifecycle = DeviceLifecycle(systemProfile = SystemProfileState.CONNECTED),
            ).withFeature(BatteryFeatureState(EarbudBattery.fromAggregate(percent))),
        session = "session-1",
        metadata = metadata,
        producer = producer,
    )

    @Test
    fun batteryAcceptedAfterLaunchIsUsedBeforeTheFirstHostRead() {
        val launched = request(null)
        val accepted = request(100, revision = 2)

        val bound = requireNotNull(launched.forBinding(accepted, producer, hasAcceptedState = true))

        assertEquals(100, requireNotNull(NativeHeadsetProjection.from(bound.state)).left.percent)
        assertEquals("100", bound.stateArray()[0])
        assertEquals(2L, bound.state.revision)
        assertEquals("window-1", bound.id)
    }

    @Test
    fun missingConsumerSnapshotRetainsTheValidatedLaunchRequest() {
        for (percent in listOf(null, 0, 100)) {
            val launched = request(percent)
            assertSame(launched, launched.forBinding(null, null, hasAcceptedState = false))
            assertSame(launched, launched.forBinding(null, producer, hasAcceptedState = false))
        }
    }

    @Test
    fun knownLifecycleWithoutAnEligibleRecordCannotReopenTheLaunchSnapshot() {
        val launched = request(100)
        val registry = DeviceStateRegistry()
        registry.accept(launched.state, launched.session)
        registry.accept(launched.state.copy(revision = 2, lifecycle = DeviceLifecycle()), launched.session)

        assertNull(registry.state(launched.state.address!!))
        assertNull(
            launched.forBinding(
                null,
                producer,
                hasAcceptedState = registry.knownState(launched.state.address!!) != null,
            ),
        )
    }

    @Test
    fun nativeOwnershipWithoutAnEligibleRecordCannotFallBackToTheLaunchSnapshot() {
        val launched = request(100)
        val system = launched.copy(metadata = metadata.copy(admission = NativeAdmission.SYSTEM))
        assertNull(launched.forBinding(system, producer, hasAcceptedState = true))
        assertNull(launched.forBinding(null, producer, hasAcceptedState = true))
    }

    @Test
    fun producerReplacementRejectsAnOldTicketEvenWithoutACachedAddress() {
        val launched = request(100)
        val replacement = binder()
        assertNull(launched.forBinding(null, replacement, hasAcceptedState = false))
        assertNull(launched.forBinding(launched, replacement, hasAcceptedState = true))
    }

    @Test
    fun ineligibleLaunchRequestsAreNeverUsedAsFallbacks() {
        val launched = request(100)
        val invalid =
            listOf(
                launched.copy(state = launched.state.copy(lifecycle = DeviceLifecycle())),
                launched.copy(metadata = metadata.copy(admission = NativeAdmission.SYSTEM)),
                launched.copy(metadata = metadata.copy(admission = NativeAdmission.UNKNOWN)),
            )
        for (ticket in invalid) {
            assertNull(ticket.forBinding(null, producer, hasAcceptedState = false))
        }
    }

    @Test
    fun identityAndOwnershipChangesRejectBindingAndLiveUpdatesRegardlessOfRevision() {
        val launched = request(100)
        for (revision in listOf(0L, 2L)) {
            val next = request(0, revision)
            val invalid =
                listOf(
                    next.copy(session = "session-2"),
                    next.copy(producer = binder()),
                    next.copy(state = next.state.copy(address = "02:00:00:00:00:02")),
                    next.copy(metadata = metadata.copy(connection = HeadsetConnection("connection-2", 100))),
                    next.copy(metadata = metadata.copy(connection = HeadsetConnection("connection-1", 200))),
                    next.copy(metadata = metadata.copy(admission = NativeAdmission.SYSTEM)),
                    next.copy(metadata = metadata.copy(admission = NativeAdmission.UNKNOWN)),
                    next.copy(state = next.state.copy(lifecycle = DeviceLifecycle())),
                )
            for (candidate in invalid) {
                assertNull(launched.forBinding(candidate, producer, hasAcceptedState = true))
                assertNull(
                    launched.updated(
                        candidate.state,
                        candidate.session,
                        candidate.metadata,
                        candidate.producer,
                    ),
                )
            }
        }
    }

    @Test
    fun olderOrEqualRevisionsCannotRollBackAWindowBeforeOrAfterBinding() {
        val latest = request(100, revision = 3)
        for (revision in listOf(1L, 3L)) {
            val old = request(null, revision)
            assertSame(latest, latest.forBinding(old, producer, hasAcceptedState = true))
            assertSame(latest, latest.updated(old.state, old.session, old.metadata, old.producer))
        }
    }

    @Test
    fun updatesBeforeAndAfterTheFirstHostReadUseTheSameMonotonicSnapshot() {
        val launched = request(null)
        val accepted = request(100, revision = 2)
        val bound = requireNotNull(launched.forBinding(accepted, producer, hasAcceptedState = true))
        val beforeCallback = request(0, revision = 3)
        val readable =
            requireNotNull(
                bound.updated(
                    beforeCallback.state,
                    beforeCallback.session,
                    beforeCallback.metadata,
                    beforeCallback.producer,
                ),
            )
        assertEquals("0", readable.stateArray()[0])

        val afterDisplay = request(75, revision = 4)
        val displayed =
            requireNotNull(
                readable.updated(
                    afterDisplay.state,
                    afterDisplay.session,
                    afterDisplay.metadata,
                    afterDisplay.producer,
                ),
            )
        assertEquals("75", displayed.stateArray()[0])
        assertSame(
            displayed,
            displayed.updated(
                accepted.state,
                accepted.session,
                accepted.metadata,
                accepted.producer,
            ),
        )
    }

    @Test
    fun consumerAdmissionRejectsOutOfOrderAndRetiredSessionSnapshots() {
        val registry = DeviceStateRegistry()
        val launched = request(null)
        registry.accept(launched.state, launched.session)
        val latest = request(100, revision = 3)
        registry.accept(latest.state, latest.session)
        assertNull(registry.accept(request(0, revision = 2).state, launched.session))
        val bound =
            requireNotNull(
                launched.forBinding(
                    latest.copy(state = registry.state(launched.state.address!!)!!),
                    producer,
                    hasAcceptedState = true,
                ),
            )
        assertEquals("100", bound.stateArray()[0])

        val reconnected =
            request(0).copy(
                session = "session-2",
                metadata = metadata.copy(connection = HeadsetConnection("connection-2", 200)),
            )
        registry.accept(reconnected.state, reconnected.session)
        assertNull(registry.accept(request(100, revision = 10).state, launched.session))
        assertNull(bound.forBinding(reconnected, producer, hasAcceptedState = true))
    }

    @Test
    fun systemAggregate100ZeroAndUnknownRemainSingleBatteryForStandardAdapter() {
        for ((percent, expected) in listOf(100 to "100", 0 to "0", null to "-")) {
            val launched = request(null)
            val latest = request(percent, revision = 2)
            val bound = requireNotNull(launched.forBinding(latest, producer, hasAcceptedState = true))
            assertEquals(listOf(expected, "-", "-"), bound.stateArray().take(3))
            assertEquals(listOf("false", "false", "false"), bound.stateArray().slice(5..7))
            assertFalse(requireNotNull(NativeHeadsetProjection.from(bound.state)).canShowConnectionHint)
        }
    }

    @Test
    fun componentUpdatesPreserveIndependentZeroUnknownAndChargingReadings() {
        val launched =
            request(null).let {
                it.copy(
                    state =
                        it.state.copy(
                            adapter =
                                it.state.adapter!!.copy(batterySource = BatterySource.PRIVATE_PROTOCOL),
                        ),
                )
            }
        val battery =
            EarbudBattery(
                left = BatteryReading(0, true),
                right = BatteryReading(100, false),
                case = BatteryReading(null, false),
            )
        val latest =
            launched.copy(
                state =
                    launched.state
                        .copy(revision = 2)
                        .withFeature(BatteryFeatureState(battery)),
                id = "another-ticket",
            )
        val bound = requireNotNull(launched.forBinding(latest, producer, hasAcceptedState = true))
        assertEquals("window-1", bound.id)
        assertEquals(
            listOf("0", "100", "-", "false", "false", "true", "false", "false"),
            bound.stateArray().take(8),
        )
        assertEquals(battery, bound.state.battery)

        val caseKnown =
            bound.state
                .copy(revision = 3)
                .withFeature(BatteryFeatureState(battery.copy(case = BatteryReading(0, true))))
        val updated = requireNotNull(bound.updated(caseKnown, bound.session, bound.metadata, producer))
        assertEquals(
            listOf("0", "100", "0", "false", "false", "true", "false", "true"),
            updated.stateArray().take(8),
        )
    }

    private fun binder(): IBinder =
        Proxy.newProxyInstance(
            IBinder::class.java.classLoader,
            arrayOf(IBinder::class.java),
        ) { instance, method, args ->
            when (method.name) {
                "equals" -> instance === args?.get(0)
                "hashCode" -> System.identityHashCode(instance)
                "isBinderAlive" -> true
                "toString" -> "TestProducer"
                else -> error("Unexpected Binder operation: ${method.name}")
            }
        } as IBinder
}
