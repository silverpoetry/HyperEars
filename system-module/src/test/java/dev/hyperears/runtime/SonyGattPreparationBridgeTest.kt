package dev.hyperears.runtime
import android.os.Handler
import android.os.Looper
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import java.time.Duration
@RunWith(RobolectricTestRunner::class)
@Config(sdk=[35],manifest=Config.NONE)
class SonyGattPreparationBridgeTest {
    @Test fun probeTimeoutThenReadThenSubscriptionAndCloseCancelDeadlines() {
        val steps=mutableListOf<String>()
        val bridge=SonyGattPreparationBridge({steps+="probe";true},{steps+="read";true},
            {steps+="data"},{steps+="ready"},{steps+="failed"},Handler(Looper.getMainLooper()))
        bridge.services(true);assertTrue(steps.isEmpty())
        bridge.mtu(247);assertEquals(listOf("probe"),steps)
        bridge.probeSubscribed(true)
        shadowOf(Looper.getMainLooper()).idleFor(Duration.ofMillis(500))
        assertEquals(listOf("probe","read"),steps)
        bridge.length(byteArrayOf(0,100),true)
        assertEquals("data",steps.last())
        bridge.dataSubscribed(true);assertEquals("ready",steps.last())
        assertEquals(listOf(100,100,1),bridge.fragments(ByteArray(201)).map{it.size})
        bridge.close();shadowOf(Looper.getMainLooper()).idleFor(Duration.ofSeconds(10))
        assertFalse(steps.contains("failed"))
    }
    @Test fun failedReadAndClosedCallbacksNeverReachReady() {
        val steps=mutableListOf<String>()
        val bridge=SonyGattPreparationBridge({true},{false},{steps+="data"},{steps+="ready"},{steps+="failed"})
        bridge.mtu(23);bridge.services(false)
        assertEquals(listOf("failed"),steps)
        bridge.length(byteArrayOf(0,20),true);bridge.dataSubscribed(true)
        assertEquals(listOf("failed"),steps)
        bridge.close()
    }
}
