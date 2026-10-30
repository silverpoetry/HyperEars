package dev.hyperears.presentation

import org.junit.Assert.assertEquals
import org.junit.Assert.fail
import org.junit.Test

class NativeAdmissionCacheTest {
    private val address = "02:00:00:00:00:01"

    @Test
    fun missingHostDuringStartupDoesNotPreventLaterConfirmation() {
        val cache = NativeAdmissionCache()
        assertEquals(NativeAdmission.UNKNOWN, cache.read(address, "session") { NativeAdmission.UNKNOWN })
        assertEquals(NativeAdmission.HYPEREARS, cache.read(address, "session") { NativeAdmission.HYPEREARS })
        assertEquals(NativeAdmission.HYPEREARS, cache.read(address, "session") { fail("Repeated read"); NativeAdmission.UNKNOWN })
    }

    @Test
    fun manualPreviewAndNativeRegistrationChangesRecheckOwnership() {
        val cache = NativeAdmissionCache()
        cache.read(address, "session") { NativeAdmission.HYPEREARS }
        assertEquals(NativeAdmission.SYSTEM, cache.read(address, "session", refresh = true) { NativeAdmission.SYSTEM })
        cache.remove(address)
        assertEquals(NativeAdmission.HYPEREARS, cache.read(address, "session") { NativeAdmission.HYPEREARS })
    }

    @Test
    fun failedRefreshAndNewSessionsCannotReuseOldEvidence() {
        val cache = NativeAdmissionCache()
        cache.read(address, "old") { NativeAdmission.HYPEREARS }
        assertEquals(NativeAdmission.UNKNOWN, cache.read(address, "old", refresh = true) { NativeAdmission.UNKNOWN })
        assertEquals(NativeAdmission.SYSTEM, cache.read(address, "old") { NativeAdmission.SYSTEM })
        assertEquals(NativeAdmission.UNKNOWN, cache.read(address, "new") { NativeAdmission.UNKNOWN })
        assertEquals(NativeAdmission.HYPEREARS, cache.read(address, "new") { NativeAdmission.HYPEREARS })
    }
}
