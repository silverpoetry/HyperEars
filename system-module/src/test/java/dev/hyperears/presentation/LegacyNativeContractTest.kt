package dev.hyperears.presentation

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class LegacyNativeContractTest {
    @Test
    fun missingAppleReaderIsAnExceptionOnlyForTheAuditedHost() {
        val hash = LegacyNativeHostContract.HOST_SHA256
        assertTrue(LegacyNativeHostContract.isVerified(35, 35, hash))
        assertFalse(LegacyNativeHostContract.isVerified(36, 35, hash))
        assertFalse(LegacyNativeHostContract.isVerified(35, 36, hash))
        assertFalse(LegacyNativeHostContract.isVerified(35, 35, "0".repeat(64)))
        assertFalse(LegacyNativeHostContract.isVerified(35, 35, ""))
    }

    @Test
    fun displayServiceNeverClaimsASecondBluetoothPeerOrAnInactiveConnection() {
        val address = "02:00:00:00:00:01"
        val legacy = NativeWindowServiceContract.LEGACY
        assertEquals(2, legacy.connectionState(address, true, arrayOf<Any>(address, false)))
        assertEquals(0, legacy.connectionState(address, true, arrayOf<Any>(address, true)))
        assertEquals(0, legacy.connectionState(address, false, arrayOf<Any>(address, false)))
        assertEquals(0, legacy.connectionState(address, true, arrayOf<Any>("02:00:00:00:00:02", false)))
        assertEquals(0, legacy.connectionState(address, true, arrayOf(address)))
        assertEquals(0, legacy.connectionState(null, true, null))
        assertEquals(2, NativeWindowServiceContract.AIRPODS.connectionState(address, true, arrayOf(address)))
        assertEquals(0, NativeWindowServiceContract.AIRPODS.connectionState(null, true, null))
        assertFalse(legacy.ignoredResourceCalls.contains("startPairAndConnect"))
        assertFalse(legacy.closeWithAddress)
        assertTrue(NativeWindowServiceContract.AIRPODS.closeWithAddress)
    }

    @Test
    fun unknownLegacyBatteryDoesNotBecomeFullAndRealZeroRemainsEmpty() {
        assertEquals(6, legacyBatteryLevel(null))
        assertEquals(6, legacyBatteryLevel(127))
        assertEquals(6, legacyBatteryLevel(255))
        assertEquals(6, legacyBatteryLevel(-1))
        assertEquals(0, legacyBatteryLevel(0))
        assertEquals(1, legacyBatteryLevel(1))
        assertEquals(1, legacyBatteryLevel(20))
        assertEquals(2, legacyBatteryLevel(21))
        assertEquals(5, legacyBatteryLevel(100))
    }

    @Test
    fun onlyTaggedNotificationManagerPublicationsAreRecognized() {
        val manager = "android.app.NotificationManager"
        val tagged = listOf("java.lang.String", "int", "android.app.Notification")
        assertTrue(isNativeDetailPublication(manager, "notify", tagged))
        assertTrue(isNativeDetailPublication(manager, "notifyAsUser", tagged + "android.os.UserHandle"))
        assertFalse(isNativeDetailPublication(manager, "notifyAsUser", tagged))
        assertFalse(isNativeDetailPublication(manager, "notify", tagged.drop(1)))
        assertFalse(isNativeDetailPublication("some.other.Manager", "notify", tagged))
        assertFalse(isNativeDetailPublication(manager, "cancel", tagged))
    }
}
