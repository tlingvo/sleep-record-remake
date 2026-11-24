package sleep.record

import android.os.Handler
import android.os.Looper
import org.json.JSONArray
import org.json.JSONObject
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URL
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicBoolean

internal fun role(name: String, type: String, active: Boolean = false) =
    json("name" to name, "type" to type, "isActive" to active, "archived" to false)
internal fun message(name: String, content: String, ms: Long = now()) = json("role" to name, "content" to content, "timestamp" to ms)
private fun defaultRoles(subType: String = "") = JSONArray(listOf(role("system", "system"), role("我", "user"), when (subType) {
    "SELF_CHAT" -> role("对方", "user")
    "ROLEPLAY" -> role("请输入名字", "assistant", true)
    else -> role("助手", "assistant", true)
}))
internal fun newConversation(s: JSONObject, subType: String = "", title: String? = null): JSONObject {
    val number = s.array("conversations").length() + 1
    val messages = JSONArray()
    if (subType == "ROLEPLAY") messages.put(message("system", "请把这一条消息改为你的角色prompt。然后在角色列表修改角色名称"))
    return json("id" to newId(), "title" to (title ?: when (subType) {
        "ROLEPLAY" -> "角色扮演$number"
        "SELF_CHAT" -> "自我对话$number"
        else -> "对话$number"
    }), "type" to "USER", "subType" to subType.ifEmpty { null }, "messages" to messages,
        "roles" to defaultRoles(subType), "selectedRoleName" to "我", "showSystemMessages" to (subType != "SELF_CHAT"),
        "aiEnabled" to (subType != "SELF_CHAT"), "assistantBackup" to JSONArray(), "showMessageTime" to true, "isCompleted" to false)
}
internal fun JSONObject.roles() = list("roles")
/** System message for a role's own prompt; names the role so several prompts in one chat stay distinguishable. */
internal fun rolePrompt(role: JSONObject): String {
    val kind = when (role.text("type", "user")) { "assistant" -> "助手"; "system" -> "系统"; else -> "用户" }
    return "以下是角色「${role.text("name")}」（$kind）的设定：\n${role.text("prompt").trim()}"
}
internal fun JSONObject.setRolePrompt(name: String, prompt: String) {
    val role = roles().firstOrNull { it.text("name") == name } ?: error("找不到角色")
    if (prompt.isBlank()) role.remove("prompt") else role.put("prompt", prompt.trim())
}
internal fun JSONObject.taskChat() = text("type") == "TASK"
internal fun JSONObject.assistant() = roles().firstOrNull { it.text("type") == "assistant" && it.optBoolean("isActive") && !it.optBoolean("archived") }
    ?: roles().firstOrNull { it.text("type") == "assistant" && !it.optBoolean("archived") }
internal fun JSONObject.usableRoles() = roles().filter {
    !it.optBoolean("archived") && (optBoolean("showSystemMessages", true) || it.text("type") != "system")
}
internal fun JSONObject.fixSelection() {
    val available = usableRoles()
    if (available.none { it.text("name") == text("selectedRoleName", "我") }) {
        put("selectedRoleName", (available.firstOrNull { it.text("type") == "user" } ?: available.firstOrNull())?.text("name") ?: "我")
    }
}
internal fun JSONObject.assignAssistant(name: String) {
    roles().filter { !it.optBoolean("archived") && it.text("type") != "system" }.forEach {
        if (it.text("name") == name) {
            it.put("type", "assistant"); it.put("isActive", true)
        } else if (it.text("type") == "assistant") {
            it.put("type", "user"); it.put("isActive", false)
        }
    }
}
private fun normalize(row: JSONObject, index: Int) {
    if (row.text("id").isBlank()) row.put("id", "legacy-chat-$index")
    val existing = if (row.has("roles")) row.roles() else defaultRoles(row.text("subType")).objects()
    val roles = linkedMapOf<String, JSONObject>()
    existing.forEach {
        val name = it.text("name").let { value -> if (value == "系统") "system" else value }
        it.put("name", name)
        if (name == "system") it.put("type", "system")
        if (!roles.containsKey(name)) roles[name] = it
    }
    roles.putIfAbsent("system", role("system", "system"))
    for (msg in row.list("messages")) {
        val name = msg.text("role").let { if (it == "系统") "system" else it }
        msg.put("role", name)
        if (roles.containsKey(name)) continue
        if (name == "assistant") {
            roles.values.filter { it.text("type") == "assistant" && !it.optBoolean("archived") }.forEach {
                it.put("type", "user"); it.put("isActive", false)
            }
            roles[name] = role(name, "assistant", true)
        } else roles[name] = role(name, if (name == "system") "system" else "user")
    }
    row.put("roles", JSONArray(roles.values.sortedBy { if (it.text("type") == "system") 0 else 1 }))
    if (row.text("selectedRoleName") == "系统") row.put("selectedRoleName", "system")
    row.fixSelection()
}

internal class Chats(private val store: Store, private val catalog: Catalog) {
    fun load(): List<JSONObject> = store.transact { _, s ->
        var list = s.list("conversations")
        list.forEachIndexed { index, row -> normalize(row, index) }
        if (list.none { !it.taskChat() }) {
            list = list + newConversation(s, title = "新对话")
            s.put("conversations", JSONArray(list))
        }
        list
    }
    fun create(subType: String) = store.transact { _, s ->
        val row = newConversation(s, subType)
        s.put("conversations", s.array("conversations").put(row))
        row.getString("id")
    }
    fun edit(id: String, action: (JSONObject, JSONObject) -> Unit) = store.edit { s ->
        val chat = s.find("conversations", id) ?: error("会话已移除")
        action(chat, s)
    }
    fun delete(id: String, abandon: Boolean = false) = store.edit { s ->
        val chat = s.find("conversations", id) ?: return@edit
        if (abandon) {
            val task = s.find("tasks", chat.text("boundChallengeTaskId"))
            if (task != null && task.text("status", "IN_PROGRESS") in listOf("IN_PROGRESS", "COMPLETED")) {
                task.put("status", "FAILED"); task.put("completedAt", now())
            }
        }
        s.put("conversations", JSONArray(s.list("conversations").filterNot { it.text("id") == id }))
    }
    fun reset(id: String) = edit(id) { chat, _ ->
        chat.put("isCompleted", false)
        chat.put("messages", JSONArray().put(message("assistant", catalog.challengeChat.text("openingMessage"))))
    }
    fun renameRole(chat: JSONObject, state: JSONObject, old: String?, new: String, type: String) {
        check(new.isNotBlank() && new != "system" && new != "系统") { "请输入非保留角色名" }
        check(chat.roles().none { it.text("name") == new && new != old }) { "角色名已存在" }
        val roles = chat.array("roles")
        val target = chat.roles().firstOrNull { it.text("name") == old }
        if (target == null) chat.put("roles", roles.put(role(new, type))) else {
            target.put("name", new)
            target.put("type", type)
            target.put("isActive", type == "assistant")
            chat.list("messages").filter { it.text("role") == old }.forEach { it.put("role", new) }
            chat.list("assistantBackup").filter { it.text("name") == old }.forEach { it.put("name", new) }
            if (chat.text("selectedRoleName") == old) chat.put("selectedRoleName", new)
        }
        if (type == "assistant") chat.assignAssistant(new)
        val names = state.array("role_history").strings() + new
        state.put("role_history", JSONArray(names.distinct()))
        chat.fixSelection()
    }
    fun deleteRole(chat: JSONObject, name: String, removeMessages: Boolean) {
        if (removeMessages) {
            chat.put("messages", JSONArray(chat.list("messages").filterNot { it.text("role") == name }))
            chat.put("roles", JSONArray(chat.roles().filterNot { it.text("name") == name }))
        } else chat.roles().firstOrNull { it.text("name") == name }?.apply {
            put("archived", true); put("type", "user"); put("isActive", false)
        }
        chat.put("assistantBackup", JSONArray(chat.list("assistantBackup").filterNot { it.text("name") == name }))
        chat.fixSelection()
    }
    fun toggleAi(chat: JSONObject, enabled: Boolean) {
        if (enabled == chat.optBoolean("aiEnabled", true)) return
        chat.put("aiEnabled", enabled)
        if (!enabled) {
            val assistants = chat.roles().filter { it.text("type") == "assistant" && !it.optBoolean("archived") }
            chat.put("assistantBackup", JSONArray(assistants.map { JSONObject(it.toString()) }))
            assistants.forEach { it.put("type", "user"); it.put("isActive", false) }
        } else {
            val backups = chat.list("assistantBackup").associateBy { it.text("name") }
            var activeFound = false
            chat.roles().filterNot { it.optBoolean("archived") }.forEach { role ->
                backups[role.text("name")]?.let { previous ->
                    role.put("type", "assistant")
                    val active = !activeFound && previous.optBoolean("isActive")
                    role.put("isActive", active)
                    activeFound = activeFound || active
                }
            }
        }
    }
    fun send(id: String, content: String, auto: Boolean, ms: Long = now()): ChatRequest? = store.transact { db, s ->
        val chat = s.find("conversations", id) ?: error("会话已移除")
        check(!chat.optBoolean("isCompleted")) { "该任务会话已完成" }
        val task = chat.taskChat()
        val name = if (task) "用户" else chat.text("selectedRoleName", "我")
        if (chat.roles().any { it.text("name") == name && it.text("type") == "user" }) count(db, s, content.length, ms)
        chat.put("messages", chat.array("messages").put(message(name, content, ms)))
        if (task || auto && chat.optBoolean("aiEnabled", true) && chat.assistant() != null) request(chat, s.config(), false) else null
    }
    fun manual(id: String): ChatRequest {
        val s = store.state()
        val chat = s.find("conversations", id) ?: error("会话已移除")
        check(chat.optBoolean("aiEnabled", true) && chat.assistant() != null && !chat.optBoolean("isCompleted")) { "请先启用 AI 并设置助手" }
        return request(chat, s.config(), true)
    }
    private fun request(chat: JSONObject, config: Config, manual: Boolean): ChatRequest {
        val messages = JSONArray()
        if (chat.taskChat() && chat.text("systemPrompt").isNotBlank()) {
            messages.put(json("role" to "system", "content" to chat.text("systemPrompt")))
        }
        // Role prompts: every role still in this conversation (not archived) sends its own setting as a system message.
        chat.roles().filter { !it.optBoolean("archived") && it.text("prompt").isNotBlank() }.forEach {
            messages.put(json("role" to "system", "content" to rolePrompt(it)))
        }
        val roles = chat.roles().associate { it.text("name") to it.text("type", "user") }
        chat.list("messages").forEach {
            messages.put(json("role" to (roles[it.text("role")] ?: "user"), "content" to it.text("content")))
        }
        return ChatRequest(chat.getString("id"), config.raw("chatProviderUrl"), config.raw("chatApiKey"), config.raw("chatModel"),
            messages, chat.assistant()?.text("name") ?: "assistant", manual)
    }
    fun reply(request: ChatRequest, content: String, ms: Long = now()): Boolean = store.transact { db, s ->
        val chat = s.find("conversations", request.id) ?: return@transact false
        if (content.isBlank() || chat.optBoolean("isCompleted")) return@transact false
        val completed = chat.taskChat() && content.contains("\"task_completed\"")
        if (completed) {
            chat.put("isCompleted", true)
            s.find("tasks", chat.text("boundChallengeTaskId"))?.let {
                if (it.text("status", "IN_PROGRESS") == "IN_PROGRESS") {
                    it.put("status", "COMPLETED"); it.put("completedAt", ms)
                }
            }
        } else if (!request.manual) count(db, s, content.length, ms)
        val text = if (completed) "任务完成！你已成功说服我。" else content
        chat.put("messages", chat.array("messages").put(message(request.assistant, text, ms)))
        completed
    }
    private fun count(db: android.database.sqlite.SQLiteDatabase, s: JSONObject, length: Int, ms: Long) {
        val config = s.config()
        val old = if (s.text("chat_day_key") == civil(ms)) s.optInt("chat_day_chars").coerceAtLeast(0) else 0
        if (old >= config.number("chatCharLimit")) return
        val next = (old.toLong() + length).coerceAtMost(Int.MAX_VALUE.toLong()).toInt()
        val divisor = config.number("chatCharsPerPoint")
        val delta = minOf(next / divisor, config.number("chatDailyMaxPoints")) - old / divisor
        s.put("chat_day_key", civil(ms)); s.put("chat_day_chars", next)
        if (delta > 0) store.bonus(db, s, ms, delta, ms)
    }
}

internal data class ChatRequest(val id: String, val provider: String, val key: String, val model: String,
                                val messages: JSONArray, val assistant: String, val manual: Boolean)
internal class ChatStream(private val request: ChatRequest) {
    companion object { private val io = Executors.newCachedThreadPool() }
    private val handler = Handler(Looper.getMainLooper())
    private val cancelled = AtomicBoolean()
    @Volatile private var connection: HttpURLConnection? = null
    private var work: java.util.concurrent.Future<*>? = null

    fun start(onChunk: (String) -> Unit, onFinish: (Result<String>) -> Unit) {
        work = io.submit {
            val result = runCatching {
                check(request.key.isNotBlank()) { "请先在设置中填写 API 密钥" }
                val url = URL(request.provider.trim().trimEnd('/') + "/chat/completions")
                check(url.protocol == "https" && url.host.isNotBlank() && url.userInfo == null && url.query == null && url.ref == null) { "提供商需填写 HTTPS API 基址" }
                val client = (url.openConnection() as HttpURLConnection).also { connection = it }
                try {
                    if (cancelled.get()) throw InterruptedException()
                    client.connectTimeout = 30_000
                    client.readTimeout = 120_000
                    client.instanceFollowRedirects = false
                    client.requestMethod = "POST"
                    client.doOutput = true
                    client.setRequestProperty("Authorization", "Bearer ${request.key.trim()}")
                    client.setRequestProperty("Content-Type", "application/json")
                    client.setRequestProperty("Accept", "text/event-stream")
                    client.outputStream.use { it.write(json("model" to request.model, "messages" to request.messages, "stream" to true).toString().toByteArray(Charsets.UTF_8)) }
                    val status = client.responseCode
                    if (status !in 200..299) throw IOException("提供商返回 HTTP $status")
                    client.inputStream.bufferedReader(Charsets.UTF_8).use { reader ->
                        var lastUpdate = 0L
                        readSse(reader, cancelled::get) { text ->
                            if (now() - lastUpdate >= 45L) {
                                lastUpdate = now()
                                val snapshot = text.toString()
                                handler.post { if (!cancelled.get()) onChunk(snapshot) }
                            }
                        }.also { check(it.isNotBlank()) { "提供商没有返回文字回复" } }
                    }
                } finally {
                    client.disconnect()
                    connection = null
                }
            }
            handler.post { if (!cancelled.get()) onFinish(result) }
        }
    }
    fun cancel() {
        cancelled.set(true)
        work?.cancel(true)
        connection?.let { client -> io.execute { client.disconnect() } }
    }
}

internal fun readSse(reader: java.io.BufferedReader, isCancelled: () -> Boolean, onText: (CharSequence) -> Unit): String {
    val output = StringBuilder()
    while (!isCancelled()) {
        val line = reader.readLine() ?: break
        if (!line.startsWith("data:")) continue
        val payload = line.substringAfter(':').trimStart()
        if (payload == "[DONE]") break
        val chunk = runCatching {
            JSONObject(payload).getJSONArray("choices").getJSONObject(0).getJSONObject("delta").text("content")
        }.getOrDefault("")
        if (chunk.isNotEmpty()) {
            output.append(chunk)
            onText(output)
        }
    }
    return output.toString()
}
