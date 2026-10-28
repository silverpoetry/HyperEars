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
        labels.getOrNull(values.indexOf(selected))?.let { summary(row, it) }
        enable(row, enabled)
        preference.getMethod("setOnPreferenceChangeListener", change).invoke(row, Proxy.newProxyInstance(loader, arrayOf(change)) { _, method, args ->
            if (method.name == "onPreferenceChange") { (args?.getOrNull(1) as? String)?.takeIf(values::contains)?.let(changed); false }
            else proxyObject(method.name)
        })
    }
    fun toggle(key: String, title: String, checked: Boolean, enabled: Boolean, changed: (Boolean) -> Unit): Any {
        val type = loader.loadClass("androidx.preference.SwitchPreferenceCompat")
        return create(type, key, title).also { row ->
            type.getMethod("setChecked", Boolean::class.javaPrimitiveType).invoke(row, checked)
            enable(row, enabled)
            preference.getMethod("setOnPreferenceChangeListener", change).invoke(row, Proxy.newProxyInstance(loader, arrayOf(change)) { _, method, args ->
                if (method.name == "onPreferenceChange") { (args?.getOrNull(1) as? Boolean)?.let(changed); false }
                else proxyObject(method.name)
            })
        }
    }
    fun range(key: String, title: String, values: IntRange, selected: Int, enabled: Boolean, changed: (Int) -> Unit): Any {
        val type = loader.loadClass("androidx.preference.SeekBarPreference")
        return create(type, key, title).also { row ->
            type.getMethod("setMin", Int::class.javaPrimitiveType).invoke(row, values.first)
            type.getMethod("setMax", Int::class.javaPrimitiveType).invoke(row, values.last)
            type.getMethod("setValue", Int::class.javaPrimitiveType).invoke(row, selected)
            type.getMethod("setAdjustable", Boolean::class.javaPrimitiveType).invoke(row, true)
            type.getMethod("setShowSeekBarValue", Boolean::class.javaPrimitiveType).invoke(row, true)
            enable(row, enabled)
            preference.getMethod("setOnPreferenceChangeListener", change).invoke(row, Proxy.newProxyInstance(loader, arrayOf(change)) { _, method, args ->
                if (method.name == "onPreferenceChange") { (args?.getOrNull(1) as? Int)?.takeIf { it in values }?.let(changed); false }
                else proxyObject(method.name)
            })
        }
    }
    fun confirm(message: String, dismissed: () -> Unit, accepted: () -> Unit): android.content.DialogInterface {
        val listener = android.content.DialogInterface.OnClickListener { _, _ -> accepted() }
        val type = runCatching { loader.loadClass("miuix.appcompat.app.AlertDialog\$Builder") }.getOrNull()
        if (type != null) {
            val dialog = runCatching {
                val builder = type.getConstructor(Context::class.java).newInstance(context)
                type.getMethod("setMessage", CharSequence::class.java).invoke(builder, message)
                type.getMethod("setNegativeButton", CharSequence::class.java, android.content.DialogInterface.OnClickListener::class.java).invoke(builder, "取消", null)
                type.getMethod("setPositiveButton", CharSequence::class.java, android.content.DialogInterface.OnClickListener::class.java).invoke(builder, "确认", listener)
                type.getMethod("show").invoke(builder) as android.content.DialogInterface
            }.getOrNull()
            if (dialog != null) {
                dialog.javaClass.getMethod("setOnDismissListener", android.content.DialogInterface.OnDismissListener::class.java)
                    .invoke(dialog, android.content.DialogInterface.OnDismissListener { dismissed() })
                return dialog
            }
        }
        return android.app.AlertDialog.Builder(context).setMessage(message).setNegativeButton("取消", null)
            .setPositiveButton("确认", listener).show().also { it.setOnDismissListener { dismissed() } }
    }
    fun onClick(row: Any, clicked: () -> Unit) {
        preference.getMethod("setOnPreferenceClickListener", click).invoke(row, Proxy.newProxyInstance(loader, arrayOf(click)) { _, method, _ ->
            if (method.name == "onPreferenceClick") { clicked(); true } else proxyObject(method.name)
        })
    }
    fun add(parent: Any, row: Any) { check(group.getMethod("addPreference", preference).invoke(parent, row) == true) }
    fun detach(row: Any) { preference.getMethod("getParent").invoke(row)?.let { group.getMethod("removePreference", preference).invoke(it, row) } }
    fun parent(row: Any): Any? = preference.getMethod("getParent").invoke(row)
    fun summary(row: Any, value: String) {
        // Host themes may install a provider; explicit report-derived summaries take precedence.
        preference.methods.firstOrNull { it.name == "setSummaryProvider" && it.parameterCount == 1 }?.invoke(row, null)
        preference.getMethod("setSummary", CharSequence::class.java).invoke(row, value)
    }
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
