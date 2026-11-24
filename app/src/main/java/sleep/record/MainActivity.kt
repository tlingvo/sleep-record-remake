package sleep.record

import android.Manifest
import android.animation.ValueAnimator
import android.annotation.SuppressLint
import android.app.Activity
import android.content.Intent
import android.content.res.Configuration
import android.graphics.Color
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.view.View
import android.view.WindowInsets
import android.view.WindowInsetsController
import android.webkit.WebResourceRequest
import android.webkit.WebResourceResponse
import android.webkit.WebView
import android.webkit.WebViewClient
import android.widget.FrameLayout
import android.widget.Toast
import org.json.JSONArray
import org.json.JSONObject
import java.io.ByteArrayInputStream
import java.io.File
import java.util.concurrent.Executors

class MainActivity : Activity() {
    companion object {
        const val ORIGIN = "https://appassets.androidplatform.net"
        private val night = Color.parseColor("#05060C")
        private val dawn = Color.parseColor("#F6F4F0")
    }
    internal val store by lazy { Store.get(this) }
    internal val catalog by lazy { Catalog(this) }
    internal val tasks by lazy { Tasks(store, catalog) }
    internal val chats by lazy { Chats(store, catalog) }
    internal val player by lazy { Player(this) }
    internal val handler = Handler(Looper.getMainLooper())
    internal val config get() = store.config()
    private val files = Executors.newSingleThreadExecutor()
    private lateinit var shell: FrameLayout
    private lateinit var web: WebView
    private lateinit var bridge: Bridge
    private var pageReady = false
    private var lastInsets = JSONObject()
    @Volatile internal var permitted = false
    private var permissionAsked = false
    private var restLockRequested = false
    private var exportTimelineDate: String? = null

    @SuppressLint("SetJavaScriptEnabled", "JavascriptInterface")
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        permissionAsked = savedInstanceState?.getBoolean("permissionAsked") ?: false
        exportTimelineDate = savedInstanceState?.getString("timelineExport")
        if (Build.VERSION.SDK_INT >= 30) window.setDecorFitsSystemWindows(false)
        @Suppress("DEPRECATION")
        if (Build.VERSION.SDK_INT in 30..34) {
            window.statusBarColor = Color.TRANSPARENT
            window.navigationBarColor = Color.TRANSPARENT
        }
        if (Build.VERSION.SDK_INT >= 29) {
            window.isNavigationBarContrastEnforced = false
            @Suppress("DEPRECATION")
            window.isStatusBarContrastEnforced = false
        }
        permitted = hasUsageAccess(this)
        bridge = Bridge(this)
        shell = FrameLayout(this)
        setContentView(shell)
        applyTheme()
        if (permitted && store.state().optBoolean("notice_shown") && !restRunning()) showLaunchCover()
        if (Build.VERSION.SDK_INT >= 30) shell.setOnApplyWindowInsetsListener { _, insets ->
            val bars = insets.getInsets(WindowInsets.Type.systemBars() or WindowInsets.Type.displayCutout())
            val ime = insets.getInsets(WindowInsets.Type.ime())
            val typing = insets.isVisible(WindowInsets.Type.ime()) && ime.bottom > bars.bottom
            if (::web.isInitialized) (web.layoutParams as FrameLayout.LayoutParams).let { lp ->
                val margin = if (typing) ime.bottom else 0
                if (lp.bottomMargin != margin) { lp.bottomMargin = margin; web.layoutParams = lp }
            }
            val d = resources.displayMetrics.density
            lastInsets = json("top" to bars.top / d, "bottom" to (if (typing) 0f else bars.bottom / d),
                "left" to bars.left / d, "right" to bars.right / d, "ime" to typing)
            emit("insets", lastInsets)
            insets
        }
        // Loading Chromium blocks the main thread for a few hundred ms on a cold process, so let the first
        // frame (cached launch image or window background) reach the screen before creating the WebView.
        shell.post { createWebView() }
    }
    @SuppressLint("SetJavaScriptEnabled", "JavascriptInterface")
    private fun createWebView() {
        if (isDestroyed || ::web.isInitialized) return
        web = WebView(this).apply {
            overScrollMode = View.OVER_SCROLL_NEVER
            isVerticalScrollBarEnabled = false
            isHorizontalScrollBarEnabled = false
            setBackgroundColor(if (isDark()) night else dawn)
            settings.javaScriptEnabled = true
            settings.domStorageEnabled = true
            settings.allowFileAccess = false
            settings.allowContentAccess = false
            settings.setSupportZoom(false)
            settings.builtInZoomControls = false
            settings.mediaPlaybackRequiresUserGesture = true
            settings.textZoom = (resources.configuration.fontScale * 100).toInt().coerceIn(85, 160)
            addJavascriptInterface(bridge, "SleepNative")
            webViewClient = Pages()
            webChromeClient = object : android.webkit.WebChromeClient() {
                override fun onConsoleMessage(message: android.webkit.ConsoleMessage): Boolean {
                    android.util.Log.println(if (message.messageLevel() == android.webkit.ConsoleMessage.MessageLevel.ERROR) android.util.Log.ERROR else android.util.Log.INFO,
                        "SleepWeb", "${message.message()} @${message.sourceId().substringAfterLast('/')}:${message.lineNumber()}")
                    return true
                }
            }
        }
        WebView.setWebContentsDebuggingEnabled(BuildConfig.DEBUG)
        shell.addView(web, 0, FrameLayout.LayoutParams(-1, -1))
        shell.requestApplyInsets()
        android.util.Log.i("SleepLaunch", "webview ${android.os.SystemClock.uptimeMillis() - launchStart} ms")
        web.loadUrl("$ORIGIN/web/index.html")
    }

    // ---- launch cover: last clean stats screen, shown on the first frame and faded out once the page has drawn ----
    private val launchStart = android.os.SystemClock.uptimeMillis()
    private var launchCover: android.widget.ImageView? = null
    private val launchImage get() = File(cacheDir, "launch.jpg")
    private val launchKey get() = File(cacheDir, "launch.key")
    private var lastCapture = 0L
    /** Screen size, theme, font scale and app version: a cached image is only reused when all of them match. */
    private fun coverKey(): String {
        val (w, h) = if (Build.VERSION.SDK_INT >= 30) windowManager.currentWindowMetrics.bounds.let { it.width() to it.height() }
        else android.util.DisplayMetrics().also { @Suppress("DEPRECATION") windowManager.defaultDisplay.getRealMetrics(it) }.let { it.widthPixels to it.heightPixels }
        return "${w}x$h ${if (isDark()) "night" else "dawn"} ${resources.configuration.fontScale} ${BuildConfig.VERSION_CODE}"
    }
    private fun showLaunchCover() {
        val image = runCatching {
            if (!launchImage.isFile || launchKey.readText() != coverKey()) return
            android.graphics.BitmapFactory.decodeFile(launchImage.path)
        }.getOrNull() ?: return
        launchCover = android.widget.ImageView(this).apply {
            scaleType = android.widget.ImageView.ScaleType.FIT_XY
            setImageBitmap(image)
            importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_NO
        }
        shell.addView(launchCover, FrameLayout.LayoutParams(-1, -1))
        handler.postDelayed({ hideLaunchCover() }, 3000)
    }
    internal fun hideLaunchCover() {
        val cover = launchCover ?: return
        launchCover = null
        android.util.Log.i("SleepLaunch", "painted ${android.os.SystemClock.uptimeMillis() - launchStart} ms")
        cover.animate().alpha(0f).setDuration(if (motionEnabled()) 180 else 0).withEndAction {
            shell.removeView(cover)
            cover.setImageDrawable(null)
        }.start()
    }
    /** Copies the window into the launch image. The page only asks for this when the stats tab is at rest. */
    internal fun captureLaunch() {
        if (Build.VERSION.SDK_INT < 26 || launchCover != null || isFinishing || isDestroyed) return
        val now = android.os.SystemClock.uptimeMillis()
        if (now - lastCapture < 5000) return
        val decor = window.decorView
        val key = coverKey()
        if (decor.width <= 0 || !key.startsWith("${decor.width}x${decor.height} ")) return
        lastCapture = now
        val bitmap = android.graphics.Bitmap.createBitmap(decor.width, decor.height, android.graphics.Bitmap.Config.ARGB_8888)
        runCatching {
            android.view.PixelCopy.request(window, bitmap, { result ->
                if (result != android.view.PixelCopy.SUCCESS) { bitmap.recycle(); return@request }
                files.execute {
                    runCatching {
                        val tmp = File(cacheDir, "launch.tmp")
                        tmp.outputStream().use { bitmap.compress(android.graphics.Bitmap.CompressFormat.JPEG, 92, it) }
                        check(tmp.renameTo(launchImage) || (launchImage.delete() && tmp.renameTo(launchImage)))
                        launchKey.writeText(key)
                    }
                    bitmap.recycle()
                }
            }, handler)
        }.onFailure { bitmap.recycle() }
    }
    override fun onPause() {
        super.onPause()
        if (pageReady) web.evaluateJavascript("window.app&&app.capturable&&app.capturable()") { if (it == "true") { lastCapture = 0; captureLaunch() } }
    }
    override fun onSaveInstanceState(outState: Bundle) {
        outState.putBoolean("permissionAsked", permissionAsked)
        outState.putString("timelineExport", exportTimelineDate)
        super.onSaveInstanceState(outState)
    }

    /** Serves bundled pages and saved avatars from one https origin, so ES modules and fonts load normally. */
    private inner class Pages : WebViewClient() {
        override fun shouldInterceptRequest(view: WebView, request: WebResourceRequest): WebResourceResponse? {
            val url = request.url
            if (url.host != "appassets.androidplatform.net") return null
            val path = url.path.orEmpty()
            return runCatching {
                when {
                    path.startsWith("/web/") && ".." !in path -> {
                        val asset = path.removePrefix("/")
                        WebResourceResponse(mime(asset), if (mime(asset).startsWith("text") || asset.endsWith(".js")) "utf-8" else null,
                            assets.open(asset)).apply { responseHeaders = mapOf("Cache-Control" to "no-cache") }
                    }
                    path.startsWith("/avatar/") -> {
                        val name = path.removePrefix("/avatar/")
                        require(name.matches(Regex("[A-Za-z0-9_.-]+")))
                        val dir = File(filesDir, "avatars").canonicalFile
                        val file = File(dir, name).canonicalFile
                        require(file.parentFile == dir && file.isFile)
                        WebResourceResponse("image/png", null, file.inputStream())
                    }
                    else -> missing()
                }
            }.getOrElse { missing() }
        }
        private fun missing() = WebResourceResponse("text/plain", "utf-8", 404, "Not Found", emptyMap(), ByteArrayInputStream(ByteArray(0)))
        private fun mime(path: String) = when (path.substringAfterLast('.').lowercase()) {
            "html" -> "text/html"; "js" -> "text/javascript"; "css" -> "text/css"; "json" -> "application/json"
            "svg" -> "image/svg+xml"; "png" -> "image/png"; "woff2" -> "font/woff2"; "txt" -> "text/plain"
            else -> "application/octet-stream"
        }
        override fun shouldOverrideUrlLoading(view: WebView, request: WebResourceRequest): Boolean {
            if (request.url.host == "appassets.androidplatform.net") return false
            runCatching { startActivity(Intent(Intent.ACTION_VIEW, request.url)) }
            return true
        }
        override fun onPageFinished(view: WebView, url: String) {
            pageReady = true
            android.util.Log.i("SleepLaunch", "page loaded ${android.os.SystemClock.uptimeMillis() - launchStart} ms")
        }
    }

    internal fun resolve(id: Int, result: Result<Any?>) {
        if (isDestroyed || !::web.isInitialized) return
        val ok = result.isSuccess
        val payload = result.fold({ js(it) }, { js(json("message" to (it.message ?: "请重试"))) })
        web.evaluateJavascript("window.__native&&__native.resolve($id,$ok,$payload)", null)
    }
    internal fun emit(type: String, data: Any?) {
        val script = "window.__native&&__native.emit(${JSONObject.quote(type)},${js(data)})"
        handler.post { if (!isDestroyed && ::web.isInitialized) web.evaluateJavascript(script, null) }
    }
    private fun js(value: Any?): String = when (value) {
        null, Unit, JSONObject.NULL -> "null"
        is JSONObject, is JSONArray -> value.toString()
        is String -> JSONObject.quote(value)
        is Boolean, is Int, is Long -> value.toString()
        is Number -> if (value.toDouble().isFinite()) value.toString() else "null"
        else -> JSONObject.quote(value.toString())
    }
    internal fun insets(): JSONObject = lastInsets
    internal fun motionEnabled() = if (Build.VERSION.SDK_INT >= 26) ValueAnimator.areAnimatorsEnabled() else
        android.provider.Settings.Global.getFloat(contentResolver, android.provider.Settings.Global.ANIMATOR_DURATION_SCALE, 1f) > 0f

    // ---- theme ----
    internal fun isDark(): Boolean = when (config.number("darkMode")) {
        1 -> true
        2 -> false
        else -> resources.configuration.uiMode and Configuration.UI_MODE_NIGHT_MASK == Configuration.UI_MODE_NIGHT_YES
    }
    private var appliedNightMode = -1
    internal fun applyTheme() {
        val setting = config.number("darkMode")
        if (Build.VERSION.SDK_INT >= 31) {
            val mode = when (setting) {
                1 -> android.app.UiModeManager.MODE_NIGHT_YES
                2 -> android.app.UiModeManager.MODE_NIGHT_NO
                else -> android.app.UiModeManager.MODE_NIGHT_AUTO
            }
            if (mode != appliedNightMode) {
                appliedNightMode = mode
                runCatching { getSystemService(android.app.UiModeManager::class.java).setApplicationNightMode(mode) }
            }
        }
        val dark = isDark()
        val bg = if (dark) night else dawn
        window.setBackgroundDrawable(android.graphics.drawable.ColorDrawable(bg))
        if (::web.isInitialized) web.setBackgroundColor(bg)
        if (Build.VERSION.SDK_INT >= 30) {
            val light = WindowInsetsController.APPEARANCE_LIGHT_STATUS_BARS or WindowInsetsController.APPEARANCE_LIGHT_NAVIGATION_BARS
            window.insetsController?.setSystemBarsAppearance(if (dark) 0 else light, light)
        } else {
            @Suppress("DEPRECATION")
            window.statusBarColor = if (dark) Color.parseColor("#0F1534") else Color.parseColor("#EEF0FF")
            @Suppress("DEPRECATION")
            window.navigationBarColor = bg
            @Suppress("DEPRECATION")
            if (Build.VERSION.SDK_INT >= 23) window.decorView.systemUiVisibility = if (dark) 0 else View.SYSTEM_UI_FLAG_LIGHT_STATUS_BAR
        }
    }
    override fun onConfigurationChanged(newConfig: Configuration) {
        super.onConfigurationChanged(newConfig)
        if (::web.isInitialized) web.settings.textZoom = (newConfig.fontScale * 100).toInt().coerceIn(85, 160)
        applyTheme()
        emit("theme", isDark())
    }

    // ---- lifecycle ----
    override fun onResume() {
        super.onResume()
        val previous = permitted
        permitted = hasUsageAccess(this)
        syncRestLockTask()
        emit("resume", json("permitted" to permitted, "changed" to (previous != permitted)))
        if (ready() && permitted != previous) enterReady()
    }
    override fun onStop() {
        bridge.cancelStream()
        player.release()
        emit("player.released", null)
        super.onStop()
    }
    override fun onDestroy() {
        bridge.cancelStream()
        player.release()
        bridge.shutdown()
        files.shutdown()
        handler.removeCallbacksAndMessages(null)
        if (::web.isInitialized) web.destroy()
        super.onDestroy()
    }
    private fun restRunning() = tasks.rest()?.end?.let { it > now() } == true
    internal fun syncRestLockTask() {
        if (restRunning()) {
            if (!restLockRequested) runCatching { startLockTask(); restLockRequested = true }
        } else if (restLockRequested) {
            runCatching { stopLockTask() }
            restLockRequested = false
        }
    }
    override fun onUserLeaveHint() {
        super.onUserLeaveHint()
        if (restRunning() && !isFinishing) handler.post {
            if (restRunning() && !isFinishing && !isDestroyed) {
                startActivity(Intent(this, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP or Intent.FLAG_ACTIVITY_REORDER_TO_FRONT))
            }
        }
    }
    private fun ready() = permitted && store.state().optBoolean("notice_shown")
    /** Called by the page once usage access is granted and the first-run notice is done. */
    internal fun enterReady() {
        if (!ready()) return
        SleepService.syncFromActivity(this)
        if (Build.VERSION.SDK_INT >= 33 && !notificationsAllowed(this) && !permissionAsked &&
            !store.state().optBoolean("notification_permission_asked")) {
            permissionAsked = true
            store.edit { it.put("notification_permission_asked", true) }
            requestPermissions(arrayOf(Manifest.permission.POST_NOTIFICATIONS), 11)
        }
    }
    override fun onRequestPermissionsResult(code: Int, permissions: Array<out String>, grantResults: IntArray) {
        super.onRequestPermissionsResult(code, permissions, grantResults)
        SleepService.update()
    }
    @Deprecated("Back is routed through the page's own history")
    override fun onBackPressed() {
        if (!pageReady) { leave(); return }
        web.evaluateJavascript("window.app&&app.back()") { handled -> if (handled != "true") leave() }
    }
    @Suppress("DEPRECATION")
    internal fun leave() { if (!restRunning()) super.onBackPressed() }

    // ---- system pickers ----
    internal fun openUrl(url: String) { startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(url))) }
    internal fun importFile() {
        startActivityForResult(Intent(Intent.ACTION_OPEN_DOCUMENT).setType("application/json").addCategory(Intent.CATEGORY_OPENABLE), 21)
    }
    internal fun saveFile(name: String, type: String, date: String? = null) {
        exportTimelineDate = date
        startActivityForResult(Intent(Intent.ACTION_CREATE_DOCUMENT).setType(type).addCategory(Intent.CATEGORY_OPENABLE).putExtra(Intent.EXTRA_TITLE, name),
            if (type == "application/json") 22 else 23)
    }
    internal fun openPhoto() { startActivityForResult(Intent(Intent.ACTION_OPEN_DOCUMENT).setType("image/*").addCategory(Intent.CATEGORY_OPENABLE), 24) }
    private fun failed(error: Throwable) = emit("error", json("title" to "操作未完成", "message" to (error.message ?: "请重试")))
    @Deprecated("System file picker result")
    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        val uri = data?.data
        if (resultCode != RESULT_OK || uri == null) { if (requestCode == 24) emit("photo", null); return }
        when (requestCode) {
            21 -> files.execute {
                runCatching {
                    val text = contentResolver.openInputStream(uri)?.bufferedReader(Charsets.UTF_8)?.use { it.readText() } ?: error("无法读取文件")
                    Backup.import(store, text)
                }.onSuccess {
                    handler.post {
                        bridge.cancelStream()
                        applyTheme()
                        enterReady()
                        emit("imported", json("invalid" to config.invalidKeys().isNotEmpty()))
                    }
                }.onFailure(::failed)
            }
            22, 23 -> {
                val date = exportTimelineDate
                files.execute {
                    runCatching {
                        val text = if (requestCode == 22) Backup.export(store)
                        else timelineData(store, packageManager, date ?: error("请重新打开时间轴导出")).export()
                        contentResolver.openOutputStream(uri, "wt")?.bufferedWriter(Charsets.UTF_8)?.use { it.write(text) } ?: error("无法写入文件")
                    }.onSuccess { emit("toast", "导出完成") }.onFailure(::failed)
                }
            }
            24 -> bridge.acceptPhoto(uri)
        }
    }
    internal fun toast(message: String) { Toast.makeText(this, message, Toast.LENGTH_SHORT).show() }
}
