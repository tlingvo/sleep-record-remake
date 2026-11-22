package sleep.record

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject
import java.util.Calendar
import java.util.UUID

internal fun newId() = UUID.randomUUID().toString()
internal fun JSONObject.config() = Config(optJSONObject("settings") ?: JSONObject())
internal fun JSONObject.list(key: String) = array(key).objects()
internal fun JSONObject.find(key: String, id: String) = list(key).firstOrNull { it.text("id") == id }
internal fun JSONObject.child(key: String): JSONObject = optJSONObject(key) ?: JSONObject().also { put(key, it) }
internal fun JSONObject.customDate(config: Config, dateKey: String, timeKey: String) =
    optLong(timeKey).takeIf { it > 0 }?.let(config::date) ?: text(dateKey)
internal fun JSONObject.earlyDate(config: Config) =
    if (text("judgedDate").isEmpty()) "" else customDate(config, "judgedDate", "createdAt")

internal class Catalog(context: Context) {
    private val source = context.assets.open("catalog.json").bufferedReader(Charsets.UTF_8).use { JSONObject(it.readText()) }
    val questions = source.list("questions").associateBy { it.getString("name") }
    val index = source.array("questionIndex")
    val oneTime = source.list("oneTimeTasks")
    val challengeChat = source.getJSONObject("challengeChat")
}

internal data class Rest(val seconds: Long, val points: Int, val key: String, val end: Long = 0)
internal class Tasks(private val store: Store, private val catalog: Catalog) {
    fun checkin(debug: Boolean = false, ms: Long = now()) = store.transact { db, s ->
        val config = s.config()
        val today = config.date(ms)
        val last = s.customDate(config, "last_checkin_date", "last_checkin_time")
        check(debug || last != today) { "今天已经打卡" }
        val consecutive = runCatching { nextDate(last) == today }.getOrDefault(false)
        s.put("last_checkin_date", today)
        s.put("last_checkin_time", ms)
        s.put("streak_days", if (consecutive) s.optInt("streak_days") + 1 else 1)
        val points = config.number("checkinBonus")
        store.bonus(db, s, ms, points, ms)
        points
    }
    fun suggestion(ms: Long = now()) = store.transact { db, s ->
        val config = s.config()
        val today = config.date(ms)
        check(s.customDate(config, "daily_task_date", "daily_task_time") != today) { "今天已完成" }
        s.put("daily_task_date", today)
        s.put("daily_task_time", ms)
        store.bonus(db, s, ms, config.number("dailyTaskBonus"), ms)
    }
    fun count(s: JSONObject, ms: Long = now()) = if (s.text("daily_count_date") == civil(ms)) s.optInt("daily_count") else 0
    fun oneTime(id: String, points: Int, ms: Long = now()) = store.transact { db, s ->
        check(catalog.oneTime.any { it.text("id") == id }) { "找不到这项任务" }
        val complete = s.array("completed_tasks")
        check(id !in complete.strings()) { "这项任务已完成" }
        val used = count(s, ms)
        check(used < 5) { "今天已做满 5 项一次性任务" }
        s.put("completed_tasks", complete.put(id))
        s.put("daily_count_date", civil(ms))
        s.put("daily_count", used + 1)
        store.bonus(db, s, ms, points, ms)
    }
    fun rest(s: JSONObject = store.state()): Rest? {
        val end = s.optLong("rest_end_time")
        val key = s.text("rest_completion_key")
        if (end <= 0 || key !in listOf("last_rest_time", "last_rest_short_time")) return null
        return Rest(0, s.optInt("rest_bonus_value"), key, end)
    }
    fun restDone(s: JSONObject, key: String, ms: Long = now()) = s.optLong(key) > 0 && civil(s.optLong(key)) == civil(ms)
    fun startRest(rest: Rest, ms: Long = now()) = store.transact { _, s ->
        rest(s)?.let { return@transact it }
        check(!restDone(s, rest.key, ms)) { "今天已完成这项休息任务" }
        val actual = rest.copy(end = ms + rest.seconds * 1000L)
        s.put("rest_end_time", actual.end)
        s.put("rest_completion_key", actual.key)
        s.put("rest_bonus_value", actual.points)
        actual
    }
    fun settleRest(ms: Long = now()): Rest? = store.transact { db, s ->
        val rest = rest(s) ?: return@transact null
        if (ms < rest.end) return@transact null
        s.put(rest.key, ms)
        listOf("rest_end_time", "rest_completion_key", "rest_bonus_value").forEach { s.remove(it) }
        store.bonus(db, s, ms, rest.points, ms)
        rest
    }
    fun accept(name: String, ms: Long = now()): String? = store.transact { db, s ->
        val config = s.config()
        val today = config.date(ms)
        val list = s.array("tasks")
        val matching = list.objects().filter { it.text("name") == name }
        check(matching.none { it.text("status", "IN_PROGRESS") != "ARCHIVED" }) { "请先处理已接取的同名挑战" }
        val task = json("id" to newId(), "name" to name, "status" to "IN_PROGRESS", "createdAt" to ms)
        val conversationId = when (name) {
            EARLY -> {
                check(matching.none { it.earlyDate(config) == today }) { "本日挑战已接取，请等到 ${clockHour(config.dayStart)} 切日" }
                val (start, end) = config.window(today)
                val stored = store.hours(start, end, db).sumOf { it.deduction.toLong() }
                val threshold = config.number("earlySleepThreshold")
                check(stored + config.guard(ms) < threshold) { "今日扣分已达接取阈值 $threshold" }
                task.put("description", "在本自定义日结束前，累计手机使用扣分低于阈值")
                task.put("points", config.number("earlySleepReward"))
                task.put("judgedDate", today)
                task.put("acceptedHourStartMs", hourStart(ms))
                store.bonus(db, s, ms, -config.number("earlySleepPenalty"), ms)
                null
            }
            PERSUADE -> {
                task.put("description", "与AI对话，说服它允许你晚睡")
                task.put("points", 10)
                val chat = newConversation(s, title = catalog.challengeChat.text("conversationTitle"))
                chat.put("type", "TASK")
                chat.put("boundChallengeTaskId", task.getString("id"))
                chat.put("systemPrompt", catalog.challengeChat.text("systemPrompt"))
                chat.put("messages", JSONArray().put(message("assistant", catalog.challengeChat.text("openingMessage"), ms)))
                s.put("conversations", JSONArray(listOf(chat) + s.list("conversations")))
                chat.getString("id").also { task.put("boundConversationId", it) }
            }
            else -> error("未知挑战")
        }
        s.put("tasks", list.put(task))
        conversationId
    }
    fun judge(ms: Long = now()) = store.transact { db, s ->
        val config = s.config()
        s.list("tasks").filter {
            it.text("name") == EARLY && it.text("status", "IN_PROGRESS") == "IN_PROGRESS" && it.text("judgedDate").isNotEmpty()
        }.forEach { task ->
            val date = task.earlyDate(config)
            val (start, end) = runCatching { config.window(date) }.getOrNull() ?: return@forEach
            val failed = store.hours(start, end, db).sumOf { it.deduction.toLong() } >= config.number("earlySleepThreshold")
            if (failed || ms >= end) {
                task.put("judgedDate", date)
                task.put("completedAt", ms)
                task.put("status", if (failed) "FAILED" else "COMPLETED")
                if (failed) task.put("settledDate", date)
            }
        }
    }
    fun archive(id: String, ms: Long = now()): Int = store.transact { db, s ->
        val task = s.find("tasks", id) ?: error("找不到任务")
        val status = task.text("status")
        check(status == "COMPLETED" || status == "FAILED") { "这项任务已处理或尚未结束" }
        val points = if (status == "COMPLETED") task.optInt("points") else 0
        store.bonus(db, s, task.optLong("acceptedHourStartMs", task.optLong("createdAt", ms)), points, ms)
        task.put("status", "ARCHIVED")
        if (task.isNull("completedAt")) task.put("completedAt", ms)
        points
    }
    fun reset() = store.edit { s ->
        listOf("tasks", "completed_tasks", "daily_count_date", "daily_count", "last_checkin_date", "last_checkin_time", "streak_days",
            "last_rest_time", "last_rest_short_time", "daily_task_date", "daily_task_time", "hourly_usage").forEach(s::remove)
    }
}

internal fun localHour(ms: Long = now()) = clock(ms).get(Calendar.HOUR_OF_DAY)
