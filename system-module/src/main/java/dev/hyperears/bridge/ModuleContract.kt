package dev.hyperears.bridge

import android.app.BroadcastOptions
import android.content.Intent
import android.os.Bundle
import android.os.IBinder
import dev.hyperears.integration.AdapterResolution
import dev.hyperears.integration.AdapterSnapshot
import dev.hyperears.integration.BatterySource
import dev.hyperears.integration.ControlRequest
import dev.hyperears.integration.ControlRequestTransport
import dev.hyperears.integration.ControlAppSpec
import dev.hyperears.integration.ControlOwnership
import dev.hyperears.integration.EarbudCapabilities
import dev.hyperears.integration.EarbudState
import dev.hyperears.integration.FeatureStateTransport
import dev.hyperears.integration.DeviceLifecycle
import dev.hyperears.integration.HeadsetFormFactor
import dev.hyperears.integration.HeadsetArtwork
import dev.hyperears.integration.HeadsetArtworkTransport
import dev.hyperears.integration.MiLinkCardPresentationId
import dev.hyperears.integration.NoiseMode
import dev.hyperears.integration.TransportKind
import dev.hyperears.integration.PrivateTransportState
import dev.hyperears.integration.ProtocolHandshakeState
import dev.hyperears.integration.SystemProfileState

object ModuleContract {
    const val ACTION_REQUEST_STATE = "dev.hyperears.action.REQUEST_STATE"
    const val ACTION_REQUEST_BRIDGE_STATUS = "dev.hyperears.action.REQUEST_BRIDGE_STATUS"
    const val ACTION_CONTROL = "dev.hyperears.action.CONTROL"
    const val ACTION_STATE_CHANGED = "dev.hyperears.action.STATE_CHANGED"
    const val ACTION_BRIDGE_STATE_OBSERVED =
        "dev.hyperears.action.BRIDGE_STATE_OBSERVED"
    const val ACTION_BRIDGE_RUNTIME_OBSERVED =
        "dev.hyperears.action.BRIDGE_RUNTIME_OBSERVED"
    const val ACTION_CONTROL_APP_REGISTER =
        "dev.hyperears.action.CONTROL_APP_REGISTER"
    const val ACTION_CONTROL_APP_QUERY =
        "dev.hyperears.action.CONTROL_APP_QUERY"
    const val ACTION_SYSTEM_OWNERSHIP_CLAIMED =
        "dev.hyperears.action.SYSTEM_OWNERSHIP_CLAIMED"

    const val MODULE_PACKAGE = "dev.hyperears"
    const val SETTINGS_PACKAGE = "com.android.settings"
    const val BLUETOOTH_PACKAGE = "com.android.bluetooth"
    const val MILINK_PACKAGE = "com.milink.service"
    const val NATIVE_HEADSET_PACKAGE = "com.xiaomi.bluetooth"

    private const val EXTRA_REPLY_PACKAGE = "reply_package"
    private const val EXTRA_SESSION_TOKEN = "session_token"
    private const val EXTRA_CONTROL_ENVELOPE = "control_envelope"
    private const val EXTRA_FEATURE_STATE_ENVELOPE = "feature_state_envelope"
    private const val EXTRA_MODEL_ID = "model_id"
    private const val EXTRA_ADAPTER_DISPLAY_NAME = "adapter_display_name"
    private const val EXTRA_ADAPTER_RESOLUTION = "adapter_resolution"
    private const val EXTRA_ADAPTER_BATTERY_SOURCE = "adapter_battery_source"
    private const val EXTRA_ADAPTER_FORM_FACTOR = "adapter_form_factor"
    private const val EXTRA_ADAPTER_ARTWORK = "adapter_artwork"
    private const val EXTRA_ADAPTER_PRESENTATION = "adapter_presentation"
    private const val EXTRA_ADAPTER_NOISE_MODES = "adapter_noise_modes"
    private const val EXTRA_ADAPTER_TRANSPORT_KINDS = "adapter_transport_kinds"
    private const val EXTRA_ADAPTER_CONTROL_APP_PACKAGES = "adapter_control_app_packages"
    private const val EXTRA_ADAPTER_CONTROL_APP_NAMES = "adapter_control_app_names"
    private const val EXTRA_CAP_BATTERY = "cap_battery"
    private const val EXTRA_CAP_NOISE = "cap_noise"
    private const val EXTRA_CAP_WIND = "cap_wind"
    private const val EXTRA_CAP_HANDOFF = "cap_handoff"
    private const val EXTRA_CAP_SPATIAL = "cap_spatial"
    private const val EXTRA_CAP_WEAR = "cap_wear"
    private const val EXTRA_CAP_FIND = "cap_find"
    private const val EXTRA_DEVICE_NAME = "device_name"
    private const val EXTRA_ADDRESS = "address"
    private const val EXTRA_SESSION_ACTIVE = "session_active"
    private const val EXTRA_PRIVATE_PROTOCOL_REQUIRED = "private_protocol_required"
    private const val EXTRA_CONNECTED = "connected"
    private const val EXTRA_PRIVATE_CHANNEL_CONNECTED = "private_channel_connected"
    private const val EXTRA_HANDSHAKE = "handshake"
    private const val EXTRA_SYSTEM_PROFILE_STATE = "system_profile_state"
    private const val EXTRA_PRIVATE_TRANSPORT_STATE = "private_transport_state"
    private const val EXTRA_PROTOCOL_HANDSHAKE_STATE = "protocol_handshake_state"
    private const val EXTRA_CONTROL_OWNERSHIP = "control_ownership"
    private const val EXTRA_EXTERNAL_CONTROL_APP_PACKAGE = "external_control_app_package"
    private const val EXTRA_EXTERNAL_CONTROL_APP_NAME = "external_control_app_name"
    private const val EXTRA_REVISION = "revision"
    private const val EXTRA_CONSUMER_PROCESS = "consumer_process"
    private const val EXTRA_BRIDGE_STAGE = "bridge_stage"
    private const val EXTRA_CONTROL_APP_PACKAGE = "control_app_package"
    private const val EXTRA_CONTROL_APP_PROCESS = "control_app_process"
    private const val EXTRA_CONTROL_APP_TOKEN = "control_app_token"

    val stateConsumerPackages = setOf(
        MODULE_PACKAGE,
        MILINK_PACKAGE,
        NATIVE_HEADSET_PACKAGE,
        SETTINGS_PACKAGE,
    )

    fun requestState(replyPackage: String): Intent =
        Intent(ACTION_REQUEST_STATE)
            .setPackage(BLUETOOTH_PACKAGE)
            .putExtra(EXTRA_REPLY_PACKAGE, replyPackage)

    fun requestBridgeStatus(replyPackage: String): Intent =
        Intent(ACTION_REQUEST_BRIDGE_STATUS)
            .setPackage(MILINK_PACKAGE)
            .putExtra(EXTRA_REPLY_PACKAGE, replyPackage)

    fun control(
        request: ControlRequest,
        address: String,
        sessionToken: String,
    ): Intent = Intent(ACTION_CONTROL)
        .setPackage(BLUETOOTH_PACKAGE)
        .putExtra(EXTRA_ADDRESS, address)
        .putExtra(EXTRA_SESSION_TOKEN, sessionToken)
        .putExtra(EXTRA_CONTROL_ENVELOPE, ControlRequestTransport.encode(request))

    fun stateChanged(
        state: EarbudState,
        sessionToken: String,
        targetPackage: String,
    ): Intent = Intent(ACTION_STATE_CHANGED)
        .setPackage(targetPackage)
        .putState(state)
        .putExtra(EXTRA_SESSION_TOKEN, sessionToken)

    fun bridgeStateObserved(
        state: EarbudState,
        sessionToken: String,
        consumerProcess: String,
        targetPackage: String,
        stage: BridgeStage = BridgeStage.STATE_ACCEPTED,
    ): Intent = Intent(ACTION_BRIDGE_STATE_OBSERVED)
        .setPackage(targetPackage)
        .putExtra(EXTRA_ADDRESS, state.address)
        .putExtra(EXTRA_SESSION_TOKEN, sessionToken)
        .putExtra(EXTRA_REVISION, state.revision)
        .putExtra(EXTRA_CONSUMER_PROCESS, consumerProcess)
        .putExtra(EXTRA_BRIDGE_STAGE, stage.name)

    fun bridgeRuntimeObserved(
        consumerProcess: String,
        targetPackage: String,
    ): Intent = Intent(ACTION_BRIDGE_RUNTIME_OBSERVED)
        .setPackage(targetPackage)
        .putExtra(EXTRA_CONSUMER_PROCESS, consumerProcess)

    fun Intent.readReplyPackage(): String? =
        getStringExtra(EXTRA_REPLY_PACKAGE)?.takeIf { it in stateConsumerPackages }

    fun Intent.readSessionToken(): String? = getStringExtra(EXTRA_SESSION_TOKEN)

    fun Intent.readBridgeReceipt(): BridgeReceipt? {
        if (action != ACTION_BRIDGE_STATE_OBSERVED) return null
        val address = getStringExtra(EXTRA_ADDRESS)?.takeIf(String::isNotBlank) ?: return null
        val sessionToken = getStringExtra(EXTRA_SESSION_TOKEN)
            ?.takeIf(String::isNotBlank)
            ?: return null
        val consumerProcess = getStringExtra(EXTRA_CONSUMER_PROCESS)
            ?.takeIf(String::isNotBlank)
            ?: return null
        val stage = getStringExtra(EXTRA_BRIDGE_STAGE)
            ?.let { runCatching { BridgeStage.valueOf(it) }.getOrNull() }
            ?: return null
        if (!hasExtra(EXTRA_REVISION)) return null
        return BridgeReceipt(
            address = address,
            sessionToken = sessionToken,
            revision = getLongExtra(EXTRA_REVISION, -1),
            consumerProcess = consumerProcess,
            stage = stage,
        )
    }

    fun controlAppRegistration(
        packageName: String,
        processName: String,
        token: IBinder,
    ): Intent = Intent(ACTION_CONTROL_APP_REGISTER)
        .setPackage(BLUETOOTH_PACKAGE)
        .putExtra(EXTRA_CONTROL_APP_PACKAGE, packageName)
        .putExtra(EXTRA_CONTROL_APP_PROCESS, processName)
        // Intent.getExtras() returns a defensive copy on current Android releases. Build a
        // separate Bundle and merge it through putExtras so the Binder is written to the Intent.
        .putExtras(Bundle().apply { putBinder(EXTRA_CONTROL_APP_TOKEN, token) })

    /**
     * Broadcast delivery options for a controller-process registration.
     *
     * Android does not expose a broadcast sender's package or UID to the receiver by default.
     * Presence registration is an ownership boundary, so the hooked controller process opts in
     * to sharing its system-authenticated identity instead of relying on self-declared extras.
     */
    fun controlAppRegistrationOptions(): Bundle = BroadcastOptions.makeBasic()
        .setShareIdentityEnabled(true)
        .toBundle()

    fun controlAppQuery(packageName: String): Intent =
        Intent(ACTION_CONTROL_APP_QUERY)
            .setPackage(packageName)
            .addFlags(Intent.FLAG_RECEIVER_FOREGROUND)

    fun systemOwnershipClaimed(address: String, targetPackage: String): Intent =
        Intent(ACTION_SYSTEM_OWNERSHIP_CLAIMED)
            .setPackage(targetPackage)
            .putExtra(EXTRA_ADDRESS, address)

    /** Shares the authenticated MiLink sender identity with the Bluetooth-process receiver. */
    fun systemOwnershipClaimOptions(): Bundle = BroadcastOptions.makeBasic()
        .setShareIdentityEnabled(true)
        .toBundle()

    fun Intent.readSystemOwnershipClaimAddress(): String? =
        if (action == ACTION_SYSTEM_OWNERSHIP_CLAIMED) {
            getStringExtra(EXTRA_ADDRESS)?.takeIf(String::isNotBlank)
        } else {
            null
        }

    data class ControlAppRegistration(
        val packageName: String,
        val processName: String,
        val token: IBinder,
    )

    fun Intent.readControlAppRegistration(): ControlAppRegistration? {
        if (action != ACTION_CONTROL_APP_REGISTER) return null
        val packageName = getStringExtra(EXTRA_CONTROL_APP_PACKAGE)
            ?.takeIf(String::isNotBlank)
            ?: return null
        val processName = getStringExtra(EXTRA_CONTROL_APP_PROCESS)
            ?.takeIf(String::isNotBlank)
            ?: return null
        val token = extras?.getBinder(EXTRA_CONTROL_APP_TOKEN) ?: return null
        return ControlAppRegistration(packageName, processName, token)
    }

    /**
     * Returns only the transport fields needed to diagnose a malformed registration. The Binder
     * itself is deliberately never stringified or persisted.
     */
    data class ControlAppRegistrationFields(
        val packageName: String?,
        val processName: String?,
        val tokenPresent: Boolean,
    )

    fun Intent.controlAppRegistrationFields(): ControlAppRegistrationFields =
        ControlAppRegistrationFields(
            packageName = getStringExtra(EXTRA_CONTROL_APP_PACKAGE),
            processName = getStringExtra(EXTRA_CONTROL_APP_PROCESS),
            tokenPresent = extras?.getBinder(EXTRA_CONTROL_APP_TOKEN) != null,
        )

    fun Intent.readBridgeRuntimeReceipt(): BridgeRuntimeReceipt? {
        if (action != ACTION_BRIDGE_RUNTIME_OBSERVED) return null
        val consumerProcess = getStringExtra(EXTRA_CONSUMER_PROCESS)
            ?.takeIf(String::isNotBlank)
            ?: return null
        return BridgeRuntimeReceipt(consumerProcess)
    }

    fun Intent.readAddress(): String? = getStringExtra(EXTRA_ADDRESS)

    fun Intent.readControl(): ControlRequest? =
        getStringExtra(EXTRA_CONTROL_ENVELOPE)
            ?.let(ControlRequestTransport::decode)

    fun Intent.putState(state: EarbudState): Intent = apply {
        putExtra(EXTRA_MODEL_ID, state.modelId)
        state.adapter?.let { adapter ->
            putExtra(EXTRA_ADAPTER_DISPLAY_NAME, adapter.displayName)
            putExtra(EXTRA_ADAPTER_RESOLUTION, adapter.resolution.name)
            putExtra(EXTRA_ADAPTER_BATTERY_SOURCE, adapter.batterySource.name)
            putExtra(EXTRA_ADAPTER_FORM_FACTOR, adapter.formFactor.name)
            putExtra(EXTRA_ADAPTER_ARTWORK, HeadsetArtworkTransport.encode(adapter.artwork))
            putExtra(EXTRA_ADAPTER_PRESENTATION, adapter.presentationId?.value)
            putExtra(EXTRA_ADAPTER_NOISE_MODES, adapter.supportedNoiseModes.map(NoiseMode::name).toTypedArray())
            putExtra(EXTRA_ADAPTER_TRANSPORT_KINDS, adapter.transportKinds.map(TransportKind::name).toTypedArray())
            putExtra(
                EXTRA_ADAPTER_CONTROL_APP_PACKAGES,
                adapter.controlApps.map(ControlAppSpec::packageName).toTypedArray(),
            )
            putExtra(
                EXTRA_ADAPTER_CONTROL_APP_NAMES,
                adapter.controlApps.map(ControlAppSpec::displayName).toTypedArray(),
            )
            putExtra(EXTRA_CAP_BATTERY, adapter.capabilities.battery)
            putExtra(EXTRA_CAP_NOISE, adapter.capabilities.noiseControl)
            putExtra(EXTRA_CAP_WIND, adapter.capabilities.windNoiseControl)
            putExtra(EXTRA_CAP_HANDOFF, adapter.capabilities.audioHandoff)
            putExtra(EXTRA_CAP_SPATIAL, adapter.capabilities.spatialAudio)
            putExtra(EXTRA_CAP_WEAR, adapter.capabilities.wearDetection)
            putExtra(EXTRA_CAP_FIND, adapter.capabilities.findDevice)
        }
        putExtra(EXTRA_DEVICE_NAME, state.deviceName)
        putExtra(EXTRA_ADDRESS, state.address)
        putExtra(EXTRA_SESSION_ACTIVE, state.sessionActive)
        putExtra(EXTRA_PRIVATE_PROTOCOL_REQUIRED, state.privateProtocolRequired)
        putExtra(EXTRA_CONNECTED, state.connected)
        putExtra(EXTRA_PRIVATE_CHANNEL_CONNECTED, state.privateChannelConnected)
        putExtra(EXTRA_HANDSHAKE, state.handshakeAccepted)
        putExtra(EXTRA_SYSTEM_PROFILE_STATE, state.lifecycle.systemProfile.name)
        putExtra(EXTRA_PRIVATE_TRANSPORT_STATE, state.lifecycle.privateTransport.name)
        putExtra(EXTRA_PROTOCOL_HANDSHAKE_STATE, state.lifecycle.protocolHandshake.name)
        putExtra(EXTRA_CONTROL_OWNERSHIP, state.lifecycle.controlOwnership.name)
        putExtra(
            EXTRA_EXTERNAL_CONTROL_APP_PACKAGE,
            state.lifecycle.externalControlApp?.packageName,
        )
        putExtra(
            EXTRA_EXTERNAL_CONTROL_APP_NAME,
            state.lifecycle.externalControlApp?.displayName,
        )
        putExtra(EXTRA_REVISION, state.revision)
        putExtra(EXTRA_FEATURE_STATE_ENVELOPE, FeatureStateTransport.encode(state.features))
    }

    fun Intent.readState(): EarbudState? {
        if (action != ACTION_STATE_CHANGED || !hasExtra(EXTRA_REVISION)) return null
        val features = getStringExtra(EXTRA_FEATURE_STATE_ENVELOPE)
            ?.let(FeatureStateTransport::decode)
            ?: return null
        return EarbudState(
            adapter = readAdapterSnapshot(),
            deviceName = getStringExtra(EXTRA_DEVICE_NAME),
            address = getStringExtra(EXTRA_ADDRESS),
            lifecycle = readLifecycle(),
            features = features,
            revision = getLongExtra(EXTRA_REVISION, 0),
        )
    }

    private fun Intent.readLifecycle(): DeviceLifecycle {
        val system = getStringExtra(EXTRA_SYSTEM_PROFILE_STATE)
            ?.let { runCatching { SystemProfileState.valueOf(it) }.getOrNull() }
        val transport = getStringExtra(EXTRA_PRIVATE_TRANSPORT_STATE)
            ?.let { runCatching { PrivateTransportState.valueOf(it) }.getOrNull() }
        val handshake = getStringExtra(EXTRA_PROTOCOL_HANDSHAKE_STATE)
            ?.let { runCatching { ProtocolHandshakeState.valueOf(it) }.getOrNull() }
        val decodedOwnership = getStringExtra(EXTRA_CONTROL_OWNERSHIP)
            ?.let { runCatching { ControlOwnership.valueOf(it) }.getOrNull() }
            ?: ControlOwnership.MODULE
        val externalControlApp = if (decodedOwnership == ControlOwnership.EXTERNAL_APP) {
            val packageName = getStringExtra(EXTRA_EXTERNAL_CONTROL_APP_PACKAGE)
            val displayName = getStringExtra(EXTRA_EXTERNAL_CONTROL_APP_NAME)
            if (!packageName.isNullOrBlank() && !displayName.isNullOrBlank()) {
                ControlAppSpec(packageName, displayName)
            } else {
                null
            }
        } else {
            null
        }
        val ownership = if (externalControlApp == null) {
            ControlOwnership.MODULE
        } else {
            ControlOwnership.EXTERNAL_APP
        }
        if (system != null && transport != null && handshake != null) {
            return DeviceLifecycle(
                systemProfile = system,
                privateTransport = transport,
                protocolHandshake = handshake,
                controlOwnership = ownership,
                externalControlApp = externalControlApp,
            )
        }

        // Backward-compatible decode for a state broadcast from an older module process.
        val active = getBooleanExtra(EXTRA_SESSION_ACTIVE, false)
        val privateRequired = getBooleanExtra(EXTRA_PRIVATE_PROTOCOL_REQUIRED, false)
        val channelConnected = getBooleanExtra(EXTRA_PRIVATE_CHANNEL_CONNECTED, false)
        val accepted = getBooleanExtra(EXTRA_HANDSHAKE, false)
        return DeviceLifecycle(
            systemProfile = if (active) {
                SystemProfileState.CONNECTED
            } else {
                SystemProfileState.DISCONNECTED
            },
            privateTransport = when {
                !privateRequired -> PrivateTransportState.NOT_REQUIRED
                channelConnected -> PrivateTransportState.CONNECTED
                else -> PrivateTransportState.IDLE
            },
            protocolHandshake = when {
                !privateRequired -> ProtocolHandshakeState.NOT_REQUIRED
                accepted -> ProtocolHandshakeState.CONFIRMED
                else -> ProtocolHandshakeState.PENDING
            },
        )
    }

    private fun Intent.readAdapterSnapshot(): AdapterSnapshot? {
        val id = getStringExtra(EXTRA_MODEL_ID)?.takeIf(String::isNotBlank) ?: return null
        val displayName = getStringExtra(EXTRA_ADAPTER_DISPLAY_NAME) ?: id
        val resolution = getStringExtra(EXTRA_ADAPTER_RESOLUTION)
            ?.let { runCatching { AdapterResolution.valueOf(it) }.getOrNull() }
            ?: AdapterResolution.FAMILY_MATCH
        val batterySource = getStringExtra(EXTRA_ADAPTER_BATTERY_SOURCE)
            ?.let { runCatching { BatterySource.valueOf(it) }.getOrNull() }
            ?: BatterySource.NONE
        val formFactor = getStringExtra(EXTRA_ADAPTER_FORM_FACTOR)
            ?.let { runCatching { HeadsetFormFactor.valueOf(it) }.getOrNull() }
            ?: HeadsetFormFactor.TWS
        val modes = getStringArrayExtra(EXTRA_ADAPTER_NOISE_MODES)
            .orEmpty()
            .mapNotNullTo(linkedSetOf()) { runCatching { NoiseMode.valueOf(it) }.getOrNull() }
        val transportKinds = getStringArrayExtra(EXTRA_ADAPTER_TRANSPORT_KINDS)
            .orEmpty()
            .mapNotNullTo(linkedSetOf()) { runCatching { TransportKind.valueOf(it) }.getOrNull() }
        val controlAppPackages = getStringArrayExtra(EXTRA_ADAPTER_CONTROL_APP_PACKAGES).orEmpty()
        val controlAppNames = getStringArrayExtra(EXTRA_ADAPTER_CONTROL_APP_NAMES).orEmpty()
        val controlApps = controlAppPackages.mapIndexedNotNull { index, packageName ->
            packageName.takeIf(String::isNotBlank)?.let {
                ControlAppSpec(
                    packageName = it,
                    displayName = controlAppNames.getOrNull(index)
                        ?.takeIf(String::isNotBlank)
                        ?: it,
                )
            }
        }
        return AdapterSnapshot(
            id = id,
            displayName = displayName,
            resolution = resolution,
            privateProtocolRequired = getBooleanExtra(EXTRA_PRIVATE_PROTOCOL_REQUIRED, false),
            batterySource = batterySource,
            formFactor = formFactor,
            capabilities = EarbudCapabilities(
                battery = getBooleanExtra(EXTRA_CAP_BATTERY, false),
                noiseControl = getBooleanExtra(EXTRA_CAP_NOISE, false),
                windNoiseControl = getBooleanExtra(EXTRA_CAP_WIND, false),
                audioHandoff = getBooleanExtra(EXTRA_CAP_HANDOFF, false),
                spatialAudio = getBooleanExtra(EXTRA_CAP_SPATIAL, false),
                wearDetection = getBooleanExtra(EXTRA_CAP_WEAR, false),
                findDevice = getBooleanExtra(EXTRA_CAP_FIND, false),
            ),
            supportedNoiseModes = modes,
            presentationId = getStringExtra(EXTRA_ADAPTER_PRESENTATION)
                ?.takeIf(String::isNotBlank)
                ?.let(::MiLinkCardPresentationId),
            transportKinds = transportKinds,
            controlApps = controlApps,
            artwork = getStringExtra(EXTRA_ADAPTER_ARTWORK)
                ?.let(HeadsetArtworkTransport::decode)
                ?: HeadsetArtwork.defaultFor(formFactor),
        )
    }
}

data class BridgeReceipt(
    val address: String,
    val sessionToken: String,
    val revision: Long,
    val consumerProcess: String,
    val stage: BridgeStage,
)

enum class BridgeStage {
    STATE_ACCEPTED,
    IDENTITY_QUERIED,
    CAPABILITIES_QUERIED,
    RUNTIME_NOTIFIED,
}

data class BridgeRuntimeReceipt(
    val consumerProcess: String,
)
