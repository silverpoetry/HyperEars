package dev.hyperears.integration

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.decodeFromString
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json

/**
 * A semantic control request for one active headset session.
 *
 * Requests are platform-neutral. The framework serializes them at the MiLink-to-Bluetooth
 * boundary and restores the same typed object before Adapter validation and protocol encoding.
 */
@Serializable
sealed interface ControlRequest

/**
 * Common controls inherited by every headset Adapter.
 *
 * Vendor or model request families remain siblings under [ControlRequest]. This keeps the standard
 * baseline stable while allowing a family to add precise request types without extending global
 * enums or IPC fields.
 */
@Serializable
sealed interface StandardControlRequest : ControlRequest {
    @Serializable
    @SerialName("standard.refresh")
    data object Refresh : StandardControlRequest

    @Serializable
    @SerialName("standard.set_noise_mode")
    data class SetNoiseMode(
        val mode: NoiseMode,
    ) : StandardControlRequest
}

/**
 * Adapter-owned execution behavior for one request.
 *
 * The policy is request-scoped so vendor controls such as ANC depth can use the same confirmation,
 * optimistic-state and rate-limit machinery as the standard noise-mode request.
 */
data class ControlExecutionPolicy(
    val confirmation: ControlConfirmationPolicy = ControlConfirmationPolicy.DEVICE_REPORT,
    val cooldownMs: Long = 0L,
    val readbackDelayMs: Long = DEFAULT_READBACK_DELAY_MS,
    val stateAfterWrite: DeviceFeatureState? = null,
) {
    init {
        require(cooldownMs >= 0L) { "Control cooldown cannot be negative" }
        require(readbackDelayMs >= 0L) { "Control readback delay cannot be negative" }
        require(
            confirmation == ControlConfirmationPolicy.DEVICE_REPORT || stateAfterWrite != null,
        ) { "Optimistic control policies require a state to publish" }
    }

    companion object {
        const val DEFAULT_READBACK_DELAY_MS = 120L
    }
}

/** Declares the requests that one Adapter may execute in its current confirmed state. */
fun interface ControlRequestContract {
    fun supports(adapter: EarbudAdapter, request: ControlRequest): Boolean
}

/** Baseline request contract inherited by all adapters. */
object StandardControlRequestContract : ControlRequestContract {
    override fun supports(adapter: EarbudAdapter, request: ControlRequest): Boolean = when {
        request === StandardControlRequest.Refresh -> true
        request is StandardControlRequest.SetNoiseMode ->
            adapter.effectiveCapabilities().noiseControl &&
                request.mode in adapter.effectiveSupportedNoiseModes()

        else -> false
    }
}

/** Adds a family or model request predicate without weakening inherited standard controls. */
fun ControlRequestContract.extending(
    additionalSupport: (EarbudAdapter, ControlRequest) -> Boolean,
): ControlRequestContract = ControlRequestContract { adapter, request ->
    supports(adapter, request) || additionalSupport(adapter, request)
}

/** Private, versioned request envelope used only at the process boundary. */
@Serializable
private data class ControlEnvelope(
    val schemaVersion: Int,
    val request: ControlRequest,
)

/**
 * Framework-owned serialization for [ControlRequest].
 *
 * Adapter and card authors create typed request objects only. They never construct JSON, Intent
 * extras, discriminators, or transport envelopes themselves.
 */
object ControlRequestTransport {
    private const val SCHEMA_VERSION = 1
    private const val MAX_ENCODED_BYTES = 4 * 1024

    private val json = Json {
        classDiscriminator = "command"
        encodeDefaults = true
        explicitNulls = false
        ignoreUnknownKeys = false
    }

    fun encode(request: ControlRequest): String {
        val encoded = json.encodeToString(
            ControlEnvelope(
                schemaVersion = SCHEMA_VERSION,
                request = request,
            ),
        )
        require(encoded.toByteArray(Charsets.UTF_8).size <= MAX_ENCODED_BYTES) {
            "Control request exceeds the transport payload limit"
        }
        return encoded
    }

    fun decode(encoded: String): ControlRequest? {
        if (encoded.toByteArray(Charsets.UTF_8).size > MAX_ENCODED_BYTES) return null
        return runCatching {
            json.decodeFromString<ControlEnvelope>(encoded)
                .takeIf { it.schemaVersion == SCHEMA_VERSION }
                ?.request
        }.getOrNull()
    }
}
