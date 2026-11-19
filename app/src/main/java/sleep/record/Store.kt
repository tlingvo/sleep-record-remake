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

internal class Store internal constructor(context: Context, name: String = "sleep-record.db") : SQLiteOpenHelper(context, name, null, 2) {
    companion object {
        @Volatile private var instance: Store? = null
        fun get(context: Context): Store = instance ?: synchronized(this) {
            instance ?: Store(context.applicationContext).also { instance = it }
        }
    }

    init { setWriteAheadLoggingEnabled(true) }

    override fun onCreate(db: SQLiteDatabase) {
        createStateTable(db)
        db.execSQL("CREATE TABLE hours (at INTEGER PRIMARY KEY, usage INTEGER NOT NULL, score INTEGER NOT NULL, deduction INTEGER NOT NULL, bonus INTEGER NOT NULL, updated INTEGER NOT NULL)")
        db.execSQL("CREATE TABLE entries (at INTEGER NOT NULL, pkg TEXT NOT NULL, duration INTEGER NOT NULL, ts INTEGER NOT NULL, screen INTEGER NOT NULL, PRIMARY KEY(at,pkg,ts))")
    }
    private fun createStateTable(db: SQLiteDatabase) {
        db.execSQL("CREATE TABLE state_chunks (part INTEGER PRIMARY KEY, payload BLOB NOT NULL)")
    }
    override fun onUpgrade(db: SQLiteDatabase, oldVersion: Int, newVersion: Int) {
        check(oldVersion == 1 && newVersion == 2) { "未知数据库版本 $oldVersion" }
        createStateTable(db)
        val contents = db.rawQuery("SELECT payload FROM state WHERE id=1", null).use {
            if (it.moveToFirst()) it.getString(0) else "{}"
        }
        writeState(db, contents)
        db.execSQL("DROP TABLE state")
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

    private fun Cursor.hour() = Hour(getLong(0), getLong(1), getInt(2), getInt(3), getInt(4), getLong(5))
    fun hours(start: Long? = null, end: Long? = null, db: SQLiteDatabase = readableDatabase): List<Hour> {
        val where = if (start != null && end != null) " WHERE at >= ? AND at < ?" else ""
        val args = if (where.isEmpty()) null else arrayOf(start.toString(), end.toString())
        return db.rawQuery("SELECT at,usage,score,deduction,bonus,updated FROM hours$where ORDER BY at", args).use { c ->
            buildList { while (c.moveToNext()) add(c.hour()) }
        }
    }
    fun entries(start: Long = 0, end: Long = Long.MAX_VALUE, db: SQLiteDatabase = readableDatabase): List<Entry> =
        db.rawQuery("SELECT at,pkg,duration,ts,screen FROM entries WHERE at >= ? AND at < ? ORDER BY ts,at,pkg", arrayOf(start.toString(), end.toString())).use { c ->
            buildList { while (c.moveToNext()) add(Entry(c.getLong(0), c.getString(1), c.getLong(2), c.getLong(3), c.getInt(4) != 0)) }
        }
    fun put(db: SQLiteDatabase, r: Hour) = db.execSQL("INSERT OR REPLACE INTO hours VALUES (?,?,?,?,?,?)", arrayOf<Any>(r.at, r.usage, r.score, r.deduction, r.bonus, r.updated))
    fun put(db: SQLiteDatabase, r: Entry) = db.execSQL("INSERT OR REPLACE INTO entries VALUES (?,?,?,?,?)", arrayOf<Any>(r.at, r.pkg, r.duration, r.timestamp, if (r.screen) 1 else 0))
    fun invalidate(state: JSONObject) { state.remove("cycles_cache") }
    fun bonus(db: SQLiteDatabase, state: JSONObject, timestamp: Long, delta: Int, updated: Long = now()) {
        if (delta == 0) return
        val at = hourStart(timestamp)
        val previous = hours(at, at + 1, db).firstOrNull() ?: Hour(at, 0, 0, 0, 0, updated)
        put(db, previous.copy(bonus = previous.bonus + delta, updated = updated))
        invalidate(state)
    }
    fun bonus(delta: Int) = transact { db, state -> bonus(db, state, now(), delta) }

    fun adjustSettings(changes: Map<String, String>, reset: Boolean = false) = edit { s ->
        val settings = if (reset) JSONObject() else Config(s.optJSONObject("settings") ?: JSONObject()).export()
        changes.forEach { (key, value) -> settings.put(key, value) }
        s.put("settings", settings)
        invalidate(s)
        s.remove("last_refresh_time")
    }

    fun overview(ms: Long = now()): List<Cycle> {
        val s = state()
        val config = s.config()
        val cache = s.optJSONObject("cycles_cache")
        if (cache != null && cache.optLong("minute", -1) == ms / 60000 && cache.text("zone") == java.util.TimeZone.getDefault().id) {
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
        repeat(24) { index ->
            val at = start + index * HOUR
            if (at >= updated) return@repeat
            val previous = existing[at]
            val fresh = data.usage[at / HOUR]
            if (fresh == null && previous != null && previous.bonus != 0) return@repeat
            if (fresh != null || previous != null || config.asleep(localHour(at))) {
                val usage = fresh ?: previous?.usage ?: 0
                val (score, deduction) = config.score(at, usage)
                put(db, Hour(at, usage, score, deduction, previous?.bonus ?: 0, updated))
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
        val config = refreshInput.config()
        val dates = clock(ms).apply { add(Calendar.DAY_OF_YEAR, -30) }
        val tomorrow = clock(dateStart(civil(ms))).apply { add(Calendar.DAY_OF_YEAR, 1) }.timeInMillis
        // Collect outside the transaction. Recheck config and import generation before committing.
        val generation = refreshInput.optLong("import_generation")
        val batches = mutableListOf<Triple<Long, Long, Replay>>()
        while (dates.timeInMillis <= tomorrow) {
            val start = dateStart(civil(dates.timeInMillis), config.dayStart)
            val end = start + DAY
            val queryEnd = minOf(end, ms)
            if (queryEnd > start) {
                val stream = source.queryEvents(start, queryEnd) ?: error("系统未返回使用事件，请先解锁设备")
                val event = UsageEvents.Event()
                val events = sequence {
                    while (stream.getNextEvent(event)) yield(Event(event.eventType, event.packageName.orEmpty(), event.timeStamp))
                }
                batches += Triple(start, end, replay(start, queryEnd, events))
            }
            dates.add(Calendar.DAY_OF_YEAR, 1)
        }
        transact { db, s ->
            val active = Config(s.optJSONObject("settings") ?: JSONObject())
            if (active.export().toString() != config.export().toString() || generation != s.optLong("import_generation")) return@transact
            for ((start, end, data) in batches) {
                applyReplay(db, config, start, end, data, now())
            }
            val timestamp = now()
            if (automatic) s.put("last_refresh_time", timestamp)
            val list = cycles(hours(db = db), config, timestamp)
            s.put("cycles_cache", json("minute" to timestamp / 60000, "zone" to java.util.TimeZone.getDefault().id,
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
