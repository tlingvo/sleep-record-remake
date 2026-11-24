package sleep.record

import android.content.Context
import android.content.res.ColorStateList
import android.content.res.Configuration
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.LinearGradient
import android.graphics.Paint
import android.graphics.Path
import android.graphics.RectF
import android.graphics.Shader
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.graphics.drawable.RippleDrawable
import android.graphics.drawable.StateListDrawable
import android.view.Gravity
import android.view.View
import android.widget.EditText
import android.widget.HorizontalScrollView
import android.widget.LinearLayout
import android.widget.ProgressBar
import android.widget.ScrollView
import android.widget.TextView
import kotlin.math.roundToInt

internal fun color(hex: String) = Color.parseColor(hex)
internal fun Int.withOpacity(alpha: Int) = (this and 0x00ffffff) or (alpha shl 24)

/**
 * Tonal palette. Depth comes from surface steps rather than outlines:
 * bg < surface (cards, sheets) < raised (inputs, popups, dialogs). Light mode keeps a hairline on cards
 * because white-on-near-white alone does not separate.
 */
internal data class Palette(val dark: Boolean, val bg: Int, val surface: Int, val raised: Int, val stroke: Int,
                            val text: Int, val muted: Int, val accent: Int, val onAccent: Int, val green: Int, val amber: Int, val red: Int,
                            val accentSoft: Int) {
    /** Hairline for cards and sheets. Sheets share the card fill, so cards keep it in both schemes. */
    val outline: Int? get() = stroke
    /** Popups and dialogs float above sheets, so they take the next tonal step in dark mode. */
    val floating: Int get() = if (dark) raised else surface
    companion object {
        fun isDark(mode: Int, c: Configuration) =
            mode == 1 || mode == 0 && c.uiMode and Configuration.UI_MODE_NIGHT_MASK == Configuration.UI_MODE_NIGHT_YES
        fun forMode(mode: Int, c: Configuration): Palette =
            if (isDark(mode, c)) Palette(true, color("#0D0F17"), color("#161925"), color("#212535"), color("#2C3144"),
                color("#ECEEF7"), color("#9CA3B9"), color("#B0BCFF"), color("#18204F"), color("#7DD8B2"), color("#EFC27C"), color("#FF9DA0"),
                color("#272D4F"))
            else Palette(false, color("#F4F5F9"), color("#FFFFFF"), color("#ECEEF4"), color("#E1E4ED"),
                color("#151926"), color("#5C6478"), color("#4152C7"), Color.WHITE, color("#11795A"), color("#8A5700"), color("#B42335"),
                color("#E5E8FB"))
    }
}

/** Type scale (sp). Every screen draws from these steps only. */
internal object TypeScale {
    const val DISPLAY = 52
    const val HEADLINE = 28
    const val PAGE = 20
    const val TITLE = 17
    const val BODY = 15
    const val LABEL = 13
    const val CAPTION = 12
}

internal fun Context.dp(value: Int) = (value * resources.displayMetrics.density).roundToInt()
internal fun MainActivity.shape(fill: Int, radius: Int = 16, border: Int? = null, borderWidth: Int = 1) = GradientDrawable().apply {
    setColor(fill); cornerRadius = dp(radius).toFloat()
    if (border != null) setStroke(maxOf(1, dp(borderWidth)), border)
}
internal fun MainActivity.clickable(view: View, fill: Int, radius: Int, onClick: () -> Unit) {
    view.background = RippleDrawable(ColorStateList.valueOf(if (palette.dark) 0x24ffffff else 0x16202a60), shape(fill, radius), shape(Color.WHITE, radius))
    view.isFocusable = true
    view.setOnClickListener { attempt(onClick) }
}
internal fun MainActivity.vertical() = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
internal fun MainActivity.row() = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL }
internal val medium: Typeface = Typeface.create("sans-serif-medium", Typeface.NORMAL)
internal fun MainActivity.text(value: String, size: Int, tint: Int = palette.text, bold: Boolean = false) = TextView(this).apply {
    text = value
    textSize = size.toFloat()
    setTextColor(tint)
    if (bold) typeface = medium
    includeFontPadding = false
    setLineSpacing(dp(if (size >= TypeScale.PAGE) 2 else 4).toFloat(), 1f)
}
/** Tabular figures so scores, clocks and durations line up column to column. */
internal fun TextView.numeric() = apply { fontFeatureSettings = "tnum" }
context(a: MainActivity) internal fun LinearLayout.label(value: String, size: Int = TypeScale.BODY, tint: Int = a.palette.text, bold: Boolean = false, gap: Int = 0): TextView {
    val view = a.text(value, size, tint, bold)
    addView(view, LinearLayout.LayoutParams(-1, -2).apply { topMargin = a.dp(gap) })
    return view
}
context(a: MainActivity) internal fun LinearLayout.card(gap: Int = 12, fill: Int = a.palette.surface): LinearLayout = a.vertical().also {
    it.setPadding(a.dp(17), a.dp(16), a.dp(17), a.dp(16))
    it.background = a.shape(fill, 22, a.palette.outline)
    addView(it, LinearLayout.LayoutParams(-1, -2).apply { topMargin = a.dp(gap) })
}
/** Card for settings-style rows: tight vertical padding and hairline dividers between rows. */
context(a: MainActivity) internal fun LinearLayout.listCard(gap: Int = 12): LinearLayout = card(gap).also {
    it.setPadding(a.dp(18), a.dp(4), a.dp(14), a.dp(4))
    it.dividerDrawable = GradientDrawable().apply { setColor(a.palette.stroke.withOpacity(if (a.palette.dark) 0xB0 else 0xFF)); setSize(1, maxOf(1, a.dp(1) / 2)) }
    it.showDividers = LinearLayout.SHOW_DIVIDER_MIDDLE
}
internal enum class Tone { PRIMARY, TONAL, QUIET }
internal fun MainActivity.button(label: String, primary: Boolean = false, enabled: Boolean = true, fill: Int? = null,
                                 tone: Tone = if (primary) Tone.PRIMARY else Tone.TONAL, onClick: () -> Unit): TextView {
    val ink = when {
        !enabled -> palette.muted
        tone == Tone.PRIMARY -> palette.onAccent
        else -> palette.accent
    }
    return text(label, TypeScale.BODY, ink, true).apply {
        gravity = Gravity.CENTER
        minHeight = dp(46)
        maxLines = 2
        setPadding(dp(16), dp(7), dp(16), dp(7))
        val surface = fill ?: when {
            !enabled -> if (tone == Tone.QUIET) Color.TRANSPARENT else palette.raised
            tone == Tone.PRIMARY -> palette.accent
            tone == Tone.TONAL -> palette.accentSoft
            else -> Color.TRANSPARENT
        }
        if (enabled) clickable(this, surface, 24, onClick) else { background = shape(surface, 24); alpha = .72f; isEnabled = false }
        accessibilityClassNameOverride(this)
    }
}
private fun accessibilityClassNameOverride(view: TextView) {
    view.accessibilityDelegate = object : View.AccessibilityDelegate() {
        override fun onInitializeAccessibilityNodeInfo(host: View, info: android.view.accessibility.AccessibilityNodeInfo) {
            super.onInitializeAccessibilityNodeInfo(host, info)
            info.className = "android.widget.Button"
        }
    }
}
context(a: MainActivity) internal fun LinearLayout.action(label: String, primary: Boolean = false, enabled: Boolean = true, onClick: () -> Unit): TextView {
    val button = a.button(label, primary, enabled, onClick = onClick)
    addView(button, LinearLayout.LayoutParams(-1, -2).apply { topMargin = a.dp(11) })
    return button
}
/**
 * Re-applies a saved scroll offset once the rebuilt content has been measured. A plain post() can run
 * before the first layout, where ScrollView clamps every offset to 0 and the page jumps to the top.
 */
internal fun restoreScroll(scroll: ScrollView, y: Int) {
    if (y <= 0) return
    scroll.viewTreeObserver.addOnPreDrawListener(object : android.view.ViewTreeObserver.OnPreDrawListener {
        override fun onPreDraw(): Boolean {
            val child = scroll.getChildAt(0)
            if (child == null || child.height == 0) return true
            scroll.viewTreeObserver.removeOnPreDrawListener(this)
            scroll.scrollTo(0, y)
            return true
        }
    })
}
internal fun MainActivity.scroller(parent: LinearLayout, pad: Int = 18): LinearLayout = scrollBox(parent, pad).second
internal fun MainActivity.scrollBox(parent: LinearLayout, pad: Int = 18): Pair<ScrollView, LinearLayout> {
    val scroll = ScrollView(this).apply {
        clipToPadding = false; isFillViewport = false; isVerticalScrollBarEnabled = false
        overScrollMode = View.OVER_SCROLL_IF_CONTENT_SCROLLS
    }
    scrollTarget(scroll, parent)
    val bottomSafeArea = if (parent === body && screen == null) dp(104) else dp(24)
    val content = vertical().apply { setPadding(dp(pad), dp(4), dp(pad), bottomSafeArea) }
    scroll.addView(content, android.widget.FrameLayout.LayoutParams(-1, -2))
    parent.addView(scroll, LinearLayout.LayoutParams(-1, 0, 1f))
    scroll.setOnScrollChangeListener { _, _, _, _, _ -> invalidateNavBackdrop() }
    scroll.setOnTouchListener { _, event ->
        when (event.actionMasked) {
            android.view.MotionEvent.ACTION_MOVE -> invalidateNavBackdrop()
            android.view.MotionEvent.ACTION_UP, android.view.MotionEvent.ACTION_CANCEL -> refreshNavBackdropDuring()
        }
        false
    }
    return scroll to content
}
internal fun MainActivity.title(heading: String, back: Boolean = false, subtitle: String? = null,
                                leading: Pair<Glyph, String>? = null, onLeading: (() -> Unit)? = null,
                                vararg actions: Pair<String, () -> Unit>) {
    val sub = back || leading != null
    val line = row().apply { setPadding(dp(if (sub) 8 else 20), dp(10), dp(10), dp(10)); minimumHeight = dp(64) }
    val icon = when {
        leading != null && onLeading != null -> iconButton(leading.first, leading.second, onClick = onLeading)
        back -> iconButton(if (overlayPage == null) Glyph.BACK else Glyph.CLOSE, "返回") { closePage() }
        else -> null
    }
    if (icon != null) line.addView(icon, LinearLayout.LayoutParams(dp(48), dp(48)).apply { marginEnd = dp(4) })
    val texts = vertical().apply {
        label(heading, if (sub) TypeScale.PAGE else TypeScale.HEADLINE, bold = true).apply {
            maxLines = if (sub) 1 else 2; ellipsize = android.text.TextUtils.TruncateAt.END
        }
        if (subtitle != null) label(subtitle, TypeScale.LABEL, palette.muted, gap = 4).apply {
            maxLines = 1; ellipsize = android.text.TextUtils.TruncateAt.END
        }
    }
    line.addView(texts, LinearLayout.LayoutParams(0, -2, 1f))
    for ((label, fn) in actions) {
        val glyph = when (label) {
            "选项" -> Glyph.MORE
            "刷新", "刷新中" -> Glyph.REFRESH
            "关于" -> Glyph.INFO
            else -> null
        }
        val button = if (glyph != null) iconButton(glyph, label, onClick = fn)
        else button(label, tone = Tone.QUIET, onClick = fn).apply { minHeight = dp(40); setPadding(dp(14), dp(6), dp(14), dp(6)) }
        line.addView(button, LinearLayout.LayoutParams(if (glyph != null) dp(48) else -2, if (glyph != null) dp(48) else -2)
            .apply { marginStart = dp(2) })
    }
    body.addView(line)
}
/** Segmented control. The highlight slides from [from] to [selected] the first time it is laid out. */
internal fun MainActivity.segments(parent: LinearLayout, labels: List<String>, selected: Int, from: Int = selected, callback: (Int) -> Unit) {
    val track = SegmentTrack(this, labels.size, if (from in labels.indices) from else selected, selected)
    track.setPadding(dp(4), dp(4), dp(4), dp(4))
    track.background = shape(palette.raised, 22)
    track.indicator.background = shape(palette.accentSoft, 18)
    val line = row()
    labels.forEachIndexed { index, label ->
        val active = index == selected
        val item = text(label, TypeScale.LABEL + 1, if (active) palette.accent else palette.muted, true).apply {
            gravity = Gravity.CENTER; minHeight = dp(38); isSelected = active
            clickable(this, Color.TRANSPARENT, 18) { callback(index) }
            accessibilityClassNameOverride(this)
        }
        line.addView(item, LinearLayout.LayoutParams(0, -2, 1f))
    }
    track.addView(line, android.widget.FrameLayout.LayoutParams(-1, -2))
    parent.addView(track, LinearLayout.LayoutParams(-1, -2).apply { topMargin = dp(4); bottomMargin = dp(4) })
}
internal class SegmentTrack(context: Context, private val count: Int, private val from: Int, private val to: Int) : android.widget.FrameLayout(context) {
    val indicator = View(context).apply { importantForAccessibility = IMPORTANT_FOR_ACCESSIBILITY_NO }
    private var cell = -1
    init { addView(indicator, LayoutParams(0, 0)) }
    override fun onLayout(changed: Boolean, left: Int, top: Int, right: Int, bottom: Int) {
        super.onLayout(changed, left, top, right, bottom)
        if (to !in 0 until count) { indicator.visibility = GONE; return }
        val size = (width - paddingLeft - paddingRight) / count
        indicator.layout(paddingLeft, paddingTop, paddingLeft + size, height - paddingBottom)
        if (size == cell) return
        val first = cell < 0
        cell = size
        indicator.animate().cancel()
        if (first && from != to && context.motionEnabled()) {
            indicator.translationX = size * from.toFloat()
            indicator.animate().translationX(size * to.toFloat()).setDuration(280).setInterpolator(settle).start()
        } else indicator.translationX = size * to.toFloat()
    }
}
/** List header: small, muted, sits close to the group it names. */
internal fun MainActivity.section(parent: LinearLayout, title: String) {
    parent.label(title, TypeScale.LABEL, palette.muted, true, 26).apply {
        setPadding(dp(4), 0, dp(4), 0)
        (layoutParams as LinearLayout.LayoutParams).bottomMargin = -dp(4)
    }
}
internal fun MainActivity.entry(parent: LinearLayout, label: String, value: String = "", onClick: () -> Unit): View =
    anchoredEntry(parent, label, value) { onClick() }
internal fun MainActivity.anchoredEntry(parent: LinearLayout, label: String, value: String = "", onClick: (View) -> Unit): View {
    val item = row().apply { setPadding(0, dp(10), 0, dp(10)); minimumHeight = dp(50) }
    item.addView(text(label, TypeScale.BODY).apply { maxLines = 2; ellipsize = android.text.TextUtils.TruncateAt.END }, LinearLayout.LayoutParams(0, -2, 1f))
    if (value.isNotBlank()) item.addView(text(value, TypeScale.LABEL + 1, palette.muted).apply {
        maxWidth = dp(170); maxLines = 1; ellipsize = android.text.TextUtils.TruncateAt.END
        gravity = Gravity.END; setPadding(dp(8), 0, dp(2), 0)
    }.numeric())
    item.addView(GlyphView(this, Glyph.NEXT, palette.muted), LinearLayout.LayoutParams(dp(20), dp(24)))
    clickable(item, Color.TRANSPARENT, 12) { onClick(item) }
    item.contentDescription = if (value.isBlank()) label else "$label，$value"
    parent.addView(item)
    return item
}
internal fun MainActivity.input(value: String = "", hint: String = "", single: Boolean = true) = EditText(this).apply {
    setText(value); this.hint = hint; textSize = TypeScale.BODY.toFloat()
    setTextColor(palette.text); setHintTextColor(palette.muted)
    highlightColor = palette.accent.withOpacity(0x55)
    if (single) setSingleLine(true) else maxLines = 5
    minimumHeight = dp(50)
    setPadding(dp(14), dp(10), dp(14), dp(10))
    background = StateListDrawable().apply {
        addState(intArrayOf(android.R.attr.state_focused), shape(palette.raised, 14, palette.accent, 2))
        addState(intArrayOf(), shape(palette.raised, 14))
    }
}
internal fun MainActivity.inputDialog(title: String, value: String = "", hint: String = "", secret: Boolean = false, multiline: Boolean = false, onDone: (String) -> Unit) {
    val field = input(value, hint, single = !multiline)
    if (secret) field.inputType = android.text.InputType.TYPE_CLASS_TEXT or android.text.InputType.TYPE_TEXT_VARIATION_PASSWORD
    panelDialog(title, negative = "取消", custom = field) { onDone(field.text.toString()) }
}
internal fun MainActivity.progress(parent: LinearLayout, maximum: Int, current: Int, tint: Int = palette.accent): ProgressBar = ProgressBar(this, null, android.R.attr.progressBarStyleHorizontal).apply {
    progressDrawable = android.graphics.drawable.LayerDrawable(arrayOf(
        shape(palette.stroke, 3), android.graphics.drawable.ClipDrawable(shape(tint, 3), Gravity.START, android.graphics.drawable.ClipDrawable.HORIZONTAL)
    )).apply {
        setId(0, android.R.id.background)
        setId(1, android.R.id.progress)
    }
    max = maximum.coerceAtLeast(1); progress = current.coerceIn(0, max)
    parent.addView(this, LinearLayout.LayoutParams(-1, dp(6)).apply { topMargin = dp(12) })
}
/** Small tinted label for points and states. */
internal fun MainActivity.chip(value: String, tint: Int): TextView = text(value, TypeScale.CAPTION, tint, true).apply {
    gravity = Gravity.CENTER
    background = shape(if (tint == palette.muted) palette.raised else tint.withOpacity(if (palette.dark) 0x26 else 0x1A), 10)
    setPadding(dp(10), dp(5), dp(10), dp(5))
    numeric()
}
internal fun MainActivity.chips(parent: LinearLayout): LinearLayout {
    val strip = HorizontalScrollView(this).apply { isHorizontalScrollBarEnabled = false }
    val content = row()
    strip.addView(content)
    parent.addView(strip, LinearLayout.LayoutParams(-1, -2))
    return content
}
internal class NavIcon(context: Context, private val index: Int, private val tint: Int) : View(context) {
    private val paint = Paint(Paint.ANTI_ALIAS_FLAG)
    override fun onDraw(c: Canvas) {
        c.save(); c.scale(width / 24f, height / 24f)
        paint.color = tint; paint.strokeWidth = 1.8f; paint.style = Paint.Style.STROKE; paint.strokeCap = Paint.Cap.ROUND; paint.strokeJoin = Paint.Join.ROUND
        when (index) {
            0 -> { c.drawLine(4f, 20f, 20f, 20f, paint); c.drawLine(7f, 16f, 7f, 11f, paint); c.drawLine(12f, 16f, 12f, 5f, paint); c.drawLine(17f, 16f, 17f, 8f, paint) }
            1 -> { c.drawRoundRect(3.5f, 4.5f, 20.5f, 16.5f, 4.5f, 4.5f, paint); c.drawLine(8f, 16.5f, 5.5f, 20f, paint); c.drawLine(8f, 10.5f, 16f, 10.5f, paint) }
            2 -> { c.drawRoundRect(5f, 3.5f, 19f, 20.5f, 3.5f, 3.5f, paint); c.drawLine(8.75f, 12f, 11f, 14.25f, paint); c.drawLine(11f, 14.25f, 15.25f, 9.5f, paint) }
            else -> { c.drawCircle(12f, 12f, 6.5f, paint); c.drawCircle(12f, 12f, 2.5f, paint); for (i in 0..7) { c.save(); c.rotate(i * 45f, 12f, 12f); c.drawLine(12f, 3f, 12f, 5f, paint); c.restore() } }
        }
        c.restore()
    }
}
internal class MoonArt(context: Context) : View(context) {
    private val p = Paint(Paint.ANTI_ALIAS_FLAG)
    override fun onDraw(c: Canvas) {
        val w = width.toFloat(); val h = height.toFloat()
        if (w == 0f || h == 0f) return
        val x = w * .67f; val y = h * .43f; val r = minOf(w, h) * .23f
        p.shader = LinearGradient(x - r, y - r, x + r, y + r, color("#E6E9FE"), color("#91ADDC"), Shader.TileMode.CLAMP)
        val moon = Path().apply { addCircle(x, y, r, Path.Direction.CW) }
        moon.op(Path().apply { addCircle(x + r * .59f, y - r * .45f, r * .9f, Path.Direction.CW) }, Path.Op.DIFFERENCE)
        c.drawPath(moon, p)
        p.shader = null
        for ((dx, dy, scale) in listOf(Triple(.20f, .22f, .025f), Triple(.37f, .63f, .018f), Triple(.83f, .24f, .018f), Triple(.51f, .14f, .012f), Triple(.87f, .61f, .010f))) {
            val s = h * scale; val sx = w * dx; val sy = h * dy
            p.color = color("#9AB6EB")
            c.drawPath(Path().apply { moveTo(sx, sy - s); lineTo(sx + s * .7f, sy); lineTo(sx, sy + s); lineTo(sx - s * .7f, sy); close() }, p)
        }
        p.style = Paint.Style.STROKE; p.color = color("#536A96"); p.strokeWidth = resources.displayMetrics.density
        for (i in 0..2) {
            val base = h * (.78f + i * .067f)
            c.drawPath(Path().apply { moveTo(w * .08f, base); cubicTo(w * .42f, base - h * .28f, w * .67f, base + h * .15f, w * .94f, base - h * .08f) }, p)
        }
        p.style = Paint.Style.FILL
    }
}
