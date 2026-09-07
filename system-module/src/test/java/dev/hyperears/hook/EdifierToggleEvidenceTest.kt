package dev.hyperears.hook

import dev.hyperears.integration.DeviceLifecycle
import dev.hyperears.integration.EarbudAdapter
import dev.hyperears.integration.EarbudState
import dev.hyperears.integration.EdifierControlRequest
import dev.hyperears.integration.EdifierFitBudsTurboAdapter
import dev.hyperears.integration.NoiseMode
import dev.hyperears.integration.PrivateTransportState
import dev.hyperears.integration.ProtocolHandshakeState
import dev.hyperears.integration.StandardControlRequest
import dev.hyperears.integration.SystemProfileState
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class EdifierToggleEvidenceTest {
    @Test
    fun windRemainsAvailableBeforeGameAndGameRemainsUsableOutsideAnc() {
        val adapter = EdifierFitBudsTurboAdapter()
        adapter.receive(reply(0xCC, 0x1B, 1))
        val windOnly = state(adapter)
        assertTrue(WindNoiseToggleSpec.render(windOnly).available)
        assertFalse(EdifierGameModeToggleSpec.render(windOnly).available)
        assertEquals(
            StandardControlRequest.SetNoiseMode(NoiseMode.WIND),
            WindNoiseToggleSpec.request(windOnly, true),
        )

        adapter.receive(reply(0x08, 0))
        adapter.receive(reply(0xCC, 0x1B, 5))
        val transparency = state(adapter)
        assertTrue(WindNoiseToggleSpec.render(transparency).available)
        assertFalse(WindNoiseToggleSpec.render(transparency).enabled)
        assertTrue(EdifierGameModeToggleSpec.render(transparency).enabled)
        assertEquals(EdifierControlRequest.SetGameMode(true), EdifierGameModeToggleSpec.request(transparency, true))

        adapter.resetProtocolSession()
        val cleared = state(adapter)
        assertFalse(WindNoiseToggleSpec.render(cleared).available)
        assertFalse(EdifierGameModeToggleSpec.render(cleared).available)
        assertNull(EdifierGameModeToggleSpec.request(cleared, true))
    }

    private fun state(adapter: EarbudAdapter) = EarbudState(
        adapter = adapter.snapshot(),
        features = adapter.runtimeState().features,
        lifecycle = DeviceLifecycle(
            SystemProfileState.CONNECTED, PrivateTransportState.CONNECTED, ProtocolHandshakeState.CONFIRMED,
        ),
    )

    private fun reply(command: Int, vararg payload: Int): ByteArray {
        val body = byteArrayOf(0xBB.toByte(), 0xEC.toByte(), command.toByte(), 0, payload.size.toByte()) +
            payload.map(Int::toByte).toByteArray()
        return body + body.sumOf { it.toInt() and 0xFF }.toByte()
    }
}
