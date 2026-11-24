package sleep.record

import android.animation.ValueAnimator
import android.content.Context
import android.graphics.Color
import android.os.Build
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import android.view.ViewConfiguration
import android.view.ViewGroup
import android.view.accessibility.AccessibilityEvent
import android.view.animation.PathInterpolator
import android.widget.FrameLayout
import kotlin.math.abs
import kotlin.math.min

internal val settle = PathInterpolator(.20f, .75f, .18f, 1f)
internal val closeEase = PathInterpolator(.40f, 0f, .75f, .35f)
internal enum class Motion { NONE, LEFT, RIGHT, PUSH, POP, QUIZ, SECTION_LEFT, SECTION_RIGHT }
internal fun Context.motionEnabled() = if (Build.VERSION.SDK_INT >= 26) ValueAnimator.areAnimatorsEnabled() else
    android.provider.Settings.Global.getFloat(contentResolver, android.provider.Settings.Global.ANIMATOR_DURATION_SCALE, 1f) > 0f

/** Keeps the outgoing live view only for the transition; chrome and overlays stay mounted. */
internal class SceneHost(private val a: MainActivity) : FrameLayout(a) {
    private var visiblePage: View? = null
    private var transitionId = 0
    private val taskSlop = ViewConfiguration.get(a).scaledTouchSlop
    private var startX = 0f
    private var startY = 0f
    private var tracking = false
    private var dragging = false
    private var edge = false
    private var blocked = false
    private var moving = false

    init { clipChildren = true; clipToPadding = true }
    fun show(next: View, motion: Motion, onSettled: (() -> Unit)? = null) {
        val id = ++transitionId
        moving = false
        val old = visiblePage
        for (i in childCount - 1 downTo 0) {
            val child = getChildAt(i)
            child.animate().cancel()
            if (child !== old) removeViewAt(i)
        }
        val animated = motion != Motion.NONE && old != null && width > 0 && a.motionEnabled()
        if (!animated) removeAllViews()
        visiblePage = next
        addView(next, LayoutParams(-1, -1))
        if (!animated) { a.invalidateNavBackdrop(); onSettled?.invoke(); return }
        old!!.importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_NO_HIDE_DESCENDANTS
        val sign = if (motion == Motion.RIGHT || motion == Motion.POP || motion == Motion.SECTION_RIGHT) -1 else 1
        val time = when (motion) { Motion.QUIZ -> 245L; Motion.SECTION_LEFT, Motion.SECTION_RIGHT -> 235L; else -> 335L }
        val fraction = when (motion) {
            Motion.LEFT, Motion.RIGHT -> 1f
            Motion.PUSH, Motion.POP -> .24f
            Motion.SECTION_LEFT, Motion.SECTION_RIGHT -> .16f
            else -> .06f
        }
        val distance = width * fraction
        next.translationX = when {
            fraction == 1f -> sign * distance + old.translationX
            motion == Motion.QUIZ -> 0f
            else -> sign * distance
        }
        next.translationY = if (motion == Motion.QUIZ) a.dp(14).toFloat() else 0f
        next.alpha = if (motion == Motion.LEFT || motion == Motion.RIGHT) 1f else .06f
        moving = true
        next.animate().translationX(0f).translationY(0f).alpha(1f).setDuration(time).setInterpolator(settle)
            .setUpdateListener { a.invalidateNavBackdrop() }.withEndAction {
            if (id == transitionId) {
                removeView(old)
                moving = false
                a.invalidateNavBackdrop()
                onSettled?.invoke()
            }
        }.start()
        old.animate().translationX(if (motion == Motion.QUIZ) 0f else -sign * distance)
            .translationY(if (motion == Motion.QUIZ) -a.dp(8).toFloat() else 0f)
            .alpha(if (fraction == 1f) 1f else 0f).setDuration(time).setInterpolator(settle).start()
    }
    private fun supportsSwipe() = a.ready() && a.screen == null && a.overlayPage == null && !a.keyboardVisible
    override fun onInterceptTouchEvent(e: MotionEvent): Boolean {
        if (moving) return true
        if (!supportsSwipe()) return false
        when (e.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                startX = e.x; startY = e.y; tracking = true; dragging = false
                edge = a.tab == 1
                blocked = a.tab == 1 && hitHorizontalChild(this, e.rawX, e.rawY)
            }
            MotionEvent.ACTION_MOVE -> if (tracking && !blocked) {
                val dx = e.x - startX; val dy = e.y - startY
                if (abs(dy) > taskSlop && abs(dy) >= abs(dx)) tracking = false
                else if (abs(dx) > taskSlop * 1.6f && abs(dx) > abs(dy) * 1.4f && canSlide(dx)) {
                    dragging = true
                    return true
                }
            }
            MotionEvent.ACTION_CANCEL, MotionEvent.ACTION_UP -> tracking = false
        }
        return false
    }
    private fun canSlide(dx: Float) = (dx < 0 && a.tab < 3) || (dx > 0 && (edge || a.tab > 0))
    override fun onTouchEvent(e: MotionEvent): Boolean {
        if (moving) return true
        if (!dragging) return super.onTouchEvent(e)
        val dx = e.x - startX
        when (e.actionMasked) {
            MotionEvent.ACTION_MOVE -> {
                visiblePage?.translationX = if (a.motionEnabled()) (dx * .28f).coerceIn(-a.dp(52).toFloat(), a.dp(52).toFloat()) else 0f
                a.invalidateNavBackdrop()
            }
            MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                dragging = false; tracking = false
                if (e.actionMasked == MotionEvent.ACTION_UP && abs(dx) > min(a.dp(78).toFloat(), width * .19f) && canSlide(dx)) {
                    if (edge && dx > 0) a.open(Screen("chat-drawer")) else a.selectTab(a.tab + if (dx < 0) 1 else -1)
                }
                visiblePage?.takeIf { !moving }?.animate()?.translationX(0f)?.setDuration(180L)?.setInterpolator(settle)?.start()
            }
        }
        return true
    }
    private fun hitHorizontalChild(group: ViewGroup, x: Float, y: Float): Boolean {
        val loc = IntArray(2)
        for (i in group.childCount - 1 downTo 0) {
            val v = group.getChildAt(i)
            if (v.visibility != View.VISIBLE) continue
            v.getLocationOnScreen(loc)
            if (x < loc[0] || y < loc[1] || x >= loc[0] + v.width || y >= loc[1] + v.height) continue
            if (v is android.widget.HorizontalScrollView || v is android.widget.EditText) return true
            if (v is ViewGroup && hitHorizontalChild(v, x, y)) return true
        }
        return false
    }
}

internal class OverlayHost(private val a: MainActivity) : FrameLayout(a) {
    private var panel: FrameLayout? = null
    private var scrim: View? = null
    private var drawer = false
    private var variant = ""
    private var sequence = 0

    init { visibility = GONE }
    fun show(view: View, page: Screen, fresh: Boolean) {
        val id = ++sequence
        val side = page.type == "chat-drawer"
        val changeShell = panel == null || side != drawer
        val oldType = variant
        variant = page.type
        drawer = side
        val active: FrameLayout
        if (changeShell) {
            removeAllViews()
            val shade = View(a).apply {
                setBackgroundColor(Color.BLACK)
                alpha = if (fresh && a.motionEnabled()) 0f else .55f
                contentDescription = "关闭浮层"
                setOnClickListener { a.closePage() }
                isFocusable = true
            }
            scrim = shade
            addView(shade, LayoutParams(-1, -1))
            active = Surface(a, side)
            panel = active
            val width = a.root.width.takeIf { it > 0 } ?: a.resources.displayMetrics.widthPixels
            val params = if (side) LayoutParams(min((width * .87f).toInt(), a.dp(378)), -1, Gravity.LEFT)
            else LayoutParams(min(width, a.dp(600)), sheetHeight(a.root.height.takeIf { it > 0 } ?: a.resources.displayMetrics.heightPixels), Gravity.BOTTOM or Gravity.CENTER_HORIZONTAL)
            addView(active, params)
            visibility = VISIBLE
            if (fresh && a.motionEnabled()) {
                if (side) active.translationX = -params.width.toFloat() else active.translationY = params.height.toFloat()
                active.animate().translationX(0f).translationY(0f).setDuration(if (side) 335 else 355).setInterpolator(settle).start()
                shade.animate().alpha(.55f).setDuration(310).setInterpolator(settle).start()
            }
        } else {
            active = panel!!
            active.animate().cancel()
            scrim?.animate()?.cancel()
            active.translationX = 0f
            active.translationY = 0f
            scrim?.alpha = .55f
        }
        active.background = a.shape(a.palette.surface, 28, a.palette.outline).apply {
            val r = a.dp(28).toFloat()
            cornerRadii = if (side) floatArrayOf(0f,0f,r,r,r,r,0f,0f) else floatArrayOf(r,r,r,r,0f,0f,0f,0f)
        }
        active.removeAllViews()
        active.addView(view, LayoutParams(-1, -1))
        if (!side) active.layoutParams = (active.layoutParams as LayoutParams).apply {
            height = sheetHeight(a.root.height.takeIf { it > 0 } ?: a.resources.displayMetrics.heightPixels)
        }
        if (!side && changeShell.not() && oldType != page.type && a.motionEnabled()) {
            view.translationY = a.dp(12).toFloat(); view.alpha = 0f
            view.animate().translationY(0f).alpha(1f).setDuration(230).setInterpolator(settle).start()
        }
        a.root.importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_NO_HIDE_DESCENDANTS
        if (fresh) post { if (sequence == id) active.sendAccessibilityEvent(AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED) }
    }
    private fun sheetHeight(available: Int): Int = when {
        a.keyboardVisible -> available
        variant == "chat-settings" -> available * 96 / 100
        else -> min(available * 86 / 100, a.dp(690))
    }
    /**
     * Sizes the sheet inside the same measure pass that delivers the new height. Updating it from
     * onSizeChanged lands one layout late, so when the keyboard opens the old, taller sheet is laid
     * out bottom-aligned in the shrunken space and its top (title, input field) slides off screen.
     */
    override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
        val w = MeasureSpec.getSize(widthMeasureSpec) - paddingLeft - paddingRight
        val h = MeasureSpec.getSize(heightMeasureSpec) - paddingTop - paddingBottom
        val params = panel?.layoutParams as? LayoutParams
        if (params != null && w > 0 && h > 0) {
            params.width = if (drawer) min((w * .87f).toInt(), a.dp(378)) else min(w, a.dp(600))
            params.height = if (drawer) -1 else sheetHeight(h)
        }
        super.onMeasure(widthMeasureSpec, heightMeasureSpec)
    }
    fun hide(animated: Boolean = true) {
        val active = panel ?: return
        val id = ++sequence
        val hideNow = {
            if (sequence == id) {
                removeAllViews(); panel = null; scrim = null; visibility = GONE; variant = ""
                a.root.importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_AUTO
            }
        }
        if (!animated || !a.motionEnabled()) { active.animate().cancel(); hideNow(); return }
        val side = drawer
        scrim?.animate()?.alpha(0f)?.setDuration(210)?.start()
        active.animate().translationX(if (side) -active.width.toFloat() else 0f)
            .translationY(if (side) 0f else active.height.toFloat()).setDuration(250).setInterpolator(closeEase)
            .withEndAction { hideNow() }.start()
    }
    private inner class Surface(context: Context, private val side: Boolean) : FrameLayout(context) {
        private var downX = 0f; private var downY = 0f; private var drag = false
        init { clipToOutline = true; elevation = a.dp(16).toFloat(); isClickable = true }
        override fun onInterceptTouchEvent(e: MotionEvent): Boolean {
            if (!side) return false
            when (e.actionMasked) {
                MotionEvent.ACTION_DOWN -> { downX = e.rawX; downY = e.rawY; drag = false }
                MotionEvent.ACTION_MOVE -> if (e.rawX - downX < -a.dp(22) && downX - e.rawX > abs(e.rawY - downY) * 1.35f) {
                    drag = true; return true
                }
            }
            return false
        }
        override fun onTouchEvent(e: MotionEvent): Boolean {
            if (!drag) return super.onTouchEvent(e)
            val distance = (downX - e.rawX).coerceIn(0f, width.toFloat())
            when (e.actionMasked) {
                MotionEvent.ACTION_MOVE -> if (a.motionEnabled()) { translationX = -distance; scrim?.alpha = .55f * (1f - distance / width.coerceAtLeast(1)) }
                MotionEvent.ACTION_CANCEL, MotionEvent.ACTION_UP -> {
                    drag = false
                    if (e.actionMasked == MotionEvent.ACTION_UP && distance > a.dp(68)) a.closePage() else {
                        animate().translationX(0f).setDuration(190L).setInterpolator(settle).start()
                        scrim?.animate()?.alpha(.55f)?.setDuration(190L)?.start()
                    }
                }
            }
            return true
        }
    }
}
