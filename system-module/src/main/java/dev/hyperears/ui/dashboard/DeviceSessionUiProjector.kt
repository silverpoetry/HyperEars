package dev.hyperears.ui.dashboard

import dev.hyperears.integration.BatteryReading
import dev.hyperears.integration.BatterySource
import dev.hyperears.integration.ControlOwnership
import dev.hyperears.integration.AdapterSnapshot
import dev.hyperears.integration.HeadsetFormFactor
import dev.hyperears.integration.NoiseMode
import dev.hyperears.integration.AdapterResolution
import dev.hyperears.integration.PrivateTransportState
import dev.hyperears.integration.ProtocolHandshakeState
import dev.hyperears.integration.SystemProfileState
import dev.hyperears.integration.TransportKind

/** Complete, adapter-agnostic data required to render one dashboard card. */
data class DeviceSessionUiModel(
    val deviceName: String,
    val address: String,
    val adapterName: String,
    val adapterId: String,
    val adapterSummary: String,
    val controlSummary: String,
    val adapterResolved: Boolean,
    val phase: DevicePhase,
    val headsetLifecycle: List<DeviceLinkStage>,
    val miLinkLifecycle: List<DeviceLifecycleStage>,
    val metrics: List<DeviceMetric>,
    val noiseControl: NoiseControlUiModel?,
    val sessionToken: String,
)

/** Generic control projection; concrete Adapter and protocol types never reach Compose. */
data class NoiseControlUiModel(
    val supportedModes: List<NoiseMode>,
    val selectedMode: NoiseMode?,
    val enabled: Boolean,
)

data class DeviceLinkStage(
    val label: String,
    val value: String,
    val status: DeviceLinkStatus,
)

enum class DeviceLinkStatus {
    READY,
    ACTIVE,
    INACTIVE,
    ERROR,
}

data class DeviceMetric(
    val label: String,
    val value: String,
    val kind: DeviceMetricKind = DeviceMetricKind.READ_ONLY,
)

enum class DeviceMetricKind {
    READ_ONLY,
    NOISE_MODE,
}

/**
 * The UI consumes only the immutable runtime adapter snapshot.
 *
 * Compose receives a stable, generic presentation model. Concrete adapters, transport classes,
 * battery topology and readiness rules never leak into the view hierarchy.
 */
object DeviceSessionUiProjector {
    fun project(session: DeviceSessionSnapshot): DeviceSessionUiModel {
        val state = session.state
        val adapter = state.adapter
        return DeviceSessionUiModel(
            deviceName = state.deviceName ?: "未命名耳机",
            address = state.address ?: "—",
            adapterName = adapter?.displayName ?: "未解析",
            adapterId = adapter?.id ?: "—",
            adapterSummary = adapter?.adapterSummary() ?: "尚未建立 Adapter 快照",
            controlSummary = adapter?.controlSummary() ?: "能力未知",
            adapterResolved = adapter != null,
            phase = session.phase,
            headsetLifecycle = headsetLifecycle(session),
            miLinkLifecycle = session.miLinkLifecycle,
            metrics = metrics(session, adapter),
            noiseControl = noiseControl(session, adapter),
            sessionToken = session.sessionToken,
        )
    }

    private fun noiseControl(
        session: DeviceSessionSnapshot,
        adapter: AdapterSnapshot?,
    ): NoiseControlUiModel? {
        adapter ?: return null
        if (!adapter.capabilities.noiseControl || adapter.supportedNoiseModes.isEmpty()) return null
        val state = session.state
        return NoiseControlUiModel(
            supportedModes = adapter.supportedNoiseModes.sortedBy(NoiseMode::controlOrder),
            selectedMode = state.noiseMode,
            enabled = state.connected &&
                state.lifecycle.controlOwnership == ControlOwnership.MODULE &&
                (
                    !adapter.privateProtocolRequired ||
                        state.lifecycle.privateTransport == PrivateTransportState.CONNECTED
                ),
        )
    }

    private fun headsetLifecycle(
        session: DeviceSessionSnapshot,
    ): List<DeviceLinkStage> = buildList {
        val state = session.state
        add(
            DeviceLinkStage(
                label = "系统连接",
                value = state.lifecycle.systemProfile.displayName(),
                status = if (state.lifecycle.systemProfile == SystemProfileState.CONNECTED) {
                    DeviceLinkStatus.READY
                } else {
                    DeviceLinkStatus.ERROR
                },
            ),
        )
        add(
            DeviceLinkStage(
                label = "控制",
                value = state.lifecycle.externalControlApp?.displayName ?: "HyperEars",
                status = if (state.lifecycle.controlOwnership == ControlOwnership.EXTERNAL_APP) {
                    DeviceLinkStatus.ACTIVE
                } else {
                    DeviceLinkStatus.READY
                },
            ),
        )
        add(
            DeviceLinkStage(
                label = "私有通道",
                value = state.lifecycle.privateTransport.displayName(),
                status = when (state.lifecycle.privateTransport) {
                    PrivateTransportState.NOT_REQUIRED,
                    PrivateTransportState.CONNECTED,
                    -> DeviceLinkStatus.READY
                    PrivateTransportState.CONNECTING,
                    PrivateTransportState.RECOVERING,
                    -> DeviceLinkStatus.ACTIVE
                    PrivateTransportState.IDLE -> DeviceLinkStatus.INACTIVE
                    PrivateTransportState.DORMANT -> DeviceLinkStatus.ERROR
                },
            ),
        )
        add(
            DeviceLinkStage(
                label = "协议",
                value = state.lifecycle.protocolHandshake.displayName(),
                status = when (state.lifecycle.protocolHandshake) {
                    ProtocolHandshakeState.NOT_REQUIRED,
                    ProtocolHandshakeState.CONFIRMED,
                    -> DeviceLinkStatus.READY
                    ProtocolHandshakeState.PENDING -> DeviceLinkStatus.ACTIVE
                    ProtocolHandshakeState.REJECTED -> DeviceLinkStatus.ERROR
                },
            ),
        )
    }

    private fun metrics(
        session: DeviceSessionSnapshot,
        adapter: AdapterSnapshot?,
    ): List<DeviceMetric> = buildList {
        val battery = session.state.battery
        if (adapter?.formFactor == HeadsetFormFactor.HEADPHONES || battery.overall.available) {
            val aggregate = battery.overall.takeIf(BatteryReading::available)
                ?: battery.left.takeIf(BatteryReading::available)
                ?: battery.right.takeIf(BatteryReading::available)
                ?: battery.case.takeIf(BatteryReading::available)
                ?: battery.overall
            add(DeviceMetric("整机", aggregate.displayValue()))
        } else {
            add(DeviceMetric("左耳", battery.left.displayValue()))
            add(DeviceMetric("右耳", battery.right.displayValue()))
            add(DeviceMetric("充电盒", battery.case.displayValue()))
        }
        add(
            DeviceMetric(
                label = "模式",
                value = if (adapter?.capabilities?.noiseControl == false) {
                    "不支持"
                } else {
                    session.state.noiseMode.displayNameOrUnknown()
                },
                kind = DeviceMetricKind.NOISE_MODE,
            ),
        )
    }

    private fun AdapterSnapshot.adapterSummary(): String =
        "匹配  ${resolution.displayName()}  ·  形态  ${formFactor.displayName()}  ·  " +
            "电量  ${batterySource.displayName()}  ·  " +
            "传输  ${transportSummary()}"

    private fun AdapterSnapshot.transportSummary(): String {
        if (!privateProtocolRequired) return "标准 A2DP/HFP"
        return transportKinds
            .map { transport ->
                when (transport) {
                    TransportKind.RFCOMM -> "RFCOMM"
                    TransportKind.GATT -> "GATT"
                    TransportKind.L2CAP -> "L2CAP"
                }
            }
            .distinct()
            .joinToString(" / ")
            .ifEmpty { "未声明" }
    }

    private fun AdapterSnapshot.controlSummary(): String {
        val modeLabels = supportedNoiseModes.map { mode -> mode.displayName() }
        return when {
            modeLabels.isNotEmpty() -> modeLabels.joinToString(" / ")
            capabilities.audioHandoff -> "MiLink 流转与系统音量；无私有模式"
            else -> "无"
        }
    }
}

private fun AdapterResolution.displayName(): String = when (this) {
    AdapterResolution.STANDARD -> "标准"
    AdapterResolution.EXACT_MATCH -> "精确"
    AdapterResolution.FAMILY_MATCH -> "家族"
    AdapterResolution.PROTOCOL_CONFIRMED -> "协议确认"
}

private fun SystemProfileState.displayName(): String = when (this) {
    SystemProfileState.DISCONNECTED -> "未连接"
    SystemProfileState.CONNECTED -> "已连接"
}

private fun PrivateTransportState.displayName(): String = when (this) {
    PrivateTransportState.NOT_REQUIRED -> "无需"
    PrivateTransportState.IDLE -> "待连接"
    PrivateTransportState.CONNECTING -> "连接中"
    PrivateTransportState.CONNECTED -> "已连接"
    PrivateTransportState.RECOVERING -> "恢复中"
    PrivateTransportState.DORMANT -> "已休眠"
}

private fun ProtocolHandshakeState.displayName(): String = when (this) {
    ProtocolHandshakeState.NOT_REQUIRED -> "无需"
    ProtocolHandshakeState.PENDING -> "确认中"
    ProtocolHandshakeState.CONFIRMED -> "已确认"
    ProtocolHandshakeState.REJECTED -> "已拒绝"
}

private fun BatteryReading.displayValue(): String =
    percent?.let { value -> if (charging) "$value%+" else "$value%" } ?: "—"

internal fun NoiseMode.displayName(): String = when (this) {
    NoiseMode.ANC -> "降噪"
    NoiseMode.OFF -> "关闭"
    NoiseMode.TRANSPARENCY -> "通透"
    NoiseMode.WIND -> "抗风噪"
}

private fun NoiseMode?.displayNameOrUnknown(): String = this?.displayName() ?: "—"

private fun NoiseMode.controlOrder(): Int = when (this) {
    NoiseMode.ANC -> 0
    NoiseMode.OFF -> 1
    NoiseMode.TRANSPARENCY -> 2
    NoiseMode.WIND -> 3
}

private fun HeadsetFormFactor.displayName(): String = when (this) {
    HeadsetFormFactor.TWS -> "TWS"
    HeadsetFormFactor.GLASSES, HeadsetFormFactor.HEADPHONES -> "头戴"
}

private fun BatterySource.displayName(): String = when (this) {
    BatterySource.NONE -> "不提供"
    BatterySource.SYSTEM_AGGREGATE -> "Android 整机"
    BatterySource.PRIVATE_PROTOCOL -> "私有协议"
}
