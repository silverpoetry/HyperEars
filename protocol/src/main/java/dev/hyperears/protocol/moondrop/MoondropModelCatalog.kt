package dev.hyperears.protocol.moondrop

/**
 * Offline snapshot of the MOONDROP model rows relevant to HyperEars adapters.
 *
 * Distilled from the public vendor catalog endpoint
 * `GET https://cdn-service.moondroplab.tech/api/v1/products/all` (fetched 2026-09-16, see
 * [SNAPSHOT_FETCHED_AT]): the MIRAGE and Pudding rows carry that snapshot's UUID, `chipType`,
 * EQ band count, OTA flag and form factor. The Robin row's `chipType`, plane and OTA flag are
 * distilled from the same snapshot as well; only its UUID follows the repository's existing
 * Robin protocol documentation, which records none, so it stays `null` until a catalog
 * re-distillation adds it.
 *
 * This table is reconciliation data only. Adapter name matching remains the single source of
 * truth for device identity, and runtime code must never contact vendor services: the vendor
 * catalog is still edited after publication (the MIRAGE row carried an update timestamp equal
 * to the fetch date), so any runtime dependency would be unreproducible and would leak
 * connectivity. Updates happen exclusively through a manual re-distillation commit.
 *
 * Only interoperable facts are stored (precedent: `BoseProductCatalog`). Frequency-response
 * files, wallpapers, policy HTML and raw JSON from the vendor are not part of this repository.
 */
object MoondropModelCatalog {

    /**
     * HyperEars protocol-plane classification. `plane` is a repository-side mapping distilled
     * from the vendor `chipType` (plus known unmapped or out-of-scope channels), registered
     * row by row; it is not a field served by the vendor catalog.
     */
    enum class MoondropPlane {
        /** Blutter-free classic SPP frame family verified on devices with `chipType=jieli`. */
        FF_JIELI,

        /** Classic SPP frame family for `chipType=bluetrum` models. */
        FF_BLUETRUM,

        /** Qualcomm GAIA v3 SPP (`chipType=qualcomm`). */
        GAIA_QUALCOMM,

        /** Airoha HAL channel (`chipType=airoha`). */
        AIROHA,

        /** BLE GATT `9ECA…` service family; the vendor catalog maps no model to it. */
        BLE_9ECA_UNMAPPED,

        /** USB DAC/IEM channel — out of HyperEars scope. */
        USB_OUT_OF_SCOPE,

        /** Wired IEM channel — out of HyperEars scope. */
        WIRED_OUT_OF_SCOPE,
    }

    /**
     * One distilled catalog row.
     *
     * @param uuid vendor catalog UUID; `null` when the repository documentation does not
     * record one.
     * @param normalizedAliases lowercase alphanumeric-only names the corresponding adapter is
     * expected to accept (kept consistent by `MoondropModelCatalogTest`).
     * @param eqBands vendor EQ band count; `null` when not recorded or deliberately not
     * mapped to any code capability (EQ is not an implemented capability surface).
     * @param formFactor vendor form-factor string, e.g. `BT-TWS`.
     */
    data class ModelEntry(
        val uuid: String?,
        val displayName: String,
        val normalizedAliases: Set<String>,
        val chipType: String,
        val plane: MoondropPlane,
        val eqBands: Int?,
        val hasOta: Boolean,
        val formFactor: String,
    )

    /** Date the `products/all` snapshot behind the distilled rows was fetched. */
    const val SNAPSHOT_FETCHED_AT: String = "2026-09-16"

    /** Public vendor endpoint the snapshot was distilled from (never contacted at runtime). */
    const val SNAPSHOT_SOURCE_URL: String =
        "https://cdn-service.moondroplab.tech/api/v1/products/all"

    /**
     * MIRAGE candidate row (reference protocol): snapshot facts only; the plane is an
     * inference from the same `chipType` and the unique catalog relation to Pudding.
     */
    val MIRAGE = ModelEntry(
        uuid = "e9aa2b61-74d5-451f-995d-254d3a9e6666",
        displayName = "MOONDROP MIRAGE",
        normalizedAliases = setOf(
            "moondropmirage",
            "水月雨mirage",
            "hatsunemikumoondrop",
        ),
        chipType = "jieli",
        plane = MoondropPlane.FF_JIELI,
        eqBands = 10,
        hasOta = true,
        formFactor = "BT-TWS",
    )

    /** Pudding row: device-verified FF frame family, distilled snapshot facts. */
    val PUDDING = ModelEntry(
        uuid = "291a21ef-bca1-4cf1-baf8-151c457bf082",
        displayName = "MOONDROP Pudding",
        normalizedAliases = setOf(
            "moondroppudding",
            "水月雨pudding",
        ),
        chipType = "jieli",
        plane = MoondropPlane.FF_JIELI,
        eqBands = 10,
        hasOta = true,
        formFactor = "BT-TWS",
    )

    /**
     * Robin row: `chipType`, plane and the OTA flag are distilled from the 2026-09-16
     * snapshot; the repository's Robin protocol documentation records no vendor UUID, so
     * `uuid` stays `null`.
     */
    val ROBIN = ModelEntry(
        uuid = null,
        displayName = "MOONDROP Robin",
        normalizedAliases = setOf(
            "robinsearphones",
            "moondroprobin",
            "moondroprobinsearphones",
            "水月雨知更鸟",
        ),
        chipType = "bluetrum",
        plane = MoondropPlane.FF_BLUETRUM,
        eqBands = null,
        hasOta = false,
        formFactor = "BT-TWS",
    )

    /**
     * Scope discipline: only models with a HyperEars adapter or adapter plan enter this
     * table. The remaining vendor catalog models stay out so the table can never read as a
     * support promise.
     */
    val models: List<ModelEntry> = listOf(
        MIRAGE,
        PUDDING,
        ROBIN,
    )

    init {
        val knownUuids = models.mapNotNull(ModelEntry::uuid)
        require(knownUuids.size == knownUuids.distinct().size) {
            "MOONDROP catalog UUIDs must be unique"
        }
        require(models.map(ModelEntry::displayName).distinct().size == models.size) {
            "MOONDROP catalog display names must be unique"
        }
    }

    fun findByUuid(uuid: String): ModelEntry? = models.firstOrNull { it.uuid == uuid }
}
