package dev.hyperears.root

import dev.hyperears.integration.ControlAppCatalog
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class VendorAppRootCommandsTest {
    @Test
    fun stopAndVerificationCoverEveryDeclaredControllerIncludingSamsungPlugins() {
        fun packages(command: String) = Regex("'([^']+)'").findAll(command)
            .map { it.groupValues[1] }.toSet()
        assertEquals(ControlAppCatalog.packageNames, packages(VendorAppRootCommands.stop))
        assertEquals(ControlAppCatalog.packageNames, packages(VendorAppRootCommands.verifyStopped))
        assertTrue(VendorAppRootCommands.stop.contains("am force-stop \"\$p\""))
        assertTrue(VendorAppRootCommands.verifyStopped.contains("pidof \"\$p\" && exit 1"))
    }
}
