package dev.hyperears.presentation

import android.content.Context
import android.os.IBinder
import dev.hyperears.integration.EarbudState

/** AirPods-shaped native renderer; all admission and window ownership stay in the shared bridge. */
internal class AirPodsWindowBridge(
    context: Context,
    forBinding: (NativeWindowRequest) -> NativeWindowRequest?,
    onOpened: (String, IBinder) -> Unit,
    onClosed: (String) -> Unit,
    openSettings: (Context, EarbudState) -> Boolean,
) : NativeWindowBridge(context, forBinding, onOpened, onClosed, openSettings) {
    private lateinit var symbols: AirPodsWindowSymbols
    override val serviceContract = NativeWindowServiceContract.AIRPODS

    override fun resolveSymbols(): NativeWindowSymbols =
        AirPodsWindowSymbols(appClassLoader).also { symbols = it }

    override fun installModelHooks() {
        hookAfter(symbols.build) {
            val binding = creatingBinding() ?: return@hookAfter
            if (args[0] != binding.state.address) return@hookAfter
            val info = result ?: return@hookAfter
            val p = projection(binding)
            setInt(info, "setConnectState", 2)
            setInt(info, "setLeftBattery", p.left.percent ?: -1)
            setInt(info, "setRightBattery", p.right.percent ?: -1)
            setInt(info, "setBoxBattery", p.chargingCase.percent ?: -1)
            setInt(info, "setChargingState", charging(p))
            setInt(info, "setInEarState", 0)
        }
        hookBefore(symbols.read) {
            val binding = bindingFor(instance) ?: return@hookBefore
            if (binding.active && args[0] == binding.state.address) result = projection(binding).airPodsStateArray()
        }
        installBatteryLayout(symbols.batteryLayout)
    }

    override fun synchronizeModel(binding: Binding) {
        val p = projection(binding)
        val info = requireNotNull(NativeMembers.get(binding.controller, "mAirPodsInfo"))
        setInt(info, "setWearType", if (p.singleBattery) 3 else 0)
        val record = requireNotNull(NativeMembers.get(binding.controller, "mCloudResRecord"))
        NativeMembers.set(record, "mTwsHeadset", !p.singleBattery)
    }

    companion object {
        const val PREFIX = NativeWindowBridge.PREFIX
        const val CONTROLLER = "$PREFIX.airpods.MiuiAirPodsFastConnectController"
    }
}
