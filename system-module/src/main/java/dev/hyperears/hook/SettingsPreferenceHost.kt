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
    private var incremental = false
    private val retained = linkedMapOf<String, Any>()
    private val seen = mutableSetOf<String>()
    private val properties = java.util.IdentityHashMap<Any, MutableMap<String, Any?>>()
    fun beginUpdate() { incremental = true; seen.clear() }
    fun endUpdate() {
        retained.keys.filter { it !in seen }.forEach { key ->
            retained.remove(key)?.let { clearListeners(it); detach(it); properties.remove(it) }
        }
    }
    fun dispose() { retained.values.forEach { automaticRanges.remove(it); CustomPreferenceRows.remove(it) }; retained.clear(); properties.clear(); seen.clear() }
    private fun updateIfChanged(row: Any, key: String, value: Any?, apply: () -> Unit) {
        if (!incremental) { apply(); return }
        val values = properties.getOrPut(row) { mutableMapOf() }
        if (values.containsKey(key) && values[key] == value) return
        apply(); values[key] = value
    }
    init {
        require(group.isInstance(screen))
        // Resolve all required entry points before touching the host screen.
        group.getMethod("addPreference", preference); group.getMethod("removePreference", preference)
        preference.getMethod("getParent"); preference.getMethod("setPersistent", Boolean::class.javaPrimitiveType)
        category.getConstructor(Context::class.java); list.getConstructor(Context::class.java)
        preference.getMethod("setOnPreferenceChangeListener", change)
        preference.getMethod("setOnPreferenceClickListener", click)
    }
    fun customRow(key:String, content:android.view.View, heightDp:Int):Any = row(key, "").also {
        CustomPreferenceRows.put(it,content,(heightDp*context.resources.displayMetrics.density).toInt())
    }
    fun category(key: String, title: String) = create(category, key, title)
    fun row(key: String, title: String) = create(preference, key, title)
    fun choice(key: String, title: String, labels: List<String>, values: List<String>, selected: String?, enabled: Boolean,
        changed: (String) -> Unit): Any = create(list, key, title).also { row ->
        updateIfChanged(row, "entries", labels) { list.getMethod("setEntries", Array<CharSequence>::class.java).invoke(row, labels.toTypedArray<CharSequence>()) }
        updateIfChanged(row, "entryValues", values) { list.getMethod("setEntryValues", Array<CharSequence>::class.java).invoke(row, values.toTypedArray<CharSequence>()) }
        updateIfChanged(row, "value", selected) { list.getMethod("setValue", String::class.java).invoke(row, selected) }
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
            updateIfChanged(row, "checked", checked) { type.getMethod("setChecked", Boolean::class.javaPrimitiveType).invoke(row, checked) }
            enable(row, enabled)
            preference.getMethod("setOnPreferenceChangeListener", change).invoke(row, Proxy.newProxyInstance(loader, arrayOf(change)) { _, method, args ->
                if (method.name == "onPreferenceChange") { (args?.getOrNull(1) as? Boolean)?.let(changed); false }
                else proxyObject(method.name)
            })
        }
    }
    fun linkAutomaticBrightness(row: Any, automatic: Boolean, ready: Boolean, changed: (Boolean) -> Unit) {
        automaticRanges[row] = AutomaticBinding(automatic, ready, changed)
        bindAutomaticBrightness(row)
    }
    fun textInput(key: String, title: String, reported: String, enabled: Boolean,
        valid: (String) -> Boolean, invalidMessage: String, changed: (String) -> Unit): Any {
        val type = runCatching { loader.loadClass("androidx.preference.EditTextPreference") }.getOrNull()
            ?: return row(key, title).also { summary(it, reported); enable(it, false) }
        return create(type, key, title).also { row ->
            type.getMethod("setText", String::class.java).invoke(row, reported)
            type.getMethod("setDialogTitle", CharSequence::class.java).invoke(row, title)
            summary(row, reported)
            enable(row, enabled)
            preference.getMethod("setOnPreferenceChangeListener", change).invoke(row,
                Proxy.newProxyInstance(loader, arrayOf(change)) { _, method, args ->
                    if (method.name == "onPreferenceChange") {
                        (args?.getOrNull(1) as? String)?.let { proposed ->
                            if (valid(proposed)) changed(proposed)
                            else android.widget.Toast.makeText(context, invalidMessage, android.widget.Toast.LENGTH_SHORT).show()
                        }
                        // Only a device report may replace the value displayed by the host.
                        false
                    } else proxyObject(method.name)
                })
        }
    }
    fun range(key: String, title: String, values: IntRange, selected: Int, enabled: Boolean, changed: (Int) -> Unit): Any =
        range(key, title, values, selected, enabled, false) { changed(it); true }
    fun range(key: String, title: String, values: IntRange, selected: Int, enabled: Boolean,
        awaitReadback: Boolean, changed: (Int) -> Boolean): Any {
        val type = loader.loadClass("androidx.preference.SeekBarPreference")
        return create(type, key, title).also { row ->
            updateIfChanged(row, "min", values.first) { type.getMethod("setMin", Int::class.javaPrimitiveType).invoke(row, values.first) }
            updateIfChanged(row, "max", values.last) { type.getMethod("setMax", Int::class.javaPrimitiveType).invoke(row, values.last) }
            updateIfChanged(row, "value", selected) { type.getMethod("setValue", Int::class.javaPrimitiveType).invoke(row, selected) }
            // Xiaomi's Settings build strips optional setters; range/value methods remain public.
            runCatching { type.getDeclaredField("mAdjustable").apply { isAccessible = true }.setBoolean(row, true) }
            runCatching { type.getDeclaredField("mShowSeekBarValue").apply { isAccessible = true }.setBoolean(row, true) }
            summary(row, selected.toString())
            if (awaitReadback) ranges[row] = values
            enable(row, enabled)
            preference.getMethod("setOnPreferenceChangeListener", change).invoke(row, Proxy.newProxyInstance(loader, arrayOf(change)) { _, method, args ->
                if (method.name == "onPreferenceChange") {
                    val next = (args?.getOrNull(1) as? Int)?.takeIf { it in values }
                    val accepted = next != null && changed(next)
                    if (accepted && awaitReadback) {
                        // Set the held value BEFORE disabling causes a native rebind.
                        updateIfChanged(row, "value", next) { type.getMethod("setValue", Int::class.javaPrimitiveType).invoke(row, next) }
                        enable(row, false)
                    }
                    awaitReadback && accepted
                }
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
    fun wifiPassword(title: String, submitted: (String) -> Unit): android.app.Dialog {
        val input = android.widget.EditText(context).apply {
            inputType = android.text.InputType.TYPE_CLASS_TEXT or android.text.InputType.TYPE_TEXT_VARIATION_PASSWORD
            isSingleLine = true
            isSaveEnabled = false
            importantForAutofill = android.view.View.IMPORTANT_FOR_AUTOFILL_NO_EXCLUDE_DESCENDANTS
            hint = "Wi-Fi 密码"
        }
        val listener = android.content.DialogInterface.OnClickListener { _, _ -> submitted(input.text.toString()) }
        val type = loader.loadClass("miuix.appcompat.app.AlertDialog\$Builder")
        val builder = type.getConstructor(Context::class.java).newInstance(context)
        type.getMethod("setTitle", CharSequence::class.java).invoke(builder,title)
        type.getMethod("setView", android.view.View::class.java).invoke(builder,input)
        type.getMethod("setPositiveButton", CharSequence::class.java, android.content.DialogInterface.OnClickListener::class.java)
            .invoke(builder,"连接",listener)
        type.getMethod("setNegativeButton", CharSequence::class.java, android.content.DialogInterface.OnClickListener::class.java)
            .invoke(builder,"取消",null)
        return (type.getMethod("show").invoke(builder) as android.app.Dialog).also {
            it.setOnDismissListener { input.text?.clear() }
        }
    }
    fun persistentChoices(title: String, message: String, labels: List<String>, selected: (Int) -> Unit): android.app.Dialog {
        val type = loader.loadClass("miuix.appcompat.app.AlertDialog\$Builder")
        val builder = type.getConstructor(Context::class.java).newInstance(context)
        type.getMethod("setTitle", CharSequence::class.java).invoke(builder, title)
        type.getMethod("setMessage", CharSequence::class.java).invoke(builder, message)
        type.getMethod("setSingleChoiceItems", Array<CharSequence>::class.java, Int::class.javaPrimitiveType,
            android.content.DialogInterface.OnClickListener::class.java).invoke(builder, labels.toTypedArray<CharSequence>(), -1,
                android.content.DialogInterface.OnClickListener { _, index -> selected(index) })
        type.getMethod("setPositiveButton", CharSequence::class.java, android.content.DialogInterface.OnClickListener::class.java)
            .invoke(builder, "关闭", null)
        return type.getMethod("show").invoke(builder) as android.app.Dialog
    }
    fun onClick(row: Any, clicked: () -> Unit) {
        preference.getMethod("setOnPreferenceClickListener", click).invoke(row, Proxy.newProxyInstance(loader, arrayOf(click)) { _, method, _ ->
            if (method.name == "onPreferenceClick") { clicked(); true } else proxyObject(method.name)
        })
    }
    fun add(parent: Any, row: Any) { if (parent(row) !== parent) check(group.getMethod("addPreference", preference).invoke(parent, row) == true) }
    fun order(row: Any, value: Int) { updateIfChanged(row, "order", value) { preference.getMethod("setOrder", Int::class.javaPrimitiveType).invoke(row, value) } }
    fun detach(row: Any) { preference.getMethod("getParent").invoke(row)?.let { group.getMethod("removePreference", preference).invoke(it, row) } }
    fun parent(row: Any): Any? = preference.getMethod("getParent").invoke(row)
    fun summary(row: Any, value: String) {
        updateIfChanged(row, "summary", value) {
        // Host themes may install a provider; explicit report-derived summaries take precedence.
        preference.methods.firstOrNull { it.name == "setSummaryProvider" && it.parameterCount == 1 }?.invoke(row, null)
        preference.getMethod("setSummary", CharSequence::class.java).invoke(row, value)
        }
    }
    fun enable(row: Any, value: Boolean) { updateIfChanged(row, "enabled", value) { preference.getMethod("setEnabled", Boolean::class.javaPrimitiveType).invoke(row, value) } }
    fun clearListeners(row: Any) {
        automaticRanges.remove(row)
        CustomPreferenceRows.remove(row)
        preference.getMethod("setOnPreferenceClickListener", click).invoke(row, null)
        preference.getMethod("setOnPreferenceChangeListener", change).invoke(row, null)
    }
    private fun create(type: Class<*>, key: String, title: String): Any {
        if (incremental) {
            seen.add(key)
            retained[key]?.let { existing ->
                require(type.isInstance(existing))
                updateIfChanged(existing, "title", title) { preference.getMethod("setTitle", CharSequence::class.java).invoke(existing, title) }
                return existing
            }
        }
        return type.getConstructor(Context::class.java).newInstance(context).also {
        preference.getMethod("setKey", String::class.java).invoke(it, key)
        preference.getMethod("setTitle", CharSequence::class.java).invoke(it, title)
        preference.getMethod("setPersistent", Boolean::class.javaPrimitiveType).invoke(it, false)
        if (incremental) { retained[key] = it; properties.getOrPut(it) { mutableMapOf() }["title"] = title }
        }
    }
    private fun proxyObject(name: String): Any? = when (name) { "hashCode" -> 0; "equals" -> false; "toString" -> "HyperEars preference listener"; else -> null }
    private data class AutomaticBinding(val automatic: Boolean, val ready: Boolean, val changed: (Boolean) -> Unit)
    companion object {
        private val automaticRanges = java.util.WeakHashMap<Any, AutomaticBinding>()
        private fun bindAutomaticBrightness(row: Any) {
            val binding = automaticRanges[row] ?: return
            val bar = row.javaClass.getDeclaredField("mSeekBar").apply { isAccessible=true }.get(row) as? android.widget.SeekBar ?: return
            val parent = bar.parent as? android.widget.LinearLayout ?: return
            val dp = bar.resources.displayMetrics.density
            val tag = "hyperears.auto_brightness"
            var button = parent.findViewWithTag<android.widget.TextView>(tag)
            if(button == null) {
                button = android.widget.TextView(bar.context).apply {
                    this.tag=tag; text="自动"; textSize=12f; gravity=android.view.Gravity.CENTER
                    contentDescription="自动亮度"; isClickable=true; isFocusable=true
                }
                parent.addView(button,parent.indexOfChild(bar),android.widget.LinearLayout.LayoutParams((48*dp).toInt(),(48*dp).toInt()).apply {
                    marginEnd=(10*dp).toInt()
                })
                val automaticButton=button
                fun align() {
                    automaticButton.translationY=bar.top+bar.height/2f-
                        (automaticButton.top+automaticButton.height/2f)
                }
                bar.addOnLayoutChangeListener { _,_,_,_,_,_,_,_,_->align() }
                automaticButton.addOnLayoutChangeListener { _,_,_,_,_,_,_,_,_->align() }
                automaticButton.post { align() }
            }
            val enabled = row.javaClass.getMethod("isEnabled").invoke(row)==true && binding.ready
            bar.isEnabled = enabled && !binding.automatic
            button.isEnabled=enabled
            button.isSelected=binding.automatic
            button.background=NativeControlStyle.rounded(if(binding.automatic) NativeControlStyle.ACCENT else NativeControlStyle.surface(bar.context),24*dp)
            button.setTextColor(if(binding.automatic) android.graphics.Color.WHITE else NativeControlStyle.primary(bar.context))
            button.setOnClickListener {
                val latest=automaticRanges[row] ?: return@setOnClickListener
                if(latest.ready) { it.isEnabled=false;latest.changed(!latest.automatic) }
            }
        }

        private val ranges = java.util.WeakHashMap<Any, IntRange>()
        private val scales = java.util.WeakHashMap<android.widget.SeekBar, java.lang.ref.WeakReference<android.graphics.drawable.Drawable>>()
        private val scaleRanges = java.util.WeakHashMap<android.widget.SeekBar, IntRange>()
        fun bindRange(row: Any) {
            bindAutomaticBrightness(row)
            val values = ranges[row] ?: return
            val field = row.javaClass.getDeclaredField("mSeekBar").apply { isAccessible = true }
            val bar = field.get(row) as? android.widget.SeekBar ?: return
            scaleRanges[bar] = values
            scales[bar]?.get()?.let { it.invalidateSelf(); return }
            val dp = bar.resources.displayMetrics.density
            val extra = (18 * dp).toInt()
            bar.setPadding(bar.paddingLeft, bar.paddingTop, bar.paddingRight, bar.paddingBottom + extra)
            val params = bar.layoutParams
            if (params.height > 0) { params.height += extra; bar.layoutParams = params }
            else bar.minimumHeight = bar.minimumHeight + extra
            val scale = object : android.graphics.drawable.Drawable() {
                private val paint = android.graphics.Paint(android.graphics.Paint.ANTI_ALIAS_FLAG).apply {
                    color = android.graphics.Color.GRAY; strokeWidth = 2 * dp; textSize = 11 * dp
                    textAlign = android.graphics.Paint.Align.CENTER
                }
                override fun draw(canvas: android.graphics.Canvas) {
                    val left = bar.paddingLeft.toFloat()
                    val right = (bar.width - bar.paddingRight).toFloat()
                    val track = (bar.paddingTop + bar.height - bar.paddingBottom) / 2f
                    val currentRange = scaleRanges[bar] ?: return
                    val labeledTicks = nativeSliderTicks(currentRange)
                    nativeSliderMarks(currentRange).forEach { value ->
                        var fraction = (value - currentRange.first).toFloat() / (currentRange.last - currentRange.first).coerceAtLeast(1)
                        if (bar.layoutDirection == android.view.View.LAYOUT_DIRECTION_RTL) fraction = 1 - fraction
                        val x = left + fraction * (right - left)
                        if (value in labeledTicks) {
                            canvas.drawLine(x, track - 4 * dp, x, track + 4 * dp, paint)
                            canvas.drawText(value.toString(), x.coerceIn(8 * dp, bar.width - 8 * dp), bar.height - 3 * dp, paint)
                        } else canvas.drawCircle(x, track, 1.5f * dp, paint)
                    }
                }
                override fun setAlpha(alpha: Int) { paint.alpha = alpha }
                override fun setColorFilter(filter: android.graphics.ColorFilter?) { paint.colorFilter = filter }
                @Deprecated("Deprecated in Java") override fun getOpacity() = android.graphics.PixelFormat.TRANSLUCENT
            }
            scales[bar] = java.lang.ref.WeakReference(scale)
            bar.overlay.add(scale)
            fun resize() { scale.setBounds(0, 0, bar.width, bar.height); scale.invalidateSelf() }
            bar.addOnLayoutChangeListener { _, _, _, _, _, _, _, _, _ -> resize() }
            resize()
        }
    }
}
