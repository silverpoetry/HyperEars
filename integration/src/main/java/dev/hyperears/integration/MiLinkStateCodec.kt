package dev.hyperears.integration

object MiLinkStateCodec {
    /** MiLink's native sentinel for hiding the ANC section when no state is available. */
    const val ANC_STATE_UNAVAILABLE = -1

    /**
     * MiLink always transports six integers, but their presentation depends on HeadsetInfo.type.
     *
     * Single-battery presentations read the level and charging flag from slots 2 and 5, so an
     * over-ear headset must not be projected onto the left/right UI.
     */
    fun batteryLevels(
        state: EarbudState,
        formFactor: HeadsetFormFactor = HeadsetFormFactor.TWS,
    ): List<Int> {
        if (formFactor != HeadsetFormFactor.TWS) {
            val aggregate = state.battery.overall.takeIf(BatteryReading::available)
                ?: state.battery.right.takeIf(BatteryReading::available)
                ?: state.battery.left
            return listOf(
                -1,
                -1,
                batteryPercent(aggregate),
                0,
                0,
                charging(aggregate),
            )
        }
        return listOf(
            batteryPercent(state.battery.case),
            batteryPercent(state.battery.left),
            batteryPercent(state.battery.right),
            charging(state.battery.case),
            charging(state.battery.left),
            charging(state.battery.right),
        )
    }

    fun regularBatteryLevel(state: EarbudState): Int {
        state.battery.overall.percent?.let { return it }
        val availableEars = listOf(state.battery.left, state.battery.right)
            .mapNotNull(BatteryReading::percent)
        return availableEars.minOrNull() ?: -1
    }

    /**
     * Encodes MiLink's stock three-state transport ABI.
     *
     * WIND uses the ANC-compatible value because it is presented as an ANC-branch option by the
     * model-specific card adapter, while MiLink continues to own its native three-state row.
     */
    fun ancState(state: EarbudState): Int = when (state.noiseMode) {
        NoiseMode.ANC, NoiseMode.WIND -> 1
        NoiseMode.TRANSPARENCY -> 2
        NoiseMode.OFF -> 0
        null -> ANC_STATE_UNAVAILABLE
    }

    private fun batteryPercent(reading: BatteryReading): Int = reading.percent ?: -1

    private fun charging(reading: BatteryReading): Int =
        if (reading.available && reading.charging) 1 else 0
}
