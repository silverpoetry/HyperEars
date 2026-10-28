package dev.hyperears.hook

import org.junit.Assert.*
import org.junit.Test

class HookInstallTransactionTest {
    @Test fun failedInstallRollsBackEveryHookInReverseOrderAndPreservesOriginalFailure() {
        val removed = mutableListOf<Int>()
        val transaction = HookInstallTransaction<Int> { removed.add(it); if (it == 2) error("rollback") }
        val original = IllegalStateException("install")
        assertSame(original, runCatching {
            transaction.install { transaction.record(1); transaction.record(2); throw original }
        }.exceptionOrNull())
        assertEquals(listOf(2, 1), removed)
    }
    @Test fun successfulInstallKeepsHooks() {
        val removed = mutableListOf<Int>()
        val transaction = HookInstallTransaction<Int>(removed::add)
        transaction.install { transaction.record(1) }
        assertTrue(removed.isEmpty())
    }
}
