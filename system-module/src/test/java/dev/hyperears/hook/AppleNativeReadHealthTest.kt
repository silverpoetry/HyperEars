package dev.hyperears.hook

import org.junit.Assert.*
import org.junit.Test

class AppleNativeReadHealthTest {
    @Test fun successfulEmptyTypeIsDeclineButAbsentReadIsUnknown() {
        val health = AppleNativeReadHealth("key")
        assertFalse(health.declineHealthy(0) { false })
        health.preference { "" }
        assertTrue(health.declineHealthy(1) { error("cloud was never read") })
    }
    @Test fun swallowedPreferenceErrorRemainsUnknown() {
        val health = AppleNativeReadHealth("key")
        runCatching { health.preference { throw IllegalStateException("native caller swallows this") } }
        assertFalse(health.declineHealthy(0) { false })
    }
    @Test fun malformedCloudAndBuiltinFailuresRemainUnknown() {
        val health = AppleNativeReadHealth("key")
        health.preference { "native-type" }
        health.cloud { 1 }
        health.builtin { false }
        assertFalse(health.declineHealthy(1) { false })
        runCatching { health.builtin { throw IllegalStateException("native caller swallows this") } }
        assertFalse(health.declineHealthy(0) { false })
    }
    @Test fun missingOptionalCloudUsesOnlyActualBuiltinDeclineAndReadErrorsAreUnknown() {
        val health = AppleNativeReadHealth("key")
        health.preference { "native-type" }
        health.cloud { 1 }
        assertFalse(health.declineHealthy(-1) { true })
        health.builtin { false }
        assertTrue(health.declineHealthy(-1) { true })
        assertFalse(health.declineHealthy(-1) { false })
        assertFalse(health.declineHealthy(-1) { throw java.io.IOException("read denied") })
        assertTrue(health.declineHealthy(0) { false })
    }
    @Test fun validCloudExclusionDeclinesButCloudPositiveOrParseErrorDoesNot() {
        val health = AppleNativeReadHealth("key")
        health.preference { "native-type" }
        health.cloud { 3 }
        assertTrue(health.declineHealthy(0) { false })
        health.cloud { 2 }
        assertFalse(health.declineHealthy(0) { false })
        health.cloud { 0 }
        assertFalse(health.declineHealthy(0) { false })
    }
}

