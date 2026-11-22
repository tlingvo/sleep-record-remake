package sleep.record

import android.Manifest
import android.app.Activity
import android.content.Intent
import android.content.res.Configuration
import android.graphics.Color
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.provider.Settings
import android.view.View
import android.view.WindowInsets
import android.view.WindowInsetsController
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.Toast
import java.util.concurrent.Executors

internal data class Screen(val type: String, val arg: String = "")
class MainActivity : Activity() {
    internal val store by lazy { Store.get(this) }
    internal val catalog by lazy { Catalog(this) }
    internal val tasks by lazy { Tasks(store, catalog) }
    internal val chats by lazy { Chats(store, catalog) }
    internal val chat by lazy { ChatUi(this) }
    internal val player by lazy { PlayerUi(this) }
    internal val handler = Handler(Looper.getMainLooper())
    private val io = Executors.newSingleThreadExecutor()
    internal lateinit var root: FrameLayout
    internal lateinit var body: LinearLayout
    internal lateinit var palette: Palette
    private lateinit var shell: FrameLayout
    private lateinit var scene: SceneHost
    private lateinit var floating: OverlayHost
    internal var keyboardVisible = false
        private set
    internal var overlayPage: Screen? = null
        private set
    private val overlayBack = ArrayDeque<Screen>()
    private var renderedKey = ""
    private var lastScroll: ScrollView? = null
    private val scrollOffsets = mutableMapOf<String, Int>()
    private var navView: View? = null
    private var navSignature: Pair<Int, Palette>? = null
    internal var screen: Screen? = null
    private val back = ArrayDeque<Screen?>()
    internal var tab = 0
    internal var taskTab = 0
    internal var questionSelection = -1
    internal var notice = false
    internal var archived = false
    internal var oneTimeExpanded = false
    internal var earlyArchive = false
    internal var timelineCollapsed = mutableSetOf<Long>()
    internal var rest: Rest? = null
    internal var restResult: Rest? = null
    internal var renderTick: Runnable? = null
    internal var refreshing = false
    private var restLockRequested = false
    private var navBackdropUntil = 0L
    private val navBackdropPulse = object : Runnable {
        override fun run() {
            invalidateNavBackdrop()
            if (SystemClock.uptimeMillis() < navBackdropUntil) handler.postDelayed(this, 16)
        }
    }
    private var permitted = false
    private var permissionAsked = false
    private var checkedUsage = false
    private var exportText: String? = null
    private var exportTimelineDate: String? = null
    private var importVisible = false
    internal val config get() = store.config()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        window.setSoftInputMode(android.view.WindowManager.LayoutParams.SOFT_INPUT_ADJUST_RESIZE)
        if (Build.VERSION.SDK_INT >= 30) window.setDecorFitsSystemWindows(false)
        @Suppress("DEPRECATION")
        if (Build.VERSION.SDK_INT < 35) {
            window.statusBarColor = Color.TRANSPARENT
            if (Build.VERSION.SDK_INT >= 30) window.navigationBarColor = Color.TRANSPARENT
        }
        tab = savedInstanceState?.getInt("tab") ?: 0
        permissionAsked = savedInstanceState?.getBoolean("permissionAsked") ?: false
        exportTimelineDate = savedInstanceState?.getString("timelineExport")
        if (!store.state().optBoolean("notice_shown")) {
            screen = Screen("question", "用户须知main")
            notice = true
        }
        if (!notice) tasks.rest()?.takeIf { it.end > now() }?.let {
            rest = it.copy(seconds = ((it.end - now() + 999) / 1000).coerceAtLeast(0))
            screen = Screen("rest")
        }
        render()
    }
    override fun onSaveInstanceState(outState: Bundle) {
        outState.putInt("tab", tab)
        outState.putBoolean("permissionAsked", permissionAsked)
        outState.putString("timelineExport", exportTimelineDate)
        super.onSaveInstanceState(outState)
    }
    private fun activeType() = overlayPage?.type ?: screen?.type
    private fun restRunning() = tasks.rest()?.end?.let { it > now() } == true
    internal fun syncRestLockTask() {
        if (restRunning()) {
            if (!restLockRequested) runCatching { startLockTask(); restLockRequested = true }
        } else if (restLockRequested) {
            runCatching { stopLockTask() }
            restLockRequested = false
        }
    }
    override fun onResume() {
        super.onResume()
        val previous = permitted
        permitted = hasUsageAccess(this)
        val returned = !checkedUsage || previous != permitted
        checkedUsage = true
        if (returned) render()
        syncRestLockTask()
        enterReadyState(returned)
        if (activeType() in listOf("rest", "player")) renderScene()
    }
    private fun enterReadyState(startService: Boolean) {
        if (ready()) {
            if (screen == null && tab == 0 && !importVisible) refresh(false)
            if (startService) SleepService.syncFromActivity(this) else SleepService.update()
            if (Build.VERSION.SDK_INT >= 33 && !notificationsAllowed(this) && !permissionAsked &&
                !store.state().optBoolean("notification_permission_asked")) {
                permissionAsked = true
                store.edit { it.put("notification_permission_asked", true) }
                requestPermissions(arrayOf(Manifest.permission.POST_NOTIFICATIONS), 11)
            }
        }
    }
    override fun onRequestPermissionsResult(code: Int, permissions: Array<out String>, grantResults: IntArray) {
        super.onRequestPermissionsResult(code, permissions, grantResults)
        SleepService.update()
    }
    override fun onStop() {
        renderTick?.let(handler::removeCallbacks)
        if (activeType() == "player") player.release()
        chat.cancel()
        super.onStop()
    }
    override fun onUserLeaveHint() {
        super.onUserLeaveHint()
        if (restRunning() && !isFinishing) handler.post {
            if (restRunning() && !isFinishing && !isDestroyed) {
                startActivity(Intent(this, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP or Intent.FLAG_ACTIVITY_REORDER_TO_FRONT))
            }
        }
    }
    override fun onDestroy() {
        chat.cancel()
        player.release()
        handler.removeCallbacksAndMessages(null)
        io.shutdown()
        super.onDestroy()
    }
    override fun onConfigurationChanged(newConfig: Configuration) {
        super.onConfigurationChanged(newConfig)
        savedScroll()
        floating.hide(false)
        renderScene()
    }

    private val sheets = setOf("chat-drawer", "chat-settings", "avatar", "audio", "player", "dev", "details")
    internal fun open(next: Screen) {
        if (restRunning()) { toast("休息计时结束前请留在此页面，可以关闭屏幕"); return }
        hideKeyboard()
        if (next.type in sheets) {
            val previous = overlayPage
            if (previous == next) return
            previous?.let(overlayBack::addLast)
            overlayPage = next
            renderOverlay(fresh = previous == null)
            return
        }
        dismissOverlays(false)
        if (screen == null && tab == 1) chat.cancel()
        back.addLast(screen ?: Screen("root"))
        screen = next
        if (next.type == "question") questionSelection = -1
        if (next.type == "timeline") timelineCollapsed.clear()
        renderScene(Motion.PUSH)
    }
    private fun dismissOverlays(animate: Boolean) {
        if (overlayPage == null) return
        if (activeType() == "player") player.release()
        if (activeType() == "avatar") chat.discardPhoto()
        overlayBack.clear()
        overlayPage = null
        floating.hide(animate)
    }
    internal fun closePage() {
        if (screen?.type == "rest" && restRunning()) { toast("休息计时结束前请留在此页面，可以关闭屏幕"); return }
        hideKeyboard()
        if (overlayPage != null) {
            val closing = activeType()
            if (closing == "player") player.release()
            if (closing == "avatar") chat.discardPhoto()
            val previous = overlayBack.removeLastOrNull()
            overlayPage = previous
            if (previous != null) renderOverlay() else {
                floating.hide()
                if (closing == "chat-settings" || closing == "dev") renderScene()
            }
            return
        }
        val wasReady = ready()
        if (notice) {
            notice = false
            store.edit { it.put("notice_shown", true) }
        }
        screen = back.removeLastOrNull()?.takeUnless { it.type == "root" }
        renderScene(Motion.POP) { enterReadyState(startService = !wasReady) }
    }
    internal fun selectTab(index: Int) {
        if (restRunning()) { toast("休息计时结束前请留在此页面，可以关闭屏幕"); return }
        if (index !in 0..3) return
        hideKeyboard()
        val previous = tab
        val inStack = screen != null
        dismissOverlays(true)
        if (tab == 1 && index != 1) chat.cancel(repaint = false)
        back.clear()
        screen = null
        tab = index
        val direction = when {
            index > previous -> Motion.LEFT
            index < previous -> Motion.RIGHT
            inStack -> Motion.POP
            else -> Motion.NONE
        }
        renderScene(direction) {
            if (index == 0 && tab == 0 && screen == null) { importVisible = false; refresh(false) }
        }
        if (ready()) SleepService.syncFromActivity(this)
    }
    internal fun selectTaskSection(index: Int) {
        if (taskTab == index) return
        val before = taskTab
        taskTab = index
        renderScene(if (index > before) Motion.SECTION_LEFT else Motion.SECTION_RIGHT)
    }
    internal fun nextQuestion(next: String) {
        if (screen?.type != "question" || catalog.questions[next] == null) return
        questionSelection = -1
        screen = Screen("question", next)
        renderScene(Motion.QUIZ)
    }
    internal fun scrollTarget(view: ScrollView, parent: LinearLayout) {
        if (!renderingOverlay && parent === body && !(screen == null && tab == 1)) lastScroll = view
    }
    private fun savedScroll() {
        lastScroll?.let { scrollOffsets[renderedKey] = it.scrollY }
    }
    private fun hideKeyboard() {
        currentFocus?.let { focused ->
            getSystemService(android.view.inputmethod.InputMethodManager::class.java).hideSoftInputFromWindow(focused.windowToken, 0)
            focused.clearFocus()
        }
    }
    @Deprecated("Used for compatibility with the in-app page stack")
    override fun onBackPressed() {
        if (restRunning()) { toast("休息计时结束前请留在此页面，可以关闭屏幕"); return }
        if (overlayPage != null) { closePage(); return }
        if (screen?.type == "question") return
        if (screen != null) closePage() else if (tab != 0) selectTab(0) else super.onBackPressed()
    }
    internal fun ready() = permitted && !notice
    internal fun inChat() = tab == 1 && screen == null
    internal fun isShowing(type: String) = activeType() == type

    private fun mount() {
        shell = FrameLayout(this)
        root = FrameLayout(this).apply { isFocusableInTouchMode = true }
        shell.addView(root, FrameLayout.LayoutParams(-1, -1))
        scene = SceneHost(this)
        root.addView(scene, FrameLayout.LayoutParams(-1, -1))
        floating = OverlayHost(this)
        shell.addView(floating, FrameLayout.LayoutParams(-1, -1))
        setContentView(shell)
        if (Build.VERSION.SDK_INT >= 30) {
            shell.setOnApplyWindowInsetsListener { view, insets ->
                val safe = insets.getInsets(WindowInsets.Type.systemBars() or WindowInsets.Type.displayCutout())
                val ime = insets.getInsets(WindowInsets.Type.ime())
                keyboardVisible = insets.isVisible(WindowInsets.Type.ime())
                updateNavVisibility()
                view.setPadding(safe.left, safe.top, safe.right, maxOf(ime.bottom, safe.bottom))
                refreshNavBackdropDuring(180)
                insets
            }
            shell.requestApplyInsets()
        }
    }
    private fun updateNavVisibility() {
        navView?.visibility = if (screen == null && ready()) View.VISIBLE else View.GONE
    }
    internal fun chatComposerBottomInset() = dp(82)
    internal val navBackdropSource: View get() = scene
    internal fun invalidateNavBackdrop() { (navView as? NavDock)?.refreshBackdrop() }
    internal fun refreshNavBackdropDuring(durationMs: Long = 420) {
        navBackdropUntil = SystemClock.uptimeMillis() + durationMs
        handler.removeCallbacks(navBackdropPulse)
        navBackdropPulse.run()
    }
    internal fun repaintChat() = renderScene()
    internal fun render() = if (overlayPage == null) renderScene() else renderOverlay()
    private fun renderScene(motion: Motion = Motion.NONE, after: (() -> Unit)? = null) {
        renderTick?.let(handler::removeCallbacks)
        renderTick = null
        if (!::shell.isInitialized) mount()
        val newPalette = Palette.forMode(config.number("darkMode"), resources.configuration)
        val restyleOverlay = ::palette.isInitialized && newPalette != palette
        palette = newPalette
        shell.setBackgroundColor(palette.bg)
        if (Build.VERSION.SDK_INT >= 30) {
            window.insetsController?.setSystemBarsAppearance(
                if (palette.dark) 0 else WindowInsetsController.APPEARANCE_LIGHT_STATUS_BARS or WindowInsetsController.APPEARANCE_LIGHT_NAVIGATION_BARS,
                WindowInsetsController.APPEARANCE_LIGHT_STATUS_BARS or WindowInsetsController.APPEARANCE_LIGHT_NAVIGATION_BARS)
        } else {
            @Suppress("DEPRECATION")
            window.decorView.systemUiVisibility = if (palette.dark) 0 else View.SYSTEM_UI_FLAG_LIGHT_STATUS_BAR
            @Suppress("DEPRECATION")
            window.statusBarColor = palette.bg
        }
        savedScroll()
        lastScroll = null
        val page = screen
        renderedKey = if (page != null) "${page.type}:${page.arg}" else if (tab == 2) "tab:$tab:$taskTab" else "tab:$tab"
        // Quiz navigation always starts at the top, even when a branch returns to a visited question.
        if (page?.type == "question" && motion != Motion.NONE) scrollOffsets.remove(renderedKey)
        body = vertical().apply { setBackgroundColor(palette.bg) }
        when {
            page?.type == "question" -> renderQuestion(page.arg)
            !ready() -> usageGate()
            page?.type == "timeline" -> renderTimeline(page.arg)
            page?.type == "rest" -> renderRest()
            page != null -> title("找不到页面", back = true)
            tab == 0 -> renderStats()
            tab == 1 -> chat.show()
            tab == 2 -> renderTasks()
            else -> renderSettings()
        }
        lastScroll?.let { scroll -> scrollOffsets[renderedKey]?.let { y -> scroll.post { scroll.scrollTo(0, y) } } }
        scene.show(body, motion, after)
        handler.postDelayed({ invalidateNavBackdrop() }, if (motion == Motion.NONE) 40L else 380L)
        if (ready() && page == null && navSignature != (tab to palette)) {
            val previous = navView
            val next = navigation(previous as? NavDock)
            previous?.let(shell::removeView)
            navView = next
            navSignature = tab to palette
            shell.addView(next, 1, FrameLayout.LayoutParams(-1, dp(79), android.view.Gravity.BOTTOM))
        }
        updateNavVisibility()
        if (overlayPage != null) {
            if (restyleOverlay) floating.hide(false)
            renderOverlay()
        }
    }
    private var renderingOverlay = false
    private fun renderOverlay(fresh: Boolean = false) {
        val page = overlayPage ?: return
        renderTick?.let(handler::removeCallbacks)
        renderTick = null
        val beneath = body
        val panel = vertical().apply { isFocusableInTouchMode = true }
        body = panel
        renderingOverlay = true
        try {
            if (page.type != "chat-drawer") body.addView(vertical().apply {
                gravity = android.view.Gravity.CENTER
                addView(View(this@MainActivity).apply { background = shape(palette.stroke, 10) }, LinearLayout.LayoutParams(dp(36), dp(4)))
            }, LinearLayout.LayoutParams(-1, dp(17)))
            when (page.type) {
                "chat-drawer" -> chat.drawer()
                "chat-settings" -> chat.settings()
                "avatar" -> chat.crop()
                "audio" -> player.list()
                "player" -> player.show(page.arg)
                "dev" -> renderDeveloper()
                "details" -> renderDetails()
            }
        } finally {
            body = beneath
            renderingOverlay = false
        }
        floating.show(panel, page, fresh)
    }
    private fun usageGate() {
        title("使用情况访问")
        val content = scroller(body)
        val card = content.card()
        card.addView(MoonArt(this), LinearLayout.LayoutParams(-1, dp(170)))
        card.label("授权后开始记录", 26, bold = true)
        card.label("睡时记录通过 Android 的使用事件计算夜间用量。应用使用历史保存在此设备。", 15, palette.muted, gap = 12)
        card.action("打开使用情况访问设置", primary = true) {
            runCatching { startActivity(Intent(Settings.ACTION_USAGE_ACCESS_SETTINGS)) }.onFailure { toast("无法打开系统设置") }
        }
        card.action("已授权，重新检查") {
            permitted = hasUsageAccess(this)
            render()
            if (ready()) enterReadyState(startService = true) else toast("请在系统设置中允许睡时记录访问使用情况")
        }
    }
    internal fun refresh(manual: Boolean) {
        if (!ready() || refreshing) return
        val elapsed = now() - store.state().optLong("last_refresh_time")
        if (!manual && elapsed in 0..600000) return
        refreshing = true
        if (screen == null && tab == 0 && overlayPage == null) renderScene()
        background({ store.refresh(applicationContext, !manual) }) {
            refreshing = false
            it.onFailure { error -> showError(error) }
            if (screen == null && overlayPage == null && tab in listOf(0, 2)) renderScene()
        }
    }
    internal fun applySettings(settings: Map<String, String>, reset: Boolean = false, repaint: Boolean = true) {
        val backfill = store.adjustSettings(settings, reset)
        val invalid = config.invalidKeys()
        if (invalid.isNotEmpty()) toast("部分参数已使用默认值：${invalid.joinToString()}")
        SleepService.syncFromActivity(this)
        if (repaint) render()
        if (backfill) refresh(false)
    }
    internal fun <T> background(work: () -> T, complete: (Result<T>) -> Unit) {
        io.execute {
            val result = runCatching(work)
            handler.post { if (!isDestroyed && !isFinishing) complete(result) }
        }
    }
    internal fun attempt(block: () -> Unit) { runCatching(block).onFailure(::showError) }
    internal fun showError(error: Throwable) = alert("操作未完成", error.message ?: "请重试")
    internal fun toast(message: String) { Toast.makeText(this, message, Toast.LENGTH_SHORT).show() }
    internal fun alert(title: String, message: String) { panelDialog(title, message) }
    internal fun confirm(title: String, message: String, confirm: String = "确认", onYes: () -> Unit) {
        panelDialog(title, message, confirm, "取消", positiveAction = onYes)
    }
    internal fun importFile() {
        startActivityForResult(Intent(Intent.ACTION_OPEN_DOCUMENT).setType("application/json").addCategory(Intent.CATEGORY_OPENABLE), 21)
    }
    internal fun saveFile(name: String, type: String, text: String? = null, date: String? = null) {
        exportText = text
        exportTimelineDate = date
        startActivityForResult(Intent(Intent.ACTION_CREATE_DOCUMENT).setType(type).addCategory(Intent.CATEGORY_OPENABLE).putExtra(Intent.EXTRA_TITLE, name), if (type == "application/json") 22 else 23)
    }
    internal fun openPhoto() { startActivityForResult(Intent(Intent.ACTION_OPEN_DOCUMENT).setType("image/*").addCategory(Intent.CATEGORY_OPENABLE), 24) }
    @Deprecated("System file picker result")
    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        val uri = data?.data ?: return
        if (resultCode != RESULT_OK) return
        when (requestCode) {
            21 -> background({
                val text = contentResolver.openInputStream(uri)?.bufferedReader(Charsets.UTF_8)?.use { it.readText() } ?: error("无法读取文件")
                Backup.import(store, text)
            }) {
                it.onSuccess {
                    importVisible = true
                    chat.reload()
                    dismissOverlays(false)
                    screen = null
                    back.clear()
                    tab = 0
                    render()
                    enterReadyState(startService = true)
                    if (config.invalidKeys().isNotEmpty()) toast("导入完成，部分评分参数已使用默认值")
                    else toast("旧版备份已导入")
                }.onFailure(::showError)
            }
            22, 23 -> {
                val savedText = exportText
                val date = exportTimelineDate
                background({
                    val text = if (requestCode == 22) Backup.export(store) else savedText ?: date?.let { timelineData(it).export() } ?: error("请重新打开时间轴导出")
                    contentResolver.openOutputStream(uri, "wt")?.bufferedWriter(Charsets.UTF_8)?.use { it.write(text) } ?: error("无法写入文件")
                }) { it.onSuccess { toast("导出完成") }.onFailure(::showError) }
            }
            24 -> chat.acceptPhoto(uri)
        }
    }
    internal fun openUrl(url: String) { startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(url))) }
}
