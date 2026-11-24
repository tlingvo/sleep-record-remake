package sleep.record

import android.app.Activity
import android.app.Instrumentation
import android.database.sqlite.SQLiteDatabase
import android.os.Bundle
import org.json.JSONArray
import org.json.JSONObject
import java.util.TimeZone

/** Runs on Android so JSON, SQLite, and packaged resources use their production implementations. */
class ContractRunner : Instrumentation() {
    override fun onCreate(arguments: Bundle?) { super.onCreate(arguments); start() }
    override fun onStart() {
        val previous = TimeZone.getDefault()
        TimeZone.setDefault(TimeZone.getTimeZone("Asia/Singapore"))
        val results = mutableListOf<String>()
        var failures = 0
        fun test(name: String, verification: () -> Unit) {
            try {
                verification()
                results += "PASS $name"
            } catch (failure: Throwable) {
                failures++
                results += "FAIL $name\n${failure.stackTraceToString()}"
            }
        }
        val catalog = Catalog(targetContext)
        test("05:00 custom-day boundary, rounding and today's dynamic range") {
            val cfg = Config()
            expect("https://botcf.com/v1", cfg.raw("chatProviderUrl"))
            expect(BuildConfig.PUBLIC_CHAT_KEY, cfg.raw("chatApiKey"))
            expect("2025-06-15", cfg.date(at("2025-06-16", 5) - 1))
            expect("2025-06-16", cfg.date(at("2025-06-16", 5)))
            expect(listOf(1.0, 1.5, 2.0, 2.5, 3.0, 3.5), listOf(23, 0, 1, 2, 3, 4).map(cfg::rate))
            expect(-810, cfg.fullMin)
            expect(360, cfg.fullMax)
            expect(57 to 5, cfg.score(at("2025-06-16", 0), 180000))
            expect(60 to 0, cfg.score(at("2025-06-15", 23), 59999))
            expect(0 to 0, cfg.score(at("2025-06-15", 22), 120000))
            expect(0, cfg.todayMax(at("2025-06-15", 23) - 60000))
            expect(60, cfg.todayMax(at("2025-06-15", 23) + 60000))
            expect(120, cfg.todayMax(at("2025-06-16", 0) + 60000))
            expect(360, cfg.todayMax(at("2025-06-16", 4) + 60000))
            val midnight = Config(json("dayStartHour" to "0"))
            expect(60, midnight.todayMax(at("2025-06-16", 0) + 60000))
            val later = Config(json("sleepStartHour" to "1"))
            expect(0, later.todayMax(at("2025-06-16", 0) + 60000))
            expect(24, Config(json("sleepStartHour" to "5", "sleepEndHour" to "5")).sleepHours)
        }
        test("UsageEvents split at hour, lock replay and overlapping usage") {
            val start = at("2025-06-15", 5)
            val eleven = at("2025-06-15", 23)
            val replay = replay(start, eleven + HOUR, sequenceOf(
                Event(2, "outside", start + 1000), Event(1, "reader", eleven - 30000), Event(16, "android", eleven + 70000)
            ))
            expect(30000L, replay.usage[eleven - HOUR])
            expect(70000L, replay.usage[eleven])
            expect(59 to 1, Config().score(eleven, replay.usage.getValue(eleven)))
            expect(Entry(eleven, "屏幕锁定", eleven + 70000, eleven + 70000, true), replay.entries.last())
            val minutes = 60000L
            val overlap = replay(start, eleven + HOUR, sequenceOf(
                Event(1, "a", eleven), Event(1, "b", eleven + 20 * minutes), Event(1, "some.LAUNCHER", eleven + 25 * minutes),
                Event(2, "a", eleven + 40 * minutes)
            ))
            expect(80 * minutes, overlap.usage[eleven])
            expect(0 to 60, Config().score(eleven, overlap.usage.getValue(eleven)))
            expect(listOf("a", "b"), overlap.entries.map { it.pkg })
        }
        test("Empty UsageEvents retain old bonus and details, fill ended sleep hours") {
            isolated { store, _ ->
                val start = at("2025-06-15", 5)
                val eleven = at("2025-06-15", 23)
                val bonus = Hour(eleven, 600000, 3, 10, 50, eleven)
                val detail = Entry(eleven, "old.reader", 600000, eleven, false)
                store.transact { db, _ ->
                    store.put(db, bonus)
                    store.put(db, detail)
                    store.applyReplay(db, Config(), start, start + DAY, Replay(emptyMap(), emptyList()), start + DAY)
                }
                expect(6, store.hours().size)
                expect(bonus, store.hours().first())
                check(store.hours().drop(1).all { it.usage == 0L && it.score == 60 })
                check(store.hours().drop(1).none { it.hasUsage })
                expect(listOf(detail), store.entries())
            }
        }
        test("Cycle gaps, consecutive index, percentage beyond 100") {
            val dates = listOf(1, 2, 3, 4, 6, 7).map { "2025-06-0$it" }
            val result = cycles(dates.map { Hour(at(it, 23), 0, 60, 0, 0, 0) }, Config(), at("2025-06-10", 23))
            expect(listOf(0, 1, 2), result.map { it.index })
            expect(listOf(dates.take(3), dates.subList(3, 4), dates.takeLast(2)), result.map { cycle -> cycle.days.map { it.date } })
            expect(listOf("2025-06-07", "2025-06-06"), result.last().recentDays.map(Daily::date))
            val sample = Cycle(0, listOf(Daily(dates.first(), 60, 0, 100, -810, 60)))
            expect(122, sample.percent())
            expect(22, sample.percent(sample.net) - sample.percent(sample.net - 100))
        }
        test("Synthetic empty sleep slots don't create data days or bridge cycle gaps") {
            val cfg = Config(json("cycleLengthDays" to "3"))
            val first = dateStart("2026-10-28", cfg.dayStart)
            val middle = dateStart("2026-10-29", cfg.dayStart)
            val last = dateStart("2026-10-30", cfg.dayStart)
            val rows = listOf(
                Hour(first, 0, 60, 0, 0, first, hasUsage = true),
                Hour(first + HOUR, 0, 60, 0, 0, first, hasUsage = false),
                Hour(middle, 0, 360, 0, 0, middle, hasUsage = false),
                Hour(last, 0, 60, 0, 0, last, hasUsage = true)
            )
            val result = cycles(rows, cfg, at("2026-11-01", 12))
            expect(listOf(listOf("2026-10-28"), listOf("2026-10-30")), result.map { cycle -> cycle.days.map(Daily::date) })
            expect(listOf(120L, 60L), result.map(Cycle::net))
            expect(emptyList<Cycle>(), cycles(rows.filterNot(Hour::hasUsage), cfg, at("2026-11-01", 12)))
            val shifted = Config(json("dayStartHour" to "0", "cycleLengthDays" to "3"))
            val afterShift = cycles(listOf(rows[0].copy(at = at("2026-10-28", 2))), shifted, at("2026-11-01", 12))
            expect("2026-10-28", afterShift.single().days.single().date)
        }
        test("Changing sleep start re-scores old periods before any usage refresh") {
            isolated { store, _ ->
                val start = at("2026-10-14", 23)
                val ms = at("2026-11-19", 14)
                val oldHours = (0..5).map { Hour(start + it * HOUR, 0, 60, 0, 0, start + DAY) }
                store.transact { db, _ -> oldHours.forEach { store.put(db, it) } }
                expect(360L, store.overview(ms).single().net)

                store.adjustSettings(mapOf("sleepStartHour" to "1"))
                val short = store.overview(ms).single()
                expect(Daily("2026-10-14", 240, 0, 0, -510, 240), short.days.single())
                expect(100, short.percent())
                expect(oldHours.map { it.at }, store.hours().map { it.at })

                store.adjustSettings(emptyMap(), reset = true)
                expect(oldHours, store.hours())
                expect(100, store.overview(ms).single().percent())
            }
        }
        test("Changing day cutover or cycle length regroups without re-scoring or fetching usage") {
            isolated { store, _ ->
                val midnight = at("2026-10-15", 0)
                val ms = at("2026-11-19", 14)
                val rows = listOf(Hour(midnight - HOUR, 0, 60, 0, 0, midnight), Hour(midnight, 180000, 57, 5, 20, midnight))
                store.transact { db, s ->
                    rows.forEach { store.put(db, it) }
                    s.put("last_refresh_time", ms)
                }
                expect(listOf("2026-10-14"), store.overview(ms).single().days.map(Daily::date))
                expect(false, store.adjustSettings(mapOf("dayStartHour" to "0")))
                val days = store.overview(ms).single().days
                expect(listOf("2026-10-14", "2026-10-15"), days.map(Daily::date))
                val (start, end) = store.config().window(days.last().date)
                expect(listOf(rows.last()), store.hours(start, end))
                expect(false, store.adjustSettings(mapOf("cycleLengthDays" to "1")))
                expect(listOf(60L, 72L), store.overview(ms).map(Cycle::net))
                expect(rows, store.hours())
                expect(ms, store.state().optLong("last_refresh_time"))
            }
        }
        test("Cycle gaps are detected after applying the configured custom-day cutover") {
            val rows = listOf(
                Hour(at("2026-10-01", 23), 0, 60, 0, 0, 0),
                Hour(at("2026-10-03", 2), 0, 60, 0, 0, 0)
            )
            val beforeCutover = Config(json("dayStartHour" to "5", "cycleLengthDays" to "3"))
            expect(listOf(listOf("2026-10-01", "2026-10-02")), cycles(rows, beforeCutover, at("2026-11-01", 12)).map { cycle -> cycle.days.map(Daily::date) })
            val midnightCutover = Config(json("dayStartHour" to "0", "cycleLengthDays" to "3"))
            expect(listOf(listOf("2026-10-01"), listOf("2026-10-03")),
                cycles(rows, midnightCutover, at("2026-11-01", 12)).map { cycle -> cycle.days.map(Daily::date) })
            expect(listOf(60L, 60L), cycles(rows, midnightCutover, at("2026-11-01", 12)).map(Cycle::net))
        }
        test("Deduction cap, base rate and sleep end re-score the same historical usage") {
            isolated { store, _ ->
                val at = at("2026-10-15", 3)
                val original = Hour(at, 180000, 57, 9, -20, at + HOUR)
                val outside = Hour(at + 3 * HOUR, 180000, 0, 0, 5, at + 4 * HOUR)
                store.transact { db, _ -> store.put(db, original); store.put(db, outside) }
                expect(false, store.adjustSettings(mapOf("lateNightMaxPenaltyPerMinute" to "7")))
                expect(5.0, store.config().rate(3))
                expect(listOf(original.copy(deduction = 15), outside), store.hours())
                expect(-1260, store.config().fullMin)
                expect(false, store.adjustSettings(mapOf("sleepNoPhonePointPerMinute" to "2")))
                expect(original.copy(score = 114, deduction = 15), store.hours().first())
                expect(true, store.adjustSettings(mapOf("sleepEndHour" to "7")))
                expect(listOf(original.copy(score = 114, deduction = 12), outside.copy(score = 114, deduction = 19)), store.hours())
                expect(false, store.adjustSettings(mapOf("showPenaltyNotification" to "false", "notificationCheckIntervalSec" to "15")))
                expect(12, store.hours().first().deduction)
                store.adjustSettings(emptyMap(), reset = true)
                expect(listOf(original, outside), store.hours())
            }
        }
        test("Zero base-rate cycles still show percentages once a sleep hour starts") {
            val config = Config(json("sleepNoPhonePointPerMinute" to "0"))
            val start = at("2026-10-14", 23)
            val today = config.date(start)
            val row = Hour(start - HOUR, 0, 0, 0, 0, start)
            val cycle = cycles(listOf(row), config, start).single()
            expect(0L, cycle.max)
            expect(100, cycle.percent())
            check(cycle.notStarted(today, config.scoringStarted(today, start - 1)))
            check(!cycle.notStarted(today, config.scoringStarted(today, start)))
            check(!cycle.notStarted(config.date(start + DAY), false))
        }
        test("Early-sleep guard follows the window and cutover, including non-overnight schedules") {
            val midnight = Config(json("dayStartHour" to "0"))
            check(midnight.guard(at("2026-10-14", 23) + 45 * 60000) > 0)
            expect(0L, midnight.guard(at("2026-10-15", 0)))
            expect(0L, Config(json("dayStartHour" to "1", "sleepStartHour" to "3")).guard(at("2026-10-15", 2) + 15 * 60000))
            expect(0L, Config(json("dayStartHour" to "7", "sleepStartHour" to "9", "sleepEndHour" to "17")).guard(at("2026-10-15", 8) + 15 * 60000))
        }
        test("Civil-day boundaries preserve repeated hours and detect spring time gaps") {
            val zone = TimeZone.getDefault()
            try {
                TimeZone.setDefault(TimeZone.getTimeZone("America/New_York"))
                val config = Config(json("dayStartHour" to "0"))
                val fall = config.window("2026-11-01")
                expect(25L * HOUR, fall.end - fall.start)
                val first = fall.start + HOUR
                val second = first + HOUR
                expect(first, hourStart(first + 60000))
                expect(second, hourStart(second + 60000))
                val replay = replay(fall.start, fall.end, sequenceOf(Event(1, "reader", first + 30 * 60000), Event(2, "reader", second + 15 * 60000)))
                expect(mapOf(first to 30 * 60000L, second to 15 * 60000L), replay.usage)
                val cutover = Config(json("dayStartHour" to "1"))
                expect("2026-10-31", cutover.date(first + 60000))
                expect("2026-11-01", cutover.date(second + 60000))
                val spring = config.window("2026-03-08")
                expect(23L * HOUR, spring.end - spring.start)
                expect(23, spring.slots().count())
                val rows = listOf("2026-03-07", "2026-03-09").map { Hour(at(it, 23), 0, 60, 0, 0, 0) }
                expect(listOf(1, 1), cycles(rows, config, at("2026-03-10", 12)).map { it.days.size })
            } finally { TimeZone.setDefault(zone) }
        }
        test("Moving the cutover keeps same-day tasks single-use and moves pending judgment") {
            isolated { store, _ ->
                val tasks = Tasks(store, catalog)
                val accepted = at("2026-10-15", 2)
                store.adjustSettings(mapOf("dayStartHour" to "0", "earlySleepThreshold" to "1000"))
                tasks.checkin(ms = accepted)
                tasks.suggestion(accepted)
                tasks.accept(EARLY, accepted)
                store.adjustSettings(mapOf("dayStartHour" to "5"))
                check(runCatching { tasks.checkin(ms = accepted + 60000) }.isFailure)
                check(runCatching { tasks.suggestion(accepted + 60000) }.isFailure)
                tasks.judge(at("2026-10-15", 5) - 1)
                expect("IN_PROGRESS", store.state().list("tasks").single().text("status"))
                tasks.judge(at("2026-10-15", 5))
                expect("2026-10-14", store.state().list("tasks").single().text("judgedDate"))
                expect("COMPLETED", store.state().list("tasks").single().text("status"))
            }
        }
        test("Re-scoring preserves usage, timestamps and bonus-only rows through backup") {
            val start = at("2026-10-14", 23)
            val old = Hour(start, 0, 60, 0, 50, start + DAY)
            val used = Hour(start + 2 * HOUR, 180000, 57, 6, -20, start + DAY)
            var backup = ""
            isolated { store, _ ->
                store.transact { db, s ->
                    store.put(db, old)
                    store.put(db, used)
                    store.bonus(db, s, start + 3 * HOUR, 20, start + DAY)
                }
                store.adjustSettings(mapOf("sleepStartHour" to "1"))
                expect(listOf(old.copy(score = 0), used.copy(deduction = 3),
                    Hour(start + 3 * HOUR, 0, 0, 0, 20, start + DAY, hasUsage = false)), store.hours())
                backup = Backup.export(store)
            }
            isolated { store, _ ->
                Backup.import(store, backup)
                store.adjustSettings(mapOf("sleepStartHour" to "23"))
                expect(listOf(old, used, Hour(start + 3 * HOUR, 0, 0, 0, 20, start + DAY, hasUsage = false)), store.hours())
            }
        }
        test("Database upgrade repairs stale historical scores and clears old cache") {
            val name = "contract-upgrade-${newId()}.db"
            val start = at("2026-10-14", 23)
            val ms = at("2026-11-19", 14)
            val cached = json("minute" to ms / 60000, "zone" to TimeZone.getDefault().id,
                "cycles" to JSONArray(listOf(JSONArray(listOf(json("date" to "2026-10-14", "score" to 360,
                    "deduction" to 0, "bonus" to 0, "min" to -510, "max" to 240))))))
            val state = json("settings" to json("sleepStartHour" to "1", "cycleLengthDays" to "1"),
                "cycles_cache" to cached, "last_refresh_time" to ms)
            try {
                val path = targetContext.getDatabasePath(name)
                path.parentFile!!.mkdirs()
                SQLiteDatabase.openOrCreateDatabase(path, null).use { db ->
                    db.execSQL("CREATE TABLE hours (at INTEGER PRIMARY KEY, usage INTEGER NOT NULL, score INTEGER NOT NULL, deduction INTEGER NOT NULL, bonus INTEGER NOT NULL, updated INTEGER NOT NULL)")
                    db.execSQL("CREATE TABLE entries (at INTEGER NOT NULL, pkg TEXT NOT NULL, duration INTEGER NOT NULL, ts INTEGER NOT NULL, screen INTEGER NOT NULL, PRIMARY KEY(at,pkg,ts))")
                    db.execSQL("CREATE TABLE state_chunks (part INTEGER PRIMARY KEY, payload BLOB NOT NULL)")
                    db.execSQL("INSERT INTO state_chunks VALUES (0, ?)", arrayOf<Any>(state.toString().toByteArray(Charsets.UTF_8)))
                    repeat(6) { db.execSQL("INSERT INTO hours VALUES (?, 0, 60, 0, 0, ?)", arrayOf(start + it * HOUR, start + DAY)) }
                    db.execSQL("INSERT INTO entries VALUES (?, ?, ?, ?, ?)", arrayOf<Any>(start, "屏幕解锁", start, start, 1))
                    db.execSQL("INSERT INTO hours VALUES (?, 0, 0, 0, 20, ?)", arrayOf(start + DAY + 2 * HOUR, start + 2 * DAY))
                    db.version = 2
                }
                Store(targetContext, name).use { store ->
                    expect(100, store.overview(ms).first().percent())
                    expect(240L, store.overview(ms).first().net)
                    expect(Hour(start + DAY + 2 * HOUR, 0, 0, 0, 20, start + 2 * DAY, hasUsage = false), store.hours().last())
                    check(!store.state().has("cycles_cache") && !store.state().has("last_refresh_time"))
                }
            } finally { targetContext.deleteDatabase(name) }
        }
        test("Old JSON net score 166, linked states, nested strings and atomic upsert") {
            isolated { store, _ ->
                val fixture = context.assets.open("legacy-fixture.json").bufferedReader().use { it.readText() }
                Backup.import(store, "\uFEFF" + fixture)
                expect(2, store.hours().size)
                expect(2, store.entries().size)
                val cycle = store.overview(at("2026-01-01", 12)).single()
                expect("2025-06-15", cycle.days.single().date)
                expect(166L, cycle.net)
                expect(50L, cycle.bonus)
                val s = store.state()
                val chat = s.list("conversations").single()
                val task = s.list("tasks").single()
                expect(task.text("id"), chat.text("boundChallengeTaskId"))
                expect(chat.text("id"), task.text("boundConversationId"))
                expect("IN_PROGRESS", task.text("status"))
                expect(3, s.optInt("streak_days"))
                expect(listOf("深呼吸练习"), s.array("completed_tasks").strings())
                expect(true, s.optBoolean("notice_shown"))
                val exported = JSONObject(Backup.export(store))
                expect(true, exported.getJSONObject("chatData").get("conversations") is String)
                expect(true, exported.getJSONObject("settings").get("dayStartHour") is String)
                expect(true, exported.getJSONObject("settings").getString("showSleepTimeNotification").toBoolean())
                val extra = at("2024-01-01", 23)
                store.transact { db, _ -> store.put(db, Hour(extra, 0, 60, 0, 0, extra)) }
                Backup.import(store, fixture)
                expect(3, store.hours().size)
                expect(2, store.entries().size)
                expect(166L, store.overview().last().net)
                val before = Backup.export(store)
                val bad = fixture.replace("\"hourStartMs\": 1749999600000", "\"hourStartMs\":\"1749999600000\"")
                val error = runCatching { Backup.import(store, bad) }.exceptionOrNull()
                check(error?.message?.contains("hourlyRecords[0].hourStartMs") == true) { "Expected a field-path error, got: ${error?.message}" }
                expect(before, Backup.export(store))
                Backup.import(store, "{}")
                expect(1, store.state().array("conversations").length())
                Backup.import(store, "{\"chatData\":{}}")
                expect(0, store.state().array("conversations").length())
                expect(0, store.state().optInt("chat_day_chars"))
            }
        }
        test("Large JSON survives CursorWindow pagination") {
            isolated { store, _ ->
                val body = "夜".repeat(800_000)
                val conv = newConversation(JSONObject()).put("messages", JSONArray().put(message("我", body, 0)))
                store.edit { it.put("conversations", JSONArray().put(conv)) }
                expect(body.length, store.state().list("conversations").single().list("messages").single().text("content").length)
            }
        }
        test("Threshold 100 rejects, 99 accepts, reward returns to original hour once") {
            isolated { store, _ ->
                val tasks = Tasks(store, catalog)
                val accepted = at("2025-06-15", 20)
                val original = Hour(accepted, 0, 0, 100, 0, accepted)
                store.transact { db, _ -> store.put(db, original) }
                check(runCatching { tasks.accept(EARLY, accepted) }.isFailure)
                store.transact { db, _ -> store.put(db, original.copy(deduction = 99)) }
                tasks.accept(EARLY, accepted)
                val id = store.state().list("tasks").single().text("id")
                expect(-20, store.hours().single().bonus)
                tasks.judge(at("2025-06-16", 5) - 1)
                expect("IN_PROGRESS", store.state().find("tasks", id)!!.text("status"))
                tasks.judge(at("2025-06-16", 5))
                expect(100, tasks.archive(id, at("2025-06-16", 12)))
                expect(80, store.hours().single().bonus)
                check(runCatching { tasks.archive(id) }.isFailure)
                check(runCatching { tasks.accept(EARLY, accepted) }.isFailure)
                val next = at("2025-06-16", 20)
                tasks.accept(EARLY, next)
                store.transact { db, _ -> store.put(db, Hour(next, 0, 0, 100, -20, next)) }
                tasks.judge(next + 1)
                val last = store.state().list("tasks").last()
                expect("FAILED", last.text("status"))
                expect("2025-06-16", last.text("settledDate"))
                expect(0, tasks.archive(last.text("id")))
            }
        }
        test("Rest survives repository recreation and settles at actual hour") {
            isolated { store, name ->
                val started = at("2025-06-15", 23) + HOUR - 15000
                val tasks = Tasks(store, catalog)
                expect(50, tasks.checkin(false, started))
                expect("2025-06-15", store.state().text("last_checkin_date"))
                val plan = Rest(30, 20, "last_rest_short_time")
                val pending = tasks.startRest(plan, started)
                store.close()
                Store(targetContext, name).use { restarted ->
                    val restored = Tasks(restarted, catalog)
                    expect(plan.key, restored.rest()!!.key)
                    expect(pending.end, restored.rest()!!.end)
                    expect(null, restored.settleRest(pending.end - 1))
                    expect(20, restored.settleRest(pending.end)!!.points)
                    expect(null, restored.settleRest(pending.end + 1))
                    expect(pending.end, restarted.state().optLong(plan.key))
                    expect(20, restarted.hours().last().bonus)
                    expect(false, restored.restDone(restarted.state(), "last_rest_time", pending.end))
                    check(runCatching { restored.checkin(false, pending.end) }.isFailure)
                }
            }
        }
        test("Chat UTF-16 bonus, normal marker, challenge completion without payout") {
            isolated { store, _ ->
                val chats = Chats(store, catalog)
                val ms = at("2025-06-15", 23)
                val id = chats.load().single().text("id")
                store.edit { it.put("chat_day_key", civil(ms)); it.put("chat_day_chars", 299) }
                val request = chats.send(id, "啊", true, ms)!!
                expect(1, store.hours().single().bonus)
                val marker = "\"task_completed\"".padEnd(300, '字')
                expect(false, chats.reply(request, marker, ms))
                expect(2, store.hours().single().bonus)
                expect(600, store.state().optInt("chat_day_chars"))
                chats.reply(chats.manual(id), "补充回复", ms)
                expect(600, store.state().optInt("chat_day_chars"))
                chats.send(id, "🌙", false, ms + HOUR)
                expect(2, store.state().optInt("chat_day_chars"))
                val taskId = Tasks(store, catalog).accept(PERSUADE, ms)!!
                chats.load()
                val taskRequest = chats.send(taskId, "理由", false, ms)!!
                expect("system", taskRequest.messages.getJSONObject(0).text("role"))
                expect(true, chats.reply(taskRequest, "{\"task_completed\":false}", ms))
                expect("COMPLETED", store.state().list("tasks").single().text("status"))
                expect(2, store.hours().single().bonus)
                check(runCatching { chats.send(taskId, "再次发送", true, ms) }.isFailure)
            }
        }
        test("Streaming SSE parser joins fragments, tolerates malformed blocks, ends on DONE") {
            val payload = """
                : keepalive
                data: malformed
                data: {"choices":[{"delta":{"role":"assistant"}}]}
                data: {"choices":[{"delta":{"content":"放下"}}]}
                data: {"choices":[{"delta":{"content":"手机"}}]}
                data: [DONE]
                data: {"choices":[{"delta":{"content":"ignored"}}]}
            """.trimIndent()
            val updates = mutableListOf<String>()
            expect("放下手机", readSse(payload.reader().buffered(), { false }) { updates += it.toString() })
            expect(listOf("放下", "放下手机"), updates)
        }
        test("All 126 nodes, 50 task entries, quota reset and audio assets") {
            expect(126, catalog.questions.size)
            expect(50, catalog.oneTime.size)
            check(catalog.index.strings().all(catalog.questions::containsKey))
            check(catalog.oneTime.all { catalog.questions.containsKey(it.text("questionName")) })
            isolated { store, _ ->
                val tasks = Tasks(store, catalog)
                val examples = catalog.oneTime.take(6)
                val ms = at("2025-06-15", 23)
                for (task in examples.take(5)) tasks.oneTime(task.text("id"), task.optInt("points"), ms)
                expect(5, tasks.count(store.state(), ms))
                check(runCatching { tasks.oneTime(examples[5].text("id"), 10, ms) }.isFailure)
                tasks.oneTime(examples[5].text("id"), 10, ms + HOUR)
                expect(1, tasks.count(store.state(), ms + HOUR))
            }
            val assets = targetContext.assets
            val tracks = assets.list("audio")!!
            expect(5, tracks.size)
            tracks.forEach { assets.openFd("audio/$it").use { fd -> check(fd.length > 0) } }
        }
        test("Timeline folds same-app segments 1 s apart for display without touching entries") {
            val h = 1_000_000_000L
            fun app(pkg: String, start: Long, ms: Long) = Entry(h, pkg, ms, h + start, false) to pkg
            val bili = "tv.danmaku.bili"
            val source = listOf(
                app(bili, 0, 7_400), app(bili, 8_400, 7_000), app(bili, 15_400, 1_000),   // gaps 1000 and 0 → one row
                app(bili, 17_401, 31_000),                                               // gap 1001 → new row
                app("com.tencent.mm", 48_401, 2_000), app(bili, 50_401, 3_000),          // another app in between
                app(bili, 53_901, 2_000),                                                // gap 500 but a lock at 53_600
                app(bili, 55_401, 4_000))                                                // overlaps by 500 ms
            val runs = mergeAppRuns(source.shuffled(java.util.Random(7)), listOf(h + 53_600))
            expect(listOf(3, 1, 1, 1, 2), runs.map(AppRun::parts))
            expect(listOf(0L, 17_401L, 48_401L, 50_401L, 53_901L), runs.map { it.start - h })
            expect(listOf(15_400L, 31_000L, 2_000L, 3_000L, 5_500L), runs.map(AppRun::duration))
            expect(8, source.size)
        }
        TimeZone.setDefault(previous)
        finish(if (failures == 0) Activity.RESULT_OK else Activity.RESULT_CANCELED, Bundle().apply {
            putString("stream", "\n${results.joinToString("\n")}\n${results.size - failures}/${results.size} passed\n")
        })
    }
    private fun at(date: String, hour: Int) = dateStart(date, hour)
    private fun expect(expected: Any?, actual: Any?) { check(expected == actual) { "Expected $expected, got $actual" } }
    private fun isolated(verification: (Store, String) -> Unit) {
        val name = "contract-${newId()}.db"
        try { Store(targetContext, name).use { verification(it, name) } }
        finally { targetContext.deleteDatabase(name) }
    }
}
