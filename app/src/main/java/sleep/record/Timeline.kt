package sleep.record

import android.content.pm.PackageManager
import java.util.Locale

internal fun signed(value: Long) = if (value > 0) "+$value" else value.toString()
internal fun clockHour(hour: Int) = String.format(Locale.ROOT, "%02d:00", hour)
internal fun hourRange(at: Long) = "${format(at, "HH:mm")}~${format(nextHourStart(at), "HH:mm")}"

/** One timeline row. [kind] is "app" or "screen"; [parts] counts the stored segments folded into an app row. */
internal data class TimelineLine(val time: String, val label: String, val detail: String, val kind: String, val parts: Int = 1) {
    val text get() = if (kind == "app") "$time  $label  $detail" else "$time  $label" + if (detail.isEmpty()) "" else " ($detail)"
}
internal data class TimelineGroup(val at: Long, val heading: String, val rows: List<TimelineLine>) {
    val lines get() = rows.map(TimelineLine::text)
}
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
/** Deduction per used minute as the timeline header shows it, e.g. 1.5 for "−90 (1.5×)". */
internal fun deductionRatio(hour: Hour) = hour.deduction.toDouble() / (hour.usage / 60000).coerceAtMost(60).coerceAtLeast(1)

/** The hour headers always use the stored hourly usage, never a sum rebuilt from the detail rows. */
internal fun timelineData(store: Store, packages: PackageManager, date: String): Timeline {
    val (records, entries) = store.transact { db, s ->
        val (start, end) = s.config().window(date)
        store.hours(start, end, db) to store.entries(start, end, db)
    }
    val cache = mutableMapOf<String, String>()
    val details = entries.map { it to if (it.screen) it.pkg else cache.getOrPut(it.pkg) { appLabel(packages, it.pkg) } }
    val byHour = details.groupBy { it.first.at }
    val groups = records.map { hour ->
        val heading = buildString {
            append("${hourRange(hour.at)}  ·  ${durationText(hour.usage)}")
            if (hour.score > 0) append("  +${hour.score}")
            if (hour.deduction > 0) append(String.format(Locale.ROOT, "  −%d (%.1f×)", hour.deduction, deductionRatio(hour)))
            if (hour.bonus > 0) append("  奖励 +${hour.bonus}")
        }
        val split = byHour[hour.at].orEmpty()
        val screens = split.filter { it.first.screen }.map { it.first }.sortedBy { it.duration }
        val rows = mergeAppRuns(split.filterNot { it.first.screen }, screens.map { it.duration }).mapTo(mutableListOf()) { run ->
            TimelineLine(format(run.start, "HH:mm:ss"), run.label, durationText(run.duration), "app", run.parts)
        }
        var i = 0
        while (i < screens.size) {
            val event = screens[i]
            val next = screens.getOrNull(i + 1)
            val pair = event.pkg == "屏幕锁定" && next?.pkg == "屏幕解锁"
            rows += TimelineLine(format(event.duration, "HH:mm:ss"), event.pkg, if (pair) durationText(next!!.duration - event.duration) else "", "screen")
            i += if (pair) 2 else 1
        }
        TimelineGroup(hour.at, heading, rows.sortedBy { it.text })
    }
    return Timeline(date, records, details, groups)
}
@Suppress("DEPRECATION")
internal fun appLabel(packages: PackageManager, pkg: String): String = runCatching {
    packages.getApplicationLabel(packages.getApplicationInfo(pkg, 0)).toString()
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
