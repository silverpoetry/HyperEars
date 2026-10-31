package dev.hyperears.hook

/** Display-only fallback: current X3 Pro BLE app replies contain package identifiers, not labels.
 * Names were read from the device PackageManager; launching still uses the original package. */
internal object RayNeoAppLabels {
    private val known = mapOf(
        "app.smarttube.rayneo" to "SmartTube",
        "com.bluberry.aircast" to "蓝莓投屏",
        "com.bytedance.android.doubaoime" to "豆包输入法",
        "com.codex.rm10autostart" to "RM10 自动激活",
        "com.duanju.duanju_app.rayneo.debug" to "真果鉴",
        "com.eg.android.AlipayGGlasses" to "看一下支付",
        "com.ffalcon.navigation" to "地图",
        "com.ffalcon.translate" to "翻译",
        "com.github.mytv.dv" to "myDV",
        "com.hongguotv.rayneo" to "红果TV",
        "com.jd.jr.joyaibuy" to "JoyGlance",
        "com.larus.nova" to "豆包",
        "com.limelight.debug" to "Moonlight",
        "com.mb.android" to "Emby",
        "com.rayneo.airscreen" to "浮窗",
        "com.rayneo.bluecast.tests" to "BlueCast renderer checks",
        "com.rayneo.combatscanner" to "战斗力探测器X",
        "com.rayneo.gallery" to "相册",
        "com.rayneo.lifeai" to "智慧生活",
        "com.rayneo.live.ai" to "探索视界",
        "com.rayneo.media" to "媒体",
        "com.rayneo.notes" to "待办",
        "com.rayneo.phone" to "电话",
        "com.rayneo.record" to "AI 录音",
        "com.sipeter.clonetts" to "CloneTTS",
        "com.swordfish.lemuroid.debug" to "Lemuroid",
        "com.wayneshao.bbll" to "BBLL",
        "com.wayneshao.bbll.rayneo.tests" to "BBLL RayNeo verification",
        "com.wayneshao.rayneovr" to "RayNeo VR",
        "com.wayneshao.x3bthelper" to "X3 Helper",
        "com.zhang.xfilemanager" to "X3 文件管理",
        "io.legado.app.debug" to "阅读·D",
    )
    fun label(packageName: String): String = known[packageName] ?: packageName
}
