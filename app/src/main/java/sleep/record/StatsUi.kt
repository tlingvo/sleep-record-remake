package sleep.record

import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.view.Gravity
import android.view.View
import android.widget.FrameLayout
import android.widget.LinearLayout
import java.util.Locale

internal fun signed(value: Long) = if (value > 0) "+$value" else value.toString()
internal fun MainActivity.scoreColor(pct: Int): Int {
    fun blend(a: Int, b: Int, fraction: Float): Int = Color.rgb(
        (Color.red(a) + (Color.red(b) - Color.red(a)) * fraction).toInt(),
        (Color.green(a) + (Color.green(b) - Color.green(a)) * fraction).toInt(),
        (Color.blue(a) + (Color.blue(b) - Color.blue(a)) * fraction).toInt())
    return when {
        pct < 0 -> if (palette.dark) color("#FF8288") else color("#842132")
        pct < 25 -> palette.red
        pct < 50 -> blend(palette.red, palette.amber, (pct - 25) / 25f)
        pct < 75 -> blend(palette.amber, palette.green, (pct - 50) / 25f)
        else -> palette.green
    }
}
internal fun MainActivity.renderStats() {
    val ms = now()
    val config = config
    val today = config.date(ms)
    val hour = localHour(ms)
    val asleep = config.asleep(hour)
    val page = FrameLayout(this)
    val (scroll, content) = scrollBox(body, 18)
    body.removeView(scroll)
    val contentHeader = row().apply {
        setPadding(0, dp(12), 0, dp(14))
        minimumHeight = dp(71)
        val texts = vertical().apply {
            label("睡眠分数", TypeScale.HEADLINE, bold = true)
            if (asleep) label(String.format(Locale.ROOT, "当前每分钟扣 %.2f 分", config.rate(hour)), TypeScale.CAPTION, palette.muted, gap = 6).numeric()
        }
        addView(texts, LinearLayout.LayoutParams(0, -2, 1f))
        val refresh = iconButton(Glyph.REFRESH, if (refreshing) "刷新中" else "刷新") { refresh(true) }
        if (refreshing) refresh.alpha = .45f
        addView(refresh, LinearLayout.LayoutParams(dp(48), dp(48)))
        val about = iconButton(Glyph.INFO, "关于") {
            panelDialog("关于睡眠分数", "连续记录按每 ${config.number("cycleLengthDays")} 天分为一个周期。睡眠区 ${clockHour(config.start)}–${clockHour(config.end)} 内，空闲分钟得分，手机使用按小时费率扣分。百分比以周期理论分值为基准，额外加分可使结果超出原范围。", "功能介绍", "关闭") {
                open(Screen("question", "功能介绍1"))
            }
        }
        addView(about, LinearLayout.LayoutParams(dp(48), dp(48)).apply { marginStart = dp(2) })
    }
    content.addView(contentHeader, 0, LinearLayout.LayoutParams(-1, -2).apply { marginEnd = -dp(10) })
    page.addView(scroll, FrameLayout.LayoutParams(-1, -1))
    val compactTitle = text("睡眠分数", 16, palette.text, true).apply {
        gravity = Gravity.CENTER
        alpha = 0f
        importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_NO
        background = shape(palette.bg.withOpacity(0xF2), 0)
    }
    page.addView(compactTitle, FrameLayout.LayoutParams(-1, dp(52), Gravity.TOP))
    fun updateTitle() {
        val progress = ((scroll.scrollY - dp(42)).toFloat() / dp(76)).coerceIn(0f, 1f)
        compactTitle.alpha = progress
        compactTitle.translationY = -dp(10) * (1f - progress)
        compactTitle.importantForAccessibility = if (progress > .5f) View.IMPORTANT_FOR_ACCESSIBILITY_YES else View.IMPORTANT_FOR_ACCESSIBILITY_NO
    }
    scroll.setOnScrollChangeListener { _, _, _, _, _ ->
        updateTitle()
        invalidateNavBackdrop()
    }
    page.addOnLayoutChangeListener { _, _, _, _, _, _, _, _, _ -> updateTitle() }
    updateTitle()
    body.addView(page, LinearLayout.LayoutParams(-1, 0, 1f))
    val cycles = store.overview(ms)
    val current = cycles.lastOrNull()
    val percentage = config.flag("usePercentageScoring")
    val started = percentage && config.scoringStarted(today, ms)
    val detailed = !config.flag("hideDetail")
    val pending = current == null || percentage && current.notStarted(today, started)
    val pct = current?.percent() ?: 0

    val hero = content.card(gap = 2)
    hero.background = GradientDrawable(GradientDrawable.Orientation.TL_BR,
        if (palette.dark) intArrayOf(color("#2A3366"), color("#1E2442"), color("#171B2B"))
        else intArrayOf(color("#E1E6FF"), color("#F6F7FF"))).apply {
        cornerRadius = dp(22).toFloat()
        setStroke(maxOf(1, dp(1)), palette.stroke)
    }
    hero.label("当前周期", TypeScale.LABEL, palette.accent, bold = true)
    hero.label("${clockHour(config.start)}–${clockHour(config.end)} · 睡眠计分时段", TypeScale.CAPTION, palette.muted, gap = 6).numeric()
    val metrics = row().apply { gravity = Gravity.CENTER_VERTICAL }
    val main = vertical()
    val value = when {
        current == null -> "暂无数据"
        pending -> "未开始"
        percentage -> "$pct%"
        else -> signed(current.net)
    }
    main.label(value, if (value.length > 5) 37 else TypeScale.DISPLAY, if (pending) palette.muted else palette.text, gap = 12).apply {
        typeface = Typeface.create("sans-serif-light", Typeface.NORMAL)
        numeric()
    }
    main.label(if (current == null) today else "${current.days.first().date} – ${current.days.last().date}", TypeScale.CAPTION, palette.muted, gap = 6).numeric()
    metrics.addView(main, LinearLayout.LayoutParams(0, -2, 1f))
    metrics.addView(MoonArt(this), LinearLayout.LayoutParams(dp(113), dp(112)))
    hero.addView(metrics)
    if (current != null) {
        val bonus = if (detailed) "额外加扣分 ${signed(current.bonus)}" else if (current.days.any { it.bonus != 0L }) "有额外加扣分" else "无额外加扣分"
        hero.label(bonus, TypeScale.LABEL, palette.muted, gap = 6).numeric()
    }
    hero.action("查看今日时间轴", primary = true) { open(Screen("timeline", today)) }

    section(content, "历史周期")
    if (cycles.isEmpty()) content.card().label("暂无数据", TypeScale.BODY, palette.muted)
    for (cycle in cycles.asReversed()) {
        val card = content.card()
        val idle = percentage && cycle.notStarted(today, started)
        val header = row()
        header.addView(text("周期 ${String.format(Locale.ROOT, "%02d", cycle.index + 1)}", 16, palette.text, true).numeric(), LinearLayout.LayoutParams(0, -2, 1f))
        val score = if (percentage) if (idle) "未开始" else "${cycle.percent()}%" else "${cycle.net} 分"
        header.addView(text(score, 20, if (idle) palette.muted else scoreColor(cycle.percent()), true).numeric())
        card.addView(header)
        card.label("${cycle.days.first().date} – ${cycle.days.last().date}  ·  ${if (percentage) "百分比制" else "原始分制"}", TypeScale.CAPTION, palette.muted, gap = 4).numeric()
        if (detailed) card.label("原始 ${cycle.net} 分  ·  理论 ${cycle.min} ～ ${cycle.max} 分  ·  额外 ${signed(cycle.bonus)}", TypeScale.CAPTION, palette.muted, gap = 7).numeric()
        else card.label(if (cycle.days.any { it.bonus != 0L }) "有额外加扣分" else "无额外加扣分", TypeScale.CAPTION, palette.muted, gap = 7)
        cycle.recentDays.forEach { day ->
            if (detailed) entry(card, day.date, "${day.score} − ${day.deduction}${if (day.bonus == 0L) "" else "  ${signed(day.bonus)}"} = ${day.net}") { open(Screen("timeline", day.date)) }
            else card.label("${day.date}    有数据", TypeScale.LABEL, palette.muted, gap = 8).numeric()
        }
    }
    if (config.flag("showDebugInfo")) content.label("当前日期键 $today · ${cycles.sumOf { it.days.size }} 个日期", TypeScale.CAPTION, palette.muted, gap = 16)
}
internal fun clockHour(hour: Int) = String.format(Locale.ROOT, "%02d:00", hour)
private fun hourRange(at: Long) = "${format(at, "HH:mm")}~${format(nextHourStart(at), "HH:mm")}"
internal data class TimelineGroup(val at: Long, val heading: String, val lines: List<String>)
/** One timeline row: back-to-back segments of one app shown together. [duration] is time actually in use. */
internal data class AppRun(val pkg: String, val label: String, val start: Long, val end: Long, val duration: Long, val parts: Int)
/**
 * Display only — stored entries are not touched. Consecutive segments of the same app whose gap is at most
 * [gap] ms (typically in-app screen changes) fold into one row, unless a lock/unlock event falls in that gap.
 * Overlapping segments count their shared time once.
 */
internal fun mergeAppRuns(apps: List<Pair<Entry, String>>, screenTimes: List<Long>, gap: Long = 1000): List<AppRun> {
    val runs = mutableListOf<AppRun>()
    for ((r, label) in apps.sortedWith(compareBy({ it.first.timestamp }, { it.first.duration }))) {
        val start = r.timestamp
        val end = r.timestamp + r.duration.coerceAtLeast(0)
        val last = runs.lastOrNull()
        val joins = last != null && last.pkg == r.pkg && start - last.end <= gap &&
            (start <= last.end || screenTimes.none { it in last.end..start })
        if (joins) runs[runs.lastIndex] = last!!.copy(end = maxOf(last.end, end),
            duration = last.duration + (end - maxOf(start, last.end)).coerceAtLeast(0), parts = last.parts + 1)
        else runs += AppRun(r.pkg, label, start, end, end - start, 1)
    }
    return runs
}
internal data class Timeline(val date: String, val records: List<Hour>, val details: List<Pair<Entry, String>>, val groups: List<TimelineGroup>) {
    fun export() = buildString {
        appendLine("=== $date ===")
        for (r in records) appendLine("${hourRange(r.at)} usage=${r.usage}ms sc=${r.score} dd=${r.deduction} bonus=${r.bonus}")
        appendLine()
        for ((r, label) in details) appendLine("${r.at} ${if (r.screen) "SCREEN" else "APP"} $label dur=${r.duration}ms ts=${r.timestamp}")
        appendLine()
        for (g in groups) {
            appendLine(g.heading)
            g.lines.forEach { appendLine("  $it") }
        }
    }
}
internal fun MainActivity.timelineData(date: String): Timeline {
    val (records, entries) = store.transact { db, s ->
        val (start, end) = s.config().window(date)
        store.hours(start, end, db) to store.entries(start, end, db)
    }
    val cache = mutableMapOf<String, String>()
    val details = entries.map { it to if (it.screen) it.pkg else cache.getOrPut(it.pkg) { appLabel(it.pkg) } }
    val byHour = details.groupBy { it.first.at }
    val groups = records.map { hour ->
        val score = hour.score
        val deduction = hour.deduction
        val usedMin = (hour.usage / 60000).coerceAtMost(60)
        val heading = buildString {
            append("${hourRange(hour.at)}  ·  ${durationText(hour.usage)}")
            if (score > 0) append("  +$score")
            if (deduction > 0) append(String.format(Locale.ROOT, "  −%d (%.1f×)", deduction, deduction.toDouble() / usedMin.coerceAtLeast(1)))
            if (hour.bonus > 0) append("  奖励 +${hour.bonus}")
        }
        val split = byHour[hour.at].orEmpty()
        val screens = split.filter { it.first.screen }.map { it.first }.sortedBy { it.duration }
        val lines = mergeAppRuns(split.filterNot { it.first.screen }, screens.map { it.duration }).mapTo(mutableListOf()) { run ->
            "${format(run.start, "HH:mm:ss")}  ${run.label}  ${durationText(run.duration)}"
        }
        var i = 0
        while (i < screens.size) {
            val event = screens[i]
            val next = screens.getOrNull(i + 1)
            val pair = event.pkg == "屏幕锁定" && next?.pkg == "屏幕解锁"
            lines += "${format(event.duration, "HH:mm:ss")}  ${event.pkg}" + if (pair) " (${durationText(next!!.duration - event.duration)})" else ""
            i += if (pair) 2 else 1
        }
        TimelineGroup(hour.at, heading, lines.sorted())
    }
    return Timeline(date, records, details, groups)
}
@Suppress("DEPRECATION")
private fun MainActivity.appLabel(pkg: String): String = runCatching {
    packageManager.getApplicationLabel(packageManager.getApplicationInfo(pkg, 0)).toString()
}.getOrElse {
    val p = pkg.lowercase(Locale.ROOT)
    when {
        "mobileqq" in p -> "QQ"
        "emmx" in p -> "Edge"
        "mm" in p && "tencent" in p -> "微信"
        "bili" in p -> "哔哩哔哩"
        "taobao" in p -> "淘宝"
        "douyin" in p -> "抖音"
        "alarmclock" in p -> "闹钟"
        "systemui" in p -> "系统界面"
        else -> pkg.substringAfterLast('.')
    }
}
internal fun MainActivity.renderTimeline(date: String) {
    title("时间轴", back = true, subtitle = date, actions = arrayOf("导出" to {
        saveFile("sleep_timeline_$date.txt", "text/plain", date = date)
    }))
    val data = runCatching { timelineData(date) }.getOrElse { error -> scroller(body).card().label(error.message ?: "无法读取时间轴"); return }
    val content = scroller(body)
    val controls = row()
    val groups = vertical()
    val bodies = linkedMapOf<Long, LinearLayout>()
    val chevrons = linkedMapOf<Long, View>()
    fun setAll(expanded: Boolean) {
        if (expanded) timelineCollapsed.clear() else timelineCollapsed.addAll(bodies.keys)
        bodies.values.forEach { it.visibility = if (expanded) View.VISIBLE else View.GONE }
        chevrons.values.forEach { it.rotation = if (expanded) 180f else 0f }
    }
    controls.addView(button("全部展开") { setAll(true) }, LinearLayout.LayoutParams(0, -2, 1f))
    controls.addView(button("全部收起") { setAll(false) }, LinearLayout.LayoutParams(0, -2, 1f).apply { marginStart = dp(8) })
    content.addView(controls, LinearLayout.LayoutParams(-1, -2).apply { topMargin = dp(4) })
    content.addView(groups)
    if (data.groups.isEmpty()) content.card().label("暂无数据", tint = palette.muted)
    val records = data.records.associateBy { it.at }
    for (g in data.groups) {
        val card = groups.card().apply { setPadding(dp(17), dp(8), dp(10), dp(8)) }
        val record = records[g.at]
        val header = row().apply { minimumHeight = dp(40) }
        val summary = if (record != null) "${hourRange(g.at)}  ·  ${durationText(record.usage)}" else hourRange(g.at)
        header.addView(text(summary, 14, palette.accent, true).apply { maxLines = 1; ellipsize = android.text.TextUtils.TruncateAt.END }.numeric(),
            LinearLayout.LayoutParams(0, -2, 1f))
        if (record != null) {
            val tags = listOfNotNull(
                if (record.score > 0) "+${record.score}" to palette.green else null,
                if (record.deduction > 0) "−${record.deduction}" to palette.red else null,
                if (record.bonus > 0) "奖励 +${record.bonus}" to palette.amber else null)
            tags.forEach { (label, tint) -> header.addView(chip(label, tint), LinearLayout.LayoutParams(-2, -2).apply { marginStart = dp(6) }) }
        }
        val collapsed = g.at in timelineCollapsed
        val chevron = GlyphView(this, Glyph.DOWN, palette.muted).apply { rotation = if (collapsed) 0f else 180f }
        header.addView(chevron, LinearLayout.LayoutParams(dp(28), dp(28)).apply { marginStart = dp(4) })
        val lines = vertical().apply { setPadding(0, 0, dp(6), dp(8)) }
        lines.visibility = if (collapsed) View.GONE else View.VISIBLE
        header.contentDescription = g.heading
        clickable(header, Color.TRANSPARENT, 12) {
            if (motionEnabled()) android.transition.TransitionManager.beginDelayedTransition(groups,
                android.transition.AutoTransition().apply { duration = 205; interpolator = settle })
            if (!timelineCollapsed.add(g.at)) timelineCollapsed.remove(g.at)
            val open = g.at !in timelineCollapsed
            lines.visibility = if (open) View.VISIBLE else View.GONE
            if (motionEnabled()) chevron.animate().rotation(if (open) 180f else 0f).setDuration(205).setInterpolator(settle).start()
            else chevron.rotation = if (open) 180f else 0f
        }
        card.addView(header)
        g.lines.forEachIndexed { i, line -> lines.label(line, TypeScale.LABEL, palette.muted, gap = if (i == 0) 4 else 7).numeric() }
        if (g.lines.isEmpty()) lines.label("无应用或屏幕事件", TypeScale.LABEL, palette.muted, gap = 2)
        card.addView(lines)
        bodies[g.at] = lines
        chevrons[g.at] = chevron
    }
}
