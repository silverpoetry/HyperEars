package dev.hyperears.presentation

/** The AirPods controller's display ABI, kept separate from the shared window authorization. */
internal fun NativeHeadsetProjection.airPodsStateArray(): Array<String> = arrayOf(
    left.percent?.toString() ?: "-",
    right.percent?.toString() ?: "-",
    chargingCase.percent?.toString() ?: "-",
    "false",
    "false",
    left.charging.toString(),
    right.charging.toString(),
    chargingCase.charging.toString(),
    address,
    "2",
    name,
)
