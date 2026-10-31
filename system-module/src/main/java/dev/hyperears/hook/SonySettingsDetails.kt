package dev.hyperears.hook

import android.content.Context
import android.graphics.Color
import android.graphics.drawable.GradientDrawable
import android.os.Handler
import android.os.Looper
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.*
import dev.hyperears.integration.*
import java.lang.ref.WeakReference
import java.util.Locale

/** MiLink-hosted settings. Device reports are authoritative; editor values are unsent drafts. */
internal class SonySettingsDetails private constructor(
    anchor: View, private val address: String, private val environment: MiLinkCardEnvironment,
) : MiLinkToggleDetails {
    private val anchor = WeakReference(anchor)
    private val context = anchor.context
    private val token = environment.sessionTokenProvider(address)
    private val popup = PopupWindow(context)
    private val handler = Handler(Looper.getMainLooper())
    private val renders = mutableListOf<(EarbudState) -> Unit>()
    private var unsubscribe: (() -> Unit)? = null
    private var closed = false
    private var rendering = false
    private var pending: SonyControlRequest? = null
    private val status = text("", 14f)
    private var alertRefreshKey: Pair<Long, Long>? = null
    private val alertExpiry = Runnable {
        if (!closed) withCurrent { current ->
            val settings = current.features.get<SonyMultipointFeatureState>()
            val key = settings?.let { multipointDeadline(it) }
            if (key != null && key == alertRefreshKey) {
                status.text = "变更等待已过期，正在重新读取耳机状态。"
                render(current)
                if (!closed) withCurrent { environment.controlSender(address, StandardControlRequest.Refresh) }
            }
        }
    }
    private val timeout = Runnable {
        if (!closed && pending != null) {
            pending = null
            status.text = "尚未收到确认，请检查耳机连接后重试。"
        }
    }
    private val detach = object : View.OnAttachStateChangeListener {
        override fun onViewAttachedToWindow(v: View) = Unit
        override fun onViewDetachedFromWindow(v: View) = dismiss()
    }
    init {
        try {
            popup.contentView = content()
            popup.width = (context.resources.displayMetrics.widthPixels * .92f).toInt()
            popup.height = (context.resources.displayMetrics.heightPixels * .82f).toInt()
            popup.isFocusable = true
            popup.isOutsideTouchable = true
            popup.setBackgroundDrawable(GradientDrawable().apply { setColor(Color.TRANSPARENT) })
            popup.setOnDismissListener { dismiss() }
            anchor.addOnAttachStateChangeListener(detach)
            val release = environment.observeState(address, ::render)
            if (closed) release() else unsubscribe = release
            render(environment.stateProvider(address))
            if (!anchor.isAttachedToWindow || anchor.windowToken == null) dismiss()
            anchor.post {
                if (!closed) runCatching {
                    check(anchor.isAttachedToWindow && anchor.windowToken != null)
                    render(environment.stateProvider(address))
                    if (!closed) popup.showAtLocation(anchor.rootView, Gravity.CENTER, 0, 0)
                }.onFailure { dismiss() }
            }
        } catch (error: Exception) { dismiss(); throw error }
    }
    private fun valid(state: EarbudState) = token != null &&
        token == environment.sessionTokenProvider(address) && SonyCardPolicy.available(state, address)
    override fun render(state: EarbudState) {
        if (closed) return
        try { renderState(state) } catch (_: Exception) { dismiss() }
    }
    private fun renderState(state: EarbudState) {
        if (closed) return
        if (!valid(state)) { dismiss(); return }
        val alert = state.features.get<SonyMultipointFeatureState>()?.let(::multipointDeadline)
        if (alert != alertRefreshKey) {
            handler.removeCallbacks(alertExpiry)
            alertRefreshKey = alert
            if (alert != null && alert.second != Long.MAX_VALUE)
                handler.postDelayed(alertExpiry, (alert.second - System.nanoTime() / 1_000_000).coerceAtLeast(0))
        }
        pending?.let {
            if (matches(state, it)) {
                pending = null; handler.removeCallbacks(timeout); status.text = "耳机已确认设置。"
            }
        }
        rendering = true
        try { renders.forEach { it(state) } } finally { rendering = false }
    }
    private fun send(request: SonyControlRequest) {
        if (closed || rendering) return
        try {
            val state = environment.stateProvider(address)
            if (!valid(state)) { dismiss(); return }
            val accepted = SonyCardPolicy.request(state, address, request) ?: run { render(state); return }
            pending = accepted
            status.text = "已发送，等待耳机确认…"
            handler.removeCallbacks(timeout)
            handler.postDelayed(timeout, 5000)
            environment.controlSender(address, accepted)
            render(environment.stateProvider(address))
        } catch (_: Exception) {
            pending = null; handler.removeCallbacks(timeout)
            status.text = "发送未完成，请重试。"
            runCatching { render(environment.stateProvider(address)) }.onFailure { dismiss() }
        }
    }
    override fun dismiss() {
        if (closed) return
        closed = true
        handler.removeCallbacks(timeout)
        pending = null
        handler.removeCallbacks(alertExpiry); alertRefreshKey = null
        anchor.get()?.removeOnAttachStateChangeListener(detach)
        val release = unsubscribe; unsubscribe = null
        runCatching { release?.invoke() }
        runCatching { if (popup.isShowing) popup.dismiss() }
    }
    private fun matches(state: EarbudState, request: SonyControlRequest): Boolean {
        val eq = state.features.get<SonyEqFeatureState>()
        val multipoint = state.features.get<SonyMultipointFeatureState>()
        val audio = state.features.get<SonyAudioSettingsFeatureState>()
        val ambient = state.features.get<SonyAmbientFeatureState>()
        return when (request) {
            is SonyControlRequest.SetLeAudio, is SonyControlRequest.ReadLeAudioHistory -> false
            is SonyControlRequest.ReplyLeAudioAlert -> false
            is SonyControlRequest.PowerOff -> false // Power-off is confirmed by lifecycle, not an EQ/state report.
            is SonyControlRequest.SetAssignableAction -> state.features.get<SonyAssignableFeatureState>()?.let {
                it.definition==request.expected && it.current==request.expectedCurrent && it.mappings==request.expectedMappings.map { mapping ->
                    if(mapping.preset!=request.expectedCurrent[request.index])mapping else mapping.copy(actions=mapping.actions.map{pair->if(pair.action==request.action)pair.copy(function=request.function)else pair})
                }
            } == true
            is SonyControlRequest.SetAssignablePreset -> state.features.get<SonyAssignableFeatureState>()?.let {
                it.definition == request.expected && it.current == request.expectedCurrent.toMutableList().apply { this[request.index]=request.preset }
            } == true
            is SonyControlRequest.SetMultipoint -> multipoint?.let { it.definition == request.expected && it.enabled == request.enabled && it.alert == null && it.pendingTransaction == null } == true
            is SonyControlRequest.ReplyMultipointAlert -> multipoint?.let { it.definition == request.expected && it.alert == null &&
                it.enabled == (if (request.confirm) request.expectedAlert.target else !request.expectedAlert.target) } == true
            is SonyControlRequest.SetVirtualSound -> state.features.get<SonyVirtualSoundFeatureState>()?.let{it.definition==request.expected && !it.awaitingReadback && it.values[request.type]==request.value}==true
            is SonyControlRequest.SetVoiceGuidance -> state.features.get<SonyVoiceGuidanceFeatureState>()?.let {it.definition==request.expected && it.enabled==request.enabled} == true
            is SonyControlRequest.SetWearingPlayback -> audio?.let {it.wearingDefinition==request.expected && it.wearingPlayback==request.enabled} == true
            is SonyControlRequest.SetDsee -> audio?.let { it.dseeDefinition == request.expected && it.dsee == request.enabled } == true
            is SonyControlRequest.SetConnectionQuality -> audio?.let { it.qualityDefinition == request.expected && it.quality == request.mode } == true
            is SonyControlRequest.SetAdaptiveAmbient -> ambient?.let{it.parameterSnapshot.firstOrNull()==0x19 && it.adaptive==request.enabled && it.adaptiveSensitivity==request.sensitivity}==true
            is SonyControlRequest.ManageDevices -> false // Device-list updates, not this legacy editor, confirm the operation.
            is SonyControlRequest.SetQuickAccess -> state.features.get<SonyQuickAccessFeatureState>()?.let { it.definition==request.expected && it.current==request.baseline.toMutableList().apply{this[request.index]=request.service} } == true
            is SonyControlRequest.SetSpeakParameters -> audio?.let { it.speakDefinition == request.expected && it.speakParameters == request.target } == true
            is SonyControlRequest.SetSpeakToChat -> audio?.let { it.speakDefinition == request.expected && it.speak == request.enabled } == true
            is SonyControlRequest.SetEqPreset -> eq?.preset == request.preset
            is SonyControlRequest.SetEqBands -> eq?.let { value -> value.definition == request.expectedDefinition &&
                value.rawSteps.map { it - value.center } == request.steps } == true
            is SonyControlRequest.SetClearBass -> eq?.clearBass == request.level
            is SonyControlRequest.SetAmbient -> ambient?.let { it.level == request.level && it.voice == request.voice } == true
        }
    }
    private fun multipointDeadline(state: SonyMultipointFeatureState): Pair<Long, Long>? =
        state.pendingTransaction?.let { id -> state.pendingExpiresAtMillis?.let { id to it } }
            ?: state.alert?.let { it.transaction to it.expiresAtMillis }
    private fun addMultipoint(parent: LinearLayout) {
        val row = column(); row.addView(text("同时连接两台设备", 17f)); parent.addView(row)
        val toggle = createToggle().apply {
            contentDescription = "同时连接两台设备"; isSaveEnabled = false
            setOnCheckedChangeListener { _, enabled -> if (!rendering) withCurrent { current ->
                current.features.get<SonyMultipointFeatureState>()?.definition?.let { send(SonyControlRequest.SetMultipoint(it, enabled)) }
            } }
        }
        row.addView(toggle)
        val confirmation = column(); val caption = text("", 16f); confirmation.addView(caption); row.addView(confirmation)
        var shown: Pair<SonyMultipointDefinition, SonyMultipointAlert>? = null
        fun reply(confirm: Boolean) {
            val expected = shown ?: return
            withCurrent { current ->
                val settings = current.features.get<SonyMultipointFeatureState>() ?: return@withCurrent
                if (settings.definition == expected.first && settings.alert == expected.second &&
                    System.nanoTime() / 1_000_000 < expected.second.expiresAtMillis)
                    send(SonyControlRequest.ReplyMultipointAlert(expected.first, expected.second, confirm))
                else render(current)
            }
        }
        confirmation.addView(text("确认并继续", 16f).apply { contentDescription = "确认双设备变更"; setOnClickListener { reply(true) } })
        confirmation.addView(text("取消变更", 16f).apply { contentDescription = "取消双设备变更"; setOnClickListener { reply(false) } })
        renders += { current ->
            val settings = current.features.get<SonyMultipointFeatureState>()
            row.visibility = if (settings?.hasSettings == true) View.VISIBLE else View.GONE
            toggle.isChecked = settings?.enabled == true
            toggle.isEnabled = settings?.let { it.available == true && it.alert == null && it.pendingTransaction == null } == true
            shown = settings?.definition?.let { definition -> settings.alert?.let { definition to it } }
            confirmation.visibility = if (shown == null) View.GONE else View.VISIBLE
            val unexpired = settings?.alert?.let { System.nanoTime() / 1_000_000 < it.expiresAtMillis } == true
            for (index in 1 until confirmation.childCount) confirmation.getChildAt(index).isEnabled = unexpired
            caption.text = when (settings?.alert?.message) {
                6 -> "耳机提示：启用双设备连接需要关闭 LDAC。确认继续吗？"
                7 -> "耳机提示：更改双设备连接需要断开并重新连接。确认继续吗？"
                112 -> "耳机提示：将同时启用双设备连接和 LDAC，音质选项可能受到限制。确认继续吗？"
                else -> ""
            }
        }
    }
    private fun addAssignable(parent:LinearLayout) {
        val container=column();parent.addView(container)
        renders += { current ->
            container.removeAllViews()
            val feature=current.features.get<SonyAssignableFeatureState>()?.takeIf{it.hasSettings}
            val definition=feature?.definition
            val values=feature?.current
            if(definition!=null && values!=null) definition.keys.forEachIndexed { index,key ->
                container.addView(text(SettingsSonyAssignableRows.title(key.id),17f))
                val group=RadioGroup(context)
                key.presets.forEach { preset -> group.addView(RadioButton(context).apply {
                    id=View.generateViewId();text=SettingsSonyAssignableRows.label(preset)
                    contentDescription="sony.assignable.${key.id}.$preset"
                    isSaveEnabled=false;isChecked=values[index]==preset
                    isEnabled=feature?.available?.getOrNull(index)==true
                    setOnClickListener { if(!rendering) withCurrent {
                        send(SonyControlRequest.SetAssignablePreset(definition,values,index,preset))
                    } }
                }) }
                container.addView(group)
                if(feature?.completeMappings==true) {
                    val mappings=feature.mappings!!
                    key.actions[values[index]].orEmpty().filter{feature.actionFunctions(index,it.id).size>1}.forEach { action ->
                        container.addView(text(SettingsSonyAssignableRows.actionLabel(action.id),16f))
                        val actions=RadioGroup(context)
                        val selected=feature.mappingForKey(index)!!.actions.single{it.action==action.id}.function
                        feature.actionFunctions(index,action.id).forEach { function -> actions.addView(RadioButton(context).apply {
                            id=View.generateViewId();text=SettingsSonyAssignableRows.functionLabel(function)
                            contentDescription="sony.assignable.${key.id}.action.${action.id}.$function"
                            isSaveEnabled=false;isChecked=selected==function;isEnabled=feature.actionAvailable(index)
                            setOnClickListener {if(!rendering)withCurrent {
                                send(SonyControlRequest.SetAssignableAction(definition,values,mappings,index,action.id,function))
                            }}
                        }) }
                        container.addView(actions)
                    }
                }
            }
        }
    }
    private fun addVirtualSound(parent:LinearLayout) {
        val row=column();parent.addView(row)
        renders += { current ->
            row.removeAllViews()
            val feature=current.features.get<SonyVirtualSoundFeatureState>()
            val definition=feature?.definition
            row.visibility=if(feature?.hasSettings==true)View.VISIBLE else View.GONE
            if(feature?.hasSettings==true && definition!=null) {
                definition.types.forEach {type ->
                    row.addView(text(SonyVirtualSoundLabels.title(type),17f))
                    SonyVirtualSoundLabels.choices(type).forEach {(value,label) ->
                        row.addView(RadioButton(context).apply {
                            text=label;setTextColor(color(true));contentDescription="sony.virtual.$type.$value"
                            isChecked=feature.values[type]==value;isEnabled=!feature.awaitingReadback;isSaveEnabled=false
                            setOnClickListener {withCurrent {send(SonyControlRequest.SetVirtualSound(definition,feature.values,type,value))}}
                        })
                    }
                }
                if(feature.awaitingReadback)row.addView(text("等待耳机回报 · 重新读取声场设置",16f).apply {
                    setOnClickListener {withCurrent {environment.controlSender(address,StandardControlRequest.Refresh)}}
                })
            }
        }
    }
    private fun addVoiceGuidance(parent:LinearLayout) {
        val row=column();row.addView(text("耳机语音提示",17f))
        var displayed:SonyVoiceGuidanceFeatureState?=null
        val control=createToggle().apply {
            contentDescription="耳机语音提示";isSaveEnabled=false
            setOnCheckedChangeListener {_,checked -> if(!rendering)withCurrent {
                val expected=displayed
                val definition=expected?.definition
                val value=expected?.enabled
                if(definition!=null && value!=null)
                    send(SonyControlRequest.SetVoiceGuidance(definition,value,checked))
            }}
        }
        row.addView(control);parent.addView(row)
        renders += { current ->
            displayed=current.features.get<SonyVoiceGuidanceFeatureState>()
            row.visibility=if(displayed?.hasSettings==true)View.VISIBLE else View.GONE
            control.isChecked=displayed?.enabled==true
            control.isEnabled=displayed?.hasSettings==true
        }
    }
    private fun addAudioSettings(parent: LinearLayout) {
        fun toggle(label: String, value: (SonyAudioSettingsFeatureState) -> Boolean?,
            request: (SonyAudioSettingsFeatureState, Boolean) -> SonyControlRequest?) {
            val row = column(); val caption = text(label, 17f); row.addView(caption)
            val control = createToggle().apply {
                contentDescription = label; isSaveEnabled = false
                setOnCheckedChangeListener { _, checked -> if (!rendering) withCurrent { current ->
                    current.features.get<SonyAudioSettingsFeatureState>()?.let { audio -> request(audio, checked)?.let(::send) }
                } }
            }
            row.addView(control); parent.addView(row)
            renders += { current ->
                val audio = current.features.get<SonyAudioSettingsFeatureState>()
                val reported = audio?.let(value)
                row.visibility = if (reported == null) View.GONE else View.VISIBLE
                control.isChecked = reported == true
                control.isEnabled = audio != null && request(audio, !(reported ?: false))?.let(audio::supports) == true
                if (label == "DSEE") caption.text = when (audio?.dseeGeneration) {
                    0 -> "DSEE HX"; 1 -> "DSEE"; 2 -> "DSEE Extreme"; 3 -> "DSEE Ultimate"; else -> "DSEE"
                }
            }
        }
        toggle("DSEE", { it.dsee }) { state, value -> state.dseeDefinition?.let { SonyControlRequest.SetDsee(it, value) } }
        toggle("摘下耳机时暂停播放", {it.wearingPlayback}) {state,value -> state.wearingDefinition?.let {definition->state.wearingPlayback?.let {SonyControlRequest.SetWearingPlayback(definition,it,value)}}}
        toggle("Speak-to-Chat", { it.speak }) { state, value -> state.speakDefinition?.let { SonyControlRequest.SetSpeakToChat(it, value) } }
        val row = column(); val caption = text("连接质量", 17f); row.addView(caption); parent.addView(row)
        val options = listOf("音质优先", "连接稳定优先").mapIndexed { mode, label ->
            text(label, 16f).apply {
                contentDescription = label
                setOnClickListener { withCurrent { current ->
                    current.features.get<SonyAudioSettingsFeatureState>()?.qualityDefinition?.let {
                        send(SonyControlRequest.SetConnectionQuality(it, mode))
                    }
                } }
                row.addView(this)
            }
        }
        renders += { current ->
            val audio = current.features.get<SonyAudioSettingsFeatureState>()
            row.visibility = if (audio?.quality == null) View.GONE else View.VISIBLE
            caption.text = if (audio?.quality == 2) "连接质量：低延迟（耳机回报）" else "连接质量"
            options.forEachIndexed { index, view ->
                view.isSelected = audio?.quality == index
                view.isEnabled = audio?.qualityDefinition?.let {
                    audio.supports(SonyControlRequest.SetConnectionQuality(it, index))
                } == true
            }
        }
    }
    private fun content(): View {
        val body = column().apply {
            setPadding(dp(20), dp(12), dp(20), dp(20))
            background = GradientDrawable().apply { setColor(color(false)); cornerRadius = dp(24).toFloat() }
        }
        body.addView(text("Sony 高级设置", 22f))
        body.addView(text("关闭", 16f).apply { setOnClickListener { dismiss() } })
        body.addView(status)
        val controls = column()
        addAssignable(controls)
        addVirtualSound(controls)
        addVoiceGuidance(controls)
        addAudioSettings(controls)
        addMultipoint(controls)
        val ambientRow = column()
        val ambientCaption = text("环境声强度", 17f)
        ambientRow.addView(ambientCaption)
        val ambientSlider = slider(1, 20) { value -> withCurrent { state ->
            state.features.get<SonyAmbientFeatureState>()?.let { send(SonyControlRequest.SetAmbient(value, it.voice)) }
        } }
        ambientSlider.contentDescription = "环境声强度"
        ambientRow.addView(ambientSlider)
        val voice = createToggle().apply {
            contentDescription = "关注语音"; isSaveEnabled = false
            setOnCheckedChangeListener { _, value -> if (!rendering) withCurrent { state ->
                state.features.get<SonyAmbientFeatureState>()?.let { send(SonyControlRequest.SetAmbient(it.level, value)) }
            } }
        }
        val voiceCaption = text("关注语音", 17f)
        ambientRow.addView(voiceCaption); ambientRow.addView(voice); controls.addView(ambientRow)
        renders += { state ->
            val ambient = state.features.get<SonyAmbientFeatureState>()
            ambientRow.visibility = if (ambient?.level in 1..20) View.VISIBLE else View.GONE
            if (ambient != null) {
                ambientCaption.text = String.format(Locale.getDefault(), "环境声强度：%d%s", ambient.level,
                    if (ambient.adaptive) "（自动环境声已开启，手动调节暂停）" else "")
                ambientSlider.progress = ambient.level; ambientSlider.isEnabled = !ambient.adaptive
                voice.visibility = if (ambient.voiceWritable) View.VISIBLE else View.GONE
                voiceCaption.visibility = voice.visibility
                voice.isChecked = ambient.voice; voice.isEnabled = !ambient.adaptive
            }
        }
        addEq(controls)
        body.addView(ScrollView(context).apply { addView(controls) },
            LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f))
        return body
    }
    private fun addEq(controls: LinearLayout) {
        val eqRow = column()
        eqRow.addView(text("耳机均衡器", 19f))
        val presets = linkedMapOf(0 to "关闭", 16 to "明亮", 17 to "激昂", 18 to "醇厚", 19 to "放松",
            20 to "人声", 21 to "高音增强", 22 to "低音增强", 23 to "语音", 160 to "自定义", 161 to "用户 1", 162 to "用户 2")
        val presetRow = column()
        val choices = presets.map { (id, label) -> text(label, 16f).apply {
            contentDescription = "EQ $label"
            setOnClickListener { send(SonyControlRequest.SetEqPreset(id)) }
        }.also(presetRow::addView) }
        eqRow.addView(presetRow)
        val bassRow = column()
        val bassCaption = text("Clear Bass", 17f); bassRow.addView(bassCaption)
        val bass = slider(0, 20) { send(SonyControlRequest.SetClearBass(it - 10)) }.apply { contentDescription = "Clear Bass" }
        bassRow.addView(bass); eqRow.addView(bassRow)
        val editor = column(); eqRow.addView(editor)
        var definition: SonyEqDefinition? = null
        val draft = mutableListOf<Int>()
        var reload = false
        controls.addView(eqRow)
        renders += { state ->
            val eq = state.features.get<SonyEqFeatureState>()
            eqRow.visibility = if (eq == null || (!eq.writablePreset && !eq.writableBands && !eq.writableBass)) View.GONE else View.VISIBLE
            if (eq != null) {
                presetRow.visibility = if (eq.writablePreset) View.VISIBLE else View.GONE
                choices.forEachIndexed { index, choice ->
                    val id = presets.keys.elementAt(index)
                    choice.isSelected = eq.preset == id
                    choice.text = String.format(Locale.getDefault(), "%s%s", if (choice.isSelected) "✓ " else "", presets.getValue(id))
                }
                bassRow.visibility = if (eq.writableBass) View.VISIBLE else View.GONE
                eq.clearBass?.let { bass.progress = it + 10; bassCaption.text = String.format(Locale.getDefault(), "Clear Bass：%d", it) }
                editor.visibility = if (eq.writableBands) View.VISIBLE else View.GONE
                if (eq.writableBands && (definition != eq.definition || reload)) {
                    definition = eq.definition; reload = false; editor.removeAllViews(); draft.clear()
                    val editorDefinition = requireNotNull(definition)
                    val offset = if (eq.clearBassSlot) 1 else 0
                    draft.addAll(eq.rawSteps.drop(offset).map { it - eq.center })
                    editor.addView(text("频段草稿（应用后等待耳机确认）", 16f))
                    eq.bands.drop(offset).forEachIndexed { index, band ->
                        val frequency = if (band.kind == 2) band.value * 1000 else band.value
                        val caption = text(String.format(Locale.getDefault(), "%d Hz：%d", frequency, draft[index]), 16f)
                        editor.addView(caption)
                        editor.addView(slider(0, eq.center * 2) { raw ->
                            draft[index] = raw - eq.center
                            caption.text = String.format(Locale.getDefault(), "%d Hz：%d（草稿）", frequency, draft[index])
                        }.apply { progress = draft[index] + eq.center; contentDescription = "$frequency Hz" })
                    }
                    editor.addView(text("应用 EQ 草稿", 17f).apply { setOnClickListener { withCurrent { fresh ->
                        val current = fresh.features.get<SonyEqFeatureState>()
                        if (current != null && current.definition == editorDefinition) {
                            val steps = if (current.clearBassSlot) listOf(requireNotNull(current.clearBass)) + draft else draft.toList()
                            send(SonyControlRequest.SetEqBands(steps, editorDefinition))
                        } else render(fresh)
                    } } })
                    editor.addView(text("从耳机重新载入", 16f).apply { setOnClickListener { withCurrent { reload = true; render(it) } } })
                }
            }
        }
    }
    private fun withCurrent(action: (EarbudState) -> Unit) {
        if (closed || rendering) return
        try {
            val state = environment.stateProvider(address)
            if (!valid(state)) dismiss() else action(state)
        } catch (_: Exception) { dismiss() }
    }
    private fun slider(low: Int, high: Int, changed: (Int) -> Unit) = SeekBar(context).apply {
        min = low; max = high; isSaveEnabled = false
        var tracking = false
        setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
            override fun onProgressChanged(bar: SeekBar, value: Int, fromUser: Boolean) {
                if (fromUser && !tracking && !rendering && !closed) changed(value)
            }
            override fun onStartTrackingTouch(bar: SeekBar) { tracking = true }
            override fun onStopTrackingTouch(bar: SeekBar) { tracking = false; if (!rendering && !closed) changed(bar.progress) }
        })
    }
    private fun column() = LinearLayout(context).apply { orientation = LinearLayout.VERTICAL }
    private fun createToggle(): CompoundButton = runCatching {
        Class.forName("miuix.slidingwidget.widget.SlidingButton", true, environment.hostClassLoader)
            .asSubclass(CompoundButton::class.java).getConstructor(Context::class.java).newInstance(context)
    }.getOrElse { @Suppress("DEPRECATION") Switch(context) }
    private fun text(value: String, size: Float) = TextView(context).apply {
        text = value; textSize = size; setTextColor(color(true)); setPadding(0, dp(10), 0, dp(10))
    }
    private fun dp(value: Int) = (value * context.resources.displayMetrics.density).toInt()
    private fun color(text: Boolean): Int {
        val dark = context.resources.configuration.uiMode and 0x30 == 0x20
        return if (text) { if (dark) Color.WHITE else Color.BLACK }
        else if (dark) 0xff242424.toInt() else 0xfff5f5f5.toInt()
    }
    companion object {
        fun open(anchor: View, address: String, environment: MiLinkCardEnvironment): MiLinkToggleDetails =
            try { SonySettingsDetails(anchor, address, environment) } catch (error: Exception) {
                ModuleLog.warn("MiLinkUi", "Sony settings unavailable (${error.javaClass.simpleName})")
                ClosedDetails
            }
        private object ClosedDetails : MiLinkToggleDetails {
            override fun render(state: EarbudState) = Unit
            override fun dismiss() = Unit
        }
    }
}
