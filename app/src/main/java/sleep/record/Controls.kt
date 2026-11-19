package sleep.record

import android.animation.ValueAnimator
import android.app.Dialog
import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.RectF
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
    private val indicator = View(a).apply {
        importantForAccessibility = IMPORTANT_FOR_ACCESSIBILITY_NO
        background = a.shape(a.palette.raised, 16, a.palette.stroke)
    }
    private var laidOut = false
    init {
        val gap = a.dp(13)
        val frame = FrameLayout(a).apply { background = a.shape(a.palette.surface, 24, a.palette.stroke) }
        addView(frame, LayoutParams(-1, a.dp(66)).apply { setMargins(gap, a.dp(5), gap, a.dp(8)) })
        frame.addView(indicator, LayoutParams(a.dp(52), a.dp(29), Gravity.TOP or Gravity.START).apply { topMargin = a.dp(8) })
        val line = a.row()
        frame.addView(line, LayoutParams(-1, -1))
        listOf("统计", "不想睡觉", "任务", "设置").forEachIndexed { i, name ->
            val item = a.vertical().apply {
                gravity = Gravity.CENTER_HORIZONTAL
                setPadding(0, a.dp(12), 0, 0)
                isSelected = i == index
            }
            item.addView(NavIcon(a, i, if (i == index) a.palette.accent else a.palette.muted), LinearLayout.LayoutParams(a.dp(21), a.dp(21)))
            item.addView(a.text(name, 11, if (i == index) a.palette.text else a.palette.muted, i == index), LinearLayout.LayoutParams(-2, -2).apply { topMargin = a.dp(6) })
            a.clickable(item, Color.TRANSPARENT, 18) { if (i != index) a.selectTab(i) }
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
}

internal enum class Glyph { DOWN, BACK, CLOSE, MENU, CHECK, MORE }
internal class GlyphView(context: Context, private val glyph: Glyph, private val tint: Int) : View(context) {
    private val ink = Paint(Paint.ANTI_ALIAS_FLAG)
    override fun onDraw(c: Canvas) {
        c.save()
        c.translate(width / 2f, height / 2f)
        val size = minOf(width, height, context.dp(21)) / 24f
        c.scale(size, size)
        ink.color = tint; ink.strokeWidth = 1.8f; ink.strokeCap = Paint.Cap.ROUND
        fun l(x: Float, y: Float, x2: Float, y2: Float) = c.drawLine(x, y, x2, y2, ink)
        when (glyph) {
            Glyph.DOWN -> { l(-4f, -2f, 0f, 2f); l(0f, 2f, 4f, -2f) }
            Glyph.BACK -> { l(-6f, 0f, 6f, 0f); l(-6f, 0f, -1f, -5f); l(-6f, 0f, -1f, 5f) }
            Glyph.CLOSE -> { l(-4.5f, -4.5f, 4.5f, 4.5f); l(4.5f, -4.5f, -4.5f, 4.5f) }
            Glyph.MENU -> { l(-6.5f, -5f, 6.5f, -5f); l(-6.5f, 0f, 6.5f, 0f); l(-6.5f, 5f, 3f, 5f) }
            Glyph.CHECK -> { l(-5f, 0f, -1.5f, 3.5f); l(-1.5f, 3.5f, 5f, -3f) }
            Glyph.MORE -> for (x in listOf(-5f, 0f, 5f)) c.drawCircle(x, 0f, 1.15f, ink)
        }
        c.restore()
    }
}
internal fun MainActivity.iconButton(glyph: Glyph, description: String, onClick: () -> Unit) = GlyphView(this, glyph, palette.accent).apply {
    minimumHeight = dp(44); minimumWidth = dp(44)
    contentDescription = description
    clickable(this, palette.raised, 15, onClick)
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
            val w = a.dp(if (compact) 32 else 40).toFloat()
            val h = a.dp(if (compact) 18 else 22).toFloat()
            val left = (width - w) / 2f
            val cy = height / 2f
            pen.color = mix(a.palette.raised, a.palette.accent, knob)
            val rect = RectF(left, cy - h / 2, left + w, cy + h / 2)
            pen.style = Paint.Style.FILL
            c.drawRoundRect(rect, h, h, pen)
            if (!checked) {
                pen.color = a.palette.stroke; pen.style = Paint.Style.STROKE; pen.strokeWidth = a.dp(1).toFloat()
                c.drawRoundRect(rect, h, h, pen); pen.style = Paint.Style.FILL
            }
            pen.color = mix(a.palette.muted, a.palette.onAccent, knob)
            val r = a.dp(if (compact) 5 else 6).toFloat() + knob * a.dp(1)
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
        addView(indicator, LayoutParams(a.dp(if (compact) 34 else 44), a.dp(40)).apply { marginStart = a.dp(9) })
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
    val item = row().apply { minimumHeight = dp(54) }
    val field = row().apply {
        setPadding(dp(12), dp(4), dp(6), dp(4))
        background = shape(palette.raised, 12, palette.stroke)
        minimumHeight = dp(36)
    }
    item.addView(text(label, 15), LinearLayout.LayoutParams(0, -2, 1f))
    field.addView(text(current, 13, palette.accent, true).apply {
        maxWidth = dp(157); maxLines = 1; ellipsize = TextUtils.TruncateAt.END
    })
    val chevron = GlyphView(this, Glyph.DOWN, palette.accent)
    field.addView(chevron, LinearLayout.LayoutParams(dp(23), dp(25)))
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
internal fun MainActivity.popup(anchor: View, choices: List<String>, selected: Int = -1, onDismiss: () -> Unit = {}, onPick: (Int) -> Unit) {
    if (!anchor.isAttachedToWindow || choices.isEmpty()) return
    val content = vertical().apply { background = shape(palette.surface, 20, palette.stroke); setPadding(dp(5), dp(5), dp(5), dp(5)) }
    val popup = PopupWindow(content, maxOf(anchor.width, dp(182)).coerceAtMost(root.width.coerceAtLeast(dp(182))), -2, true)
    popup.isOutsideTouchable = true
    popup.setBackgroundDrawable(ColorDrawable(Color.TRANSPARENT))
    popup.elevation = dp(13).toFloat()
    popup.inputMethodMode = PopupWindow.INPUT_METHOD_NOT_NEEDED
    popup.animationStyle = 0
    popup.setOnDismissListener(onDismiss)
    val scroll = ScrollView(this).apply { overScrollMode = View.OVER_SCROLL_NEVER; isVerticalScrollBarEnabled = false }
    val list = vertical()
    scroll.addView(list)
    content.addView(scroll, LinearLayout.LayoutParams(-1, if (choices.size > 6) dp(270) else -2))
    choices.forEachIndexed { i, title ->
        val line = row().apply { setPadding(dp(12), dp(5), dp(9), dp(5)); minimumHeight = dp(45); isSelected = i == selected }
        line.addView(text(title, 14, if (i == selected) palette.accent else palette.text, i == selected), LinearLayout.LayoutParams(0, -2, 1f))
        if (i == selected) line.addView(GlyphView(this, Glyph.CHECK, palette.accent), LinearLayout.LayoutParams(dp(24), dp(24)))
        clickable(line, if (i == selected) palette.raised else Color.TRANSPARENT, 12) { popup.dismiss(); onPick(i) }
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
        setPadding(dp(21), dp(19), dp(21), dp(17)); background = shape(palette.surface, 27, palette.stroke)
    }
    card.label(title, 22, bold = true)
    if (message.isNotBlank()) card.label(message, 15, palette.muted, gap = 10)
    if (custom != null) card.addView(custom, LinearLayout.LayoutParams(-1, -2).apply { topMargin = dp(14) })
    val actions = row().apply { gravity = Gravity.END }
    negative?.let { actions.addView(button(it) { dialog.dismiss() }, LinearLayout.LayoutParams(-2, -2).apply { marginEnd = dp(8) }) }
    actions.addView(button(positive, true) { dialog.dismiss(); positiveAction() })
    card.addView(actions, LinearLayout.LayoutParams(-1, -2).apply { topMargin = dp(18) })
    val contents = ScrollView(this).apply { isVerticalScrollBarEnabled = false; clipToPadding = false; addView(card) }
    dialog.setContentView(contents)
    dialog.window?.let { w ->
        w.setBackgroundDrawable(ColorDrawable(Color.TRANSPARENT))
        w.addFlags(WindowManager.LayoutParams.FLAG_DIM_BEHIND)
        w.setDimAmount(.52f)
        w.setSoftInputMode(WindowManager.LayoutParams.SOFT_INPUT_ADJUST_RESIZE or WindowManager.LayoutParams.SOFT_INPUT_STATE_ALWAYS_HIDDEN)
        w.setWindowAnimations(0)
    }
    dialog.show()
    dialog.window?.setLayout(minOf(root.width - dp(38), dp(426)).coerceAtLeast(dp(215)), -2)
    if (motionEnabled()) {
        card.alpha = .1f; card.translationY = dp(11).toFloat(); card.scaleX = .985f; card.scaleY = .985f
        card.animate().alpha(1f).translationY(0f).scaleX(1f).scaleY(1f).setDuration(240).setInterpolator(settle).start()
    }
}
