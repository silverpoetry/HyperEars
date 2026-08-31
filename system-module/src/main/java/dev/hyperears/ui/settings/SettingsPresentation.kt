package dev.hyperears.ui.settings

import dev.hyperears.integration.EarbudAdapterKind
import dev.hyperears.settings.MoreSettingsTarget

internal val MoreSettingsTarget.actionLabel: String
    get() = when (this) {
        MoreSettingsTarget.SYSTEM_SETTINGS -> "打开系统设置"
        MoreSettingsTarget.VENDOR_APP -> "打开厂商 App"
        MoreSettingsTarget.HYPEREARS -> "打开 HyperEars"
    }

internal val EarbudAdapterKind.sectionTitle: String
    get() = when (this) {
        EarbudAdapterKind.MODEL -> "具体型号"
        EarbudAdapterKind.FAMILY -> "家族回退"
        EarbudAdapterKind.STANDARD -> "标准回退"
    }
