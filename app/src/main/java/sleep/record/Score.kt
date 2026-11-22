package sleep.record

import org.json.JSONArray
import org.json.JSONObject
import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Date
import java.util.Locale
import java.util.TimeZone
import kotlin.math.roundToInt

internal const val HOUR = 3_600_000L
internal const val DAY = 24 * HOUR
internal const val EARLY = "早睡挑战"
internal const val PERSUADE = "说服AI晚睡"
internal fun now() = System.currentTimeMillis()
internal fun json(vararg fields: Pair<String, Any?>) = JSONObject().apply {
    fields.forEach { (key, value) -> put(key, value ?: JSONObject.NULL) }
}
internal fun JSONArray.objects() = (0 until length()).map { getJSONObject(it) }
internal fun JSONArray.strings() = (0 until length()).map { getString(it) }
internal fun JSONObject.text(key: String, fallback: String = "") =
    if (isNull(key)) fallback else optString(key, fallback)
internal fun JSONObject.array(key: String) = optJSONArray(key) ?: JSONArray()
internal fun clock(ms: Long) = Calendar.getInstance().apply { timeInMillis = ms }
internal fun format(ms: Long, pattern: String) = SimpleDateFormat(pattern, Locale.ROOT).format(Date(ms))
internal fun civil(ms: Long = now()) = format(ms, "yyyy-MM-dd")
internal fun dateStart(date: String, hour: Int = 0): Long {
    val utc = TimeZone.getTimeZone("UTC")
    val parser = SimpleDateFormat("yyyy-MM-dd", Locale.ROOT).apply { isLenient = false; timeZone = utc }
    require(date.matches(Regex("[0-9]{4}-[0-9]{2}-[0-9]{2}"))) { "日期格式应为 yyyy-MM-dd" }
    val dateFields = Calendar.getInstance(utc).apply { time = parser.parse(date)!! }
    return Calendar.getInstance().apply {
        clear()
        set(dateFields.get(Calendar.YEAR), dateFields.get(Calendar.MONTH), dateFields.get(Calendar.DAY_OF_MONTH), hour, 0)
    }.timeInMillis
}
internal fun nextDate(date: String) = civil(clock(dateStart(date)).apply { add(Calendar.DAY_OF_YEAR, 1) }.timeInMillis)
internal data class DayWindow(val start: Long, val end: Long) {
    fun slots() = generateSequence(start, ::nextHourStart).takeWhile { it < end }
}
internal fun dayWindow(date: String, cutover: Int = 0) = DayWindow(dateStart(date, cutover), dateStart(nextDate(date), cutover))

private fun hourOffset(ms: Long, zone: TimeZone) = Math.floorMod(ms + zone.getOffset(ms), HOUR)
private fun offsetChange(left: Long, right: Long, zone: TimeZone): Long {
    var low = left
    var high = right
    val previous = zone.getOffset(left)
    while (high - low > 1) {
        val mid = low + (high - low) / 2
        if (zone.getOffset(mid) == previous) low = mid else high = mid
    }
    return high
}
internal fun hourStart(ms: Long): Long {
    val zone = TimeZone.getDefault()
    val nominal = ms - hourOffset(ms, zone)
    return if (zone.getOffset(nominal) == zone.getOffset(ms)) nominal else offsetChange(nominal, ms, zone)
}
internal fun nextHourStart(ms: Long): Long {
    val zone = TimeZone.getDefault()
    val nominal = ms + HOUR - hourOffset(ms, zone)
    return if (zone.getOffset(nominal) == zone.getOffset(ms)) nominal else offsetChange(ms, nominal, zone)
}
internal fun durationText(ms: Long): String {
    val seconds = (ms / 1000).coerceAtLeast(0)
    return if (seconds < 60) "${seconds}秒" else "${seconds / 60}分${seconds % 60}秒"
}
internal fun mmss(seconds: Long) = String.format(Locale.ROOT, "%02d:%02d", seconds / 60, seconds % 60)

internal class Config(private val values: JSONObject = JSONObject()) {
    companion object {
        val defaults = linkedMapOf<String, Any>(
            "dayStartHour" to 5, "cycleLengthDays" to 3, "sleepStartHour" to 23, "sleepEndHour" to 5,
            "sleepNoPhonePointPerMinute" to 1, "lateNightMaxPenaltyPerMinute" to 4, "noPhoneThresholdMs" to 1000L,
            "usePercentageScoring" to true, "darkMode" to 1, "showDebugInfo" to false, "summaryMode" to true,
            "ignoreDesktopApps" to true, "showDebugTasks" to false, "hideDetail" to true,
            "chatApiKey" to BuildConfig.PUBLIC_CHAT_KEY, "chatProviderUrl" to "https://botcf.com/v1",
            "chatModel" to "deepseek-v4.1-flash", "showPenaltyNotification" to true, "showSleepTimeNotification" to true,
            "chatCharsPerPoint" to 300, "chatDailyMaxPoints" to 100, "chatCharLimit" to 100000,
            "dailyTaskBonus" to 20, "checkinBonus" to 50, "restBonus" to 45, "restDurationMin" to 3,
            "earlySleepPenalty" to 20, "earlySleepReward" to 100, "earlySleepThreshold" to 100,
            "restCooldownHour" to 1, "notificationCheckIntervalSec" to 60, "defaultTab" to 0
        )
        private val ranges = mapOf(
            "dayStartHour" to 0..23, "sleepStartHour" to 0..23, "sleepEndHour" to 0..23,
            "darkMode" to 0..2, "cycleLengthDays" to 1..Int.MAX_VALUE,
            "sleepNoPhonePointPerMinute" to 0..100000, "lateNightMaxPenaltyPerMinute" to 1..100000,
            "chatCharsPerPoint" to 1..Int.MAX_VALUE, "chatDailyMaxPoints" to 0..Int.MAX_VALUE,
            "chatCharLimit" to 1..Int.MAX_VALUE, "restDurationMin" to 1..Int.MAX_VALUE,
            "notificationCheckIntervalSec" to 1..Int.MAX_VALUE, "earlySleepThreshold" to 1..Int.MAX_VALUE
        )
    }
    fun raw(key: String): String {
        val default = defaults[key]?.toString().orEmpty()
        val value = values.text(key, default)
        return when {
            key == "chatProviderUrl" && value == "https://openrouter.ai/api/v1" -> default
            key == "chatModel" && value == "qwen/qwen3.6-plus:free" -> default
            defaults[key] is String -> value.ifBlank { default }
            defaults[key] is Int -> value.toIntOrNull()?.toString() ?: default
            defaults[key] is Long -> value.toLongOrNull()?.toString() ?: default
            defaults[key] is Boolean -> value.toBooleanStrictOrNull()?.toString() ?: default
            else -> value
        }
    }
    fun number(key: String) = raw(key).toInt().takeIf { ranges[key]?.contains(it) != false } ?: defaults.getValue(key) as Int
    fun flag(key: String) = raw(key) == "true"
    fun export() = JSONObject().also { target ->
        values.keys().forEach { target.put(it, values.text(it)) }
        defaults.keys.forEach { target.put(it, raw(it)) }
    }
    fun invalidKeys() = ranges.keys.filter { values.has(it) && values.text(it).toIntOrNull()?.let(ranges.getValue(it)::contains) != true }
    val dayStart get() = number("dayStartHour")
    val start get() = number("sleepStartHour")
    val end get() = number("sleepEndHour")
    val points get() = number("sleepNoPhonePointPerMinute")
    val maxRate get() = number("lateNightMaxPenaltyPerMinute")
    fun sameScoringAs(other: Config) = start == other.start && end == other.end &&
        points == other.points && maxRate == other.maxRate
    val sleepHours get() = (end - start + 23) % 24 + 1
    fun asleep(hour: Int) = if (start < end) hour in start until end else hour >= start || hour < end
    fun rate(hour: Int): Double {
        if (!asleep(hour)) return 0.0
        val position = (hour - start + 24) % 24
        return (1 + position.toDouble() / sleepHours * (maxRate - 1)).coerceIn(1.0, maxRate.toDouble())
    }
    fun date(ms: Long = now()): String {
        val c = clock(ms)
        val localDate = civil(ms)
        val hour = c.get(Calendar.HOUR_OF_DAY)
        if (hour < dayStart || (hour == dayStart && ms < dateStart(localDate, dayStart))) c.add(Calendar.DAY_OF_YEAR, -1)
        return civil(c.timeInMillis)
    }
    fun window(date: String) = dayWindow(date, dayStart)
    fun score(ms: Long, usage: Long): Pair<Int, Int> {
        val hour = clock(ms).get(Calendar.HOUR_OF_DAY)
        val minutes = (usage / 60000).coerceIn(0, 60).toInt()
        return if (asleep(hour)) (60 - minutes) * points to (minutes * rate(hour)).roundToInt() else 0 to 0
    }
    val fullMin by lazy { -(0..23).sumOf { (60 * rate(it)).roundToInt() } }
    val fullMax get() = sleepHours * 60 * points
    fun range(date: String, through: Long? = null): Pair<Long, Long> {
        val window = window(date)
        if (through == null && window.end - window.start == DAY) return fullMin.toLong() to fullMax.toLong()
        var min = 0L
        var max = 0L
        for (at in window.slots()) {
            val hour = localHour(at)
            if (!asleep(hour)) continue
            min -= (60 * rate(hour)).roundToInt()
            if (through == null || at <= through) max += 60L * points
        }
        return min to max
    }
    fun todayMax(ms: Long): Int = range(date(ms), ms).second.toInt()
    fun scoringStarted(date: String, ms: Long) = window(date).slots().takeWhile { it <= ms }.any { asleep(localHour(it)) }
    fun guard(ms: Long): Long {
        val c = clock(ms)
        val h = c.get(Calendar.HOUR_OF_DAY)
        val m = c.get(Calendar.MINUTE)
        if (!asleep(h)) return 0
        val sinceSleepStart = (h - start + 24) % 24 * 60 + m
        val sinceDayStart = (ms - window(date(ms)).start) / 60000
        val estimatedRate = 1 + sinceSleepStart / (60.0 * sleepHours) * (maxRate - 1)
        return (minOf(sinceSleepStart.toLong(), sinceDayStart) * estimatedRate).toLong()
    }
}

internal data class Hour(val at: Long, val usage: Long, val score: Int, val deduction: Int, val bonus: Int, val updated: Long, val hasUsage: Boolean = true) {
    val inferredHasUsage get() = usage != 0L || deduction != 0
}
internal data class Entry(val at: Long, val pkg: String, val duration: Long, val timestamp: Long, val screen: Boolean)
internal data class Event(val type: Int, val pkg: String, val timestamp: Long)
internal data class Replay(val usage: Map<Long, Long>, val entries: List<Entry>)
internal fun replay(start: Long, end: Long, events: Sequence<Event>): Replay {
    val foreground = linkedMapOf<String, Long>()
    val usage = mutableMapOf<Long, Long>()
    val entries = mutableListOf<Entry>()
    fun desktop(pkg: String) = pkg.contains("launcher", true) || pkg.contains("桌面") || pkg.contains("systemui", true)
    fun close(pkg: String, a: Long, b: Long) {
        if (b <= a || desktop(pkg)) return
        var hour = hourStart(a)
        while (hour < b) {
            val next = nextHourStart(hour)
            val left = maxOf(a, hour)
            val duration = minOf(b, next) - left
            if (duration > 0) {
                usage[hour] = (usage[hour] ?: 0L) + duration
                entries += Entry(hour, pkg, duration, left, false)
            }
            hour = next
        }
    }
    for (event in events) {
        val (type, pkg, ts) = event
        if (ts < start || ts >= end) continue
        when (type) {
            1 -> foreground[pkg] = ts
            2 -> foreground.remove(pkg)?.let { close(pkg, it, ts) }
            15, 16 -> {
                if (type == 16) {
                    val iterator = foreground.iterator()
                    while (iterator.hasNext()) {
                        val (app, after) = iterator.next()
                        if (!desktop(app) && ts > after) {
                            close(app, after, ts)
                            iterator.remove()
                        }
                    }
                }
                entries += Entry(hourStart(ts), if (type == 16) "屏幕锁定" else "屏幕解锁", ts, ts, true)
            }
        }
    }
    foreground.forEach { (pkg, after) -> close(pkg, after, end) }
    return Replay(usage, entries)
}

internal data class Daily(val date: String, val score: Long, val deduction: Long, val bonus: Long, val min: Long, val max: Long) {
    val net get() = score - deduction + bonus
}
internal data class Cycle(val index: Int, val days: List<Daily>) {
    val recentDays get() = days.asReversed()
    val min get() = days.sumOf { it.min }
    val max get() = days.sumOf { it.max }
    val net get() = days.sumOf { it.net }
    val bonus get() = days.sumOf { it.bonus }
    fun percent(value: Long = net) = if (max <= min) 0 else (((value - min).toFloat() / (max - min)) * 200 - 100).toInt()
    fun notStarted(today: String, scoringStarted: Boolean) = days.singleOrNull()?.date == today && !scoringStarted
}
internal fun cycles(rows: List<Hour>, config: Config, ms: Long): List<Cycle> {
    val today = config.date(ms)
    val days = rows.groupBy { config.date(it.at) }.toSortedMap()
        .filterValues { hours -> hours.any { it.hasUsage || it.bonus != 0 } }
        .map { (date, hours) ->
        val (minimum, maximum) = config.range(date, ms.takeIf { date == today })
        Daily(date, hours.sumOf { it.score.toLong() }, hours.sumOf { it.deduction.toLong() }, hours.sumOf { it.bonus.toLong() },
            minimum, maximum)
    }
    val result = mutableListOf<Cycle>()
    val pending = mutableListOf<Daily>()
    var previous: String? = null
    val length = config.number("cycleLengthDays")
    for (day in days) {
        if (previous != null && (pending.size == length || day.date != nextDate(previous))) {
            result += Cycle(result.size, pending.toList())
            pending.clear()
        }
        pending += day
        previous = day.date
    }
    if (pending.isNotEmpty()) result += Cycle(result.size, pending.toList())
    return result
}
