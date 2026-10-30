package dev.hyperears.presentation

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class NativePresentationDecisionTest {
    @Test
    fun freshLocalConfirmationCannotBypassCanonicalStateOrSystemOwnership() {
        assertEquals(NativePresentationResult.SYNC_PENDING,
            previewAdmissionResult(NativeAdmission.HYPEREARS, NativeAdmission.UNKNOWN))
        assertEquals(NativePresentationResult.OWNERSHIP_UNAVAILABLE,
            previewAdmissionResult(NativeAdmission.UNKNOWN, NativeAdmission.HYPEREARS))
        for (other in NativeAdmission.entries) {
            assertEquals(NativePresentationResult.SYSTEM_DEVICE, previewAdmissionResult(NativeAdmission.SYSTEM, other))
            assertEquals(NativePresentationResult.SYSTEM_DEVICE, previewAdmissionResult(other, NativeAdmission.SYSTEM))
        }
        assertNull(previewAdmissionResult(NativeAdmission.HYPEREARS, NativeAdmission.HYPEREARS))
    }

    @Test
    fun closingANativeWindowAllowsManualPreviewButStillSuppressesAutomaticDuplicates() {
        val visible = NativeFlowState(showing = true, shownBefore = true)
        assertTrue(visible.blocks(manual = true))
        assertTrue(visible.blocks(manual = false))
        val closed = visible.copy(showing = false)
        assertFalse(closed.blocks(manual = true))
        assertTrue(closed.blocks(manual = false))
    }

    @Test
    fun manualPreviewDoesNotInterruptAnActiveCirculationRequest() {
        for (shownBefore in listOf(false, true)) {
            val pending = NativeFlowState(connecting = true, shownBefore = shownBefore)
            assertTrue(pending.blocks(manual = true))
            assertTrue(pending.blocks(manual = false))
            assertFalse(pending.copy(connecting = false).blocks(manual = true))
        }
    }
}
