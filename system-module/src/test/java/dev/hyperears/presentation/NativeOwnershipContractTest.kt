package dev.hyperears.presentation

import org.junit.Assert.*
import org.junit.Test

class NativeOwnershipContractTest {
    @Test
    fun absentContractKeepsAcceptedActiveSessionOnHyperEars() {
        assertEquals(NativeAdmission.HYPEREARS,
            fallbackAdmission(OwnershipContractState.MISSING, milinkAccepted = true))
        assertEquals(NativeAdmission.HYPEREARS,
            ownershipAdmission(reading = null, contract = OwnershipContractState.MISSING, milinkAccepted = true))
    }

    @Test
    fun absentContractCannotClaimASessionMiLinkHasNotAccepted() {
        assertEquals(NativeAdmission.UNKNOWN,
            fallbackAdmission(OwnershipContractState.MISSING, milinkAccepted = false))
        assertEquals(NativeAdmission.UNKNOWN,
            ownershipAdmission(reading = null, contract = OwnershipContractState.MISSING, milinkAccepted = false))
    }

    @Test
    fun unverifiedAndResolvedContractsStayUnknown() {
        for (contract in listOf(OwnershipContractState.RESOLVED, OwnershipContractState.MISMATCH)) {
            assertEquals(contract.name, NativeAdmission.UNKNOWN,
                fallbackAdmission(contract, milinkAccepted = true))
            assertEquals(contract.name, NativeAdmission.UNKNOWN,
                ownershipAdmission(reading = null, contract = contract, milinkAccepted = true))
        }
    }

    /** The store's own readings decide ownership whenever they exist, including its own unknown. */
    @Test
    fun everyReadAdmissionPassesThroughUnchanged() {
        for (contract in OwnershipContractState.entries) {
            for (accepted in listOf(false, true)) {
                for (reading in NativeAdmission.entries) {
                    assertEquals("$contract/$accepted", reading,
                        ownershipAdmission(reading = reading, contract = contract, milinkAccepted = accepted))
                }
            }
        }
    }

    @Test
    fun classNotFoundIsAnAbsentContractEvenWhenWrapped() {
        assertEquals(OwnershipContractState.MISSING,
            ownershipContractStateOf(ClassNotFoundException(
                "com.android.bluetooth.ble.app.headset.BluetoothHeadsetService\$HeadsetBinder")))
        assertEquals(OwnershipContractState.MISSING, ownershipContractStateOf(ClassNotFoundException(
            "com.android.bluetooth.ble.app.headset.FragmentHandler")))
        val wrapped = RuntimeException("native ownership resolve",
            IllegalStateException("member lookup", ClassNotFoundException("FragmentHandler")))
        assertEquals(OwnershipContractState.MISSING, ownershipContractStateOf(wrapped))
    }

    @Test
    fun contractShapeFailuresStayUnexpectedContracts() {
        val failures: List<Throwable> = listOf(
            IllegalArgumentException("Unexpected native ownership contract: isMiTWS"),
            IllegalArgumentException("Unverified native registration store"),
            NoSuchMethodException("BluetoothHeadsetService\$HeadsetBinder.checkIsAirPods"),
            NoSuchFieldException("FragmentHandler.HEADSET_DEVICE_INFO"),
            ClassCastException("HEADSET_DEVICE_INFO is not a String"),
            // Only the explicit Class.forName failure proves the data plane is absent; a link error
            // whose cause is not a ClassNotFoundException cannot prove it either.
            NoClassDefFoundError("com.android.bluetooth.ble.app.headset.FragmentHandler"),
        )
        failures.forEach { failure ->
            assertEquals(failure.toString(), OwnershipContractState.MISMATCH, ownershipContractStateOf(failure))
        }
    }

    @Test
    fun causeCyclesAndDeepChainsStayBounded() {
        val first = RuntimeException("first")
        val second = RuntimeException("second")
        first.initCause(second)
        second.initCause(first)
        val cycle = ownershipContractDiagnosis(first)
        assertEquals(OwnershipContractState.MISMATCH, cycle.state)
        assertTrue(cycle.truncated)

        val verified = wrappedAbsentClass(MAX_CAUSE_DEPTH - 1)
        assertEquals(OwnershipContractState.MISSING, ownershipContractStateOf(verified))
        assertEquals(MAX_CAUSE_DEPTH, ownershipContractDiagnosis(verified).chainDepth)
        assertFalse(ownershipContractDiagnosis(verified).truncated)

        val unverified = wrappedAbsentClass(MAX_CAUSE_DEPTH)
        assertEquals(OwnershipContractState.MISMATCH, ownershipContractStateOf(unverified))
        assertEquals(MAX_CAUSE_DEPTH + 1, ownershipContractDiagnosis(unverified).chainDepth)
        assertTrue(ownershipContractDiagnosis(unverified).truncated)
    }

    @Test
    fun onlyVerifiedAbsenceCountsAsMissingContract() {
        assertTrue(isContractMissing(outerClassMissing = true, reprobeClassMissing = false))
        assertTrue(isContractMissing(outerClassMissing = true, reprobeClassMissing = true))
        assertTrue(isContractMissing(outerClassMissing = false, reprobeClassMissing = true))
        // The enclosing service exists and its own loader finds the class again: a link problem, not an
        // absent data plane, so the fallback must stay unknown.
        assertFalse(isContractMissing(outerClassMissing = false, reprobeClassMissing = false))
    }

    /** The cross-probe marker stops the cause walk, so its wrapped ClassNotFoundException cannot grant. */
    @Test
    fun unverifiedReprobeOverridesItsWrappedClassNotFound() {
        val absent = ClassNotFoundException("com.android.bluetooth.ble.app.headset.FragmentHandler")
        val failure = OwnershipContractUnverifiedException("cannot be re-probed", absent)
        assertEquals(OwnershipContractState.MISMATCH, ownershipContractStateOf(failure))
        assertEquals(2, ownershipContractDiagnosis(failure).chainDepth)
        // A marker found first stops the walk, so a ClassNotFoundException below it cannot grant either.
        val nested = RuntimeException("outer", OwnershipContractUnverifiedException("cannot be re-probed", absent))
        assertEquals(OwnershipContractState.MISMATCH, ownershipContractStateOf(nested))
    }

    /** A ClassNotFoundException wrapped in [layers] outer exceptions. */
    private fun wrappedAbsentClass(layers: Int): Throwable {
        var failure: Throwable = ClassNotFoundException("com.android.bluetooth.ble.app.headset.FragmentHandler")
        repeat(layers) { failure = RuntimeException("wrapper", failure) }
        return failure
    }
}
