package dev.hyperears.hook

import org.junit.Assert.*
import org.junit.Test

class BluetoothCallbackBoundaryTest {
    @Test fun registryInitializerErrorDoesNotEscapePostedBluetoothCallback() {
        var failure: Throwable? = null
        val cause = ExceptionInInitializerError(IllegalArgumentException("adapter registration"))
        runBluetoothCallback({ failure = it }) { throw cause }
        assertSame(cause,failure)
        var completed = false
        runBluetoothCallback({ fail("unexpected failure") }) { completed = true }
        assertTrue(completed)
    }
    @Test fun staleInitializerAndOrdinaryErrorsAreContainedButVmFailurePropagates() {
        var count = 0
        runBluetoothCallback({ count++ }) { throw NoClassDefFoundError("registry") }
        runBluetoothCallback({ count++ }) { throw IllegalStateException("session") }
        assertEquals(2,count)
        val fatal = OutOfMemoryError("test")
        try { runBluetoothCallback({ fail("must not swallow VM errors") }) { throw fatal }; fail("must propagate") }
        catch (e: OutOfMemoryError) { assertSame(fatal,e) }
    }
}
