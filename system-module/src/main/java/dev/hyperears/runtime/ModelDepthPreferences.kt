package dev.hyperears.runtime

import android.content.Context
import androidx.core.content.edit
import android.content.SharedPreferences
import dev.hyperears.integration.DailingAncDepth

/**
 * Small persistent store for model preferences that must outlive one Bluetooth session.
 *
 * It lives in the Bluetooth process data directory because the session runs there and the
 * shared module settings surface is read-only for injected processes. Only values the model
 * adapter exposes through a typed request are stored here.
 */
internal object ModelDepthPreferences {
    private const val PREFERENCES_NAME = "hyperears_model_preferences"
    private const val DAILING_ANC_DEPTH = "dailing_anc_depth"

    @Volatile
    private var preferences: SharedPreferences? = null

    fun bind(context: Context) {
        if (preferences != null) return
        preferences = context.getSharedPreferences(PREFERENCES_NAME, Context.MODE_PRIVATE)
    }

    /** Last depth chosen by the user, or null when nothing has been chosen yet. */
    val dailingAncDepth: DailingAncDepth?
        get() = preferences
            ?.getString(DAILING_ANC_DEPTH, null)
            ?.let { stored -> DailingAncDepth.entries.firstOrNull { it.name == stored } }

    fun setDailingAncDepth(depth: DailingAncDepth): Boolean = runCatching {
        val current = preferences ?: return false
        current.edit(commit = true) { putString(DAILING_ANC_DEPTH, depth.name) }
        true
    }.getOrDefault(false)
}
