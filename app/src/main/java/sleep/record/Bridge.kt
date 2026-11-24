package sleep.record

import android.content.Intent
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.net.Uri
import android.os.Looper
import android.provider.Settings
import android.util.Base64
import android.webkit.JavascriptInterface
import org.json.JSONArray
import org.json.JSONObject
import java.io.ByteArrayOutputStream
import java.io.File
import java.util.Locale
import java.util.UUID
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean

/**
 * The page calls `SleepNative.call(id, method, json)`; the result comes back through `__native.resolve`.
 * Calls run in order on one worker thread so store transactions never interleave; refreshes use their own
 * thread so a slow UsageEvents query does not hold up the page.
 */
internal class Bridge(private val a: MainActivity) {
    private val io = Executors.newSingleThreadExecutor()
    private val refresher = Executors.newSingleThreadExecutor()
    private val refreshing = AtomicBoolean()
    private val store get() = a.store
    private val tasks get() = a.tasks
    private val chats get() = a.chats
    private val catalog get() = a.catalog
    // Chat stream state lives on the main thread.
    private var stream: ChatStream? = null
    private var streamId: String? = null
    private var partial = ""
    private var avatarRole: String? = null

    @JavascriptInterface
    fun call(id: Int, method: String, args: String) {
        val input = runCatching { JSONObject(args.ifBlank { "{}" }) }.getOrDefault(JSONObject())
        val queued = android.os.SystemClock.uptimeMillis()
        val work = Runnable {
            val begin = android.os.SystemClock.uptimeMillis()
            val result = runCatching { dispatch(method, input) }
            val end = android.os.SystemClock.uptimeMillis()
            if (BuildConfig.DEBUG && end - queued > 80) android.util.Log.i("SleepLaunch", "call $method waited ${begin - queued} ms, ran ${end - begin} ms")
            a.handler.post { a.resolve(id, result) }
        }
        if (method == "stats.refresh") refresher.execute(work) else io.execute(work)
    }
    fun shutdown() { io.shutdown(); refresher.shutdown() }

    private fun <T> onMain(block: () -> T): T {
        if (Looper.myLooper() == Looper.getMainLooper()) return block()
        val latch = CountDownLatch(1)
        var result: Result<T>? = null
        a.handler.post { result = runCatching(block); latch.countDown() }
        check(latch.await(10, TimeUnit.SECONDS)) { "界面线程无响应" }
        return result!!.getOrThrow()
    }
    private fun JSONObject.str(key: String) = text(key).trim()

    private fun dispatch(method: String, x: JSONObject): Any? = when (method) {
        "app.boot" -> boot()
        "app.ready" -> onMain { a.enterReady() }
        "app.noticeDone" -> { store.edit { it.put("notice_shown", true) }; onMain { a.enterReady() } }
        "app.usage" -> hasUsageAccess(a).also { a.permitted = it }
        "app.openUsageSettings" -> onMain {
            runCatching { a.startActivity(Intent(Settings.ACTION_USAGE_ACCESS_SETTINGS)) }.getOrElse { error("无法打开系统设置") }
        }
        "app.openUrl" -> onMain { a.openUrl(x.str("url")) }
        "app.leave" -> onMain { a.leave() }
        "app.painted" -> onMain { a.hideLaunchCover() }
        "app.capture" -> onMain { a.captureLaunch() }

        "stats.overview" -> statsOverview()
        "stats.refresh" -> refresh(x.optBoolean("manual"))
        "timeline.get" -> timeline(x.str("date"))
        "timeline.export" -> { dateStart(x.str("date")); onMain { a.saveFile("sleep_timeline_${x.str("date")}.txt", "text/plain", date = x.str("date")) } }

        "tasks.overview" -> taskOverview(x.optInt("section"))
        "tasks.checkin" -> tasks.checkin()
        "tasks.suggestion" -> { onMain { a.openUrl("https://bilibili.com/search?keyword=%E7%9D%A1%E8%A7%89") }; tasks.suggestion(); null }
        "tasks.debugBonus" -> store.bonus(x.optInt("delta"))
        "tasks.archive" -> tasks.archive(x.str("id"))
        "tasks.reset" -> tasks.reset()
        "rest.get" -> restInfo(x.str("key"))
        "rest.start" -> startRest(x.str("key"))
        "rest.settle" -> settleRest()
        "question.finish" -> finishQuestion(x.str("name"))

        "audio.tracks" -> JSONArray(a.player.tracks())
        "player.open" -> onMain { a.player.open(x.str("name")); a.player.state() }
        "player.toggle" -> onMain { a.player.toggle(); a.player.state() }
        "player.seek" -> onMain { a.player.seek(x.optDouble("fraction")); a.player.state() }
        "player.state" -> onMain { a.player.state() }
        "player.release" -> onMain { a.player.release() }

        "chat.list" -> chatList(x.str("id"))
        "chat.get" -> chatJson(select(x.str("id")))
        "chat.select" -> selectChat(x.str("id"))
        "chat.send" -> send(x.str("id"), x.str("text"), x.optBoolean("auto"))
        "chat.manual" -> { onMain { check(stream == null) { "请等待当前回复" } }; val request = chats.manual(x.str("id")); onMain { startStream(request) }; null }
        "chat.cancel" -> onMain { cancelStream() }
        "chat.create" -> chats.create(x.str("kind")).also { selectChat(it) }
        "chat.delete" -> { onMain { if (streamId == x.str("id")) cancelStream() }; chats.delete(x.str("id")) }
        "chat.rename" -> x.str("title").let { title ->
            check(title.isNotBlank()) { "名称不能为空" }
            chats.edit(x.str("id")) { chat, _ -> chat.put("title", title) }
        }
        "chat.flag" -> chatFlag(x.str("id"), x.str("key"), x.optBoolean("value"))
        "chat.role" -> chats.edit(x.str("id")) { chat, _ -> chat.put("selectedRoleName", x.text("name")) }
        "chat.editMessage" -> x.str("content").let { content ->
            check(content.isNotEmpty()) { "消息不能为空" }
            chats.edit(x.str("id")) { chat, _ -> chat.array("messages").optJSONObject(x.optInt("index", -1))?.put("content", content) }
        }
        "chat.deleteMessage" -> chats.edit(x.str("id")) { chat, _ -> chat.array("messages").remove(x.optInt("index", -1)) }
        "chat.clear" -> { onMain { cancelStream() }; chats.edit(x.str("id")) { chat, _ -> chat.put("messages", JSONArray()) } }
        "chat.reset" -> { onMain { cancelStream() }; chats.reset(x.str("id")) }
        "chat.abandon" -> { onMain { cancelStream() }; chats.delete(x.str("id"), true) }
        "chat.forTask" -> taskChat(x.str("task"))
        "role.save" -> saveRole(x.str("id"), x.str("old").ifEmpty { null }, x.str("name"))
        "role.toggle" -> toggleRole(x.str("id"), x.text("name"))
        "role.delete" -> chats.edit(x.str("id")) { chat, _ -> chats.deleteRole(chat, x.text("name"), x.optBoolean("messages")) }
        "role.prompt" -> chats.edit(x.str("id")) { chat, _ -> chat.setRolePrompt(x.text("name"), x.text("prompt")) }
        "role.historyAdd" -> addHistoryRole(x.str("id"), x.text("name"))
        "role.historyRemove" -> store.edit { s -> s.put("role_history", JSONArray(s.array("role_history").strings().filterNot { it == x.text("name") })) }
        "avatar.pick" -> { avatarRole = x.text("name"); onMain { a.openPhoto() } }
        "avatar.save" -> saveAvatar(x.text("name"), x.str("data"))
        "avatar.reset" -> writeAvatar(x.text("name"), null)

        "settings.get" -> json("values" to a.config.export(), "avatars" to store.state().optBoolean("show_avatars", true),
            "version" to BuildConfig.VERSION_NAME, "model" to Config.defaults.getValue("chatModel").toString())
        "settings.apply" -> applySettings(x.optJSONObject("changes") ?: JSONObject(), x.optBoolean("reset"))
        "settings.avatars" -> store.edit { it.put("show_avatars", x.optBoolean("value")) }
        "backup.export" -> onMain { a.saveFile("sleep_score_export.json", "application/json") }
        "backup.import" -> onMain { a.importFile() }
        else -> error("未知操作 $method")
    }

    private fun boot(): JSONObject {
        a.permitted = hasUsageAccess(a)
        val questions = JSONObject().apply { catalog.questions.forEach { (name, node) -> put(name, node) } }
        return json("version" to BuildConfig.VERSION_NAME, "permitted" to a.permitted, "notice" to !store.state().optBoolean("notice_shown"),
            "dark" to onMain { a.isDark() }, "motion" to a.motionEnabled(), "publicKey" to BuildConfig.PUBLIC_CHAT_KEY.isNotBlank(),
            "insets" to onMain { a.insets() }, "rest" to restJson(), "questions" to questions, "questionIndex" to catalog.index)
    }

    // ---- statistics ----
    private fun prevDate(date: String) = civil(clock(dateStart(date)).apply { add(java.util.Calendar.DAY_OF_YEAR, -1) }.timeInMillis)
    private fun statsOverview(): JSONObject {
        val ms = now()
        val s = store.state()
        val c = s.config()
        val today = c.date(ms)
        val hour = localHour(ms)
        val cycles = store.overview(ms)
        val percentage = c.flag("usePercentageScoring")
        val started = percentage && c.scoringStarted(today, ms)
        val byDate = store.hours().groupBy { c.date(it.at) }
        fun slots(date: String): List<Pair<Long, Hour?>> {
            val rows = byDate[date].orEmpty().associateBy { it.at }
            return c.window(date).slots().filter { c.asleep(localHour(it)) }.map { it to rows[it] }.toList()
        }
        val tonight = slots(today)
        val useToday = tonight.isNotEmpty() && tonight.first().first <= ms
        val nightDate = if (useToday) today else prevDate(today)
        val night = if (useToday) tonight else slots(nightDate)
        val nightJson = JSONArray(night.map { (at, r) ->
            json("at" to at, "end" to nextHourStart(at), "hour" to localHour(at), "usage" to r?.usage, "has" to (r?.hasUsage == true),
                "score" to r?.score, "deduction" to r?.deduction, "bonus" to r?.bonus)
        })
        return json("now" to ms, "today" to today, "weekday" to clock(ms).get(java.util.Calendar.DAY_OF_WEEK), "hour" to hour,
            "asleep" to c.asleep(hour), "rate" to c.rate(hour), "start" to c.start, "end" to c.end, "dayStart" to c.dayStart,
            "cycleLength" to c.number("cycleLengthDays"), "percentage" to percentage, "started" to started,
            "detailed" to !c.flag("hideDetail"), "debug" to c.flag("showDebugInfo"), "refreshing" to refreshing.get(),
            "night" to json("date" to nightDate, "today" to useToday, "slots" to nightJson,
                "usage" to night.sumOf { it.second?.usage ?: 0L }, "recorded" to night.count { it.second?.hasUsage == true }),
            "cycles" to JSONArray(cycles.map { cycle ->
                json("index" to cycle.index, "from" to cycle.days.first().date, "to" to cycle.days.last().date,
                    "percent" to cycle.percent(), "net" to cycle.net, "min" to cycle.min, "max" to cycle.max, "bonus" to cycle.bonus,
                    "hasBonus" to cycle.days.any { it.bonus != 0L }, "notStarted" to (percentage && cycle.notStarted(today, started)),
                    "days" to JSONArray(cycle.recentDays.map { day ->
                        json("date" to day.date, "score" to day.score, "deduction" to day.deduction, "bonus" to day.bonus, "net" to day.net,
                            "sleep" to JSONArray(slots(day.date).map { it.second?.usage ?: JSONObject.NULL }))
                    }))
            }))
    }
    private fun refresh(manual: Boolean): JSONObject {
        check(hasUsageAccess(a)) { "请先授予使用情况访问权限" }
        val elapsed = now() - store.state().optLong("last_refresh_time")
        if (!manual && elapsed in 0..600000) return json("skipped" to true)
        if (!refreshing.compareAndSet(false, true)) return json("busy" to true)
        try {
            a.emit("refreshing", true)
            store.refresh(a.applicationContext, !manual)
        } finally {
            refreshing.set(false)
            a.emit("refreshing", false)
        }
        return json("done" to true)
    }
    private fun timeline(date: String): JSONObject {
        dateStart(date)
        val data = timelineData(store, a.packageManager, date)
        val records = data.records.associateBy { it.at }
        return json("date" to date, "groups" to JSONArray(data.groups.map { g ->
            val r = records.getValue(g.at)
            json("at" to g.at, "range" to hourRange(g.at), "usage" to r.usage, "usageText" to durationText(r.usage), "score" to r.score,
                "deduction" to r.deduction, "ratio" to String.format(Locale.ROOT, "%.1f", deductionRatio(r)), "bonus" to r.bonus, "heading" to g.heading,
                "rows" to JSONArray(g.rows.map { json("time" to it.time, "label" to it.label, "detail" to it.detail, "kind" to it.kind, "parts" to it.parts) }))
        }))
    }

    // ---- tasks ----
    private fun taskOverview(section: Int): JSONObject {
        if (section == 1) tasks.judge()
        val ms = now()
        val s = store.state()
        val c = s.config()
        val today = c.date(ms)
        val (start, end) = c.window(today)
        val cycle = store.overview(ms).lastOrNull()
        val simulation = if (cycle == null || cycle.max <= cycle.min) null else (cycle.percent(cycle.net + 100) - cycle.percent()).toLong()
        val last = s.customDate(c, "last_checkin_date", "last_checkin_time")
        val streak = s.optInt("streak_days")
        val streakDates = mutableSetOf<String>()
        if (last.isNotEmpty() && streak > 0) {
            var d = last
            repeat(minOf(streak, 7)) { streakDates += d; d = runCatching { prevDate(d) }.getOrDefault(d) }
        }
        val week = mutableListOf<String>()
        var cursor = today
        repeat(7) { week += cursor; cursor = prevDate(cursor) }
        val chars = if (s.text("chat_day_key") == civil(ms)) s.optInt("chat_day_chars").coerceAtLeast(0) else 0
        val pending = tasks.rest(s)
        val rests = listOf(true, false).map { long ->
            val key = if (long) "last_rest_time" else "last_rest_short_time"
            val done = tasks.restDone(s, key, ms)
            val available = !long || localHour(ms) >= 22 || localHour(ms) < 4
            json("key" to key, "long" to long, "minutes" to c.number("restDurationMin"), "points" to (if (long) c.number("restBonus") else 20),
                "done" to done, "available" to available, "pending" to (pending?.key == key))
        }
        val all = s.list("tasks")
        val challenges = listOf(EARLY, PERSUADE).map { name ->
            val same = all.filter { it.text("name") == name }
            val active = same.any { it.text("status", "IN_PROGRESS") != "ARCHIVED" }
            val used = name == EARLY && same.any { it.earlyDate(c) == today }
            json("name" to name, "early" to (name == EARLY),
                "description" to if (name == EARLY) "让本自定义日的手机使用扣分低于 ${c.number("earlySleepThreshold")} 分" else "与 AI 对话，说服它允许你晚睡",
                "reward" to if (name == EARLY) c.number("earlySleepReward") else 10,
                "penalty" to if (name == EARLY) c.number("earlySleepPenalty") else 0,
                "enabled" to (!active && !used),
                "label" to when {
                    (active || used) && name == EARLY -> "下一自定义日 ${clockHour(c.dayStart)} 可再接取"
                    active -> "等待归档后接取"
                    else -> "接取挑战"
                }, "question" to if (name == EARLY) "早睡挑战main" else "调试题目AI1")
        }
        val live = all.filter { it.text("status", "IN_PROGRESS") != "ARCHIVED" }.map { task ->
            val status = task.text("status", "IN_PROGRESS")
            val early = task.text("name") == EARLY
            val date = if (early && status == "IN_PROGRESS") task.earlyDate(c) else task.text("judgedDate")
            json("id" to task.text("id"), "name" to task.text("name"), "status" to status, "early" to early, "points" to task.optInt("points"),
                "description" to if (date.isBlank()) task.text("description") else "判定日期 $date")
        }
        val archived = all.filter { it.text("status") == "ARCHIVED" }.asReversed().map { task ->
            val time = task.optLong("completedAt")
            json("name" to task.text("name"), "detail" to if (time > 0) "完成时间 ${format(time, "yyyy-MM-dd HH:mm")}" else "已归档")
        }
        val completed = s.array("completed_tasks").strings().toSet()
        fun oneTimeJson(t: JSONObject) = json("id" to t.text("id"), "name" to t.text("name"), "description" to t.text("description"),
            "points" to t.optInt("points"), "question" to t.text("questionName"))
        return json("today" to today, "bonus" to store.hours(start, end).sumOf { it.bonus.toLong() }, "simulation" to simulation,
            "checkin" to json("done" to (last == today), "streak" to streak, "points" to c.number("checkinBonus"),
                "week" to JSONArray(week.asReversed().map { d -> json("date" to d, "done" to (d in streakDates), "today" to (d == today),
                    "weekday" to clock(dateStart(d)).get(java.util.Calendar.DAY_OF_WEEK)) })),
            "suggestion" to json("done" to (s.customDate(c, "daily_task_date", "daily_task_time") == today), "points" to c.number("dailyTaskBonus")),
            "chat" to json("chars" to chars, "perPoint" to c.number("chatCharsPerPoint"), "max" to c.number("chatDailyMaxPoints"),
                "points" to minOf(chars / c.number("chatCharsPerPoint"), c.number("chatDailyMaxPoints"))),
            "rests" to JSONArray(rests), "debugTasks" to c.flag("showDebugTasks"),
            "challenges" to JSONArray(challenges), "live" to JSONArray(live), "archived" to JSONArray(archived),
            "oneTime" to json("used" to tasks.count(s), "limit" to 5,
                "done" to JSONArray(catalog.oneTime.filter { it.text("id") in completed }.map(::oneTimeJson)),
                "open" to JSONArray(catalog.oneTime.filterNot { it.text("id") in completed }.map(::oneTimeJson))))
    }
    private fun restPlan(key: String): Rest {
        val c = a.config
        return if (key == "last_rest_time") Rest(c.number("restDurationMin") * 60L, c.number("restBonus"), key) else Rest(30, 20, "last_rest_short_time")
    }
    private fun restJson(): JSONObject? = tasks.rest()?.let {
        json("key" to it.key, "end" to it.end, "points" to it.points, "seconds" to restPlan(it.key).seconds, "now" to now())
    }
    private fun restInfo(key: String): JSONObject {
        val plan = restPlan(key)
        return json("key" to plan.key, "seconds" to plan.seconds, "points" to plan.points, "running" to restJson(), "now" to now())
    }
    private fun startRest(key: String): JSONObject {
        val plan = restPlan(key)
        val pending = tasks.rest()
        if (pending == null && plan.key == "last_rest_time") check(localHour() >= 22 || localHour() < 4) { "长休息需在 22:00～04:00 开始" }
        val actual = tasks.startRest(plan)
        onMain { a.syncRestLockTask() }
        return json("key" to actual.key, "end" to actual.end, "points" to actual.points, "now" to now())
    }
    private fun settleRest(): JSONObject? {
        val current = tasks.rest() ?: return null
        if (current.end > now()) return null
        val done = tasks.settleRest()
        onMain { a.syncRestLockTask() }
        return done?.let { json("key" to it.key, "points" to it.points) }
    }
    private fun finishQuestion(name: String): JSONObject {
        val node = catalog.questions[name] ?: error("找不到题目")
        val next = node.text("linkedQuestionName")
        val points = node.optInt("bonusPoints")
        return when {
            name.startsWith("每日任务结局") -> json("action" to "close", "toast" to "已打卡 ${signed(tasks.checkin(debug = true).toLong())} 分")
            next.startsWith("onetime_") -> { tasks.oneTime(next.removePrefix("onetime_"), points); json("action" to "close", "toast" to "任务完成 +$points 分") }
            next.startsWith("挑战任务-") -> json("action" to "close", "chat" to tasks.accept(next.removePrefix("挑战任务-")))
            next == "daily_checkin_complete" -> json("action" to "close")
            next == "return" || next.isBlank() -> { if (points > 0) store.bonus(points); json("action" to "close") }
            catalog.questions[next] != null -> { if (points > 0) store.bonus(points); json("action" to "next", "next" to next) }
            else -> json("action" to "none")
        }
    }

    // ---- chat ----
    private fun select(id: String): JSONObject {
        val list = chats.load()
        return list.firstOrNull { it.text("id") == id } ?: list.getOrNull(store.state().optInt("last_session_index")) ?: list.first()
    }
    private fun selectChat(id: String): JSONObject {
        val list = chats.load()
        val index = list.indexOfFirst { it.text("id") == id }
        check(index >= 0) { "此会话已移除" }
        onMain { if (streamId != null && streamId != id) cancelStream() }
        store.edit { it.put("last_session_index", index) }
        return chatJson(list[index])
    }
    private fun status(chat: JSONObject) = when {
        chat.taskChat() -> if (chat.optBoolean("isCompleted")) "已完成" else "进行中"
        chat.text("subType") == "SELF_CHAT" -> "自我对话"
        chat.text("subType") == "ROLEPLAY" -> "角色扮演"
        else -> "普通对话"
    }
    private fun chatList(id: String): JSONObject {
        val current = select(id).text("id")
        return json("current" to current, "items" to JSONArray(chats.load().map {
            json("id" to it.text("id"), "title" to it.text("title", "新对话"), "task" to it.taskChat(), "completed" to it.optBoolean("isCompleted"),
                "status" to status(it))
        }))
    }
    private fun avatarUrl(filename: String): String? {
        if (filename.isBlank()) return null
        val dir = File(a.filesDir, "avatars").canonicalFile
        val file = File(dir, filename).canonicalFile
        return if (file.parentFile == dir && file.isFile) "${MainActivity.ORIGIN}/avatar/$filename" else ""
    }
    private fun chatJson(chat: JSONObject): JSONObject {
        val s = store.state()
        val task = chat.taskChat()
        val map = s.optJSONObject("avatar_map") ?: JSONObject()
        val names = (chat.roles().map { it.text("name") } + chat.list("messages").map { it.text("role") } +
            s.array("role_history").strings()).distinct()
        val id = chat.text("id")
        val streaming = onMain { if (streamId == id && stream != null) partial else null }
        return json("id" to id, "title" to chat.text("title", "新对话"), "task" to task, "completed" to chat.optBoolean("isCompleted"),
            "subType" to chat.text("subType"), "status" to status(chat), "showSystem" to chat.optBoolean("showSystemMessages", true),
            "showTime" to chat.optBoolean("showMessageTime", true), "aiEnabled" to chat.optBoolean("aiEnabled", true),
            "assistant" to chat.assistant()?.text("name"), "selected" to if (task) "用户" else chat.text("selectedRoleName", "我"),
            "usable" to JSONArray(chat.usableRoles().map { it.text("name") }),
            "roles" to JSONArray(chat.roles().map { json("name" to it.text("name"), "type" to it.text("type", "user"),
                "archived" to it.optBoolean("archived"), "active" to it.optBoolean("isActive"), "prompt" to it.text("prompt")) }),
            "messages" to JSONArray(chat.list("messages").map { json("role" to it.text("role"), "content" to it.text("content"), "ts" to it.optLong("timestamp")) }),
            "history" to JSONArray(s.array("role_history").strings().filterNot { it == "system" || it == "系统" }.distinct()),
            "avatars" to (!task && s.optBoolean("show_avatars", true)),
            "avatarMap" to JSONObject().apply { names.forEach { name -> avatarUrl(map.text(name))?.let { put(name, it) } } },
            "streaming" to streaming)
    }
    private fun send(id: String, text: String, auto: Boolean): JSONObject {
        check(text.isNotBlank()) { "请输入消息" }
        onMain { check(stream == null) { "请等待当前回复" } }
        val request = chats.send(id, text, auto)
        if (request != null) onMain { startStream(request) }
        return json("streaming" to (request != null))
    }
    private fun startStream(request: ChatRequest) {
        check(stream == null) { "请等待当前回复" }
        partial = ""
        val client = ChatStream(request)
        stream = client
        streamId = request.id
        a.emit("chat.start", json("id" to request.id))
        client.start(onChunk = { text ->
            if (stream === client) { partial = text; a.emit("chat.chunk", json("id" to request.id, "text" to text)) }
        }, onFinish = { result ->
            if (stream === client) {
                stream = null; streamId = null; partial = ""
                result.onSuccess { response ->
                    runCatching { chats.reply(request, response) }
                        .onSuccess { done -> a.emit("chat.done", json("id" to request.id, "completed" to done)) }
                        .onFailure { a.emit("chat.done", json("id" to request.id, "title" to "操作未完成", "error" to (it.message ?: "请重试"))) }
                }.onFailure { error ->
                    a.emit("chat.done", json("id" to request.id, "title" to "未收到 AI 回复", "error" to (error.message ?: "请检查网络和提供商设置")))
                }
            }
        })
    }
    fun cancelStream() {
        val id = streamId
        stream?.cancel(); stream = null; streamId = null; partial = ""
        if (id != null) a.emit("chat.done", json("id" to id, "cancelled" to true))
    }
    private fun chatFlag(id: String, key: String, value: Boolean) {
        when (key) {
            "showMessageTime" -> chats.edit(id) { chat, _ -> chat.put(key, value) }
            "showSystemMessages" -> chats.edit(id) { chat, _ -> chat.put(key, value); chat.fixSelection() }
            "aiEnabled" -> { onMain { cancelStream() }; chats.edit(id) { chat, _ -> chats.toggleAi(chat, value) } }
            else -> error("未知开关")
        }
    }
    private fun taskChat(taskId: String): String {
        val task = store.state().find("tasks", taskId) ?: error("找不到任务")
        val list = chats.load()
        val item = list.firstOrNull { it.taskChat() && it.text("id") == task.text("boundConversationId") }
            ?: list.firstOrNull { it.taskChat() && it.text("boundChallengeTaskId") == task.text("id") }
            ?: error("此挑战的绑定会话未包含在本机数据中")
        selectChat(item.text("id"))
        return item.text("id")
    }
    private fun saveRole(id: String, old: String?, name: String) = chats.edit(id) { chat, state ->
        val type = old?.let { o -> chat.roles().firstOrNull { it.text("name") == o }?.text("type", "user") } ?: "user"
        chats.renameRole(chat, state, old, name, type)
    }
    private fun toggleRole(id: String, name: String) = chats.edit(id) { chat, state ->
        val target = chat.roles().firstOrNull { it.text("name") == name } ?: error("找不到角色")
        when {
            target.optBoolean("archived") -> {
                target.put("archived", false)
                if (target.text("type") == "assistant") chat.assignAssistant(name)
            }
            target.text("type") == "assistant" -> { target.put("type", "user"); target.put("isActive", false) }
            else -> {
                check(chat.optBoolean("aiEnabled", true)) { "请先启用 AI" }
                target.put("archived", false); chat.assignAssistant(name)
            }
        }
        state.put("role_history", JSONArray((state.array("role_history").strings() + name).distinct()))
    }
    private fun addHistoryRole(id: String, name: String) = chats.edit(id) { chat, state ->
        val existing = chat.roles().firstOrNull { it.text("name") == name }
        if (existing != null) { existing.put("archived", false); chat.fixSelection() }
        else chats.renameRole(chat, state, null, name, "user")
    }

    // ---- avatars ----
    fun acceptPhoto(uri: Uri) {
        val name = avatarRole ?: return
        io.execute {
            runCatching {
                val resolver = a.contentResolver
                val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
                resolver.openInputStream(uri)?.use { BitmapFactory.decodeStream(it, null, bounds) }
                require(bounds.outWidth > 0 && bounds.outHeight > 0) { "无法读取图片" }
                var scale = 1
                while (maxOf(bounds.outWidth, bounds.outHeight) / scale > 2400) scale *= 2
                val decoded = resolver.openInputStream(uri)?.use {
                    BitmapFactory.decodeStream(it, null, BitmapFactory.Options().apply { inSampleSize = scale })
                } ?: error("无法解码图片")
                val longest = maxOf(decoded.width, decoded.height)
                val image = if (longest > 1280) Bitmap.createScaledBitmap(decoded, decoded.width * 1280 / longest, decoded.height * 1280 / longest, true) else decoded
                val bytes = ByteArrayOutputStream().also { image.compress(Bitmap.CompressFormat.JPEG, 90, it) }.toByteArray()
                if (image !== decoded) decoded.recycle()
                image.recycle()
                "data:image/jpeg;base64," + Base64.encodeToString(bytes, Base64.NO_WRAP)
            }.onSuccess { a.emit("photo", json("name" to name, "data" to it)) }
                .onFailure { a.emit("error", json("title" to "操作未完成", "message" to (it.message ?: "无法读取图片"))) }
        }
    }
    private fun saveAvatar(name: String, data: String) {
        check(name.isNotEmpty()) { "请选择角色" }
        val bytes = Base64.decode(data.substringAfter("base64,"), Base64.DEFAULT)
        val bitmap = BitmapFactory.decodeByteArray(bytes, 0, bytes.size) ?: error("头像保存失败")
        val directory = File(a.filesDir, "avatars").apply { mkdirs() }
        val filename = "${UUID.randomUUID()}_${now()}.png"
        try {
            File(directory, filename).outputStream().use { check(bitmap.compress(Bitmap.CompressFormat.PNG, 100, it)) { "头像保存失败" } }
            writeAvatar(name, filename)
        } catch (failure: Exception) {
            File(directory, filename).delete()
            throw failure
        } finally { bitmap.recycle() }
        avatarRole = null
    }
    private fun writeAvatar(name: String, filename: String?) {
        val previous = store.transact { _, s ->
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

    // ---- settings ----
    private fun applySettings(changes: JSONObject, reset: Boolean): JSONObject {
        val map = changes.keys().asSequence().associateWith { changes.text(it) }
        val backfill = store.adjustSettings(map, reset)
        val invalid = a.config.invalidKeys()
        val dark = onMain {
            SleepService.syncFromActivity(a)
            a.applyTheme()
            a.isDark()
        }
        if (backfill) refresher.execute {
            if (refreshing.compareAndSet(false, true)) try {
                a.emit("refreshing", true)
                runCatching { store.refresh(a.applicationContext, true) }
            } finally {
                refreshing.set(false)
                a.emit("refreshing", false)
                a.emit("refreshed", null)
            }
        }
        return json("invalid" to JSONArray(invalid), "backfill" to backfill, "dark" to dark)
    }
}
