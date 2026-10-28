package dev.hyperears.hook

import android.content.Context
import java.lang.reflect.Proxy

/** Reflects only verified public host preference APIs; host classes never cross module type casts. */
internal class SettingsPreferenceHost(private val loader: ClassLoader, private val context: Context, val screen: Any) {
    private val preference = loader.loadClass("androidx.preference.Preference")
    private val group = loader.loadClass("androidx.preference.PreferenceGroup")
    private val category = loader.loadClass("androidx.preference.PreferenceCategory")
    private val list = loader.loadClass("androidx.preference.ListPreference")
    private val change = loader.loadClass("androidx.preference.Preference\$OnPreferenceChangeListener")
    private val click = loader.loadClass("androidx.preference.Preference\$OnPreferenceClickListener")
    init {
        require(group.isInstance(screen))
        // Resolve all required entry points before touching the host screen.
        group.getMethod("addPreference", preference); group.getMethod("removePreference", preference)
        preference.getMethod("getParent"); preference.getMethod("setPersistent", Boolean::class.javaPrimitiveType)
        category.getConstructor(Context::class.java); list.getConstructor(Context::class.java)
        preference.getMethod("setOnPreferenceChangeListener", change)
        preference.getMethod("setOnPreferenceClickListener", click)
    }
    fun category(key: String, title: String) = create(category, key, title)
    fun row(key: String, title: String) = create(preference, key, title)
    fun choice(key: String, title: String, labels: List<String>, values: List<String>, selected: String?, enabled: Boolean,
        changed: (String) -> Unit): Any = create(list, key, title).also { row ->
        list.getMethod("setEntries", Array<CharSequence>::class.java).invoke(row, labels.toTypedArray<CharSequence>())
        list.getMethod("setEntryValues", Array<CharSequence>::class.java).invoke(row, values.toTypedArray<CharSequence>())
        list.getMethod("setValue", String::class.java).invoke(row, selected)
        enable(row, enabled)
        preference.getMethod("setOnPreferenceChangeListener", change).invoke(row, Proxy.newProxyInstance(loader, arrayOf(change)) { _, method, args ->
            if (method.name == "onPreferenceChange") { (args?.getOrNull(1) as? String)?.takeIf(values::contains)?.let(changed); false }
            else proxyObject(method.name)
        })
    }
    fun onClick(row: Any, clicked: () -> Unit) {
        preference.getMethod("setOnPreferenceClickListener", click).invoke(row, Proxy.newProxyInstance(loader, arrayOf(click)) { _, method, _ ->
            if (method.name == "onPreferenceClick") { clicked(); true } else proxyObject(method.name)
        })
    }
    fun add(parent: Any, row: Any) { check(group.getMethod("addPreference", preference).invoke(parent, row) == true) }
    fun detach(row: Any) { preference.getMethod("getParent").invoke(row)?.let { group.getMethod("removePreference", preference).invoke(it, row) } }
    fun parent(row: Any): Any? = preference.getMethod("getParent").invoke(row)
    fun summary(row: Any, value: String) { preference.getMethod("setSummary", CharSequence::class.java).invoke(row, value) }
    fun enable(row: Any, value: Boolean) { preference.getMethod("setEnabled", Boolean::class.javaPrimitiveType).invoke(row, value) }
    fun clearListeners(row: Any) {
        preference.getMethod("setOnPreferenceClickListener", click).invoke(row, null)
        preference.getMethod("setOnPreferenceChangeListener", change).invoke(row, null)
    }
    private fun create(type: Class<*>, key: String, title: String) = type.getConstructor(Context::class.java).newInstance(context).also {
        preference.getMethod("setKey", String::class.java).invoke(it, key)
        preference.getMethod("setTitle", CharSequence::class.java).invoke(it, title)
        preference.getMethod("setPersistent", Boolean::class.javaPrimitiveType).invoke(it, false)
    }
    private fun proxyObject(name: String): Any? = when (name) { "hashCode" -> 0; "equals" -> false; "toString" -> "HyperEars preference listener"; else -> null }
}
