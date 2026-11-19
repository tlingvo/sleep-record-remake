package sleep.record

import android.widget.LinearLayout

private fun MainActivity.choice(parent: LinearLayout, label: String, key: String, values: List<String>, names: List<String> = values, refresh: Boolean = true) {
    val value = config.raw(key)
    val index = values.indexOf(value)
    selector(parent, label, names.getOrNull(index) ?: value, names, index) { i ->
        applySettings(mapOf(key to values[i]), refreshStats = refresh)
    }
}
internal fun MainActivity.renderSettings() {
    title("设置", subtitle = "外观 · 计分 · 本地数据")
    val content = scroller(body)
    val c = config
    val appearance = content.card(gap = 2)
    appearance.label("外观", 16, bold = true)
    choice(appearance, "主题", "darkMode", listOf("0", "1", "2"), listOf("跟随系统", "深色", "浅色"), false)
    switch(appearance, "显示聊天头像", store.state().optBoolean("show_avatars", true)) { show -> store.edit { it.put("show_avatars", show) } }
    val schedules = content.card()
    schedules.label("计分时段", 16, bold = true)
    choice(schedules, "周期长度", "cycleLengthDays", listOf("1", "3", "4", "5"), listOf("1 天", "3 天", "4 天", "5 天"))
    choice(schedules, "每日起始", "dayStartHour", listOf("0", "1", "3", "4", "5"), listOf("00:00", "25:00", "03:00", "04:00", "05:00"))
    choice(schedules, "睡眠开始", "sleepStartHour", listOf("22", "23", "0", "1"), listOf("22:00", "23:00", "00:00", "25:00"))
    choice(schedules, "睡眠结束", "sleepEndHour", listOf("5", "6", "7"), listOf("05:00", "06:00", "07:00"))
    val reminders = content.card()
    reminders.label("通知", 16, bold = true)
    switch(reminders, "显示扣分率", c.flag("showPenaltyNotification")) { applySettings(mapOf("showPenaltyNotification" to it.toString()), refreshStats = false, repaint = false) }
    switch(reminders, "提醒睡觉时间", c.flag("showSleepTimeNotification")) { applySettings(mapOf("showSleepTimeNotification" to it.toString()), refreshStats = false, repaint = false) }
    val chat = content.card()
    chat.label("AI 对话", 16, bold = true)
    choice(chat, "模型", "chatModel", listOf(Config.defaults.getValue("chatModel").toString()), refresh = false)
    entry(chat, "API 密钥", if (c.raw("chatApiKey").isEmpty()) "未设置" else "已设置") {
        inputDialog("API 密钥", c.raw("chatApiKey"), secret = true) { applySettings(mapOf("chatApiKey" to it.trim()), refreshStats = false) }
    }
    val data = content.card()
    data.label("本地数据", 16, bold = true)
    entry(data, "导出备份") {
        confirm("导出备份", "备份包含使用历史、会话内容及 API 密钥。请选择妥善保管的位置。", "选择位置") { saveFile("sleep_score_export.json", "application/json") }
    }
    entry(data, "导入旧版备份") { importFile() }
    val advanced = content.card()
    entry(advanced, "隐藏开发者选项", if (c.flag("summaryMode")) "已隐藏" else "已显示") {
        if (!c.flag("summaryMode")) applySettings(mapOf("summaryMode" to "true"), refreshStats = false)
        else inputDialog("显示开发者选项", hint = "输入 我确认 或 我确定") { value ->
            check(value.trim() in listOf("我确认", "我确定", "1101")) { "确认文字不匹配" }
            applySettings(mapOf("summaryMode" to "false"), refreshStats = false)
        }
    }
    if (!c.flag("summaryMode")) entry(advanced, "开发者选项") { open(Screen("dev")) }
    if (!c.flag("hideDetail")) entry(advanced, "详情") { open(Screen("details")) }
}
private val developerParameters = linkedMapOf(
    "时间参数" to linkedMapOf("dayStartHour" to "每日起始小时", "cycleLengthDays" to "周期天数", "sleepStartHour" to "睡眠开始小时", "sleepEndHour" to "睡眠结束小时"),
    "评分与任务" to linkedMapOf("sleepNoPhonePointPerMinute" to "每空闲分钟基础分", "lateNightMaxPenaltyPerMinute" to "每分钟扣分率上限", "noPhoneThresholdMs" to "无手机时长阈值（兼容）",
        "chatCharsPerPoint" to "聊天每分字数", "chatDailyMaxPoints" to "聊天每日分数上限", "chatCharLimit" to "聊天字符硬上限",
        "dailyTaskBonus" to "早睡建议奖励", "checkinBonus" to "每日打卡奖励", "restBonus" to "长休息奖励", "restDurationMin" to "长休息分钟",
        "restCooldownHour" to "休息冷却小时（兼容）", "earlySleepPenalty" to "早睡挑战接取扣分", "earlySleepReward" to "早睡挑战奖励",
        "earlySleepThreshold" to "早睡挑战扣分阈值", "notificationCheckIntervalSec" to "通知检查间隔（秒）", "defaultTab" to "默认页编号（兼容）"),
    "聊天提供商" to linkedMapOf("chatProviderUrl" to "HTTPS API 基址", "chatModel" to "模型", "chatApiKey" to "API 密钥")
)
internal fun MainActivity.renderDeveloper() {
    val save = mutableMapOf<String, () -> String>()
    title("开发者选项", back = true)
    val content = scroller(body)
    val c = config
    val toggles = content.card()
    val switches = mapOf("showDebugInfo" to "显示统计调试信息", "hideDetail" to "隐藏详情", "usePercentageScoring" to "使用百分比计分", "ignoreDesktopApps" to "忽略桌面应用（兼容）")
    switches.forEach { (key, label) ->
        val field = switch(toggles, label, c.flag(key)) { }
        save[key] = { field.isChecked().toString() }
    }
    val debug = switch(toggles, "显示调试任务", c.flag("showDebugTasks")) { }
    var debugConfirmed = debug.isChecked()
    var changing = false
    debug.onChecked { _, checked ->
        if (changing || !checked || debugConfirmed) return@onChecked
        changing = true; debug.setChecked(false); changing = false
        inputDialog("启用调试任务", secret = true, hint = "输入调试口令") {
            check(it.trim() == "1101") { "调试口令不正确" }
            debugConfirmed = true
            debug.setChecked(true)
        }
    }
    save["showDebugTasks"] = { debug.isChecked().toString() }
    for ((name, fields) in developerParameters) {
        val card = content.card()
        val group = vertical().apply { visibility = android.view.View.GONE }
        entry(card, name, "展开") { group.visibility = if (group.visibility == android.view.View.VISIBLE) android.view.View.GONE else android.view.View.VISIBLE }
        fields.forEach { (key, label) ->
            group.label(label, 13, palette.muted, gap = 9)
            val field = input(c.raw(key), label)
            if (key == "chatApiKey") field.inputType = android.text.InputType.TYPE_CLASS_TEXT or android.text.InputType.TYPE_TEXT_VARIATION_PASSWORD
            if (Config.defaults[key] is Number) field.inputType = android.text.InputType.TYPE_CLASS_NUMBER or android.text.InputType.TYPE_NUMBER_FLAG_SIGNED
            group.addView(field)
            save[key] = { field.text.toString().trim() }
        }
        card.addView(group)
    }
    content.action("恢复默认设置") {
        confirm("恢复默认设置", "将所有设置恢复为初始值。", "恢复") { closePage(); applySettings(emptyMap(), reset = true); toast("已恢复默认设置") }
    }
    if (c.flag("showDebugTasks")) content.action("重置所有任务状态和额外加分") {
        confirm("重置任务状态", "将清除挑战、打卡、休息完成标记和一次性任务完成状态。按旧版行为，已经写入小时记录的加扣分保留。", "重置") {
            tasks.reset(); toast("任务状态已重置"); render()
        }
    }
    val footer = vertical().apply { setPadding(dp(18), 0, dp(18), dp(12)) }
    footer.action("保存并应用", primary = true) { applySettings(save.mapValues { it.value() }); toast("设置已保存") }
    body.addView(footer)
}
internal fun MainActivity.renderDetails() {
    title("详情", back = true)
    val content = scroller(body)
    val card = content.card()
    card.label("按自定义日查询时间轴", 16, bold = true)
    val field = input(config.date(), "yyyy-MM-dd")
    card.addView(field, LinearLayout.LayoutParams(-1, -2).apply { topMargin = dp(9) })
    card.action("查看时间轴", primary = true) {
        val date = field.text.toString().trim()
        dateStart(date)
        open(Screen("timeline", date))
    }
    if (config.flag("showDebugTasks")) {
        section(content, "调试题目入口")
        val all = content.card()
        catalog.index.strings().forEach { key -> entry(all, key) { open(Screen("question", key)) } }
    }
}
