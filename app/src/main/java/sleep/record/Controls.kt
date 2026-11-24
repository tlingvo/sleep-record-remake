package sleep.record

import android.animation.ValueAnimator
import android.app.Dialog
import android.content.Context
import android.os.Build
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Bitmap
import android.graphics.RenderEffect
import android.graphics.RectF
import android.graphics.Shader
import android.graphics.drawable.ColorDrawable
import android.text.TextUtils
import android.view.Gravity
import android.view.View
import android.view.WindowManager
import android.view.accessibility.AccessibilityEvent
import android.view.accessibility.AccessibilityNodeInfo
import android.widget.Checkable
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.PopupWindow
import android.widget.ScrollView

internal fun MainActivity.navigation(previous: NavDock? = null) = NavDock(this, previous?.index ?: tab, tab)
internal class NavDock(private val a: MainActivity, private val from: Int, val index: Int) : FrameLayout(a) {
    private val backdrop: BackdropBlurView
    private val indicator = View(a).apply {
        importantForAccessibility = IMPORTANT_FOR_ACCESSIBILITY_NO
        background = a.shape(a.palette.accentSoft, 15)
    }
    private var laidOut = false
    init {
        val gap = a.dp(14)
        val frame = FrameLayout(a).apply {
            backdrop = BackdropBlurView(a, a.navBackdropSource, cornerRadius = 28, blurRadius = a.dp(8).toFloat(),
                tint = a.palette.surface.withOpacity(if (a.palette.dark) 0x9A else 0xB8),
                fallbackTint = a.palette.surface.withOpacity(0xF2))
            addView(backdrop, LayoutParams(-1, -1))
            foreground = a.shape(Color.TRANSPARENT, 28, a.palette.stroke.withOpacity(if (a.palette.dark) 0xC0 else 0xFF))
        }
        addView(frame, LayoutParams(-1, a.dp(66)).apply { setMargins(gap, a.dp(5), gap, a.dp(8)) })
        frame.addView(indicator, LayoutParams(a.dp(56), a.dp(30), Gravity.TOP or Gravity.START).apply { topMargin = a.dp(7) })
        val line = a.row()
        frame.addView(line, LayoutParams(-1, -1))
        listOf("统计", "不想睡觉", "任务", "设置").forEachIndexed { i, name ->
            val item = a.vertical().apply {
                gravity = Gravity.CENTER_HORIZONTAL
                setPadding(0, a.dp(11), 0, 0)
                isSelected = i == index
                contentDescription = name
            }
            item.addView(NavIcon(a, i, if (i == index) a.palette.accent else a.palette.muted), LinearLayout.LayoutParams(a.dp(22), a.dp(22)))
            item.addView(a.text(name, 11, if (i == index) a.palette.text else a.palette.muted, true), LinearLayout.LayoutParams(-2, -2).apply { topMargin = a.dp(6) })
            a.clickable(item, Color.TRANSPARENT, 24) { if (i != index) a.selectTab(i) }
            line.addView(item, LinearLayout.LayoutParams(0, -1, 1f))
        }
        frame.addOnLayoutChangeListener { _, left, _, right, _, oldLeft, _, oldRight, _ ->
            val width = right - left
            if (width > 0 && (!laidOut || width != oldRight - oldLeft)) {
                fun mark(i: Int) = width / 4f * (i + .5f) - indicator.width / 2f
                indicator.animate().cancel()
                if (!laidOut && from != index && a.motionEnabled()) {
                    indicator.translationX = mark(from)
                    indicator.animate().translationX(mark(index)).setDuration(335).setInterpolator(settle).start()
                } else indicator.translationX = mark(index)
                laidOut = true
            }
        }
    }

    fun refreshBackdrop() {
        backdrop.invalidate()
        invalidate()
    }
}

internal class BackdropBlurView(context: Context, private val source: View,
                                private val cornerRadius: Int = 24, blurRadius: Float = 4.5f,
                                private val baseColor: Int = Color.TRANSPARENT, private val tint: Int = Color.TRANSPARENT,
                                private val fallbackTint: Int = Color.argb(22, 155, 170, 220)) : View(context) {
    private val bitmapPaint = Paint(Paint.ANTI_ALIAS_FLAG or Paint.FILTER_BITMAP_FLAG).apply {
        if (Build.VERSION.SDK_INT >= 31) setRenderEffect(RenderEffect.createBlurEffect(blurRadius, blurRadius, Shader.TileMode.CLAMP))
    }
    private var snapshot: Bitmap? = null

    override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
        setMeasuredDimension(resolveSize(0, widthMeasureSpec), resolveSize(0, heightMeasureSpec))
    }

    override fun onSizeChanged(w: Int, h: Int, oldw: Int, oldh: Int) {
        super.onSizeChanged(w, h, oldw, oldh)
        snapshot = null
        postInvalidateOnAnimation()
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        if (Build.VERSION.SDK_INT < 31 || !canvas.isHardwareAccelerated) {
            canvas.drawColor(fallbackTint)
            return
        }
        if (width == 0 || height == 0) return
        // Capture at the display scale so software-rendered text and bitmap content stay aligned.
        val bitmap = snapshot ?: Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888).also { snapshot = it }
        bitmap.eraseColor(baseColor)
        val sourcePosition = IntArray(2).also(source::getLocationOnScreen)
        val glassPosition = IntArray(2).also(::getLocationOnScreen)
        val capture = Canvas(bitmap).apply {
            translate((glassPosition[0] - sourcePosition[0]) * -1f, (glassPosition[1] - sourcePosition[1]) * -1f)
        }
        source.draw(capture)
        capture.drawColor(tint)
        val radius = context.dp(cornerRadius).toFloat()
        val clip = android.graphics.Path().apply {
            addRoundRect(RectF(0f, 0f, this@BackdropBlurView.width.toFloat(), this@BackdropBlurView.height.toFloat()),
                radius, radius, android.graphics.Path.Direction.CW)
        }
        canvas.save()
        canvas.clipPath(clip)
        canvas.drawBitmap(bitmap, null, RectF(0f, 0f, width.toFloat(), height.toFloat()), bitmapPaint)
        canvas.restore()
    }
}

internal enum class Glyph { DOWN, NEXT, BACK, CLOSE, MENU, CHECK, MORE, INFO, REFRESH, SEND }
internal class GlyphView(context: Context, private val glyph: Glyph, private val tint: Int) : View(context) {
    private val ink = Paint(Paint.ANTI_ALIAS_FLAG)
    override fun onDraw(c: Canvas) {
        c.save()
        c.translate(width / 2f, height / 2f)
        val size = minOf(width, height, context.dp(22)) / 24f
        c.scale(size, size)
        ink.color = tint; ink.strokeWidth = 1.9f; ink.strokeCap = Paint.Cap.ROUND; ink.strokeJoin = Paint.Join.ROUND
        fun l(x: Float, y: Float, x2: Float, y2: Float) = c.drawLine(x, y, x2, y2, ink)
        when (glyph) {
            Glyph.DOWN -> { l(-4f, -2f, 0f, 2f); l(0f, 2f, 4f, -2f) }
            Glyph.NEXT -> { l(-1.5f, -4.5f, 2.5f, 0f); l(2.5f, 0f, -1.5f, 4.5f) }
            Glyph.BACK -> { l(-6f, 0f, 6f, 0f); l(-6f, 0f, -1f, -5f); l(-6f, 0f, -1f, 5f) }
            Glyph.CLOSE -> { l(-4.5f, -4.5f, 4.5f, 4.5f); l(4.5f, -4.5f, -4.5f, 4.5f) }
            Glyph.MENU -> { l(-6.5f, -5f, 6.5f, -5f); l(-6.5f, 0f, 6.5f, 0f); l(-6.5f, 5f, 3f, 5f) }
            Glyph.CHECK -> { l(-5f, 0f, -1.5f, 3.5f); l(-1.5f, 3.5f, 5f, -3f) }
            Glyph.MORE -> for (x in listOf(-5f, 0f, 5f)) c.drawCircle(x, 0f, 1.15f, ink)
            Glyph.INFO -> {
                c.drawCircle(0f, 0f, 7f, ink)
                ink.style = Paint.Style.FILL
                c.drawCircle(0f, -3.3f, 1f, ink)
                c.drawRoundRect(-1f, -1f, 1f, 4.5f, 1f, 1f, ink)
                ink.style = Paint.Style.STROKE
            }
            Glyph.REFRESH -> {
                ink.style = Paint.Style.STROKE
                c.drawArc(-6.2f, -6.2f, 6.2f, 6.2f, 10f, 310f, false, ink)
                l(4.75f, -4f, 4.75f, -7.6f); l(4.75f, -4f, 1.15f, -4f)
                ink.style = Paint.Style.FILL
            }
            Glyph.SEND -> {
                val path = android.graphics.Path().apply {
                    moveTo(-7f, 0f); lineTo(7f, -6f); lineTo(3f, 7f); lineTo(0f, 1f); close()
                    moveTo(0f, 1f); lineTo(7f, -6f)
                }
                c.drawPath(path, ink)
            }
        }
        c.restore()
    }
}
internal fun MainActivity.iconButton(glyph: Glyph, description: String, fill: Int = Color.TRANSPARENT, tint: Int = palette.text,
                                     onClick: () -> Unit) = GlyphView(this, glyph, tint).apply {
    minimumHeight = dp(44); minimumWidth = dp(44)
    contentDescription = description
    clickable(this, fill, 24, onClick)
    accessibilityDelegate = object : View.AccessibilityDelegate() {
        override fun onInitializeAccessibilityNodeInfo(host: View, info: AccessibilityNodeInfo) {
            super.onInitializeAccessibilityNodeInfo(host, info); info.className = "android.widget.Button"
        }
    }
}

internal class ToggleControl(private val a: MainActivity, label: String, value: Boolean, private val compact: Boolean = false) : LinearLayout(a), Checkable {
    private var checked = value
    private var knob = if (value) 1f else 0f
    private var animator: ValueAnimator? = null
    private var changed: ((ToggleControl, Boolean) -> Unit)? = null
    private val labelView = a.text(label, if (compact) 13 else 15, if (compact) a.palette.muted else a.palette.text)
    private val indicator = object : View(a) {
        private val pen = Paint(Paint.ANTI_ALIAS_FLAG)
        override fun onDraw(c: Canvas) {
            val w = a.dp(if (compact) 36 else 46).toFloat()
            val h = a.dp(if (compact) 22 else 28).toFloat()
            val left = (width - w) / 2f
            val cy = height / 2f
            val rect = RectF(left, cy - h / 2, left + w, cy + h / 2)
            pen.style = Paint.Style.FILL
            pen.color = mix(a.palette.raised, a.palette.accent, knob)
            c.drawRoundRect(rect, h, h, pen)
            if (knob < 1f) {
                val inset = a.dp(1).toFloat()
                pen.color = a.palette.muted.withOpacity(((1f - knob) * 0x70).toInt())
                pen.style = Paint.Style.STROKE; pen.strokeWidth = inset * 1.5f
                c.drawRoundRect(RectF(rect.left + inset, rect.top + inset, rect.right - inset, rect.bottom - inset), h, h, pen)
                pen.style = Paint.Style.FILL
            }
            pen.color = mix(a.palette.muted, a.palette.onAccent, knob)
            val r = h / 2f - a.dp(if (compact) 5 else 6) + knob * a.dp(2)
            c.drawCircle(left + h / 2f + knob * (w - h), cy, r, pen)
        }
    }
    init {
        gravity = Gravity.CENTER_VERTICAL
        minimumHeight = a.dp(if (compact) 40 else 49)
        orientation = HORIZONTAL
        contentDescription = label
        labelView.importantForAccessibility = IMPORTANT_FOR_ACCESSIBILITY_NO
        indicator.importantForAccessibility = IMPORTANT_FOR_ACCESSIBILITY_NO
        addView(labelView, LayoutParams(if (compact) -2 else 0, -2, if (compact) 0f else 1f))
        addView(indicator, LayoutParams(a.dp(if (compact) 40 else 52), a.dp(40)).apply { marginStart = a.dp(8) })
        a.clickable(this, Color.TRANSPARENT, 12) { if (isEnabled) toggle() }
    }
    override fun isChecked() = checked
    override fun setChecked(value: Boolean) {
        if (checked == value) return
        checked = value
        animator?.cancel()
        val target = if (value) 1f else 0f
        if (isAttachedToWindow && a.motionEnabled()) {
            animator = ValueAnimator.ofFloat(knob, target).apply {
                duration = 205; interpolator = settle
                addUpdateListener { knob = it.animatedValue as Float; indicator.invalidate() }
                start()
            }
        } else { knob = target; indicator.invalidate() }
        sendAccessibilityEvent(AccessibilityEvent.TYPE_WINDOW_CONTENT_CHANGED)
        changed?.invoke(this, value)
    }
    fun onChecked(listener: (ToggleControl, Boolean) -> Unit) { changed = listener }
    override fun toggle() { setChecked(!checked) }
    override fun setEnabled(enabled: Boolean) { super.setEnabled(enabled); alpha = if (enabled) 1f else .52f }
    override fun getAccessibilityClassName(): CharSequence = "android.widget.Switch"
    override fun onInitializeAccessibilityNodeInfo(info: AccessibilityNodeInfo) {
        super.onInitializeAccessibilityNodeInfo(info)
        info.isCheckable = true; info.isChecked = checked
    }
}
internal fun mix(a: Int, b: Int, t: Float): Int = Color.rgb(
    (Color.red(a) + (Color.red(b) - Color.red(a)) * t).toInt(),
    (Color.green(a) + (Color.green(b) - Color.green(a)) * t).toInt(),
    (Color.blue(a) + (Color.blue(b) - Color.blue(a)) * t).toInt())

internal fun MainActivity.switch(parent: LinearLayout, label: String, checked: Boolean, onChange: (Boolean) -> Unit) = ToggleControl(this, label, checked).apply {
    onChecked { _, value -> attempt { onChange(value) } }
    parent.addView(this, LinearLayout.LayoutParams(-1, -2))
}
internal fun MainActivity.selector(parent: LinearLayout, label: String, current: String, values: List<String>, selected: Int, onPick: (Int) -> Unit) {
    val item = row().apply { minimumHeight = dp(52) }
    val field = row().apply {
        setPadding(dp(10), dp(4), dp(2), dp(4))
        minimumHeight = dp(40)
    }
    item.addView(text(label, 15), LinearLayout.LayoutParams(0, -2, 1f))
    field.addView(text(current, 14, palette.muted).apply {
        maxWidth = dp(170); maxLines = 1; ellipsize = TextUtils.TruncateAt.END
    })
    val chevron = GlyphView(this, Glyph.DOWN, palette.muted)
    field.addView(chevron, LinearLayout.LayoutParams(dp(24), dp(24)).apply { marginStart = dp(2) })
    item.addView(field)
    item.contentDescription = "$label，$current，下拉选项"
    clickable(item, Color.TRANSPARENT, 12) {
        if (motionEnabled()) chevron.animate().rotation(180f).setDuration(175).start()
        popup(field, values, selected, onDismiss = {
            if (motionEnabled()) chevron.animate().rotation(0f).setDuration(155).start() else chevron.rotation = 0f
        }, onPick = onPick)
    }
    parent.addView(item, LinearLayout.LayoutParams(-1, -2))
}
internal fun MainActivity.popup(anchor: View, choices: List<String>, selected: Int = -1,
                                dangerIndices: Set<Int> = emptySet(), onDismiss: () -> Unit = {}, onPick: (Int) -> Unit) {
    if (!anchor.isAttachedToWindow || choices.isEmpty()) return
    val content = vertical().apply { background = shape(palette.floating, 16, palette.outline); setPadding(dp(6), dp(6), dp(6), dp(6)) }
    val popup = PopupWindow(content, maxOf(anchor.width, dp(196)).coerceAtMost(root.width.coerceAtLeast(dp(196))), -2, true)
    popup.isOutsideTouchable = true
    popup.setBackgroundDrawable(ColorDrawable(Color.TRANSPARENT))
    popup.elevation = dp(12).toFloat()
    popup.inputMethodMode = PopupWindow.INPUT_METHOD_NOT_NEEDED
    popup.animationStyle = 0
    popup.setOnDismissListener(onDismiss)
    val scroll = ScrollView(this).apply { overScrollMode = View.OVER_SCROLL_NEVER; isVerticalScrollBarEnabled = false }
    val list = vertical()
    scroll.addView(list)
    content.addView(scroll, LinearLayout.LayoutParams(-1, if (choices.size > 6) dp(290) else -2))
    choices.forEachIndexed { i, title ->
        val line = row().apply { setPadding(dp(14), dp(6), dp(10), dp(6)); minimumHeight = dp(48); isSelected = i == selected }
        val tint = when { i in dangerIndices -> palette.red; i == selected -> palette.accent; else -> palette.text }
        line.addView(text(title, 15, tint, i == selected || i in dangerIndices), LinearLayout.LayoutParams(0, -2, 1f))
        if (i == selected) line.addView(GlyphView(this, Glyph.CHECK, palette.accent), LinearLayout.LayoutParams(dp(24), dp(24)))
        clickable(line, if (i == selected) palette.accentSoft else Color.TRANSPARENT, 10) { popup.dismiss(); onPick(i) }
        list.addView(line, LinearLayout.LayoutParams(-1, -2))
    }
    popup.showAsDropDown(anchor, 0, dp(4), Gravity.END)
    if (motionEnabled()) {
        content.pivotX = (popup.width - dp(20)).toFloat()
        content.alpha = 0f; content.scaleX = .97f; content.scaleY = .93f
        content.post {
            content.pivotY = if (popup.isAboveAnchor) content.height.toFloat() else 0f
            content.animate().alpha(1f).scaleX(1f).scaleY(1f).setDuration(205).setInterpolator(settle).start()
        }
    }
}

internal fun MainActivity.panelDialog(title: String, message: String = "", positive: String = "确定", negative: String? = null,
                                      custom: View? = null, positiveAction: () -> Unit = {}) {
    val dialog = Dialog(this).apply { requestWindowFeature(android.view.Window.FEATURE_NO_TITLE) }
    val card = vertical().apply {
        setPadding(dp(24), dp(24), dp(20), dp(18)); background = shape(palette.floating, 28, palette.outline)
    }
    card.label(title, 20, bold = true).apply { setPadding(0, 0, dp(4), 0) }
    if (message.isNotBlank()) card.label(message, 15, palette.muted, gap = 12).apply { setPadding(0, 0, dp(4), 0) }
    if (custom != null) card.addView(custom, LinearLayout.LayoutParams(-1, -2).apply { topMargin = dp(18); marginEnd = dp(4) })
    val actions = row().apply { gravity = Gravity.END }
    negative?.let { actions.addView(button(it, tone = Tone.QUIET) { dialog.dismiss() }, LinearLayout.LayoutParams(-2, -2).apply { marginEnd = dp(6) }) }
    actions.addView(button(positive, true) { dialog.dismiss(); positiveAction() })
    card.addView(actions, LinearLayout.LayoutParams(-1, -2).apply { topMargin = dp(22) })
    val contents = ScrollView(this).apply { isVerticalScrollBarEnabled = false; clipToPadding = false; addView(card) }
    dialog.setContentView(contents)
    dialog.window?.let { w ->
        w.setBackgroundDrawable(ColorDrawable(Color.TRANSPARENT))
        w.addFlags(WindowManager.LayoutParams.FLAG_DIM_BEHIND)
        w.setDimAmount(.5f)
        w.setSoftInputMode(WindowManager.LayoutParams.SOFT_INPUT_ADJUST_RESIZE or WindowManager.LayoutParams.SOFT_INPUT_STATE_ALWAYS_HIDDEN)
        w.setWindowAnimations(0)
    }
    dialog.show()
    dialog.window?.setLayout(minOf(root.width - dp(48), dp(400)).coerceAtLeast(dp(260)), -2)
    if (motionEnabled()) {
        card.alpha = .1f; card.translationY = dp(11).toFloat(); card.scaleX = .985f; card.scaleY = .985f
        card.animate().alpha(1f).translationY(0f).scaleX(1f).scaleY(1f).setDuration(240).setInterpolator(settle).start()
    }
}
