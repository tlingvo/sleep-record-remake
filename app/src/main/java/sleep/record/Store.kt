package sleep.record

import android.app.AppOpsManager
import android.app.usage.UsageEvents
import android.app.usage.UsageStatsManager
import android.content.Context
import android.database.Cursor
import android.database.sqlite.SQLiteDatabase
import android.database.sqlite.SQLiteOpenHelper
import android.os.Build
import android.os.Process
import org.json.JSONArray
import org.json.JSONObject
import java.util.Calendar
import java.util.TimeZone

internal class Store internal constructor(context: Context, name: String = "sleep-record.db") : SQLiteOpenHelper(context, name, null, 4) {
    companion object {
        @Volatile private var instance: Store? = null
        fun get(context: Context): Store = instance ?: synchronized(this) {
            instance ?: Store(context.applicationContext).also { instance = it }
        }
        private const val CACHE_VERSION = 3
    }

    init { setWriteAheadLoggingEnabled(true) }

    override fun onCreate(db: SQLiteDatabase) {
        createStateTable(db)
        db.execSQL("CREATE TABLE hours (at INTEGER PRIMARY KEY, usage INTEGER NOT NULL, score INTEGER NOT NULL, deduction INTEGER NOT NULL, bonus INTEGER NOT NULL, updated INTEGER NOT NULL, has_usage INTEGER NOT NULL DEFAULT 1)")
        db.execSQL("CREATE TABLE entries (at INTEGER NOT NULL, pkg TEXT NOT NULL, duration INTEGER NOT NULL, ts INTEGER NOT NULL, screen INTEGER NOT NULL, PRIMARY KEY(at,pkg,ts))")
    }
    private fun createStateTable(db: SQLiteDatabase) {
        db.execSQL("CREATE TABLE state_chunks (part INTEGER PRIMARY KEY, payload BLOB NOT NULL)")
    }
    override fun onUpgrade(db: SQLiteDatabase, oldVersion: Int, newVersion: Int) {
        check(oldVersion in 1..3 && newVersion == 4) { "未知数据库版本 $oldVersion" }
        if (oldVersion == 1) {
            createStateTable(db)
            val contents = db.rawQuery("SELECT payload FROM state WHERE id=1", null).use {
                if (it.moveToFirst()) it.getString(0) else "{}"
            }
            writeState(db, contents)
            db.execSQL("DROP TABLE state")
        }
        if (oldVersion <= 2) db.execSQL("ALTER TABLE hours ADD COLUMN has_usage INTEGER NOT NULL DEFAULT 1")
        db.execSQL("UPDATE hours SET has_usage = 0 WHERE usage = 0 AND NOT EXISTS (SELECT 1 FROM entries WHERE entries.at = hours.at)")
        val s = state(db)
        rescore(db, s.config())
        invalidate(s)
        s.remove("last_refresh_time")
        writeState(db, s.toString())
    }

    // Page large chat histories through CursorWindow instead of putting them in a single row.
    private fun writeState(db: SQLiteDatabase, text: String) {
        val payload = text.toByteArray(Charsets.UTF_8)
        val chunkSize = 128 * 1024
        db.delete("state_chunks", null, null)
        for ((index, offset) in (payload.indices step chunkSize).withIndex()) {
            val batch = payload.copyOfRange(offset, minOf(offset + chunkSize, payload.size))
            db.execSQL("INSERT INTO state_chunks VALUES (?, ?)", arrayOf<Any>(index, batch))
        }
    }
    fun state(db: SQLiteDatabase = readableDatabase): JSONObject = db.rawQuery("SELECT payload FROM state_chunks ORDER BY part", null).use { cursor ->
        if (!cursor.moveToFirst()) return@use JSONObject()
        val contents = java.io.ByteArrayOutputStream()
        do { contents.write(cursor.getBlob(0)) } while (cursor.moveToNext())
        JSONObject(contents.toString("UTF-8"))
    }
    fun config() = Config(state().optJSONObject("settings") ?: JSONObject())
    fun <T> transact(block: (SQLiteDatabase, JSONObject) -> T): T {
        val db = writableDatabase
        db.beginTransactionNonExclusive()
        try {
            val state = state(db)
            val before = state.toString()
            val result = block(db, state)
            val after = state.toString()
            if (before != after) writeState(db, after)
            db.setTransactionSuccessful()
            return result
        } finally {
            db.endTransaction()
        }
    }
    fun edit(block: (JSONObject) -> Unit) = transact { _, state -> block(state) }

    private fun Cursor.hour() = Hour(getLong(0), getLong(1), getInt(2), getInt(3), getInt(4), getLong(5), getInt(6) != 0)
    fun hours(start: Long? = null, end: Long? = null, db: SQLiteDatabase = readableDatabase): List<Hour> {
        val where = if (start != null && end != null) " WHERE at >= ? AND at < ?" else ""
        val args = if (where.isEmpty()) null else arrayOf(start.toString(), end.toString())
        return db.rawQuery("SELECT at,usage,score,deduction,bonus,updated,has_usage FROM hours$where ORDER BY at", args).use { c ->
            buildList { while (c.moveToNext()) add(c.hour()) }
        }
    }
    fun entries(start: Long = 0, end: Long = Long.MAX_VALUE, db: SQLiteDatabase = readableDatabase): List<Entry> =
        db.rawQuery("SELECT at,pkg,duration,ts,screen FROM entries WHERE at >= ? AND at < ? ORDER BY ts,at,pkg", arrayOf(start.toString(), end.toString())).use { c ->
            buildList { while (c.moveToNext()) add(Entry(c.getLong(0), c.getString(1), c.getLong(2), c.getLong(3), c.getInt(4) != 0)) }
        }
    fun put(db: SQLiteDatabase, r: Hour) = db.execSQL("INSERT OR REPLACE INTO hours VALUES (?,?,?,?,?,?,?)", arrayOf<Any>(r.at, r.usage, r.score, r.deduction, r.bonus, r.updated, if (r.hasUsage) 1 else 0))
    fun put(db: SQLiteDatabase, r: Entry) = db.execSQL("INSERT OR REPLACE INTO entries VALUES (?,?,?,?,?)", arrayOf<Any>(r.at, r.pkg, r.duration, r.timestamp, if (r.screen) 1 else 0))
    fun invalidate(state: JSONObject) { state.remove("cycles_cache") }
    fun bonus(db: SQLiteDatabase, state: JSONObject, timestamp: Long, delta: Int, updated: Long = now()) {
        if (delta == 0) return
        val at = hourStart(timestamp)
        val previous = hours(at, at + 1, db).firstOrNull() ?: Hour(at, 0, 0, 0, 0, updated, hasUsage = false)
        put(db, previous.copy(bonus = previous.bonus + delta, updated = updated))
        invalidate(state)
    }
    fun bonus(delta: Int) = transact { db, state -> bonus(db, state, now(), delta) }

    private fun rescore(db: SQLiteDatabase, config: Config) {
        // Missing and bonus-only hours are not evidence of zero screen use.
        for (row in hours(db = db)) {
            if (!row.hasUsage && row.usage == 0L && row.score == 0 && row.deduction == 0 && row.bonus != 0) continue
            val (score, deduction) = config.score(row.at, row.usage)
            if (score != row.score || deduction != row.deduction) {
                db.execSQL("UPDATE hours SET score = ?, deduction = ? WHERE at = ?", arrayOf<Any>(score, deduction, row.at))
            }
        }
    }

    fun setSettings(db: SQLiteDatabase, s: JSONObject, settings: JSONObject): Boolean {
        val config = Config(settings)
        val previous = s.config()
        val scoringChanged = !config.sameScoringAs(previous)
        val backfill = scoringChanged && (0..23).any { config.asleep(it) && !previous.asleep(it) }
        if (scoringChanged) rescore(db, config)
        s.put("settings", settings)
        if (scoringChanged || config.dayStart != previous.dayStart || config.number("cycleLengthDays") != previous.number("cycleLengthDays")) invalidate(s)
        if (backfill) s.remove("last_refresh_time")
        return backfill
    }

    fun adjustSettings(changes: Map<String, String>, reset: Boolean = false) = transact { db, s ->
        val settings = if (reset) JSONObject() else s.config().export()
        changes.forEach { (key, value) -> settings.put(key, value) }
        setSettings(db, s, settings)
    }

    fun overview(ms: Long = now()): List<Cycle> {
        val s = state()
        val config = s.config()
        val cache = s.optJSONObject("cycles_cache")
        if (cache != null && cache.optInt("version") == CACHE_VERSION && cache.optLong("minute", -1) == ms / 60000 && cache.text("zone") == TimeZone.getDefault().id) {
            runCatching {
                return cache.getJSONArray("cycles").let { list ->
                    (0 until list.length()).map { index ->
                        Cycle(index, list.getJSONArray(index).objects().map {
                            Daily(it.getString("date"), it.getLong("score"), it.getLong("deduction"), it.getLong("bonus"), it.getLong("min"), it.getLong("max"))
                        })
                    }
                }
            }
        }
        return cycles(hours(), config, ms)
    }

    fun applyReplay(db: SQLiteDatabase, config: Config, start: Long, end: Long, data: Replay, updated: Long) {
        val existing = hours(start, end, db).associateBy { it.at }
        val observed = data.entries.mapTo(hashSetOf(), Entry::at)
        for (at in DayWindow(start, end).slots()) {
            if (at >= updated) break
            val previous = existing[at]
            val fresh = data.usage[at]
            if (fresh == null && previous != null && previous.bonus != 0) continue
            if (fresh != null || previous != null || config.asleep(localHour(at))) {
                val usage = fresh ?: previous?.usage ?: 0
                val (score, deduction) = config.score(at, usage)
                val hasUsage = fresh != null || at in observed || previous?.hasUsage == true
                put(db, Hour(at, usage, score, deduction, previous?.bonus ?: 0, updated, hasUsage))
            }
        }
        if (data.entries.isNotEmpty()) {
            db.delete("entries", "at >= ? AND at < ?", arrayOf(start.toString(), end.toString()))
            data.entries.forEach { put(db, it) }
        }
    }

    fun refresh(context: Context, automatic: Boolean, ms: Long = now()) {
        check(hasUsageAccess(context)) { "请先授予使用情况访问权限" }
        val source = context.getSystemService(UsageStatsManager::class.java)
        val refreshInput = state()
        val generation = refreshInput.optLong("import_generation")
        val config = refreshInput.config()
        val zone = TimeZone.getDefault().id
        val firstDate = config.date(clock(ms).apply { add(Calendar.DAY_OF_YEAR, -30) }.timeInMillis)
        val dates = generateSequence(firstDate, ::nextDate).takeWhile { it <= config.date(ms) }.toList()
        val batches = dates.mapNotNull { date ->
            val (start, end) = config.window(date)
            val queryEnd = minOf(end, ms)
            if (start >= queryEnd) return@mapNotNull null
            val stream = source.queryEvents(start, queryEnd) ?: error("系统未返回使用事件，请先解锁设备")
            val event = UsageEvents.Event()
            val events = sequence {
                while (stream.getNextEvent(event)) yield(Event(event.eventType, event.packageName.orEmpty(), event.timeStamp))
            }
            Triple(start, end, replay(start, queryEnd, events))
        }
        transact { db, s ->
            val active = Config(s.optJSONObject("settings") ?: JSONObject())
            if (active.export().toString() != config.export().toString() || generation != s.optLong("import_generation") || zone != TimeZone.getDefault().id) return@transact
            for ((start, end, data) in batches) {
                applyReplay(db, config, start, end, data, now())
            }
            val timestamp = now()
            if (automatic) s.put("last_refresh_time", timestamp)
            val list = cycles(hours(db = db), config, timestamp)
            s.put("cycles_cache", json("version" to CACHE_VERSION, "minute" to timestamp / 60000, "zone" to zone,
                "cycles" to JSONArray(list.map { cycle -> JSONArray(cycle.days.map {
                    json("date" to it.date, "score" to it.score, "deduction" to it.deduction, "bonus" to it.bonus, "min" to it.min, "max" to it.max)
                }) })))
        }
    }
}

@Suppress("DEPRECATION")
internal fun hasUsageAccess(context: Context): Boolean {
    val ops = context.getSystemService(AppOpsManager::class.java)
    val mode = if (Build.VERSION.SDK_INT >= 29) ops.unsafeCheckOpNoThrow(AppOpsManager.OPSTR_GET_USAGE_STATS, Process.myUid(), context.packageName)
    else ops.checkOpNoThrow(AppOpsManager.OPSTR_GET_USAGE_STATS, Process.myUid(), context.packageName)
    return mode == AppOpsManager.MODE_ALLOWED
}
