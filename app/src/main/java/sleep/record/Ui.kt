package sleep.record

import android.content.Context
import android.content.res.ColorStateList
import android.content.res.Configuration
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.LinearGradient
import android.graphics.Paint
import android.graphics.Path
import android.graphics.Shader
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.graphics.drawable.RippleDrawable
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
internal data class Palette(val dark: Boolean, val bg: Int, val surface: Int, val raised: Int, val stroke: Int,
                            val text: Int, val muted: Int, val accent: Int, val onAccent: Int, val green: Int, val amber: Int, val red: Int) {
    companion object {
        fun forMode(mode: Int, c: Configuration): Palette {
            val dark = mode == 1 || mode == 0 && c.uiMode and Configuration.UI_MODE_NIGHT_MASK == Configuration.UI_MODE_NIGHT_YES
            return if (dark) Palette(true, color("#10131E"), color("#181D2C"), color("#252D44"), color("#303951"),
                color("#F3F4FF"), color("#AEB9D1"), color("#C0CEFF"), color("#182242"), color("#93DDC0"), color("#F5CC83"), color("#FF9D9E"))
            else Palette(false, color("#F3F5FD"), color("#FFFFFF"), color("#E9EEFD"), color("#DCE3F3"),
                color("#17253E"), color("#4C5F7E"), color("#214D8C"), Color.WHITE, color("#0F7154"), color("#875300"), color("#AB3542"))
        }
    }
}
internal fun Context.dp(value: Int) = (value * resources.displayMetrics.density).roundToInt()
internal fun MainActivity.shape(fill: Int, radius: Int = 16, border: Int? = null) = GradientDrawable().apply {
    setColor(fill); cornerRadius = dp(radius).toFloat()
    if (border != null) setStroke(maxOf(1, dp(1)), border)
}
internal fun MainActivity.clickable(view: View, fill: Int, radius: Int, onClick: () -> Unit) {
    view.background = RippleDrawable(ColorStateList.valueOf(if (palette.dark) 0x22ffffff else 0x18224580), shape(fill, radius), shape(Color.WHITE, radius))
    view.isFocusable = true
    view.setOnClickListener { attempt(onClick) }
}
internal fun MainActivity.vertical() = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
internal fun MainActivity.row() = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL }
internal fun MainActivity.text(value: String, size: Int, tint: Int = palette.text, bold: Boolean = false) = TextView(this).apply {
    text = value
    textSize = size.toFloat()
    setTextColor(tint)
    if (bold) typeface = Typeface.create("sans-serif-medium", Typeface.NORMAL)
    includeFontPadding = false
    setLineSpacing(dp(3).toFloat(), 1.08f)
}
context(a: MainActivity) internal fun LinearLayout.label(value: String, size: Int = 15, tint: Int = a.palette.text, bold: Boolean = false, gap: Int = 0): TextView {
    val view = a.text(value, size, tint, bold)
    addView(view, LinearLayout.LayoutParams(-1, -2).apply { topMargin = a.dp(gap) })
    return view
}
context(a: MainActivity) internal fun LinearLayout.card(gap: Int = 12, fill: Int = a.palette.surface): LinearLayout = a.vertical().also {
    it.setPadding(a.dp(17), a.dp(16), a.dp(17), a.dp(16))
    it.background = a.shape(fill, 22, a.palette.stroke)
    addView(it, LinearLayout.LayoutParams(-1, -2).apply { topMargin = a.dp(gap) })
}
internal fun MainActivity.button(label: String, primary: Boolean = false, enabled: Boolean = true, fill: Int? = null, onClick: () -> Unit): TextView =
    text(label, 14, if (!enabled) palette.muted else if (primary) palette.onAccent else palette.accent, true).apply {
        gravity = Gravity.CENTER
        minHeight = dp(46)
        setPadding(dp(13), dp(7), dp(13), dp(7))
        val surface = fill ?: if (primary && enabled) palette.accent else palette.raised
        if (enabled) clickable(this, surface, 15, onClick) else { background = shape(surface, 15); alpha = .55f; isEnabled = false }
        accessibilityClassNameOverride(this)
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
internal fun MainActivity.scroller(parent: LinearLayout, pad: Int = 18): LinearLayout = scrollBox(parent, pad).second
internal fun MainActivity.scrollBox(parent: LinearLayout, pad: Int = 18): Pair<ScrollView, LinearLayout> {
    val scroll = ScrollView(this).apply {
        clipToPadding = false; isFillViewport = false; isVerticalScrollBarEnabled = false
    }
    scrollTarget(scroll, parent)
    val bottomSafeArea = if (parent === body && screen == null) dp(102) else dp(22)
    val content = vertical().apply { setPadding(dp(pad), dp(2), dp(pad), bottomSafeArea) }
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
    val line = row().apply { setPadding(dp(19), dp(12), dp(16), dp(14)); minimumHeight = dp(71) }
    val icon = when {
        leading != null && onLeading != null -> iconButton(leading.first, leading.second, onClick = onLeading)
        back -> iconButton(if (overlayPage == null) Glyph.BACK else Glyph.CLOSE, "返回") { closePage() }
        else -> null
    }
    if (icon != null) line.addView(icon, LinearLayout.LayoutParams(dp(44), dp(44)).apply { marginEnd = dp(12) })
    val texts = vertical().apply {
        label(heading, if (back || leading != null) 22 else 28, bold = true).apply {
            maxLines = 2; ellipsize = android.text.TextUtils.TruncateAt.END
        }
        if (subtitle != null) label(subtitle, 12, palette.muted, gap = 6)
    }
    line.addView(texts, LinearLayout.LayoutParams(0, -2, 1f))
    for ((label, fn) in actions) {
        val glyph = when (label) {
            "选项" -> Glyph.MORE
            "刷新", "刷新中" -> Glyph.REFRESH
            "关于" -> Glyph.INFO
            else -> null
        }
        val button = if (glyph != null) iconButton(glyph, label, onClick = fn) else this.button(label, onClick = fn)
        line.addView(button, LinearLayout.LayoutParams(if (glyph != null) dp(44) else -2, if (glyph != null) dp(44) else -2)
            .apply { marginStart = dp(7) })
    }
    body.addView(line)
}
internal fun MainActivity.segments(parent: LinearLayout, labels: List<String>, selected: Int, callback: (Int) -> Unit) {
    parent.addView(row().apply {
        setPadding(dp(4), dp(4), dp(4), dp(4))
        background = shape(palette.surface, 17, palette.stroke)
        labels.forEachIndexed { index, label ->
            val active = index == selected
            val item = text(label, 14, if (active) palette.accent else palette.muted, active).apply {
                gravity = Gravity.CENTER; minHeight = dp(39); isSelected = active
                clickable(this, if (active) palette.raised else Color.TRANSPARENT, 13) { callback(index) }
                accessibilityClassNameOverride(this)
            }
            addView(item, LinearLayout.LayoutParams(0, -2, 1f).apply { if (index > 0) marginStart = dp(3) })
        }
    }, LinearLayout.LayoutParams(-1, -2).apply { topMargin = dp(6); bottomMargin = dp(5) })
}
internal fun MainActivity.section(parent: LinearLayout, title: String) { parent.label(title, 17, palette.text, true, 21) }
internal fun MainActivity.entry(parent: LinearLayout, label: String, value: String = "", onClick: () -> Unit): View =
    anchoredEntry(parent, label, value) { onClick() }
internal fun MainActivity.anchoredEntry(parent: LinearLayout, label: String, value: String = "", onClick: (View) -> Unit): View {
    val item = row().apply { setPadding(dp(3), dp(10), dp(2), dp(10)); minimumHeight = dp(50) }
    item.addView(text(label, 15), LinearLayout.LayoutParams(0, -2, 1f))
    if (value.isNotBlank()) item.addView(text(value, 13, palette.muted).apply {
        maxWidth = dp(165); gravity = Gravity.END; setPadding(dp(5), 0, 0, 0)
    })
    item.addView(GlyphView(this, Glyph.DOWN, palette.muted).apply { rotation = -90f }, LinearLayout.LayoutParams(dp(21), dp(25)))
    clickable(item, Color.TRANSPARENT, 12) { onClick(item) }
    parent.addView(item)
    return item
}
internal fun MainActivity.input(value: String = "", hint: String = "", single: Boolean = true) = EditText(this).apply {
    setText(value); this.hint = hint; textSize = 15f
    setTextColor(palette.text); setHintTextColor(palette.muted)
    if (single) setSingleLine(true) else maxLines = 5
    minimumHeight = dp(47)
    setPadding(dp(12), dp(9), dp(12), dp(9))
    background = shape(palette.raised, 12, palette.stroke)
}
internal fun MainActivity.inputDialog(title: String, value: String = "", hint: String = "", secret: Boolean = false, multiline: Boolean = false, onDone: (String) -> Unit) {
    val field = input(value, hint, single = !multiline)
    if (secret) field.inputType = android.text.InputType.TYPE_CLASS_TEXT or android.text.InputType.TYPE_TEXT_VARIATION_PASSWORD
    panelDialog(title, negative = "取消", custom = field) { onDone(field.text.toString()) }
}
internal fun MainActivity.progress(parent: LinearLayout, maximum: Int, current: Int): ProgressBar = ProgressBar(this, null, android.R.attr.progressBarStyleHorizontal).apply {
    progressDrawable = android.graphics.drawable.LayerDrawable(arrayOf(
        shape(palette.stroke, 6), android.graphics.drawable.ClipDrawable(shape(palette.accent, 6), Gravity.START, android.graphics.drawable.ClipDrawable.HORIZONTAL)
    )).apply {
        setId(0, android.R.id.background)
        setId(1, android.R.id.progress)
    }
    max = maximum.coerceAtLeast(1); progress = current.coerceIn(0, max)
    parent.addView(this, LinearLayout.LayoutParams(-1, dp(5)).apply { topMargin = dp(10) })
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
        paint.color = tint; paint.strokeWidth = 1.65f; paint.style = Paint.Style.STROKE; paint.strokeCap = Paint.Cap.ROUND
        when (index) {
            0 -> { c.drawLine(4f, 20f, 20f, 20f, paint); c.drawLine(6f, 15f, 6f, 10f, paint); c.drawLine(12f, 15f, 12f, 4f, paint); c.drawLine(18f, 15f, 18f, 7f, paint) }
            1 -> { c.drawRoundRect(3f, 4f, 21f, 17f, 4f, 4f, paint); c.drawLine(8f, 17f, 5f, 21f, paint); c.drawLine(8f, 10.5f, 16f, 10.5f, paint) }
            2 -> { c.drawRoundRect(5f, 3f, 19f, 21f, 3f, 3f, paint); c.drawLine(8.5f, 12f, 11f, 14.5f, paint); c.drawLine(11f, 14.5f, 15.5f, 9f, paint) }
            else -> { c.drawCircle(12f, 12f, 7f, paint); c.drawCircle(12f, 12f, 2.5f, paint); for(i in 0..7) { c.save(); c.rotate(i * 45f, 12f, 12f); c.drawLine(12f, 3f, 12f, 5f, paint); c.restore() } }
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

