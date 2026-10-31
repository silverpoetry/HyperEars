package dev.hyperears.firmware

import java.io.ByteArrayOutputStream
import java.io.IOException
import java.net.URI
import java.util.concurrent.CancellationException
import javax.net.ssl.HttpsURLConnection
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

/** In-memory server information; never evidence of a verified firmware signature. */
class CoralFirmwareMetadata private constructor(
    val version: Int,
    val versionDescribe: String,
    internal val otaUrl: URI,
    internal val encodeData: String?,
) {
    val model: String = "coral"
    val target: String = "self"

    companion object {
        internal fun parse(bytes: ByteArray): CoralFirmwareMetadata {
            val root = Json.parseToJsonElement(bytes.toString(Charsets.UTF_8)) as? JsonObject
                ?: error("Invalid metadata envelope")
            require((root["code"] as? JsonPrimitive)?.content == "200") { "Metadata service rejected request" }
            val data = root["data"] as? JsonObject ?: error("Missing firmware metadata")
            fun field(name: String): String? = (data[name] as? JsonPrimitive)?.takeUnless { it.content == "null" }?.content
            val versionText = field("version") ?: error("Missing firmware version")
            require(versionText.isNotEmpty() && versionText.all { it in '0'..'9' }) { "Invalid firmware version" }
            val version = versionText.toIntOrNull()
            require(version != null && version in 1..65535) { "Firmware version outside LE16 range" }
            val url = requireHttps(field("otaUrl") ?: error("Missing firmware URL"))
            return CoralFirmwareMetadata(version, field("versionDescribe").orEmpty(), url, field("encodeData"))
        }
    }
}

data class CoralFirmwareCheck(val currentVersion: Int, val latest: CoralFirmwareMetadata) {
    val updateAvailable: Boolean get() = latest.version > currentVersion
}

/** One operation owns one token. cancel() also disconnects a blocked HTTPS request. */
class CoralFirmwareCancellation {
    @Volatile private var cancelled = false
    private var disconnect: (() -> Unit)? = null

    fun cancel() {
        val action = synchronized(this) { cancelled = true; disconnect.also { disconnect = null } }
        action?.invoke()
    }

    fun check() {
        if (cancelled || Thread.currentThread().isInterrupted) throw CancellationException("Firmware request cancelled")
    }

    internal fun attach(action: () -> Unit) {
        synchronized(this) { check(); check(disconnect == null) { "Cancellation token already in use" }; disconnect = action }
    }

    internal fun detach() { synchronized(this) { disconnect = null } }
}

class CoralFirmwareHttpRequest internal constructor(
    val url: URI,
    val method: String,
    val body: ByteArray?,
    val maxResponseBytes: Int,
)

fun interface CoralFirmwareHttpTransport {
    fun execute(request: CoralFirmwareHttpRequest, cancellation: CoralFirmwareCancellation): ByteArray
}

/** Blocking IO: caller supplies its IO dispatcher/executor and owns operation cancellation. */
class CoralFirmwareRepository(
    private val transport: CoralFirmwareHttpTransport = CoralFirmwareHttpsTransport(),
) {
    fun check(
        currentVersion: Int,
        language: String = "en",
        cancellation: CoralFirmwareCancellation = CoralFirmwareCancellation(),
    ): CoralFirmwareCheck {
        require(currentVersion in 1..65535) { "Current firmware version unavailable" }
        require(language == "en" || language == "zh") { "Unsupported metadata language" }
        val body = buildJsonObject {
            put("keyId", "coral")
            put("pid", "")
            put("tag", 1)
            // ROSELINK SystemService uses parsed PackageInfo.buildNumber, then toString().
            put("clientVersion", "5409")
            put("lang", language)
        }.toString().toByteArray(Charsets.UTF_8)
        cancellation.check()
        val bytes = transport.execute(CoralFirmwareHttpRequest(URI(METADATA_URL), "POST", body, MAX_METADATA_BYTES), cancellation)
        cancellation.check()
        require(bytes.size <= MAX_METADATA_BYTES) { "Metadata response too large" }
        return CoralFirmwareCheck(currentVersion, CoralFirmwareMetadata.parse(bytes))
    }

    companion object {
        const val METADATA_URL = "https://api-internal.rosetechnic.com/app/ota/v2/get-by-key-id-and-tag"
        const val MAX_METADATA_BYTES = 1024 * 1024
    }
}

internal fun requireHttps(value: String): URI {
    val uri = try { URI(value) } catch (_: Exception) { throw IllegalArgumentException("Invalid firmware URL") }
    require(uri.scheme.equals("https", ignoreCase = true) && !uri.host.isNullOrBlank() && uri.userInfo == null && uri.fragment == null) {
        "Firmware URL must use HTTPS without embedded credentials or fragment"
    }
    return uri
}

/** Uses platform TLS trust; no embedded credentials, account tokens or signature claims. */
class CoralFirmwareHttpsTransport : CoralFirmwareHttpTransport {
    override fun execute(request: CoralFirmwareHttpRequest, cancellation: CoralFirmwareCancellation): ByteArray {
        var url = requireHttps(request.url.toString())
        val deadline = System.nanoTime() + 120_000_000_000L
        try {
            repeat(4) { redirect ->
                cancellation.check()
                val connection = url.toURL().openConnection() as HttpsURLConnection
                var attached = false
                try {
                    cancellation.attach { connection.disconnect() }
                    attached = true
                    connection.instanceFollowRedirects = false
                    connection.connectTimeout = 15_000
                    connection.readTimeout = 15_000
                    connection.useCaches = false
                    connection.requestMethod = request.method
                    connection.setRequestProperty("Accept-Encoding", "identity")
                    request.body?.let { body ->
                        connection.setRequestProperty("Content-Type", "application/json")
                        connection.doOutput = true
                        connection.setFixedLengthStreamingMode(body.size)
                        connection.outputStream.use { it.write(body) }
                    }
                    val status = connection.responseCode
                    if (status in setOf(301, 302, 303, 307, 308)) {
                        // Only GET image redirects: never forward POST data to a different origin.
                        require(request.method == "GET" && redirect < 3) { "Firmware redirect rejected" }
                        val location = connection.getHeaderField("Location") ?: error("Missing redirect location")
                        url = requireHttps(url.resolve(location).toString())
                    } else {
                        require(status == 200) { "Firmware HTTP request rejected" }
                        val contentLength = connection.contentLengthLong
                        require(contentLength <= request.maxResponseBytes) { "Firmware response too large" }
                        return connection.inputStream.use { input ->
                            val output = ByteArrayOutputStream()
                            val buffer = ByteArray(8192)
                            while (true) {
                                cancellation.check()
                                check(System.nanoTime() < deadline) { "Firmware request deadline exceeded" }
                                val count = input.read(buffer)
                                if (count < 0) break
                                require(output.size().toLong() + count <= request.maxResponseBytes) { "Firmware response too large" }
                                output.write(buffer, 0, count)
                            }
                            cancellation.check()
                            output.toByteArray()
                        }
                    }
                } finally {
                    if (attached) cancellation.detach()
                    connection.disconnect()
                }
            }
            error("Firmware redirect limit exceeded")
        } catch (e: CancellationException) {
            throw e
        } catch (_: Exception) {
            cancellation.check()
            // URL query strings may carry temporary download credentials. Do not retain causes.
            throw IOException("Firmware HTTPS request failed")
        }
    }
}
