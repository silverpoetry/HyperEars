package dev.hyperears.protocol.apple

/** Source identity records, not transport or capability promises. No namespace is inferred. */
object AppleModelCatalog {
    enum class Namespace { APPLE_AACP, CLONE_HQB }
    data class Model(val key: String, val label: String, val modelIndex: Int, val namespace: Namespace,
        val modelIdentifiers: Set<String> = emptySet())
    val models: List<Model> = listOf(
        Model("AIRPODS", "AirPods", 2, Namespace.APPLE_AACP, setOf("A1523", "A1722")),
        Model("AIRPODS2", "AirPods 2", 15, Namespace.APPLE_AACP, setOf("A2032", "A2031")),
        Model("AIRPODS3", "AirPods 3", 19, Namespace.APPLE_AACP, setOf("A2565", "A2564")),
        Model("AIRPODS4", "AirPods 4", 25, Namespace.APPLE_AACP, setOf("A3053", "A3050", "A3054")),
        Model("AIRPODS4_ANC", "AirPods 4 ANC", 27, Namespace.APPLE_AACP, setOf("A3056", "A3055", "A3057")),
        Model("AIRPODS5", "AirPods 5", 54, Namespace.APPLE_AACP, setOf("A3531", "A3532", "A3533")),
        Model("AIRPODS5_WIRELESS", "AirPods 5 Wireless", 48, Namespace.APPLE_AACP, setOf("A3439", "A3440", "A3441")),
        Model("AIRPODSPRO", "AirPods Pro", 14, Namespace.APPLE_AACP, setOf("A2084", "A2083")),
        Model("AIRPODSPRO2", "AirPods Pro 2 Lightning", 20, Namespace.APPLE_AACP, setOf("A2931", "A2699", "A2698")),
        Model("AIRPODSPRO3", "AirPods Pro 3", 39, Namespace.APPLE_AACP, setOf("A3063", "A3064", "A3065")),
        Model("AIRPODSPRO2_USBC", "AirPods Pro 2 USB-C", 36, Namespace.APPLE_AACP, setOf("A3047", "A3048", "A3049")),
        Model("POWERBEATSPRO2", "Powerbeats Pro 2", 29, Namespace.APPLE_AACP),
        Model("AIRPODSMAX", "AirPods Max", 10, Namespace.APPLE_AACP),
        Model("AIRPODSMAX_USBC", "AirPods Max USB-C", 31, Namespace.APPLE_AACP),
        Model("AIRPODSMAX2", "AirPods Max 2", 45, Namespace.APPLE_AACP),
        Model("BEATSSTUDIOBUDS", "Beats Studio Buds", 17, Namespace.APPLE_AACP),
        Model("BEATSSTUDIOBUDSPLUS", "Beats Studio Buds +", 22, Namespace.APPLE_AACP),
        Model("BEATS360", "Beats 360", 56, Namespace.APPLE_AACP),
        Model("BEATSSOLOBUDS", "Beats Solo Buds", 38, Namespace.APPLE_AACP),
        Model("POWERBEATSPRO", "Powerbeats Pro", 11, Namespace.APPLE_AACP),
        Model("BEATSX", "BeatsX", 5, Namespace.APPLE_AACP),
        Model("POWERBEATS3", "Powerbeats 3", 3, Namespace.APPLE_AACP),
        Model("POWERBEATS4", "Powerbeats 4", 13, Namespace.APPLE_AACP),
        Model("BEATSSOLO3", "Beats Solo 3", 6, Namespace.APPLE_AACP),
        Model("BEATSSOLO4", "Beats Solo 4", 37, Namespace.APPLE_AACP),
        Model("BEATSSOLOPRO", "Beats Solo Pro", 12, Namespace.APPLE_AACP),
        Model("BEATSSTUDIO3", "Beats Studio 3", 9, Namespace.APPLE_AACP),
        Model("BEATSFITPRO", "Beats Fit Pro", 18, Namespace.APPLE_AACP),
        Model("POWERBEATSFIT", "Powerbeats Fit", 47, Namespace.APPLE_AACP),
        Model("BEATSFLEX", "Beats Flex", 16, Namespace.APPLE_AACP),
        Model("BEATSSTUDIOPRO", "Beats Studio Pro", 23, Namespace.APPLE_AACP),
        Model("AIRPODSPROHQB", "AIRPODSPROHQB", 14, Namespace.CLONE_HQB),
        Model("AIRPODSHQB", "AIRPODSHQB", 2, Namespace.CLONE_HQB),
        Model("AIRPODS3HQB", "AIRPODS3HQB", 19, Namespace.CLONE_HQB),
        Model("AIRPODS4HQB", "AIRPODS4HQB", 27, Namespace.CLONE_HQB),
        Model("AIRPODSPRO2HQB", "AIRPODSPRO2HQB", 20, Namespace.CLONE_HQB),
        Model("AIRPODSPRO3HQB", "AIRPODSPRO3HQB", 39, Namespace.CLONE_HQB),
    )
    fun byIndex(namespace: Namespace, index: Int): Model? = models.singleOrNull { it.namespace == namespace && it.modelIndex == index }
    fun byModelIdentifier(identifier: String): Model? = models.singleOrNull { identifier in it.modelIdentifiers }
}
