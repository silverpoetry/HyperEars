package dev.hyperears.integration

/** X3 Pro candidate probe; valid protocol replies unlock individual controls. */
class RayNeoX3ProAdapter : EarbudAdapter() {
    override val id = "rayneo-x3-pro"
    override val displayName = "RayNeo X3 Pro"
    override val resolution = AdapterResolution.EXACT_MATCH
    override val privateProtocolRequired = true
    override val batterySource = BatterySource.SYSTEM_AGGREGATE
    override val capabilities = EarbudCapabilities(battery = true, audioHandoff = true)
    override val formFactor = HeadsetFormFactor.GLASSES
    override val controlApps = listOf(ControlAppCatalog.rayneo)
    override val transports = listOf(GattTransportSpec(
        serviceUuid = "0000b81d-0000-1000-8000-00805f9b34fb",
        writeCharacteristicUuid = "ea8b70d5-2bd3-49ab-9c31-9c38b2c3c4f9",
        notifyCharacteristicUuid = "7db3e235-3608-41f3-a03c-955fcbd2ea4b",
        notificationsRequired = true,
        requestedMtu = 256,
        minimumMtu = 256,
        id = "rayneo-x3-pro-control",
    ))
    override fun matches(identity: EarbudIdentity): Boolean =
        // The paired X3 Pro exposes A2DP/HFP but reports uncategorized class 0x1F00.
        // Its exact model name selects only a probe, never confirmed controls.
        !identity.nativeSystemEarbud &&
            Regex("rayneox3pro(?:[a-z0-9]{4})?").matches(normalizeDeviceName(identity.deviceName.orEmpty()))

    override fun createProtocolSession(): ProtocolSession = RayNeoProtocolSession()
    override val featureStateContract = StandardDeviceFeatureStateContract.extending { _, state ->
        state is RayNeoAppsFeatureState || state is RayNeoSettingsFeatureState || state is RayNeoWifiFeatureState || state is RayNeoCaptureFeatureState
    }
    override val controlRequestContract = ControlRequestContract { adapter, request ->
        when (request) {
            is RayNeoControlRequest.LaunchApp -> adapter.runtimeState().features.get<RayNeoAppsFeatureState>()?.supports(request) == true
            is RayNeoControlRequest.LaunchInstalledApp -> adapter.runtimeState().features.get<RayNeoAppsFeatureState>()?.supports(request) == true
            is RayNeoControlRequest.ScanWifi -> adapter.runtimeState().features.get<RayNeoSettingsFeatureState>()?.let {
                it.verifiedX3Pro && it.wifiEnabled == true && (request.countryCode.isEmpty() || Regex("[A-Z]{2}").matches(request.countryCode))
            } == true
            is RayNeoControlRequest.ConnectWifi -> adapter.runtimeState().features.get<RayNeoSettingsFeatureState>()?.let {
                it.verifiedX3Pro && it.wifiEnabled == true && adapter.runtimeState().features.get<RayNeoWifiFeatureState>()?.supports(request) == true
            } == true
            is RayNeoControlRequest -> adapter.runtimeState().features.get<RayNeoSettingsFeatureState>()?.supports(request) == true
            else -> StandardControlRequestContract.supports(adapter, request)
        }
    }
    override fun batterySourceAfterProtocolEvidence() = BatterySource.PRIVATE_PROTOCOL
    override fun onControlWritten(request: ControlRequest, scope: AdapterEventScope) {
        if(request is RayNeoControlRequest.ScanWifi) scope.requestState(RayNeoWifiFeatureState.STOP_QUERY,12_000L)
        else if(request === RayNeoControlRequest.CaptureShot) scope.requestState(RayNeoCaptureCodec.SHOT_COMMIT_QUERY,4_000L)
        else super.onControlWritten(request,scope)
    }
    override fun onProtocolReset() {
        removeFeatureState(RayNeoAppsFeatureState.FEATURE_ID)
        removeFeatureState(RayNeoSettingsFeatureState.FEATURE_ID)
        removeFeatureState(RayNeoWifiFeatureState.FEATURE_ID)
        removeFeatureState(RayNeoCaptureFeatureState.FEATURE_ID)
    }
}
