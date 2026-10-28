package dev.hyperears.runtime

import dev.hyperears.integration.ControlAppSpec
import org.junit.Assert.*
import org.junit.Test

class VendorHandoffLeaseTest {
    private val primary = ControlAppSpec("test.primary", "Primary")
    private val secondary = ControlAppSpec("test.secondary", "Secondary")
    private val apps = listOf(primary, secondary)
    private val lease = VendorHandoffLease()

    @Test fun explicitLaunchYieldsEvenWhenAutomaticYieldIsOffAndExpiresWithoutPresence() {
        lease.begin(primary.packageName, "one", emptySet())
        assertEquals(primary, lease.owner(apps, emptySet(), false))
        lease.presence(emptySet())
        assertNotNull(lease.current)
        assertTrue(lease.expire("one"))
        assertNull(lease.owner(apps, emptySet(), false))
    }

    @Test fun RegisteredPresenceSurvivesGraceButDeathReturnsControl() {
        lease.begin(primary.packageName, "one", emptySet())
        lease.presence(setOf(primary.packageName))
        assertFalse(lease.expire("one"))
        lease.presence(emptySet())
        assertNull(lease.current)
        assertNull(lease.owner(apps, emptySet(), false))
    }

    @Test fun anAlreadyRunningAppIsObservedImmediately() {
        lease.begin(primary.packageName, "one", setOf(primary.packageName))
        assertFalse(lease.expire("one"))
        lease.presence(emptySet())
        assertNull(lease.current)
    }

    @Test fun staleCleanupAndGraceCannotEndANewerLease() {
        lease.begin(primary.packageName, "one", emptySet())
        lease.begin(secondary.packageName, "two", emptySet())
        assertFalse(lease.end("one"))
        assertFalse(lease.expire("one"))
        assertEquals(secondary, lease.owner(apps, emptySet(), false))
    }

    @Test fun explicitReturnWhileAppLivesRespectsTheGlobalPolicy() {
        val active = setOf(primary.packageName, secondary.packageName)
        lease.begin(secondary.packageName, "one", active)
        assertEquals(secondary, lease.owner(apps, active, true))
        assertTrue(lease.end("one"))
        assertNull(lease.owner(apps, active, false))
        assertEquals(primary, lease.owner(apps, active, true))
    }

    @Test fun appDeathFallsBackToAnotherAutomaticOwnerAndCloseClearsTheLease() {
        lease.begin(primary.packageName, "one", setOf(primary.packageName))
        lease.presence(setOf(secondary.packageName))
        assertEquals(secondary, lease.owner(apps, setOf(secondary.packageName), true))
        lease.begin(primary.packageName, "two", emptySet())
        lease.clear()
        assertNull(lease.current)
    }
}
