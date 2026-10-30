package dev.hyperears.presentation

internal data class NativeFlowState(
    val connecting: Boolean = false,
    val showing: Boolean = false,
    val shownBefore: Boolean = false,
) {
    fun blocks(manual: Boolean): Boolean = connecting || showing || (!manual && shownBefore)
}

/** Submission is observable; the native void API does not confirm that SystemUI rendered it. */
internal enum class NativePresentationResult(val message: String) {
    SUBMITTED("已提交预览"),
    NO_DEVICE("没有已连接的耳机"),
    PAUSED("模块已暂停"),
    OWNERSHIP_UNAVAILABLE("系统耳机登记检查不可用"),
    SYNC_PENDING("耳机状态正在同步，请稍后预览"),
    SYSTEM_DEVICE("该耳机由系统原生功能管理"),
    HOST_UNAVAILABLE("当前系统的展示入口不可用"),
    SCREEN_UNAVAILABLE("请在解锁并亮屏后预览"),
    BUSY("系统正在处理其他连接提示"),
    NATIVE_DISABLED("系统快速连接提示已关闭"),
    NO_BATTERY("耳机电量尚未就绪"),
    RESOURCE_UNAVAILABLE("系统耳机展示资源不可用"),
    FAILED("预览请求失败，请查看详细日志"),
}

/** A fresh host check requests the canonical Bluetooth update; it cannot forge a newer snapshot. */
internal fun previewAdmissionResult(confirmed: NativeAdmission, published: NativeAdmission): NativePresentationResult? = when {
    confirmed == NativeAdmission.SYSTEM || published == NativeAdmission.SYSTEM -> NativePresentationResult.SYSTEM_DEVICE
    confirmed == NativeAdmission.UNKNOWN -> NativePresentationResult.OWNERSHIP_UNAVAILABLE
    published == NativeAdmission.UNKNOWN -> NativePresentationResult.SYNC_PENDING
    else -> null
}
