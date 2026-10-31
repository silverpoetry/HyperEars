package dev.hyperears.firmware

import java.util.concurrent.CancellationException
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.Assert.*
import org.junit.Test

class CoralFirmwareRepositoryTest {
    private fun response(version: String = "12", url: String = "https://firmware.example/coral.hex"): ByteArray =
        """{"code":200,"data":{"version":"$version","versionDescribe":"Release notes","otaUrl":"$url","encodeData":"vendor-data"}}""".toByteArray()

    @Test fun metadataRequestUsesExactSourceFieldsAndBuildNumber() {
        val repository = CoralFirmwareRepository { request, _ ->
            assertEquals(CoralFirmwareRepository.METADATA_URL, request.url.toString())
            assertEquals("POST", request.method)
            val body = Json.parseToJsonElement(request.body!!.toString(Charsets.UTF_8)).jsonObject
            assertEquals(setOf("keyId", "pid", "tag", "clientVersion", "lang"), body.keys)
            assertEquals("coral", body["keyId"]!!.jsonPrimitive.content)
            assertEquals("", body["pid"]!!.jsonPrimitive.content)
            assertEquals("1", body["tag"]!!.jsonPrimitive.content)
            assertEquals("5409", body["clientVersion"]!!.jsonPrimitive.content)
            assertTrue(body["clientVersion"]!!.jsonPrimitive.isString)
            assertEquals("zh", body["lang"]!!.jsonPrimitive.content)
            response()
        }
        val result = repository.check(11, "zh")
        assertTrue(result.updateAvailable)
        assertEquals("Release notes", result.latest.versionDescribe)
        assertEquals("coral", result.latest.model)
        assertEquals("self", result.latest.target)
        assertFalse(repository.check(12, "zh").updateAvailable)
        assertFalse(repository.check(13, "zh").updateAvailable)
    }

    @Test fun malformedAndOutOfRangeVersionsAreRejected() {
        for (version in listOf("0", "65536", "-1", "3.8.2", "12 ", "", "99999999999999999999")) {
            assertThrows(IllegalArgumentException::class.java) { CoralFirmwareMetadata.parse(response(version)) }
        }
        assertThrows(IllegalArgumentException::class.java) {
            CoralFirmwareMetadata.parse("""{"code":401,"data":{}}""".toByteArray())
        }
    }

    @Test fun insecureAndCredentialBearingUrlsAreRejected() {
        for (url in listOf("http://firmware.example/a", "https://user:secret@firmware.example/a", "file:///a", "https:///a", "https://firmware.example/a#fragment")) {
            assertThrows(IllegalArgumentException::class.java) { CoralFirmwareMetadata.parse(response(url = url)) }
        }
    }

    @Test fun cancellationBeforeAndAfterTransportDiscardsResults() {
        val token = CoralFirmwareCancellation()
        token.cancel()
        val repository = CoralFirmwareRepository { _, _ -> fail("Cancelled operation reached transport"); response() }
        assertThrows(CancellationException::class.java) { repository.check(1, cancellation = token) }
        val lateToken = CoralFirmwareCancellation()
        val lateRepository = CoralFirmwareRepository { _, cancellation -> cancellation.cancel(); response() }
        assertThrows(CancellationException::class.java) { lateRepository.check(1, cancellation = lateToken) }
    }

    @Test fun cancellationDisconnectsOnceAndRejectsNewConnection() {
        val token = CoralFirmwareCancellation()
        var disconnects = 0
        token.attach { disconnects++ }
        token.cancel()
        token.cancel()
        assertEquals(1, disconnects)
        assertThrows(CancellationException::class.java) { token.attach { disconnects++ } }
    }

    @Test fun injectedOversizeResponseIsStillRejected() {
        val repository = CoralFirmwareRepository { _, _ -> ByteArray(CoralFirmwareRepository.MAX_METADATA_BYTES + 1) }
        assertThrows(IllegalArgumentException::class.java) { repository.check(1) }
    }
}
