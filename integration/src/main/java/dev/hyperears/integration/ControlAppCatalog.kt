package dev.hyperears.integration

/**
 * Verified Android package identities for vendor headset controllers.
 *
 * Package names are transport ownership boundaries, not device-identification evidence. Adapters
 * opt into one or more entries; an installed application alone never changes adapter matching.
 */
object ControlAppCatalog {
    val vivoEarphones = ControlAppSpec(
        packageName = "com.vivo.vivotws",
        displayName = "vEarphones",
    )

    val heyMelody = ControlAppSpec(
        packageName = "com.heytap.headset",
        displayName = "HeyMelody",
    )

    val oplusWirelessEarphones = ControlAppSpec(
        packageName = "com.oplus.melody",
        displayName = "Wireless Earphones",
    )

    val colorOsWirelessEarphones = ControlAppSpec(
        packageName = "com.coloros.oppopods",
        displayName = "Wireless Earphones (ColorOS 11)",
    )

    val bose = ControlAppSpec(
        packageName = "com.bose.bosemusic",
        displayName = "Bose",
    )

    val boseConnect = ControlAppSpec(
        packageName = "com.bose.monet",
        displayName = "Bose Connect",
    )

    val edifierConnect = ControlAppSpec(
        packageName = "com.edifier.edifierconnect",
        displayName = "Edifier Connect",
    )

    val roseLink = ControlAppSpec(
        packageName = "cn.ikaile.ruoshui.client",
        displayName = "ROSELINK",
    )

    val lightYear = ControlAppSpec(
        packageName = "cn.lightyeartech.android",
        displayName = "LightYear",
    )

    val yuanDao = ControlAppSpec(
        packageName = "com.yuandao.nicehck",
        displayName = "原道",
    )

    val sonySoundConnect = ControlAppSpec(
        packageName = "com.sony.songpal.mdr",
        displayName = "Sony | Sound Connect",
    )

    val qcy = ControlAppSpec(
        packageName = "com.qcymall.googleearphonesetup",
        displayName = "QCY",
    )

    val moondrop = ControlAppSpec(
        packageName = "com.moondroplab.moondrop.moondrop_app",
        displayName = "MOONDROP",
    )

    val all: List<ControlAppSpec> = listOf(
        vivoEarphones,
        heyMelody,
        oplusWirelessEarphones,
        colorOsWirelessEarphones,
        bose,
        boseConnect,
        edifierConnect,
        roseLink,
        lightYear,
        yuanDao,
        sonySoundConnect,
        qcy,
        moondrop,
    )

    private val byPackage = all.associateBy(ControlAppSpec::packageName)

    init {
        require(byPackage.size == all.size) { "Control app package names must be unique" }
    }

    fun find(packageName: String): ControlAppSpec? = byPackage[packageName]

    val packageNames: Set<String> = byPackage.keys

    /** Preserves adapter-declared priority when several controller processes are alive. */
    fun activeOwner(
        candidates: List<ControlAppSpec>,
        activePackages: Set<String>,
    ): ControlAppSpec? = candidates.firstOrNull { it.packageName in activePackages }
}
