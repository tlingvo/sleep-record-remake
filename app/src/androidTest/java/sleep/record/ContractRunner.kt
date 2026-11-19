package sleep.record

import android.app.Activity
import android.app.Instrumentation
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
            expect(360, cfg.todayMax(at("2025-06-16", 0) + 60000))
            expect(24, Config(json("sleepStartHour" to "5", "sleepEndHour" to "5")).sleepHours)
        }
        test("UsageEvents split at hour, lock replay and overlapping usage") {
            val start = at("2025-06-15", 5)
            val eleven = at("2025-06-15", 23)
            val replay = replay(start, eleven + HOUR, sequenceOf(
                Event(2, "outside", start + 1000), Event(1, "reader", eleven - 30000), Event(16, "android", eleven + 70000)
            ))
            expect(30000L, replay.usage[(eleven - HOUR) / HOUR])
            expect(70000L, replay.usage[eleven / HOUR])
            expect(59 to 1, Config().score(eleven, replay.usage.getValue(eleven / HOUR)))
            expect(Entry(eleven, "屏幕锁定", eleven + 70000, eleven + 70000, true), replay.entries.last())
            val minutes = 60000L
            val overlap = replay(start, eleven + HOUR, sequenceOf(
                Event(1, "a", eleven), Event(1, "b", eleven + 20 * minutes), Event(1, "some.LAUNCHER", eleven + 25 * minutes),
                Event(2, "a", eleven + 40 * minutes)
            ))
            expect(80 * minutes, overlap.usage[eleven / HOUR])
            expect(0 to 60, Config().score(eleven, overlap.usage.getValue(eleven / HOUR)))
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
                expect(listOf(detail), store.entries())
            }
        }
        test("Cycle gaps, consecutive index, percentage beyond 100") {
            val dates = listOf(1, 2, 3, 4, 6, 7).map { "2025-06-0$it" }
            val result = cycles(dates.map { Hour(at(it, 23), 0, 60, 0, 0, 0) }, Config(), at("2025-06-10", 23))
            expect(listOf(0, 1, 2), result.map { it.index })
            expect(listOf(dates.take(3), dates.subList(3, 4), dates.takeLast(2)), result.map { cycle -> cycle.days.map { it.date } })
            val sample = Cycle(0, listOf(Daily(dates.first(), 60, 0, 100, -810, 60)))
            expect(122, sample.percent())
            expect(22, sample.percent(sample.net) - sample.percent(sample.net - 100))
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
                check(error?.message?.contains("hourlyRecords[0].hourStartMs") == true)
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
