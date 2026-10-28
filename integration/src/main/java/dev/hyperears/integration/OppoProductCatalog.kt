package dev.hyperears.integration

/**
 * Identity-only catalog from Leaf-lsgtky/OppoPods 929d813f, device_models.json.
 * GPL-3.0 declared by upstream; see THIRD_PARTY_NOTICES.md. No artwork or feature flags imported.
 * All 141 records are candidates, not hardware validation. Explicit SPP plus headset type admits
 * a private probe; absent/false SPP and speakers remain catalog-only.
 */
object OppoProductCatalog {
    private fun normalizeDeviceName(name: String): String =
        name.lowercase(java.util.Locale.ROOT).filter { it.isLetterOrDigit() || it == '+' }

    data class Product(val productId: Int, val privateProbeCandidate: Boolean, val aliases: List<String>) {
        val displayName: String get() = aliases.first()
        val adapterId: String get() = when (productId) {
            0x063810 -> OppoEncoAir2ProAdapter.ID
            0x068C10 -> OppoEncoFree4Adapter.ID
            0x067410 -> OppoEncoX3Adapter.ID
            0x06D810 -> OppoEncoAir5Adapter.ID
            else -> "oppo-product-${productId.toString(16).padStart(6, '0')}"
        }
        val wireConfig: OppoWireConfig get() = if (productId == 0x063810) {
            OppoWireConfig.COMPATIBLE
        } else {
            OppoWireConfig.STANDARD
        }
    }

    val products: List<Product> = listOf(
        Product(0x06F010, true, listOf("OPPO Enco Air4s", "Enco Air4s")),
        Product(0x06B450, true, listOf("OPPO Enco R5", "OPPO Enco xiapu OF", "Enco R5", "Enco xiapu OF")),
        Product(0x066812, true, listOf("realme Buds Air8")),
        Product(0x067414, true, listOf("OnePlus Flow Buds", "OnePlus Nord Buds Laplace 4")),
        Product(0x06E810, true, listOf("OPPO Enco Air5s（星光版）", "OPPO Enco Laplace 3", "Enco Air5s（星光版）", "Enco Laplace 3")),
        Product(0x06C810, true, listOf("OPPO Enco Air5s", "OPPO Enco Laplace 1", "OPPO Enco Laplace 2", "Enco Air5s", "Enco Laplace 1", "Enco Laplace 2")),
        Product(0x067014, true, listOf("OnePlus Nord Buds 4", "OnePlus Nord Buds ohm EX")),
        Product(0x06E010, true, listOf("OPPO Enco Clip2", "OPPO Enco Nobel", "Enco Clip2", "Enco Nobel")),
        Product(0x06D810, true, listOf("OPPO Enco Air5", "OPPO Enco ohm DO", "OPPO Enco ohm EX", "OPPO Enco ohm", "Enco Air5", "Enco ohm DO", "Enco ohm EX", "Enco ohm")),
        Product(0x06C410, true, listOf("OPPO Enco Air5 Pro", "OPPO Enco Einstein 1", "OPPO Enco Einstein 3", "Enco Air5 Pro", "Enco Einstein 1", "Enco Einstein 3")),
        Product(0x066414, true, listOf("OnePlus Buds Ace 3", "OnePlus Nord Buds Einstein 1")),
        Product(0x066814, true, listOf("OnePlus Nord Buds 4 Pro", "OnePlus Nord Buds Einstein 2")),
        Product(0x066054, true, listOf("OnePlus Buds 3V", "OnePlus Nord Curie DO")),
        Product(0x065854, true, listOf("OnePlus Nord Buds 3r", "OnePlus Nord Curie", "OnePlus Nord Newton")),
        Product(0x06B810, true, listOf("OPPO Enco Clip", "Enco Clip")),
        Product(0x065C14, true, listOf("OnePlus Open Buds")),
        Product(0x061410, true, listOf("OPPO Enco X", "Enco X")),
        Product(0x060410, true, listOf("OPPO Enco Free", "Enco Free")),
        Product(0x062010, true, listOf("OPPO Enco Free2", "Enco Free2")),
        Product(0x061C10, true, listOf("OPPO Enco Play", "Enco Play")),
        Product(0x061810, true, listOf("OPPO Enco Air", "Enco Air")),
        Product(0x062410, true, listOf("OPPO Enco Buds", "Enco Buds")),
        Product(0x062810, true, listOf("OPPO Enco Air Lite", "Enco Air Lite")),
        Product(0x060C10, true, listOf("OPPO Enco W51", "Enco W51")),
        Product(0x060810, true, listOf("OPPO Enco W31", "Enco W31")),
        Product(0x061010, false, listOf("OPPO Enco W11", "Enco W11")),
        Product(0x060412, false, listOf("OPPO O-Free")),
        Product(0x062C10, false, listOf("OPPO Enco W31 Lite", "Enco W31 Lite")),
        Product(0x050410, true, listOf("OPPO Enco M31", "Enco M31")),
        Product(0x050010, false, listOf("OPPO Enco Quiet", "Enco Quiet")),
        Product(0x060414, true, listOf("OnePlus Buds")),
        Product(0x060814, true, listOf("OnePlus Buds Z")),
        Product(0x3D59CF, false, listOf("realme Buds Air")),
        Product(0xF85450, false, listOf("realme Buds Air Neo")),
        Product(0xE3009F, false, listOf("realme Buds Wireless Pro")),
        Product(0x8CD10F, false, listOf("realme Buds Air Pro")),
        Product(0xBA5D56, false, listOf("realme Buds Air 2")),
        Product(0x1B5374, false, listOf("realme Buds Air 2 Neo")),
        Product(0xC1E2B1, false, listOf("realme Buds Wireless 2")),
        Product(0xC70A6D, false, listOf("realme Buds Wireless 2 Neo")),
        Product(0x693878, false, listOf("realme Buds Q2")),
        Product(0x49074B, false, listOf("realme Cobble Speaker")),
        Product(0xC70A75, false, listOf("realme Pocket Speaker")),
        Product(0xA5FD66, false, listOf("realme Brick Speaker")),
        Product(0x060812, false, listOf("realme Buds Air 3")),
        Product(0x060C12, true, listOf("realme Buds Q2s")),
        Product(0x050412, false, listOf("realme Buds Wireless 2S")),
        Product(0x061812, true, listOf("realme Buds Air 3 Neo")),
        Product(0x061412, true, listOf("realme Buds T100")),
        Product(0x061012, true, listOf("realme Buds Air 3S")),
        Product(0x061C12, true, listOf("realme Buds Air 5 Pro")),
        Product(0x063012, true, listOf("realme Buds Air6 Pro")),
        Product(0x050812, true, listOf("realme Buds Wireless 3")),
        Product(0x062012, true, listOf("realme Buds Air 5")),
        Product(0x063412, true, listOf("realme Buds Air6")),
        Product(0x063812, true, listOf("realme Buds N1 Pro")),
        Product(0x063C12, true, listOf("realme Buds T310")),
        Product(0x064012, true, listOf("realme Buds N1")),
        Product(0x062412, true, listOf("realme Buds T300")),
        Product(0x064812, true, listOf("realme Buds Air7")),
        Product(0x064C12, true, listOf("realme Buds Air7 Pro")),
        Product(0x066C12, true, listOf("realme Buds Air8 Pro")),
        Product(0x066452, true, listOf("realme Buds T500 Pro")),
        Product(0x067452, true, listOf("realme Buds T500 Pro Harry Potter Edition")),
        Product(0x067052, true, listOf("realme Buds T500")),
        Product(0x065C12, true, listOf("realme Buds Clip")),
        Product(0x065452, true, listOf("realme Buds T200")),
        Product(0x062812, true, listOf("realme Buds T110")),
        Product(0x064412, true, listOf("realme Buds T01")),
        Product(0x065052, true, listOf("realme Buds T200 Lite")),
        Product(0x065852, true, listOf("realme Buds T200x")),
        Product(0x066012, true, listOf("realme TechLife Buds")),
        Product(0x050C12, true, listOf("realme Buds Wireless 3 Neo")),
        Product(0x051412, true, listOf("realme Buds Wireless 5 ANC")),
        Product(0x052052, true, listOf("realme Buds Wireless 6 ANC")),
        Product(0x051812, true, listOf("realme Buds Wireless 6 Neo")),
        Product(0x051C52, true, listOf("realme Buds Wireless 6")),
        Product(0xC70A6E, false, listOf("DIZO Wireless")),
        Product(0xC70A6F, false, listOf("DIZO GoPods D")),
        Product(0xC70A70, false, listOf("DIZO GoPods")),
        Product(0x060C14, true, listOf("OnePlus Buds Pro")),
        Product(0x062014, true, listOf("OnePlus Buds Pro 2")),
        Product(0x063014, true, listOf("OnePlus Buds Pro 2（轻享版）")),
        Product(0x063414, true, listOf("OnePlus Buds Pro 2R")),
        Product(0x064C10, true, listOf("OPPO Enco Air3", "Enco Air3")),
        Product(0x065C10, true, listOf("OPPO Enco Air3 Pro", "Enco Air3 Pro")),
        Product(0x065810, true, listOf("OPPO Enco R2", "Enco R2")),
        Product(0x066010, true, listOf("OPPO Enco Free3", "Enco Free3")),
        Product(0x061014, true, listOf("OnePlus Buds Z2")),
        Product(0x050810, false, listOf("OPPO Enco M32", "Enco M32")),
        Product(0x063010, true, listOf("OPPO Enco R", "Enco R")),
        Product(0x063410, true, listOf("OPPO Enco Air2", "Enco Air2")),
        Product(0x061C14, true, listOf("OnePlus Nord Buds CE")),
        Product(0x064010, true, listOf("OPPO Enco Free2i", "Enco Free2i")),
        Product(0x068414, true, listOf("OnePlus Buds")),
        Product(0x063C10, true, listOf("OPPO Enco X2", "Enco X2")),
        Product(0x068814, true, listOf("OnePlus Buds Z")),
        Product(0x063810, true, listOf("OPPO Enco Air2 Pro", "Enco Air2 Pro")),
        Product(0x050414, false, listOf("OnePlus Bullets Wireless Z2")),
        Product(0x061414, true, listOf("OnePlus Nord Buds")),
        Product(0x061814, true, listOf("OnePlus Buds N")),
        Product(0x064410, true, listOf("OPPO Enco Air2i", "Enco Air2i")),
        Product(0x064810, true, listOf("OPPO Enco Buds2", "Enco Buds2")),
        Product(0x065010, true, listOf("OPPO Enco R Pro", "Enco R Pro")),
        Product(0x062414, true, listOf("OnePlus Nord Buds 2")),
        Product(0x062C14, true, listOf("OnePlus Nord Buds 2r")),
        Product(0x062814, true, listOf("OnePlus Buds Ace")),
        Product(0x066C10, true, listOf("OPPO Enco Air2（新声版）", "Enco Air2（新声版）")),
        Product(0x067010, true, listOf("OPPO Enco Air3i", "Enco Air3i")),
        Product(0x050C10, true, listOf("OPPO Enco M33", "Enco M33")),
        Product(0x050C14, true, listOf("OnePlus BulletsWireless Z2 ANC")),
        Product(0x051014, true, listOf("OnePlus Bullets Wireless Z3")),
        Product(0x063C14, true, listOf("OnePlus Buds 3")),
        Product(0x066810, true, listOf("OPPO Enco X3i", "Enco X3i")),
        Product(0x067410, true, listOf("OPPO Enco X3", "Enco X3")),
        Product(0x064014, true, listOf("OnePlus Buds Pro 3")),
        Product(0x067810, true, listOf("OPPO Enco Air3s", "Enco Air3s")),
        Product(0x068010, true, listOf("OPPO Enco Buds2 Pro", "Enco Buds2 Pro")),
        Product(0x064814, true, listOf("OnePlus Buds V")),
        Product(0x068810, true, listOf("OPPO Enco Air 3i", "Enco Air 3i")),
        Product(0x068410, true, listOf("OPPO Enco R3", "Enco R3")),
        Product(0x067C10, true, listOf("OPPO Enco Air4 Pro", "Enco Air4 Pro")),
        Product(0x064414, true, listOf("OnePlus Nord Buds 3 Pro")),
        Product(0x069010, true, listOf("OPPO Enco Air4", "Enco Air4")),
        Product(0x065014, true, listOf("OnePlus Nord Buds 3")),
        Product(0x069C10, true, listOf("OPPO Enco R3 Pro", "Enco R3 Pro")),
        Product(0x06A010, true, listOf("OPPO Enco Buds3 Pro+", "Enco Buds3 Pro+")),
        Product(0x064C14, true, listOf("OnePlus Buds Ace 2")),
        Product(0x068C10, true, listOf("OPPO Enco Free4", "Enco Free4")),
        Product(0x06C010, true, listOf("OPPO Enco Free4（丹拿版）", "Enco Free4（丹拿版）")),
        Product(0x06BC10, true, listOf("OPPO Enco X3s", "Enco X3s")),
        Product(0x065414, true, listOf("OnePlus Buds 4")),
        Product(0x06A850, true, listOf("OPPO Enco Buds3 Pro", "Enco Buds3 Pro")),
        Product(0x069850, true, listOf("OPPO Enco Air4i", "Enco Air4i")),
        Product(0x06AC50, true, listOf("OPPO Enco R4", "Enco R4")),
        Product(0x06A450, true, listOf("OPPO Enco Buds3", "Enco Buds3")),
        Product(0x06B050, true, listOf("OPPO Enco Air4（新声版）", "Enco Air4（新声版）")),
        Product(0x06EC10, true, listOf("OPPO Enco X4", "OPPO Enco Bohr", "Enco X4", "Enco Bohr")),
        Product(0x06F450, true, listOf("OPPO Enco Buds3s", "Enco Buds3s")),
        Product(0x067814, true, listOf("OnePlus Buds Pro 4", "OnePlus Buds Bohr")),
        Product(0x06E410, true, listOf("OnePlus Buds Clip", "OnePlus Open Nobel")),
    )
    private val byId = products.associateBy(Product::productId)
    private val byName = products.flatMap { product ->
        product.aliases.map { normalizeDeviceName(it) to product }
    }.groupBy({ it.first }, { it.second })

    fun find(productId: Int): Product? = byId[productId]
    fun named(name: String): List<Product> = byName[normalizeDeviceName(name)].orEmpty().distinct()
    fun exact(name: String): Product? = named(name).singleOrNull()
}
