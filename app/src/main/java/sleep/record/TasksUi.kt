package sleep.record

import android.view.Gravity
import android.widget.LinearLayout
import org.json.JSONObject

internal fun MainActivity.renderTasks() {
    if (taskTab == 1) tasks.judge()
    val s = store.state()
    val config = s.config()
    val ms = now()
    val today = config.date(ms)
    val (start, end) = config.window(today)
    val bonus = store.hours(start, end).sumOf { it.bonus.toLong() }
    title("任务", subtitle = "今日加分 ${signed(bonus)}")
    val tabs = vertical().apply { setPadding(dp(18), 0, dp(18), dp(7)) }
    segments(tabs, listOf("常规", "挑战", "一次性"), taskTab) { selectTaskSection(it) }
    body.addView(tabs)
    val content = scroller(body)
    val cycle = store.overview(ms).lastOrNull()
    val simulation = if (cycle == null || cycle.max <= cycle.min) "N/A" else "${signed((cycle.percent(cycle.net + 100) - cycle.percent()).toLong())} 个百分点"
    content.label("额外 +100 分对当前周期的影响  $simulation", 12, palette.muted, gap = 4)
    when (taskTab) {
        0 -> regularTasks(content, s, ms)
        1 -> challengeTasks(content, s)
        2 -> oneTimeTasks(content, s)
    }
}
private fun MainActivity.taskCard(parent: LinearLayout, title: String, description: String, points: String = ""): LinearLayout {
    val card = parent.card()
    val heading = row()
    heading.addView(text(title, 16, palette.text, true), LinearLayout.LayoutParams(0, -2, 1f))
    if (points.isNotBlank()) heading.addView(text(points, 12, if (points.startsWith("−")) palette.red else palette.green, true).apply {
        background = shape(palette.raised, 11)
        setPadding(dp(9), dp(6), dp(9), dp(6))
    })
    card.addView(heading)
    if (description.isNotBlank()) card.label(description, 13, palette.muted, gap = 5)
    return card
}
private fun MainActivity.regularTasks(content: LinearLayout, s: JSONObject, ms: Long) {
    val c = s.config()
    val today = c.date(ms)
    val checkin = s.customDate(c, "last_checkin_date", "last_checkin_time") == today
    taskCard(content, "每日打卡", "已连续打卡 ${s.optInt("streak_days")} 天", signed(c.number("checkinBonus").toLong()))
        .action(if (checkin) "今日已打卡" else "打卡", primary = true, enabled = !checkin) { toast("已打卡 ${signed(tasks.checkin().toLong())} 分"); render() }
    val read = s.customDate(c, "daily_task_date", "daily_task_time") == today
    taskCard(content, "查看早睡建议", "打开睡眠主题视频搜索", signed(c.number("dailyTaskBonus").toLong()))
        .action(if (read) "今日已完成" else "查看建议", enabled = !read) {
            openUrl("https://bilibili.com/search?keyword=%E7%9D%A1%E8%A7%89")
            tasks.suggestion(); render()
        }
    val chars = if (s.text("chat_day_key") == civil(ms)) s.optInt("chat_day_chars").coerceAtLeast(0) else 0
    val max = c.number("chatDailyMaxPoints")
    val points = minOf(chars / c.number("chatCharsPerPoint"), max)
    taskCard(content, "聊天", "今日已计 $chars 字，每 ${c.number("chatCharsPerPoint")} 字 1 分", "$points / $max 分").apply {
        progress(this, max, points)
        action("去聊天") { selectTab(1) }
    }
    val pending = tasks.rest(s)
    listOf(Rest(c.number("restDurationMin") * 60L, c.number("restBonus"), "last_rest_time"), Rest(30, 20, "last_rest_short_time")).forEachIndexed { index, plan ->
        val isLong = index == 0
        val done = tasks.restDone(s, plan.key, ms)
        val available = !isLong || localHour(ms) >= 22 || localHour(ms) < 4
        val continueRest = pending?.key == plan.key
        val caption = if (isLong) "${c.number("restDurationMin")} 分钟 · 22:00～04:00 可开始" else "30 秒 · 全天可开始"
        taskCard(content, if (isLong) "长休息" else "短休息", caption, signed(plan.points.toLong())).action(when {
            continueRest -> "继续计时或结算"
            done -> "今日已完成"
            !available -> "尚未到开始时段"
            else -> "打开倒计时"
        }, enabled = continueRest || available && !done) {
            rest = plan; restResult = null; open(Screen("rest"))
        }
    }
    taskCard(content, "放松睡觉音频", "离线播放 · 睡前放松").action("打开音频列表") { open(Screen("audio")) }
    if (c.flag("showDebugTasks")) {
        val card = taskCard(content, "调试加扣分", "记入当前小时")
        segments(card, listOf("−100", "+100"), -1) { store.bonus(if (it == 0) -100 else 100); render() }
    }
}
private fun MainActivity.challengeTasks(content: LinearLayout, s: JSONObject) {
    val all = s.list("tasks")
    val c = s.config()
    val today = c.date()
    for (name in listOf(EARLY, PERSUADE)) {
        val same = all.filter { it.text("name") == name }
        val active = same.any { it.text("status", "IN_PROGRESS") != "ARCHIVED" }
        val used = name == EARLY && same.any { it.earlyDate(c) == today }
        val card = taskCard(content, name, if (name == EARLY) "让本自定义日的手机使用扣分低于 ${c.number("earlySleepThreshold")} 分" else "与 AI 对话，说服它允许你晚睡",
            if (name == EARLY) "+${c.number("earlySleepReward")}" else "+10")
        card.action(when {
            (active || used) && name == EARLY -> "下一自定义日 ${clockHour(c.dayStart)} 可再接取"
            active -> "等待归档后接取"
            else -> "接取挑战"
        }, enabled = !active && !used) { open(Screen("question", if (name == EARLY) "早睡挑战main" else "调试题目AI1")) }
    }
    val live = all.filter { it.text("status", "IN_PROGRESS") != "ARCHIVED" }
    if (live.isNotEmpty()) section(content, "已接取")
    live.forEach { task ->
        val status = task.text("status", "IN_PROGRESS")
        val isEarly = task.text("name") == EARLY
        val date = if (isEarly && status == "IN_PROGRESS") task.earlyDate(c) else task.text("judgedDate")
        val desc = if (date.isBlank()) task.text("description") else "判定日期 $date"
        taskCard(content, task.text("name"), desc, "+${task.optInt("points")}").apply {
            when (status) {
                "IN_PROGRESS" -> action(if (isEarly) "进行中 · 查看统计" else "进行中 · 继续对话") {
                    if (isEarly) selectTab(0) else chat.openTask(task)
                }
                "COMPLETED" -> action("领取奖励", primary = true) { toast("已领取 ${tasks.archive(task.text("id"))} 分"); render() }
                "FAILED" -> action("挑战失败 · 确认") { tasks.archive(task.text("id")); render() }
            }
        }
    }
    val done = all.filter { it.text("status") == "ARCHIVED" }
    if (done.isNotEmpty()) {
        content.action("${if (earlyArchive) "收起" else "展开"}已归档挑战（${done.size}）") { earlyArchive = !earlyArchive; render() }
        if (earlyArchive) done.asReversed().forEach { task ->
            val time = task.optLong("completedAt")
            taskCard(content, task.text("name"), if (time > 0) "完成时间 ${format(time, "yyyy-MM-dd HH:mm")}" else "已归档")
        }
    }
}
private fun MainActivity.oneTimeTasks(content: LinearLayout, s: JSONObject) {
    val completed = s.array("completed_tasks").strings().toSet()
    val used = tasks.count(s)
    content.card().apply {
        label("今日完成 $used / 5", 16, bold = true)
        progress(this, 5, used)
    }
    val done = catalog.oneTime.filter { it.text("id") in completed }
    if (done.isNotEmpty()) {
        content.action("${if (oneTimeExpanded) "收起" else "展开"}已完成（${done.size}）") { oneTimeExpanded = !oneTimeExpanded; render() }
        if (oneTimeExpanded) done.forEach { taskCard(content, it.text("name"), it.text("description"), "已完成") }
    }
    section(content, "可完成任务")
    catalog.oneTime.filterNot { it.text("id") in completed }.forEach { task ->
        taskCard(content, task.text("name"), task.text("description"), "+${task.optInt("points")}")
            .action(if (used < 5) "开始" else "今日配额已满", enabled = used < 5) { open(Screen("question", task.text("questionName"))) }
    }
}
internal fun MainActivity.renderQuestion(name: String) {
    val node = catalog.questions[name]
    if (node == null) {
        title("找不到题目")
        scroller(body).card().apply { label(name); action("退出") { closePage() } }
        return
    }
    val head = row().apply { setPadding(dp(18), dp(12), dp(18), dp(12)); minimumHeight = dp(66) }
    if (node.optBoolean("showCloseButton", true)) head.addView(iconButton(Glyph.CLOSE, "关闭") { closePage() },
        LinearLayout.LayoutParams(dp(44), dp(44)).apply { marginEnd = dp(12) })
    head.addView(text(if (notice) "睡时记录" else "任务与说明", 19, palette.text, true), LinearLayout.LayoutParams(0, -2, 1f))
    body.addView(head)
    val content = scroller(body)
    if (notice) content.addView(MoonArt(this), LinearLayout.LayoutParams(-1, dp(115)))
    val card = content.card()
    val type = node.text("type")
    val kicker = when (type) { "QUIZ" -> "选择题"; "ENDING" -> "结局"; else -> if (notice) "初次使用" else "阅读" }
    card.label(kicker, 12, palette.accent, bold = true)
    card.label(node.text("title", name), 28, bold = true, gap = 7)
    if (node.optBoolean("showProgressBar")) {
        val mark = (node.optDouble("progressRatio", 0.0) * 100).toInt().coerceIn(0, 100)
        card.label("进度 $mark%", 12, palette.muted, gap = 13)
        progress(card, 100, mark)
    }
    val info = if (name == "用户须知main" && BuildConfig.PUBLIC_CHAT_KEY.isBlank())
        node.text("infoText").replace("默认使用内置的公开低额度 AI 服务（Catbee）", "AI 对话需在设置中填写 API 密钥") else node.text("infoText")
    if (info.isNotBlank()) card.label(info, 16, palette.muted, gap = 13)
    when (type) {
        "INFO" -> card.action("继续", primary = true) { advanceQuestion(node.text("linkedQuestionName")) }
        "QUIZ" -> {
            card.label(node.text("question"), 16, gap = 13)
            val options = node.list("options")
            val choiceList = content.card()
            options.forEachIndexed { i, option ->
                val chosen = questionSelection == i
                val answer = row().apply {
                    minimumHeight = dp(56); isSelected = chosen
                    setPadding(dp(7), dp(7), dp(8), dp(7))
                }
                val badge = text(('A' + i).toString(), 13, if (chosen) palette.onAccent else palette.accent, true).apply {
                    gravity = Gravity.CENTER; background = shape(if (chosen) palette.accent else palette.raised, 10)
                }
                answer.addView(badge, LinearLayout.LayoutParams(dp(32), dp(32)).apply { marginEnd = dp(11) })
                answer.addView(text(option.text("text"), 15, palette.text, chosen), LinearLayout.LayoutParams(0, -2, 1f))
                if (chosen) answer.addView(GlyphView(this, Glyph.CHECK, palette.accent), LinearLayout.LayoutParams(dp(24), dp(24)))
                clickable(answer, if (chosen) palette.raised else android.graphics.Color.TRANSPARENT, 14) {
                    if (option.text("type") == "ERROR") alert("再试一次", option.text("errorMessage", "请重新选择"))
                    else { questionSelection = i; render() }
                }
                choiceList.addView(answer, LinearLayout.LayoutParams(-1, -2).apply { if (i > 0) topMargin = dp(4) })
            }
            val footer = vertical().apply { setPadding(dp(19), 0, dp(19), dp(14)) }
            footer.action("确认", true, questionSelection >= 0) {
                options.getOrNull(questionSelection)?.let { advanceQuestion(it.text("linkedQuestionName")) }
            }
            body.addView(footer, LinearLayout.LayoutParams(-1, -2))
        }
        "ENDING" -> {
            val points = node.optInt("bonusPoints")
            if (points > 0) card.label("可获得 +$points 分", 15, palette.green, true, 13)
            card.action("完成", primary = true) { finishQuestion(name, node) }
        }
    }
}
private fun MainActivity.advanceQuestion(next: String) {
    if (catalog.questions[next] == null) return
    nextQuestion(next)
}
private fun MainActivity.finishQuestion(name: String, node: JSONObject) {
    val next = node.text("linkedQuestionName")
    val points = node.optInt("bonusPoints")
    when {
        name.startsWith("每日任务结局") -> { toast("已打卡 ${signed(tasks.checkin(debug = true).toLong())} 分"); closePage() }
        next.startsWith("onetime_") -> { tasks.oneTime(next.removePrefix("onetime_"), points); toast("任务完成 +$points 分"); closePage() }
        next.startsWith("挑战任务-") -> {
            val id = tasks.accept(next.removePrefix("挑战任务-"))
            closePage()
            if (id != null) chat.switchTo(id)
        }
        next == "daily_checkin_complete" -> closePage()
        next == "return" || next.isBlank() -> {
            if (points > 0) store.bonus(points)
            closePage()
        }
        catalog.questions[next] != null -> {
            if (points > 0) store.bonus(points)
            advanceQuestion(next)
        }
    }
}
internal fun MainActivity.renderRest() {
    val selected = rest ?: Rest(30, 20, "last_rest_short_time")
    val current = tasks.rest()
    if (current != null && current.end <= now()) {
        restResult = tasks.settleRest() ?: restResult
        syncRestLockTask()
    }
    val display = restResult ?: tasks.rest() ?: selected
    val completed = restResult != null
    val running = !completed && display.end > 0
    title(if (display.key == "last_rest_time") "长休息" else "短休息", back = true)
    val content = scroller(body)
    val card = content.card()
    card.addView(MoonArt(this), LinearLayout.LayoutParams(-1, dp(155)))
    card.label(if (completed) "休息完成" else "放下手机，休息一下", 24, bold = true).gravity = Gravity.CENTER
    val timer = card.label(if (completed) "${signed(display.points.toLong())} 分" else mmss(display.seconds), 46,
        if (completed) palette.green else palette.accent, gap = 9).apply { gravity = Gravity.CENTER }
    card.label(if (completed) "奖励已记入当前小时" else if (running) "计时中 · 可关闭屏幕" else "准备好后开始计时", 13, palette.muted, gap = 7).gravity = Gravity.CENTER
    if (completed) card.action("返回", true) { closePage() } else if (!running) card.action("开始计时", true) {
        rest = tasks.startRest(selected)
        syncRestLockTask()
        render()
    }
    if (running) {
        val tick = object : Runnable {
            override fun run() {
                if (screen?.type != "rest") return
                val remaining = display.end - now()
                if (remaining <= 0) {
                    restResult = tasks.settleRest()
                    syncRestLockTask()
                    render()
                    return
                }
                timer.text = mmss((remaining + 999) / 1000)
                handler.postDelayed(this, minOf(1000, remaining))
            }
        }
        renderTick = tick
        tick.run()
    }
}
