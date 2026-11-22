package sleep.record

import android.util.JsonReader
import android.util.JsonToken
import org.json.JSONArray
import org.json.JSONObject
import java.io.StringReader

internal object Backup {
    private val sections = linkedMapOf(
        "chatData" to linkedMapOf("conversations" to JSONArray(), "chat_day_key" to "", "chat_day_chars" to 0),
        "challengeData" to linkedMapOf("tasks" to JSONArray()),
        "checkinData" to linkedMapOf("last_checkin_date" to "", "streak_days" to 0, "last_checkin_time" to 0L),
        "oneTimeData" to linkedMapOf("completed_tasks" to JSONArray()),
        "appData" to linkedMapOf("notice_shown" to false)
    )

    // JsonReader keeps JSON token types distinct. Never coerce a quoted timestamp to a number.
    private fun parse(text: String, path: String): Any {
        try {
            JsonReader(StringReader(text.removePrefix("\uFEFF"))).use { r ->
                val result = read(r, path, 0)
                require(r.peek() == JsonToken.END_DOCUMENT) { "$path 有多余内容" }
                return result
            }
        } catch (e: Exception) {
            throw IllegalArgumentException("$path：${e.message ?: "JSON 格式错误"}", e)
        }
    }
    private fun read(r: JsonReader, path: String, depth: Int): Any {
        require(depth <= 64) { "$path 嵌套过深" }
        return when (r.peek()) {
            JsonToken.BEGIN_OBJECT -> JSONObject().apply {
                r.beginObject()
                while (r.hasNext()) {
                    val name = r.nextName()
                    put(name, read(r, "$path.$name", depth + 1))
                }
                r.endObject()
            }
            JsonToken.BEGIN_ARRAY -> JSONArray().apply {
                r.beginArray()
                while (r.hasNext()) put(read(r, "$path[${length()}]", depth + 1))
                r.endArray()
            }
            JsonToken.NUMBER -> r.nextString().let { it.toLongOrNull() ?: it.toDouble() }
            JsonToken.STRING -> r.nextString()
            JsonToken.BOOLEAN -> r.nextBoolean()
            JsonToken.NULL -> { r.nextNull(); JSONObject.NULL }
            else -> error("$path 不是有效的 JSON 值")
        }
    }
    private inline fun <reified T : Any> field(row: JSONObject, key: String, at: String, fallback: T? = null): T {
        if (!row.has(key) && fallback != null) return fallback
        return row.opt(key) as? T ?: error("$at.$key 的字段类型错误（应为 ${T::class.simpleName}）")
    }
    private fun integer(row: JSONObject, key: String, at: String, fallback: Int = 0): Int {
        val v = field(row, key, at, fallback.toLong())
        require(v in Int.MIN_VALUE.toLong()..Int.MAX_VALUE.toLong()) { "$at.$key 超出整数范围" }
        return v.toInt()
    }
    private fun nonnegative(v: Long, path: String): Long {
        require(v >= 0) { "$path 不可为负数" }
        return v
    }
    private fun rows(list: JSONArray, path: String) = (0 until list.length()).map { i ->
        (list.opt(i) as? JSONObject ?: error("$path[$i] 应为对象")) to "$path[$i]"
    }
    private fun validateList(list: JSONArray, key: String, path: String) {
        if (key == "completed_tasks") {
            for (i in 0 until list.length()) require(list.opt(i) is String) { "$path[$i] 应为字符串" }
            return
        }
        rows(list, path).forEach { (row, at) ->
            if (row.has("id")) field<String>(row, "id", at)
            if (key == "tasks") {
                field<String>(row, "name", at)
                field<String>(row, "description", at, "")
                integer(row, "points", at)
                val status = field(row, "status", at, "IN_PROGRESS")
                require(status in listOf("IN_PROGRESS", "COMPLETED", "FAILED", "ARCHIVED")) { "$at.status 无效" }
                field(row, "createdAt", at, 0L)
                for (keyName in listOf("completedAt", "targetHourMs", "acceptedHourStartMs")) if (!row.isNull(keyName)) field<Long>(row, keyName, at)
                for (keyName in listOf("boundConversationId", "settledDate", "judgedDate")) if (!row.isNull(keyName)) field<String>(row, keyName, at)
            } else {
                val type = field(row, "type", at, "USER")
                require(type == "USER" || type == "TASK") { "$at.type 无效" }
                for (keyName in listOf("title", "selectedRoleName")) if (row.has(keyName)) field<String>(row, keyName, at)
                for (keyName in listOf("subType", "boundChallengeTaskId", "systemPrompt")) if (!row.isNull(keyName)) field<String>(row, keyName, at)
                for (keyName in listOf("isCompleted", "showSystemMessages", "aiEnabled", "showMessageTime")) {
                    if (row.has(keyName)) field<Boolean>(row, keyName, at)
                }
                rows(field(row, "messages", at, JSONArray()), "$at.messages").forEach { (msg, p) ->
                    field<String>(msg, "role", p); field<String>(msg, "content", p); field(msg, "timestamp", p, 0L)
                }
                for (keyName in listOf("roles", "assistantBackup")) {
                    rows(field(row, keyName, at, JSONArray()), "$at.$keyName").forEach { (role, p) ->
                        field<String>(role, "name", p)
                        require(field(role, "type", p, "user") in listOf("system", "user", "assistant")) { "$p.type 无效" }
                        field(role, "archived", p, false); field(role, "isActive", p, false)
                        if (!role.isNull("avatarPath")) field<String>(role, "avatarPath", p)
                    }
                }
            }
        }
    }

    fun import(store: Store, text: String, ms: Long = now()) {
        val root = parse(text, "备份") as? JSONObject ?: error("备份根节点应为对象")
        val settings = field(root, "settings", "备份", JSONObject())
        val parsedHours = rows(field(root, "hourlyRecords", "备份", JSONArray()), "hourlyRecords").map { (r, p) ->
            val hour = Hour(field(r, "hourStartMs", p), nonnegative(field(r, "totalUsageMs", p), "$p.totalUsageMs"), integer(r, "score", p),
                nonnegative(integer(r, "deduction", p).toLong(), "$p.deduction").toInt(), integer(r, "bonusScore", p), field(r, "lastUpdated", p, ms))
            Triple(r, p, hour)
        }
        val entries = rows(field(root, "appUsageEntries", "备份", JSONArray()), "appUsageEntries").map { (r, p) ->
            Entry(field(r, "hourStartMs", p), field(r, "packageName", p, ""), field(r, "totalTimeMs", p),
                field(r, "lastEventTime", p, 0L), field(r, "isScreenEvent", p, false))
        }
        val observed = entries.mapTo(hashSetOf(), Entry::at)
        val hourRows = parsedHours.map { (r, p, hour) ->
            hour.copy(hasUsage = field(r, "hasUsage", p, hour.inferredHasUsage || hour.at in observed))
        }
        val replacement = JSONObject()
        for ((section, keys) in sections) {
            if (!root.has(section)) continue
            val content = field<JSONObject>(root, section, "备份")
            for ((key, default) in keys) {
                val path = "$section.$key"
                val value = when (default) {
                    is JSONArray -> {
                        val list = parse(field(content, key, section, "[]"), path) as? JSONArray ?: error("$path 内层应为数组")
                        validateList(list, key, path)
                        if (key == "completed_tasks") JSONArray(list.strings().distinct()) else list
                    }
                    is String -> field(content, key, section, default)
                    is Int -> integer(content, key, section, default)
                    is Long -> nonnegative(field(content, key, section, default), path)
                    is Boolean -> field(content, key, section, default)
                    else -> error("未知的段 $section")
                }
                replacement.put(key, value)
            }
        }
        store.transact { db, s ->
            // Re-score retained local rows; imported rows keep the scores supplied by the backup.
            if (settings.length() != 0) store.setSettings(db, s, Config(settings).export())
            hourRows.forEach { store.put(db, it) }
            entries.forEach { store.put(db, it) }
            replacement.keys().forEach { s.put(it, replacement.get(it)) }
            store.invalidate(s)
            s.remove("last_refresh_time")
            s.put("import_generation", s.optLong("import_generation") + 1)
        }
    }

    fun export(store: Store): String = store.transact { db, s ->
        val root = json("settings" to Config(s.optJSONObject("settings") ?: JSONObject()).export(),
            "hourlyRecords" to JSONArray(store.hours(db = db).map {
                json("hourStartMs" to it.at, "totalUsageMs" to it.usage, "score" to it.score, "deduction" to it.deduction,
                    "bonusScore" to it.bonus, "lastUpdated" to it.updated)
                    .apply { if (it.hasUsage != it.inferredHasUsage) put("hasUsage", it.hasUsage) }
            }), "appUsageEntries" to JSONArray(store.entries(db = db).map {
                json("hourStartMs" to it.at, "packageName" to it.pkg, "totalTimeMs" to it.duration,
                    "lastEventTime" to it.timestamp, "isScreenEvent" to it.screen)
            }))
        sections.forEach { (section, keys) ->
            root.put(section, JSONObject().apply {
                keys.forEach { (key, fallback) ->
                    if (key == "last_checkin_time" && s.optLong(key) <= 0L) return@forEach
                    if (key == "last_checkin_date") {
                        put(key, s.customDate(s.config(), key, "last_checkin_time"))
                        return@forEach
                    }
                    val value = s.opt(key) ?: fallback
                    put(key, if (value is JSONArray) value.toString() else value)
                }
            })
        }
        root.toString(2)
    }
}
