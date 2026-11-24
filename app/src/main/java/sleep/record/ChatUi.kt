package sleep.record

import android.content.ClipData
import android.content.ClipboardManager
import android.graphics.Bitmap
import android.graphics.Color
import android.graphics.BitmapFactory
import android.net.Uri
import android.text.Editable
import android.text.TextWatcher
import android.view.Gravity
import android.view.View
import android.widget.ImageView
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.util.UUID

internal class ChatUi(private val a: MainActivity) {
    private var currentId: String? = null
    private var auto: Boolean? = null
    private var stream: ChatStream? = null
    private var partial = ""
    private var streamLabel: TextView? = null
    private var transcript: ScrollView? = null
    private var composerBackdrop: View? = null
    private val drafts = mutableMapOf<String, String>()
    private var avatarRole: String? = null
    private var photo: Bitmap? = null
    private var archivedRolesExpanded = false
    private var roleHistoryExpanded = false

    private fun current(): JSONObject {
        val list = a.chats.load()
        val selected = list.firstOrNull { it.text("id") == currentId }
            ?: list.getOrNull(a.store.state().optInt("last_session_index")) ?: list.first()
        if (currentId != selected.text("id")) {
            currentId = selected.text("id")
            auto = null
        }
        if (auto == null) auto = selected.optBoolean("aiEnabled", true) && selected.assistant() != null
        return selected
    }
    fun openTask(task: JSONObject) {
        val list = a.chats.load()
        val item = list.firstOrNull { it.taskChat() && it.text("id") == task.text("boundConversationId") }
            ?: list.firstOrNull { it.taskChat() && it.text("boundChallengeTaskId") == task.text("id") }
        if (item == null) a.alert("会话缺失", "此挑战的绑定会话未包含在本机数据中") else switchTo(item.text("id"))
    }
    fun switchTo(id: String) {
        if (currentId == id && a.inChat() && a.isShowing("chat-drawer")) {
            a.closePage()
            return
        }
        val i = a.chats.load().indexOfFirst { it.text("id") == id }
        check(i >= 0) { "此会话已移除" }
        cancel(repaint = false)
        currentId = id
        auto = null
        a.store.edit { it.put("last_session_index", i) }
        a.selectTab(1)
    }
    fun discardPhoto() { photo = null; avatarRole = null }
    fun refreshComposerBackdrop() { composerBackdrop?.takeIf { it.isAttachedToWindow }?.invalidate() }
    fun reload() { cancel(); currentId = null; auto = null; drafts.clear() }
    fun cancel(repaint: Boolean = true) {
        stream?.cancel(); stream = null; partial = ""
        val wasStreaming = streamLabel != null
        streamLabel = null
        if (repaint && wasStreaming && a.tab == 1 && a.screen == null && !a.isDestroyed && !a.isFinishing) a.render()
    }
    fun drawer() = with(a) {
        val list = chats.load()
        title("会话", back = true, subtitle = "切换或新建对话")
        val content = scroller(body, pad = 14)
        val kinds = listOf("新建普通对话" to "", "新建角色扮演" to "ROLEPLAY", "新建自我对话" to "SELF_CHAT")
        selector(content, "新建会话", "选择模板", kinds.map { it.first }, -1) { switchTo(chats.create(kinds[it].second)) }
        section(content, "用户会话")
        for (chat in list.filterNot { it.taskChat() }) conversationRow(content, chat, true)
        section(content, "任务会话")
        val taskChats = list.filter { it.taskChat() }
        val todo = taskChats.filterNot { it.optBoolean("isCompleted") }
        val done = taskChats.filter { it.optBoolean("isCompleted") }
        if (todo.isEmpty()) content.label("暂无进行中的任务会话", 13, palette.muted, gap = 12)
        todo.forEach { conversationRow(content, it, false) }
        if (done.isNotEmpty()) {
            content.action("${if (archived) "收起" else "展开"}已完成会话（${done.size}）") { archived = !archived; render() }
            if (archived) done.forEach { conversationRow(content, it, false) }
        }
    }
    private fun conversationRow(parent: LinearLayout, chat: JSONObject, allowDelete: Boolean) = with(a) {
        val id = chat.text("id")
        val name = chat.text("title", "新对话")
        val active = id == currentId
        val line = row().apply { isSelected = active; setPadding(dp(9), dp(2), dp(3), dp(2)); minimumHeight = dp(56) }
        val conversation = row().apply { minimumHeight = dp(52) }
        val status = when {
            chat.taskChat() -> if (chat.optBoolean("isCompleted")) "已完成" else "进行中"
            chat.text("subType") == "SELF_CHAT" -> "自我对话"
            chat.text("subType") == "ROLEPLAY" -> "角色扮演"
            else -> "普通对话"
        }
        val marker = text(if (chat.taskChat()) "任" else name.take(1), 15, if (active) palette.onAccent else palette.accent, true).apply {
            gravity = Gravity.CENTER; background = shape(if (active) palette.accent else palette.accentSoft, 20)
        }
        conversation.addView(marker, LinearLayout.LayoutParams(dp(40), dp(40)).apply { marginEnd = dp(12) })
        conversation.addView(vertical().apply {
            label(name, 15, bold = true).apply { maxLines = 1; ellipsize = android.text.TextUtils.TruncateAt.END }
            label(status, 12, palette.muted, gap = 4)
        }, LinearLayout.LayoutParams(0, -2, 1f))
        clickable(conversation, Color.TRANSPARENT, 16) { switchTo(id) }
        line.addView(conversation, LinearLayout.LayoutParams(0, -1, 1f))
        if (allowDelete) {
            lateinit var menu: View
            menu = iconButton(Glyph.MORE, "管理 $name") {
                popup(menu, listOf("删除会话")) {
                    confirm("删除会话", "删除「$name」及其消息？", "删除") {
                        val wasCurrent = currentId == id
                        if (wasCurrent) { cancel(repaint = false); currentId = null; auto = null }
                        chats.delete(id)
                        if (wasCurrent) repaintChat() else render()
                    }
                }
            }
            line.addView(menu, LinearLayout.LayoutParams(dp(44), dp(44)))
        } else if (active) line.addView(GlyphView(this, Glyph.CHECK, palette.accent), LinearLayout.LayoutParams(dp(40), dp(40)))
        line.background = shape(if (active) palette.accentSoft else Color.TRANSPARENT, 18)
        parent.addView(line, LinearLayout.LayoutParams(-1, -2).apply { topMargin = dp(4) })
    }
    fun show() = with(a) {
        val row = current()
        val id = row.text("id")
        val isTask = row.taskChat()
        val completed = row.optBoolean("isCompleted")
        val showSystem = row.optBoolean("showSystemMessages", true)
        val state = store.state()
        val avatars = !isTask && state.optBoolean("show_avatars", true)
        val selected = if (isTask) "用户" else row.text("selectedRoleName", "我")
        val roles = row.roles().associateBy { it.text("name") }
        val avatarMap = state.optJSONObject("avatar_map") ?: JSONObject()
        composerBackdrop = null
        title(row.text("title", "新对话"), subtitle = if (isTask) "任务会话" else if (row.text("subType") == "SELF_CHAT") "离线 · 自我对话" else "不想睡觉",
            leading = Glyph.MENU to "会话", onLeading = { open(Screen("chat-drawer")) },
            actions = arrayOf("选项" to { open(Screen("chat-settings")) }))
        val dock = FrameLayout(this)
        body.addView(dock, LinearLayout.LayoutParams(-1, 0, 1f))
        val scroll = ScrollView(this).apply {
            clipToPadding = false
            isFillViewport = false
            isVerticalScrollBarEnabled = false
            setOnScrollChangeListener { _, _, _, _, _ -> invalidateNavBackdrop() }
            setOnTouchListener { _, event ->
                when (event.actionMasked) {
                    android.view.MotionEvent.ACTION_MOVE -> invalidateNavBackdrop()
                    android.view.MotionEvent.ACTION_UP, android.view.MotionEvent.ACTION_CANCEL -> refreshNavBackdropDuring()
                }
                false
            }
        }
        val content = vertical().apply { setPadding(dp(18), dp(2), dp(18), chatComposerBottomInset() + dp(12)) }
        scroll.addView(content, FrameLayout.LayoutParams(-1, -2))
        val messagesLayer = FrameLayout(this).apply { addView(scroll, FrameLayout.LayoutParams(-1, -1)) }
        dock.addView(messagesLayer, FrameLayout.LayoutParams(-1, -1))
        scrollTarget(scroll, body)
        transcript = scroll
        val messages = row.list("messages")
        val composer = input(drafts[id].orEmpty(), if (completed) "该任务会话已完成" else "输入消息", single = false).apply {
            inputType = android.text.InputType.TYPE_CLASS_TEXT or android.text.InputType.TYPE_TEXT_FLAG_MULTI_LINE or android.text.InputType.TYPE_TEXT_FLAG_CAP_SENTENCES
            addTextChangedListener(object : TextWatcher {
                override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) = Unit
                override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) { drafts[id] = s?.toString().orEmpty() }
                override fun afterTextChanged(s: Editable?) = Unit
            })
        }
        if (messages.isEmpty() && !isTask && row.text("subType").isEmpty()) {
            val empty = content.card()
            empty.label("从一句话开始", 18, bold = true)
            listOf("很晚了但是我现在不想睡觉", "今天过得怎么样", "给我讲个故事吧").forEach { suggestion ->
                empty.action(suggestion) { composer.setText(suggestion); composer.setSelection(composer.length()) }
            }
        }
        messages.forEachIndexed { index, msg ->
            val name = msg.text("role")
            val system = roles[name]?.text("type") == "system" || name == "system"
            if (system && !showSystem) return@forEachIndexed
            val time = if (row.optBoolean("showMessageTime", true) && msg.optLong("timestamp") > 0) format(msg.optLong("timestamp"), "HH:mm") else ""
            val bubble = bubble(content, name, msg.text("content"), time, name == selected && !system, system, avatars, avatarMap)
            bubble.setOnLongClickListener { messageOptions(id, index, msg, bubble); true }
        }
        streamLabel = if (stream != null) bubble(content, row.assistant()?.text("name") ?: "助手", partial.ifEmpty { "正在等待回复…" }, "", false, false, avatars, avatarMap) else null
        if (completed) {
            content.card().label("挑战已完成，请在任务页领取奖励。", tint = palette.green)
        } else {
            val controlFill = palette.raised.withOpacity(0xB8)
            val glass = FrameLayout(this).apply {
                background = shape(Color.TRANSPARENT, 21)
                clipToOutline = true
            }
            val backdrop = BackdropBlurView(this, messagesLayer, cornerRadius = 21, blurRadius = dp(11).toFloat(),
                baseColor = palette.bg, tint = palette.bg.withOpacity(0x55), fallbackTint = palette.bg.withOpacity(0xDF))
            composerBackdrop = backdrop
            glass.addView(backdrop, FrameLayout.LayoutParams(-1, -1))
            glass.addOnLayoutChangeListener { _, _, top, _, bottom, _, _, _, _ ->
                backdrop.invalidate()
                val clearance = bottom - top + chatComposerBottomInset() + dp(12)
                if (content.paddingBottom != clearance) {
                    val follow = content.height - scroll.height - scroll.scrollY <= dp(24)
                    content.setPadding(content.paddingLeft, content.paddingTop, content.paddingRight, clearance)
                    if (follow) scroll.post { scroll.scrollTo(0, content.height) }
                }
            }
            content.addOnLayoutChangeListener { _, _, _, _, _, _, _, _, _ -> backdrop.invalidate() }
            val panel = vertical().apply { setPadding(dp(6), dp(2), dp(6), dp(5)) }
            val controls = row().apply { gravity = Gravity.BOTTOM }
            if (!isTask) {
                val meta = row().apply { minimumHeight = dp(43); setPadding(dp(5), 0, dp(5), 0); gravity = Gravity.CENTER_VERTICAL }
                val roles = row.usableRoles().map { it.text("name") }
                lateinit var identity: TextView
                identity = button("$selected  ▾", enabled = stream == null && roles.isNotEmpty(), fill = controlFill) {
                    popup(identity, roles, roles.indexOf(selected)) { i ->
                        chats.edit(id) { chat, _ -> chat.put("selectedRoleName", roles[i]) }; render()
                    }
                }.apply {
                    minHeight = dp(40); setPadding(dp(11), dp(4), dp(11), dp(4))
                    maxWidth = dp(174); maxLines = 1; ellipsize = android.text.TextUtils.TruncateAt.END
                }
                meta.addView(identity, LinearLayout.LayoutParams(-2, -2))
                meta.addView(View(this), LinearLayout.LayoutParams(0, 1, 1f))
                val available = row.optBoolean("aiEnabled", true) && row.assistant() != null
                val reply = ToggleControl(this, "请求AI", available && auto == true, compact = true).apply {
                    minimumHeight = dp(40)
                    isEnabled = available && stream == null
                    onChecked { _, checked -> auto = checked }
                }
                meta.addView(reply, LinearLayout.LayoutParams(-2, -2))
                panel.addView(meta, LinearLayout.LayoutParams(-1, dp(43)))
            }
            composer.background = shape(controlFill, 24, palette.stroke.withOpacity(0x90))
            composer.minHeight = dp(48)
            composer.maxHeight = dp(176)
            composer.setPadding(dp(16), dp(12), dp(16), dp(11))
            controls.addView(composer, LinearLayout.LayoutParams(0, -2, 1f))
            val idle = stream == null
            val send = iconButton(Glyph.SEND, "发送", fill = if (idle) palette.accent else palette.raised, tint = if (idle) palette.onAccent else palette.muted) {
                val value = composer.text.toString().trim()
                if (value.isNotBlank() && stream == null) {
                    val request = chats.send(id, value, auto == true)
                    drafts.remove(id)
                    if (request == null) render() else start(request)
                }
            }.apply { isEnabled = stream == null }
            controls.addView(send, LinearLayout.LayoutParams(dp(48), dp(48)).apply { marginStart = dp(8) })
            panel.addView(controls, LinearLayout.LayoutParams(-1, -2).apply { topMargin = dp(5) })
            glass.addView(panel, FrameLayout.LayoutParams(-1, -2))
            dock.addView(glass, FrameLayout.LayoutParams(-1, -2, Gravity.BOTTOM).apply {
                marginStart = dp(8); marginEnd = dp(8)
                bottomMargin = chatComposerBottomInset()
            })
        }
        scroll.post { scroll.scrollTo(0, content.height) }
    }
    private fun bubble(parent: LinearLayout, name: String, value: String, time: String, own: Boolean, system: Boolean,
                       avatars: Boolean, avatarMap: JSONObject): TextView = with(a) {
        val align = when {
            system -> Gravity.CENTER_HORIZONTAL
            own -> Gravity.END
            else -> Gravity.START
        }
        val line = FrameLayout(this)
        val column = vertical().apply { gravity = align }
        val width = (resources.displayMetrics.widthPixels * .72f).toInt()
        val nameLabel = text(if (system) "系统" else name, 12, palette.muted).apply {
            maxWidth = width
            maxLines = 1
            ellipsize = android.text.TextUtils.TruncateAt.END
        }
        column.addView(nameLabel, LinearLayout.LayoutParams(-2, -2))
        val fill = when {
            system -> palette.raised
            own -> palette.accentSoft
            else -> palette.surface
        }
        val message = text(value, if (system) 13 else 15, if (system) palette.muted else palette.text).apply {
            maxWidth = width
            gravity = if (system) Gravity.CENTER else Gravity.START
            if (system) setPadding(dp(14), dp(7), dp(14), dp(7)) else setPadding(dp(14), dp(10), dp(14), dp(11))
            background = shape(fill, if (system) 14 else 18, if (system || own) null else palette.outline).apply {
                if (!system) {
                    val r = dp(18).toFloat(); val tail = dp(6).toFloat()
                    cornerRadii = if (own) floatArrayOf(r, r, tail, tail, r, r, r, r) else floatArrayOf(tail, tail, r, r, r, r, r, r)
                }
            }
        }
        column.addView(message, LinearLayout.LayoutParams(-2, -2).apply { topMargin = dp(5) })
        if (time.isNotEmpty()) {
            column.addView(text(time, 11, palette.muted), LinearLayout.LayoutParams(-2, -2).apply { topMargin = dp(5) })
        }
        val showAvatar = avatars && !system
        val avatarSize = dp(40)
        line.addView(column, FrameLayout.LayoutParams(-2, -2, align or Gravity.TOP).apply {
            if (showAvatar) {
                if (own) marginEnd = avatarSize + dp(8) else marginStart = avatarSize + dp(8)
            }
        })
        if (showAvatar) {
            line.addView(avatar(name, avatarMap), FrameLayout.LayoutParams(avatarSize, avatarSize, align or Gravity.TOP).apply {
                topMargin = dp(7)
            })
        }
        parent.addView(line, LinearLayout.LayoutParams(-1, -2).apply { topMargin = dp(if (system) 12 else 10) })
        message
    }
    private fun messageOptions(id: String, index: Int, msg: JSONObject, anchor: View) = with(a) {
        if (stream != null) return@with
        popup(anchor, listOf("复制", "编辑", "删除")) { choice ->
            when (choice) {
                0 -> getSystemService(ClipboardManager::class.java).setPrimaryClip(ClipData.newPlainText("消息", msg.text("content")))
                1 -> inputDialog("编辑消息", msg.text("content"), multiline = true) { value ->
                    val replacement = value.trim()
                    if (replacement.isNotEmpty()) {
                        chats.edit(id) { chat, _ -> chat.array("messages").optJSONObject(index)?.put("content", replacement) }
                        render()
                    }
                }
                2 -> confirm("删除消息", "删除所选消息？", "删除") {
                    chats.edit(id) { chat, _ -> chat.array("messages").remove(index) }; render()
                }
            }
        }
    }
    private fun start(request: ChatRequest) = with(a) {
        check(stream == null) { "请等待当前回复" }
        partial = ""
        val client = ChatStream(request)
        stream = client
        if (overlayPage != null) closePage() else render()
        client.start(onChunk = { text ->
            if (stream === client && currentId == request.id && screen == null && tab == 1) {
                partial = text
                val scroll = transcript
                val follow = scroll?.let { it.getChildAt(0).height - it.height - it.scrollY < dp(100) } ?: false
                streamLabel?.text = text
                refreshComposerBackdrop()
                if (follow) scroll?.post { scroll.scrollTo(0, scroll.getChildAt(0).height) }
            }
        }, onFinish = { result ->
            if (stream === client) {
                stream = null; streamLabel = null; partial = ""
                result.onSuccess { response ->
                    attempt {
                        val done = chats.reply(request, response)
                        if (tab == 1 && screen == null && currentId == request.id) repaintChat()
                        if (done) alert("挑战完成", "请到任务页领取奖励。")
                    }
                }.onFailure { error ->
                    if (tab == 1 && screen == null) repaintChat()
                    alert("未收到 AI 回复", error.message ?: "请检查网络和提供商设置")
                }
            }
        })
    }
    fun settings() = with(a) {
        val c = current()
        val id = c.text("id")
        title("会话设置", back = true)
        val content = scroller(body)
        val general = content.card()
        if (c.taskChat()) {
            general.action("重置到初始") {
                confirm("重置任务会话", "恢复初始开场消息并清空本会话。挑战奖励仍按任务状态处理。", "重置") {
                    cancel(); chats.reset(id); closePage()
                }
            }
            general.action("放弃挑战") {
                confirm("放弃挑战", "将结束挑战并删除这条任务会话。", "放弃") { cancel(); chats.delete(id, true); currentId = null; selectTab(2) }
            }
            return@with
        }
        entry(general, "会话名称", c.text("title", "新对话")) {
            inputDialog("会话名称", c.text("title")) { title ->
                check(title.isNotBlank()) { "名称不能为空" }
                chats.edit(id) { chat, _ -> chat.put("title", title.trim()) }; render()
            }
        }
        fun change(update: (JSONObject) -> Unit) {
            chats.edit(id) { chat, _ -> update(chat) }
            render()
        }
        switch(general, "显示消息时间", c.optBoolean("showMessageTime", true)) { value -> change { it.put("showMessageTime", value) } }
        switch(general, "显示系统角色", c.optBoolean("showSystemMessages", true)) { value -> change { it.put("showSystemMessages", value); it.fixSelection() } }
        switch(general, "启用 AI", c.optBoolean("aiEnabled", true)) { value ->
            cancel(); auto = null; change { chats.toggleAi(it, value) }
        }
        val actions = row().apply { gravity = Gravity.END }
        fun smallAction(label: String, enabled: Boolean = true, danger: Boolean = false, onClick: () -> Unit) {
            val tint = if (danger) palette.red else palette.accent
            val control = text(label, 13, tint, true).apply {
                gravity = Gravity.CENTER
                minHeight = dp(36)
                setPadding(dp(13), dp(5), dp(13), dp(5))
                alpha = if (enabled) 1f else .5f
                if (enabled) clickable(this, tint and 0x00ffffff or (0x1c shl 24), 18, onClick)
            }
            actions.addView(control, LinearLayout.LayoutParams(-2, -2).apply { marginStart = dp(7) })
        }
        smallAction("立即请求回复", c.optBoolean("aiEnabled", true) && c.assistant() != null && stream == null) { start(chats.manual(id)) }
        general.addView(actions, LinearLayout.LayoutParams(-1, -2).apply { topMargin = dp(8) })
        section(content, "角色列表")
        val roles = content.card()
        val avatarMap = store.state().optJSONObject("avatar_map") ?: JSONObject()
        val roleLine = { role: JSONObject, label: String, description: String, onAvatar: (View) -> Unit, onRole: (View) -> Unit ->
            val line = row().apply { minimumHeight = dp(55); setPadding(dp(3), dp(3), dp(3), dp(3)) }
            val image = avatar(role.text("name"), avatarMap).apply {
                contentDescription = "$label 头像设置"
                clickable(this, Color.TRANSPARENT, 20) { onAvatar(this) }
            }
            line.addView(image, LinearLayout.LayoutParams(dp(42), dp(42)).apply { marginEnd = dp(11) })
            val name = text(label, 15, palette.text)
            line.addView(name, LinearLayout.LayoutParams(0, -2, 1f))
            line.addView(text(description, 13, palette.muted), LinearLayout.LayoutParams(-2, -2))
            val arrow = GlyphView(this, Glyph.NEXT, palette.muted)
            line.addView(arrow, LinearLayout.LayoutParams(dp(22), dp(24)))
            clickable(line, Color.TRANSPARENT, 12) { onRole(line) }
            line
        }
        c.roles().filter { it.text("type") == "system" }.forEach { role ->
            val line = row().apply { minimumHeight = dp(44); setPadding(dp(12), dp(2), dp(12), dp(2)) }
            line.addView(text("system · 系统", 14, palette.muted), LinearLayout.LayoutParams(0, -2, 1f))
            line.background = shape(palette.raised, 12)
            roles.addView(line)
        }
        val activeRoles = c.roles().filter { it.text("type") != "system" && !it.optBoolean("archived") }
        activeRoles.forEach { role ->
            val name = role.text("name")
            roles.addView(roleLine(role, name, if (role.text("type") == "assistant") "助手" else "用户", {
                popup(it, listOf("设置头像", "恢复默认头像")) { choice ->
                    if (choice == 0) { avatarRole = name; openPhoto() } else { writeAvatar(name, null); render() }
                }
            }) { anchor -> roleOptions(id, role, anchor) }, LinearLayout.LayoutParams(-1, -2))
        }
        val archived = c.roles().filter { it.text("type") != "system" && it.optBoolean("archived") }
        if (archived.isNotEmpty()) {
            anchoredEntry(roles, "已归档角色 (${archived.size})", if (archivedRolesExpanded) "收起" else "展开") {
                archivedRolesExpanded = !archivedRolesExpanded
                render()
            }
            if (archivedRolesExpanded) archived.forEach { role ->
                val name = role.text("name")
                roles.addView(roleLine(role, name, "已归档", {
                    popup(it, listOf("设置头像", "恢复默认头像")) { choice ->
                        if (choice == 0) { avatarRole = name; openPhoto() } else { writeAvatar(name, null); render() }
                    }
                }) { anchor -> roleOptions(id, role, anchor) }, LinearLayout.LayoutParams(-1, -2))
            }
        }
        val roleActions = row().apply { gravity = Gravity.START }
        fun roleAction(label: String, danger: Boolean = false, onClick: () -> Unit) {
            val tint = if (danger) palette.red else palette.accent
            val control = text(label, 13, tint, true).apply {
                gravity = Gravity.CENTER; minHeight = dp(36); setPadding(dp(13), dp(5), dp(13), dp(5))
                clickable(this, tint and 0x00ffffff or (0x1c shl 24), 18, onClick)
            }
            roleActions.addView(control, LinearLayout.LayoutParams(-2, -2).apply { marginEnd = dp(7) })
        }
        roleAction("新建角色") { editRole(id, null) }
        roles.addView(roleActions, LinearLayout.LayoutParams(-1, -2).apply { topMargin = dp(8) })
        val history = store.state().array("role_history").strings().filterNot { it == "system" || it == "系统" }.distinct()
        if (history.isNotEmpty()) {
            anchoredEntry(roles, "使用过的角色 (${history.size})", if (roleHistoryExpanded) "收起" else "展开") {
                roleHistoryExpanded = !roleHistoryExpanded
                render()
            }
            if (roleHistoryExpanded) history.forEach { name ->
                val item = row().apply { minimumHeight = dp(52); setPadding(dp(3), dp(3), dp(3), dp(3)) }
                item.addView(avatar(name, avatarMap), LinearLayout.LayoutParams(dp(42), dp(42)).apply { marginEnd = dp(11) })
                item.addView(text(name, 14, palette.text), LinearLayout.LayoutParams(0, -2, 1f))
                fun historyAction(label: String, danger: Boolean, callback: () -> Unit) {
                    val tint = if (danger) palette.red else palette.accent
                    val control = text(label, 13, tint, true).apply {
                        gravity = Gravity.CENTER; minHeight = dp(35); setPadding(dp(9), dp(4), dp(9), dp(4))
                        clickable(this, tint and 0x00ffffff or (0x1c shl 24), 17, callback)
                    }
                    item.addView(control, LinearLayout.LayoutParams(-2, -2).apply { marginStart = dp(5) })
                }
                historyAction("添加", false) {
                    chats.edit(id) { chat, state ->
                        val existing = chat.roles().firstOrNull { it.text("name") == name }
                        if (existing != null) { existing.put("archived", false); chat.fixSelection() }
                        else chats.renameRole(chat, state, null, name, "user")
                    }
                    render()
                }
                historyAction("删除", true) {
                    store.edit { state -> state.put("role_history", JSONArray(state.array("role_history").strings().filterNot { it == name })) }
                    render()
                }
                roles.addView(item, LinearLayout.LayoutParams(-1, -2))
            }
        }
        val clear = text("清空消息", 14, palette.red, true).apply {
            gravity = Gravity.CENTER; minHeight = dp(43); background = shape(palette.red and 0x00ffffff or (0x1c shl 24), 14)
        }
        clickable(clear, palette.red and 0x00ffffff or (0x1c shl 24), 14) {
            confirm("清空消息", "清空此会话的全部消息？", "清空") { cancel(); change { it.put("messages", JSONArray()) } }
        }
        content.addView(clear, LinearLayout.LayoutParams(-1, -2).apply { topMargin = dp(12); bottomMargin = dp(16) })
    }
    private fun roleOptions(id: String, role: JSONObject, anchor: View) = with(a) {
        val name = role.text("name")
        val archived = role.optBoolean("archived")
        val options = listOf("编辑名称", if (archived) "启用角色" else if (role.text("type") == "assistant") "改为用户" else "设为助手", "删除角色")
        popup(anchor, options, dangerIndices = setOf(2)) { choice ->
            when (choice) {
                0 -> editRole(id, role)
                1 -> {
                    if (archived) {
                        chats.edit(id) { chat, state ->
                            val target = chat.roles().first { it.text("name") == name }
                            target.put("archived", false)
                            if (target.text("type") == "assistant") chat.assignAssistant(name)
                            state.put("role_history", JSONArray((state.array("role_history").strings() + name).distinct()))
                        }
                        auto = null; render()
                        return@popup
                    }
                    chats.edit(id) { chat, state ->
                        val target = chat.roles().first { it.text("name") == name }
                        if (target.text("type") == "assistant") { target.put("type", "user"); target.put("isActive", false) }
                        else {
                            check(chat.optBoolean("aiEnabled", true)) { "请先启用 AI" }
                            target.put("archived", false); chat.assignAssistant(name)
                        }
                        state.put("role_history", JSONArray((state.array("role_history").strings() + name).distinct()))
                    }
                    auto = null; render()
                }
                2 -> {
                    val box = ToggleControl(this, "同时删除该角色的消息", false)
                    panelDialog("删除 $name", "保留消息时，该角色会进入归档。", "删除", "取消", box) {
                        chats.edit(id) { chat, _ -> chats.deleteRole(chat, name, box.isChecked()) }; render()
                    }
                }
            }
        }
    }
    private fun editRole(id: String, role: JSONObject?) = with(a) {
        val old = role?.text("name")
        inputDialog(if (old == null) "添加角色" else "编辑角色名", old.orEmpty()) { value ->
            chats.edit(id) { chat, state -> chats.renameRole(chat, state, old, value.trim(), role?.text("type", "user") ?: "user") }; render()
        }
    }
    private fun avatar(name: String, mapping: JSONObject): View = with(a) {
        val filename = mapping.text(name)
        if (filename.isBlank()) return@with ImageView(this).apply {
            scaleType = ImageView.ScaleType.CENTER_CROP; setImageResource(R.drawable.default_avatar)
            background = shape(palette.raised, 20); clipToOutline = true; contentDescription = "$name 头像"
        }
        val bitmap = runCatching {
            val dir = File(filesDir, "avatars").canonicalFile
            val file = File(dir, filename).canonicalFile
            check(file.parentFile == dir)
            BitmapFactory.decodeFile(file.path)
        }.getOrNull()
        if (bitmap == null) text(name.take(1), 14, palette.accent, true).apply {
            gravity = Gravity.CENTER; background = shape(palette.raised, 20); contentDescription = "$name 头像"
        }
        else ImageView(this).apply {
            setImageBitmap(bitmap); scaleType = ImageView.ScaleType.CENTER_CROP
            background = shape(palette.raised, 20); clipToOutline = true; contentDescription = "$name 头像"
        }
    }
    fun acceptPhoto(uri: Uri) = with(a) {
        if (avatarRole == null || !isShowing("chat-settings")) return@with
        background({
            val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
            contentResolver.openInputStream(uri)?.use { BitmapFactory.decodeStream(it, null, bounds) }
            require(bounds.outWidth > 0 && bounds.outHeight > 0) { "无法读取图片" }
            var scale = 1
            while (maxOf(bounds.outWidth, bounds.outHeight) / scale > 1800) scale *= 2
            contentResolver.openInputStream(uri)?.use {
                BitmapFactory.decodeStream(it, null, BitmapFactory.Options().apply { inSampleSize = scale })
            } ?: error("无法解码图片")
        }) { result -> result.onSuccess {
            if (isShowing("chat-settings") && avatarRole != null) {
                photo = it
                open(Screen("avatar"))
            }
        }.onFailure(::showError) }
    }
    fun crop() = with(a) {
        title("裁剪头像", back = true)
        val card = scroller(body).card()
        val bitmap = photo
        val name = avatarRole
        if (bitmap == null || name == null) { card.label("请选择图片"); return@with }
        val crop = CropView(this, bitmap, palette)
        card.addView(crop, LinearLayout.LayoutParams(-1, dp(290)))
        card.label("拖动和缩放，保留方框内的画面。", 13, palette.muted, gap = 8)
        card.action("保存头像", primary = true) {
            val directory = File(filesDir, "avatars").apply { mkdirs() }
            val filename = "${UUID.randomUUID()}_${now()}.png"
            val output = crop.output()
            try {
                File(directory, filename).outputStream().use { check(output.compress(Bitmap.CompressFormat.PNG, 100, it)) { "头像保存失败" } }
                writeAvatar(name, filename)
            } catch (failure: Exception) {
                File(directory, filename).delete()
                throw failure
            } finally { output.recycle() }
            photo = null; closePage()
        }
    }
    private fun writeAvatar(name: String, filename: String?) {
        val previous = a.store.transact { _, s ->
            val map = s.child("avatar_map")
            val old = map.text(name)
            if (filename == null) map.remove(name) else map.put(name, filename)
            s.list("conversations").flatMap { it.roles() + it.list("assistantBackup") }.filter { it.text("name") == name }.forEach {
                if (filename == null) it.remove("avatarPath") else it.put("avatarPath", "avatars/$filename")
            }
            s.put("role_history", JSONArray((s.array("role_history").strings() + name).distinct()))
            old
        }
        if (previous.matches(Regex("[a-f0-9-]+_[0-9]+\\.png"))) File(File(a.filesDir, "avatars"), previous).delete()
    }
}
