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
    private val drafts = mutableMapOf<String, String>()
    private var avatarRole: String? = null
    private var photo: Bitmap? = null

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
        selector(content, "新建会话", "选择类型", kinds.map { it.first }, -1) { switchTo(chats.create(kinds[it].second)) }
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
        val marker = text(if (chat.taskChat()) "任" else name.take(1), 14, palette.accent, true).apply {
            gravity = Gravity.CENTER; background = shape(palette.raised, 12)
        }
        conversation.addView(marker, LinearLayout.LayoutParams(dp(36), dp(36)).apply { marginEnd = dp(10) })
        conversation.addView(vertical().apply {
            label(name, 15, bold = active).apply { maxLines = 1; ellipsize = android.text.TextUtils.TruncateAt.END }
            label(status, 11, palette.muted, gap = 4)
        }, LinearLayout.LayoutParams(0, -2, 1f))
        clickable(conversation, Color.TRANSPARENT, 12) { switchTo(id) }
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
            line.addView(menu, LinearLayout.LayoutParams(dp(43), dp(42)))
        } else if (active) line.addView(GlyphView(this, Glyph.CHECK, palette.accent), LinearLayout.LayoutParams(dp(37), dp(37)))
        line.background = shape(if (active) palette.raised else palette.surface, 16, if (active) palette.stroke else null)
        parent.addView(line, LinearLayout.LayoutParams(-1, -2).apply { topMargin = dp(4) })
    }
    fun show() = with(a) {
        val row = current()
        val id = row.text("id")
        val isTask = row.taskChat()
        val completed = row.optBoolean("isCompleted")
        val showSystem = row.optBoolean("showSystemMessages", true)
        val avatars = !isTask && row.text("subType") in listOf("SELF_CHAT", "ROLEPLAY") && store.state().optBoolean("show_avatars", true)
        val selected = if (isTask) "用户" else row.text("selectedRoleName", "我")
        val roles = row.roles().associateBy { it.text("name") }
        val avatarMap = store.state().optJSONObject("avatar_map") ?: JSONObject()
        title(row.text("title", "新对话"), subtitle = if (isTask) "任务会话" else if (row.text("subType") == "SELF_CHAT") "离线 · 自我对话" else "不想睡觉",
            leading = Glyph.MENU to "会话", onLeading = { open(Screen("chat-drawer")) },
            actions = arrayOf("选项" to { open(Screen("chat-settings")) }))
        val (scroll, content) = scrollBox(body)
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
            val panel = vertical().apply { setPadding(dp(14), dp(3), dp(14), dp(9)); setBackgroundColor(palette.bg) }
            if (!isTask) {
                val meta = row().apply { minimumHeight = dp(43) }
                val roles = row.usableRoles().map { it.text("name") }
                lateinit var identity: TextView
                identity = button("$selected  ▾", enabled = stream == null && roles.isNotEmpty()) {
                    popup(identity, roles, roles.indexOf(selected)) { i ->
                        chats.edit(id) { chat, _ -> chat.put("selectedRoleName", roles[i]) }; render()
                    }
                }.apply { minHeight = dp(36); maxWidth = dp(174); maxLines = 1; ellipsize = android.text.TextUtils.TruncateAt.END }
                meta.addView(identity, LinearLayout.LayoutParams(-2, -2))
                meta.addView(View(this), LinearLayout.LayoutParams(0, 1, 1f))
                val available = row.optBoolean("aiEnabled", true) && row.assistant() != null
                val reply = ToggleControl(this, "自动回复", available && auto == true, compact = true).apply {
                    isEnabled = available && stream == null
                    onChecked { _, checked -> auto = checked }
                }
                meta.addView(reply, LinearLayout.LayoutParams(-2, -2))
                panel.addView(meta)
            }
            val controls = row()
            controls.addView(composer, LinearLayout.LayoutParams(0, -2, 1f))
            controls.addView(button("发送", true, stream == null) {
                val value = composer.text.toString().trim()
                if (value.isNotBlank() && stream == null) {
                    val request = chats.send(id, value, auto == true)
                    drafts.remove(id)
                    if (request == null) render() else start(request)
                }
            }, LinearLayout.LayoutParams(-2, -2).apply { marginStart = dp(7) })
            panel.addView(controls)
            body.addView(panel)
        }
        scroll.post { scroll.scrollTo(0, content.height) }
    }
    private fun bubble(parent: LinearLayout, name: String, value: String, time: String, own: Boolean, system: Boolean,
                       avatars: Boolean, avatarMap: JSONObject): TextView = with(a) {
        val line = row().apply { gravity = if (own) Gravity.END or Gravity.TOP else Gravity.TOP }
        val wrap = vertical().apply {
            setPadding(dp(13), dp(8), dp(13), dp(10))
            background = shape(if (own) palette.raised else palette.surface, 15, palette.stroke)
        }
        if (avatars && !system && !own) line.addView(avatar(name, avatarMap), LinearLayout.LayoutParams(dp(34), dp(34)).apply { marginEnd = dp(7) })
        val w = (resources.displayMetrics.widthPixels * .72f).toInt()
        wrap.label((if (system) "系统" else name) + if (time.isNotEmpty()) "  $time" else "", 11, palette.muted, bold = true).maxWidth = w
        val message = wrap.label(value, 15, if (system) palette.accent else palette.text, gap = 4).apply { maxWidth = w }
        line.addView(wrap, LinearLayout.LayoutParams(-2, -2))
        if (avatars && !system && own) line.addView(avatar(name, avatarMap), LinearLayout.LayoutParams(dp(34), dp(34)).apply { marginStart = dp(7) })
        parent.addView(line, LinearLayout.LayoutParams(-1, -2).apply { topMargin = dp(10) })
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
        general.action("立刻请求回复", enabled = c.optBoolean("aiEnabled", true) && c.assistant() != null && stream == null) { start(chats.manual(id)) }
        section(content, "角色")
        val roles = content.card()
        c.roles().forEach { role ->
            val name = role.text("name")
            if (role.text("type") == "system") roles.label("system  ·  系统", 14, palette.muted, gap = 8)
            else anchoredEntry(roles, name, if (role.optBoolean("archived")) "已归档" else if (role.text("type") == "assistant") "助手" else "用户") { roleOptions(id, role, it) }
        }
        roles.action("添加角色") { editRole(id, null) }
        anchoredEntry(roles, "使用过的角色") { roleHistory(id, it) }
        content.action("清空消息") {
            confirm("清空消息", "清空此会话的全部消息？", "清空") { cancel(); change { it.put("messages", JSONArray()) } }
        }
    }
    private fun roleOptions(id: String, role: JSONObject, anchor: View) = with(a) {
        val name = role.text("name")
        val archived = role.optBoolean("archived")
        val options = listOf("编辑名称", if (role.text("type") == "assistant") "改为用户" else "设为助手", if (archived) "启用角色" else "删除角色", "设置头像", "恢复默认头像")
        popup(anchor, options) { choice ->
            when (choice) {
                0 -> editRole(id, role)
                1 -> {
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
                2 -> if (archived) {
                    chats.edit(id) { chat, _ -> chat.roles().first { it.text("name") == name }.put("archived", false); chat.fixSelection() }
                    render()
                } else {
                    val box = ToggleControl(this, "同时删除该角色的消息", false)
                    panelDialog("删除 $name", "保留消息时，该角色会进入归档。", "删除", "取消", box) {
                        chats.edit(id) { chat, _ -> chats.deleteRole(chat, name, box.isChecked()) }; render()
                    }
                }
                3 -> { avatarRole = name; openPhoto() }
                4 -> { writeAvatar(name, null); render() }
            }
        }
    }
    private fun editRole(id: String, role: JSONObject?) = with(a) {
        val old = role?.text("name")
        inputDialog(if (old == null) "添加角色" else "编辑角色名", old.orEmpty()) { value ->
            chats.edit(id) { chat, state -> chats.renameRole(chat, state, old, value.trim(), role?.text("type", "user") ?: "user") }; render()
        }
    }
    private fun roleHistory(id: String, anchor: View) = with(a) {
        val names = store.state().array("role_history").strings()
        if (names.isEmpty()) { alert("使用过的角色", "暂无角色历史"); return@with }
        popup(anchor, names) { i ->
            val name = names[i]
            popup(anchor, listOf("加入会话：$name", "从历史移除")) { action ->
                if (action == 0) chats.edit(id) { chat, state ->
                    val existing = chat.roles().firstOrNull { it.text("name") == name }
                    if (existing != null) { existing.put("archived", false); chat.fixSelection() }
                    else chats.renameRole(chat, state, null, name, "user")
                } else store.edit { it.put("role_history", JSONArray(it.array("role_history").strings().filterNot { n -> n == name })) }
                render()
            }
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
        if (bitmap == null) text(name.take(1), 14, palette.accent, true).apply { gravity = Gravity.CENTER; background = shape(palette.raised, 18) }
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
