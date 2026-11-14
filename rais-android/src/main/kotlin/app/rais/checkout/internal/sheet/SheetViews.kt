package app.rais.checkout.internal.sheet

import android.annotation.SuppressLint
import android.content.Context
import android.content.res.ColorStateList
import android.graphics.Bitmap
import android.graphics.Outline
import android.graphics.drawable.GradientDrawable
import android.os.Build
import android.text.Editable
import android.text.InputFilter
import android.text.SpannableStringBuilder
import android.text.Spanned
import android.text.TextUtils
import android.text.TextWatcher
import android.text.style.AbsoluteSizeSpan
import android.text.style.ForegroundColorSpan
import android.view.Gravity
import android.view.HapticFeedbackConstants
import android.view.View
import android.view.ViewGroup
import android.view.ViewOutlineProvider
import android.view.inputmethod.EditorInfo
import android.view.inputmethod.InputMethodManager
import android.widget.EditText
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.ProgressBar
import android.widget.Switch
import android.widget.TextView
import androidx.annotation.DrawableRes
import androidx.core.widget.ImageViewCompat
import app.rais.checkout.R
import kotlin.math.max
import kotlin.math.min

/** What every part of the native checkout is built with: the look, the server's words, and the reading direction of those words. */
internal class SheetContext(val theme: SheetTheme, val words: Words, val rtl: Boolean, val sandbox: Boolean)

internal const val MATCH = ViewGroup.LayoutParams.MATCH_PARENT
internal const val WRAP = ViewGroup.LayoutParams.WRAP_CONTENT

/**
 * A column (or row) of views with a gap between them, as a stack view: [add] takes the gap before the view (the stack's own gap when
 * `null`). A hidden view takes its gap with it.
 */
internal open class Stack(context: Context, vertical: Boolean = true, private val gap: Int = 0) : LinearLayout(context) {
    init {
        orientation = if (vertical) VERTICAL else HORIZONTAL
        if (!vertical) gravity = Gravity.CENTER_VERTICAL
    }

    fun add(view: View, before: Int? = null, width: Int = if (orientation == VERTICAL) MATCH else WRAP, height: Int = WRAP, weight: Float = 0f, gravity: Int = -1): View {
        val first = childCount == 0
        val space = if (first) 0 else before ?: gap
        val params = LayoutParams(width, height, weight)
        if (orientation == VERTICAL) params.topMargin = space else params.marginStart = space
        if (gravity != -1) params.gravity = gravity
        addView(view, params)
        return view
    }

    /** The gap before [view], once it is in the stack. */
    fun gapBefore(view: View, space: Int) {
        val params = view.layoutParams as LayoutParams
        if (orientation == VERTICAL) params.topMargin = space else params.marginStart = space
        view.layoutParams = params
    }
}

/** A line of text in one of the theme's colors. */
@SuppressLint("ViewConstructor")
internal class Label(
    context: Context,
    private val theme: SheetTheme,
    size: Float,
    weight: Int = 400,
    digits: Boolean = false,
    var color: () -> Int = { theme.text },
) : TextView(context), Themed {
    init {
        theme.type(this, size, weight, digits)
        textAlignment = TEXT_ALIGNMENT_VIEW_START
        setLineSpacing(0f, 1.12f)
        applyTheme()
    }

    fun centered(): Label = apply { textAlignment = TEXT_ALIGNMENT_CENTER; gravity = Gravity.CENTER_HORIZONTAL }

    override fun applyTheme() = setTextColor(color())
}

/** A glyph of the SDK (a Material icon or a card network's mark), tinted with one of the theme's colors unless it is a picture. */
@SuppressLint("ViewConstructor")
internal class Glyph(context: Context, @DrawableRes icon: Int, private val tint: (() -> Int)?) : ImageView(context), Themed {
    init {
        setImageResource(icon)
        scaleType = ScaleType.FIT_CENTER
        importantForAccessibility = IMPORTANT_FOR_ACCESSIBILITY_NO
        applyTheme()
    }

    fun set(@DrawableRes icon: Int) = setImageResource(icon)

    override fun applyTheme() {
        tint?.let { ImageViewCompat.setImageTintList(this, ColorStateList.valueOf(it())) }
    }
}

internal fun View.haptic(kind: Int) {
    performHapticFeedback(kind)
}

internal val HAPTIC_SUCCESS = if (Build.VERSION.SDK_INT >= 30) HapticFeedbackConstants.CONFIRM else HapticFeedbackConstants.VIRTUAL_KEY
internal val HAPTIC_ERROR = if (Build.VERSION.SDK_INT >= 30) HapticFeedbackConstants.REJECT else HapticFeedbackConstants.LONG_PRESS
internal val HAPTIC_SELECTION = if (Build.VERSION.SDK_INT >= 27) HapticFeedbackConstants.KEYBOARD_TAP else HapticFeedbackConstants.VIRTUAL_KEY

// ── Buttons ───────────────────────────────────────────────────────────────────────────────────────────────────────

/** The checkout's button: a filled one for the action of the screen, a quiet one for what is beside it, a plain one for a way out. */
@SuppressLint("ViewConstructor")
internal class SheetButton(
    context: Context,
    private val theme: SheetTheme,
    private val kind: Kind,
    title: String,
    @DrawableRes icon: Int? = null,
) : FrameLayout(context), Themed {
    enum class Kind { Primary, Secondary, Plain }

    private val label = TextView(context)
    private val iconView = ImageView(context)
    private val spinner = ProgressBar(context, null, android.R.attr.progressBarStyleSmall)
    private val row = LinearLayout(context)

    var title: String = title
        set(value) {
            field = value
            label.text = value
            contentDescription = value
        }

    @DrawableRes var icon: Int? = icon
        set(value) {
            field = value
            applyIcon()
        }

    /** A request is on its way: the title gives way to a spinner and the button takes no tap. */
    var isLoading = false
        set(value) {
            if (field == value) return
            field = value
            row.alpha = if (value) 0f else 1f
            spinner.visibility = if (value) VISIBLE else GONE
            isClickable = !value
        }

    init {
        isClickable = true
        isFocusable = true
        if (Build.VERSION.SDK_INT >= 26) defaultFocusHighlightEnabled = false   // a ring of the button's own shape instead (applyTheme)
        contentDescription = title
        val plain = kind == Kind.Plain
        minimumHeight = theme.dp(if (plain) 44 else 54)
        theme.type(label, if (plain) 15f else 17f, 600)
        label.text = title
        label.maxLines = 2
        label.ellipsize = TextUtils.TruncateAt.END
        label.gravity = Gravity.CENTER
        label.importantForAccessibility = IMPORTANT_FOR_ACCESSIBILITY_NO
        iconView.importantForAccessibility = IMPORTANT_FOR_ACCESSIBILITY_NO
        row.orientation = LinearLayout.HORIZONTAL
        row.gravity = Gravity.CENTER
        val iconSize = theme.dp(if (plain) 15 else 18)
        row.addView(iconView, LinearLayout.LayoutParams(iconSize, iconSize).apply { marginEnd = theme.dp(8) })
        row.addView(label, LinearLayout.LayoutParams(WRAP, WRAP))
        val side = theme.dp(if (plain) 4 else 16)
        addView(row, LayoutParams(WRAP, WRAP, Gravity.CENTER).apply { setMargins(side, theme.dp(8), side, theme.dp(8)) })
        spinner.visibility = GONE
        addView(spinner, LayoutParams(theme.dp(22), theme.dp(22), Gravity.CENTER))
        applyIcon()
        applyTheme()
    }

    private fun applyIcon() {
        val id = icon
        if (id == null) {
            iconView.visibility = GONE
        } else {
            iconView.setImageResource(id)
            iconView.visibility = VISIBLE
        }
    }

    fun onTap(action: () -> Unit) = setOnClickListener { if (!isLoading && isEnabled) action() }

    /** As on iOS, a title that does not fit shrinks (down to three quarters of its size) before it takes a second line. */
    override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
        val plain = kind == Kind.Plain
        val base = theme.textPx(if (plain) 15f else 17f)
        if (MeasureSpec.getMode(widthMeasureSpec) != MeasureSpec.UNSPECIFIED) {
            val side = theme.dp(if (plain) 4 else 16) * 2
            val iconRoom = if (iconView.visibility == VISIBLE) theme.dp(if (plain) 15 else 18) + theme.dp(8) else 0
            val available = MeasureSpec.getSize(widthMeasureSpec) - side - iconRoom
            val paint = android.text.TextPaint(label.paint).apply { textSize = base }
            val wanted = paint.measureText(title)
            val size = if (wanted > available && available > 0) base * max(0.75f, available / wanted) else base
            if (kotlin.math.abs(label.textSize - size) > 0.5f) label.setTextSize(android.util.TypedValue.COMPLEX_UNIT_PX, size)
        }
        super.onMeasure(widthMeasureSpec, heightMeasureSpec)
    }

    override fun applyTheme() {
        val ink: Int
        // (focused from a keyboard or a D-pad only: a touch never focuses a button)
        val ring = if (isFocused) theme.dp(2f) else 0f
        when (kind) {
            Kind.Primary -> {
                background = theme.rounded(theme.primary, theme.radius, theme.soft(theme.onPrimary, 0.6f), ring)
                ink = theme.onPrimary
            }
            Kind.Secondary -> {
                background = theme.rounded(theme.surface, theme.radius, theme.primary, ring)
                ink = theme.text
            }
            Kind.Plain -> {
                background = if (isFocused) theme.rounded(0, theme.radius, theme.primary, ring) else null
                ink = theme.secondary
            }
        }
        label.setTextColor(ink)
        ImageViewCompat.setImageTintList(iconView, ColorStateList.valueOf(ink))
        spinner.indeterminateTintList = ColorStateList.valueOf(ink)
        refreshAlpha()
    }

    private fun refreshAlpha() {
        alpha = if (!isEnabled) 0.4f else if (isPressed) 0.72f else 1f
    }

    override fun setEnabled(enabled: Boolean) {
        super.setEnabled(enabled)
        refreshAlpha()
    }

    override fun setPressed(pressed: Boolean) {
        super.setPressed(pressed)
        refreshAlpha()
    }

    override fun onFocusChanged(gainFocus: Boolean, direction: Int, previouslyFocusedRect: android.graphics.Rect?) {
        super.onFocusChanged(gainFocus, direction, previouslyFocusedRect)
        applyTheme()
    }
}

// ── Fields ────────────────────────────────────────────────────────────────────────────────────────────────────────

/** One field: a small caption that never moves and the value under it. Several of them make an [InputGroup]. */
@SuppressLint("ViewConstructor")
internal class InputCell(
    context: Context,
    private val theme: SheetTheme,
    caption: String,
    placeholder: String? = null,
    latin: Boolean = true,
    accessory: View? = null,
) : LinearLayout(context), Themed {
    val edit = EditText(context)
    private val captionView = Label(context, theme, 12f, 500) { if (hasError) theme.danger else theme.secondary }

    /** Rewrites what is typed (groups of four, "MM / YY", digits only); the caret stays where the payer is typing. */
    var formatter: ((String) -> String)? = null
    var maxLength: Int? = null
        set(value) {
            field = value
            edit.filters = if (value == null) arrayOf() else arrayOf(InputFilter.LengthFilter(value))
        }
    var onChange: (() -> Unit)? = null
    var onReturn: (() -> Unit)? = null
    var onFocus: ((Boolean) -> Unit)? = null

    var text: String
        get() = edit.text?.toString() ?: ""
        set(value) {
            edit.setText(formatter?.invoke(value) ?: value)
        }

    var hasError = false
        set(value) {
            field = value
            captionView.applyTheme()
        }

    private var formatting = false
    private var removedSeparatorAt = -1
    private var inserted = false

    private val watcher = object : TextWatcher {
        override fun beforeTextChanged(text: CharSequence, start: Int, count: Int, after: Int) {
            if (formatting || formatter == null) return
            // Backspace over a separator takes the digit before it too, or the separator would only come back.
            removedSeparatorAt = if (count == 1 && after == 0 && CardRules.digits(text.substring(start, start + 1)).isEmpty()) start else -1
        }

        override fun onTextChanged(text: CharSequence, start: Int, before: Int, count: Int) {
            if (!formatting) inserted = count > 0
        }

        override fun afterTextChanged(editable: Editable) {
            if (formatting) return
            val format = formatter
            if (format == null) {
                onChange?.invoke()
                return
            }
            formatting = true
            var proposed = editable.toString()
            var caret = edit.selectionEnd.coerceIn(0, proposed.length)
            if (removedSeparatorAt >= 0) {
                var index = min(removedSeparatorAt, proposed.length) - 1
                while (index >= 0 && CardRules.digits(proposed.substring(index, index + 1)).isEmpty()) index--
                if (index >= 0) {
                    proposed = proposed.removeRange(index, index + 1)
                    caret = index
                }
                removedSeparatorAt = -1
            }
            val digitsBefore = CardRules.digits(proposed.substring(0, min(caret, proposed.length))).length
            val formatted = format(proposed)
            if (formatted != editable.toString()) editable.replace(0, editable.length, formatted)
            var position = 0
            var seen = 0
            while (position < formatted.length && seen < digitsBefore) {
                if (formatted[position] in '0'..'9') seen++
                position++
            }
            if (inserted) while (position < formatted.length && formatted[position] !in '0'..'9') position++
            edit.setSelection(position.coerceIn(0, edit.text?.length ?: 0))
            formatting = false
            onChange?.invoke()
        }
    }

    init {
        orientation = HORIZONTAL
        gravity = Gravity.CENTER_VERTICAL
        minimumHeight = theme.dp(60)
        setPadding(theme.dp(14), theme.dp(10), theme.dp(14), theme.dp(10))
        captionView.text = caption
        captionView.importantForAccessibility = IMPORTANT_FOR_ACCESSIBILITY_NO

        edit.background = null
        edit.setPadding(0, theme.dp(3), 0, 0)
        theme.type(edit, 17f, 400, digits = true)
        edit.isSingleLine = true
        edit.maxLines = 1
        edit.contentDescription = caption
        edit.hint = placeholder
        edit.imeOptions = EditorInfo.IME_ACTION_NEXT or EditorInfo.IME_FLAG_NO_EXTRACT_UI
        if (latin) {
            edit.textDirection = TEXT_DIRECTION_LTR
        }
        edit.textAlignment = TEXT_ALIGNMENT_VIEW_START
        edit.addTextChangedListener(watcher)
        edit.setOnFocusChangeListener { _, focused -> onFocus?.invoke(focused) }
        edit.setOnEditorActionListener { _, action, _ ->
            val done = action == EditorInfo.IME_ACTION_DONE || action == EditorInfo.IME_ACTION_SEND || action == EditorInfo.IME_ACTION_GO
            if (done) {
                onReturn?.invoke() ?: blur()
                true
            } else {
                false
            }
        }

        val column = LinearLayout(context).apply { orientation = VERTICAL }
        column.addView(captionView, LayoutParams(MATCH, WRAP))
        column.addView(edit, LayoutParams(MATCH, WRAP))
        addView(column, LayoutParams(0, WRAP, 1f))
        if (accessory != null) addView(accessory, LayoutParams(WRAP, WRAP).apply { marginStart = theme.dp(10) })
        setOnClickListener { focus() }
        applyTheme()
    }

    fun focus() {
        edit.requestFocus()
        edit.setSelection(edit.text?.length ?: 0)
        (context.getSystemService(Context.INPUT_METHOD_SERVICE) as InputMethodManager).showSoftInput(edit, InputMethodManager.SHOW_IMPLICIT)
    }

    fun blur() {
        edit.clearFocus()
        (context.getSystemService(Context.INPUT_METHOD_SERVICE) as InputMethodManager).hideSoftInputFromWindow(edit.windowToken, 0)
    }

    override fun applyTheme() {
        captionView.applyTheme()
        edit.setTextColor(theme.text)
        edit.setHintTextColor(theme.tertiary)
        edit.highlightColor = theme.soft(theme.primary, 0.25f)
        if (Build.VERSION.SDK_INT >= 29) {
            edit.textCursorDrawable = GradientDrawable().apply {
                setColor(theme.primary)
                setSize(theme.dp(2), theme.dp(20))
            }
        }
    }
}

/**
 * Fields that belong together, in one bordered box: rows of one or more cells, a hairline between them, and under the box the first thing
 * that is wrong with what was typed (and an optional note).
 */
@SuppressLint("ViewConstructor")
internal class InputGroup(context: Context, private val theme: SheetTheme, rows: List<List<InputCell>>, note: String? = null) :
    LinearLayout(context), Themed {
    private val box = LinearLayout(context)
    private val message = Label(context, theme, 13f) { theme.danger }
    private val noteView = Label(context, theme, 13f) { theme.secondary }
    private val separators = mutableListOf<View>()
    val cells = mutableListOf<InputCell>()
    private var focusCount = 0

    /** A field of the group took the focus. */
    var onFocus: ((InputCell) -> Unit)? = null

    init {
        orientation = VERTICAL
        box.orientation = VERTICAL
        rows.forEachIndexed { index, cellsOfRow ->
            if (index > 0) box.addView(hairline(), LayoutParams(MATCH, max(1, theme.dp(1))))
            val row = LinearLayout(context).apply { orientation = HORIZONTAL }
            cellsOfRow.forEachIndexed { position, cell ->
                if (position > 0) row.addView(hairline(), LayoutParams(max(1, theme.dp(1)), MATCH))
                row.addView(cell, LayoutParams(0, WRAP, 1f))
                cells.add(cell)
                cell.onFocus = { on ->
                    focusCount = max(0, focusCount + if (on) 1 else -1)
                    applyBorder()
                    if (on) onFocus?.invoke(cell)
                }
            }
            box.addView(row, LayoutParams(MATCH, WRAP))
        }
        addView(box, LayoutParams(MATCH, WRAP))
        message.visibility = GONE
        addView(message, LayoutParams(MATCH, WRAP).apply { topMargin = theme.dp(6) })
        noteView.text = note
        noteView.visibility = if (note == null) GONE else VISIBLE
        addView(noteView, LayoutParams(MATCH, WRAP).apply { topMargin = theme.dp(6) })
        applyTheme()
    }

    private fun hairline() = View(context).also { separators.add(it) }

    /** Says what is wrong under the box and marks the fields it is about; `null` clears it. */
    fun setError(text: String?, wrong: List<InputCell> = emptyList()) {
        for (cell in cells) cell.hasError = text != null && wrong.any { it === cell }
        message.text = text
        message.visibility = if (text == null) GONE else VISIBLE
        if (text != null) announceForAccessibility(text)
        applyBorder()
    }

    val hasError: Boolean get() = message.visibility == VISIBLE

    private fun applyBorder() {
        val color = if (hasError) theme.danger else if (focusCount > 0) theme.primary else theme.border
        val width = theme.dp(if (hasError || focusCount > 0) 1.5f else 1f)
        box.background = theme.rounded(theme.surface, theme.radius, color, width)
    }

    override fun applyTheme() {
        for (line in separators) line.setBackgroundColor(theme.line)
        applyBorder()
    }
}

// ── A switch with its sentence ────────────────────────────────────────────────────────────────────────────────────

/** Something the payer may turn on or off ("Save this card"), as a row of its own: the sentence, and the system's switch. */
@SuppressLint("ViewConstructor", "UseSwitchCompatOrMaterialCode")
internal class ToggleRow(context: Context, private val theme: SheetTheme, text: String, isOn: Boolean) : LinearLayout(context), Themed {
    private val toggle = Switch(context)
    private val label = Label(context, theme, 15f)

    var isOn: Boolean
        get() = toggle.isChecked
        set(value) {
            toggle.isChecked = value
        }

    init {
        orientation = HORIZONTAL
        gravity = Gravity.CENTER_VERTICAL
        minimumHeight = theme.dp(52)
        setPadding(theme.dp(14), theme.dp(10), theme.dp(12), theme.dp(10))
        label.text = text
        label.importantForAccessibility = IMPORTANT_FOR_ACCESSIBILITY_NO
        toggle.isChecked = isOn
        toggle.contentDescription = text
        toggle.setOnCheckedChangeListener { view, _ -> view.haptic(HAPTIC_SELECTION) }
        addView(label, LayoutParams(0, WRAP, 1f))
        addView(toggle, LayoutParams(WRAP, WRAP).apply { marginStart = theme.dp(12) })
        setOnClickListener { toggle.toggle() }
        applyTheme()
    }

    /** The switch is drawn here, not by the platform's theme (which shows the accent at half strength): the accent when on, as on iOS. */
    override fun applyTheme() {
        background = theme.rounded(theme.surface, theme.radius)
        val states = arrayOf(intArrayOf(android.R.attr.state_checked), intArrayOf())
        val off = androidx.core.graphics.ColorUtils.blendARGB(theme.surface, theme.text, 0.18f)
        val track = GradientDrawable().apply {
            cornerRadius = theme.dp(14f)
            setSize(theme.dp(48), theme.dp(28))
            color = ColorStateList(states, intArrayOf(theme.primary, off))
        }
        val knob = GradientDrawable().apply {
            shape = GradientDrawable.OVAL
            setSize(theme.dp(24), theme.dp(24))
            color = ColorStateList(states, intArrayOf(theme.onPrimary, 0xFFFFFFFF.toInt()))
        }
        toggle.trackTintList = null
        toggle.thumbTintList = null
        toggle.trackDrawable = track
        toggle.thumbDrawable = android.graphics.drawable.InsetDrawable(knob, theme.dp(2))
        toggle.switchMinWidth = theme.dp(48)
        toggle.showText = false
        label.applyTheme()
    }
}

// ── Panes ─────────────────────────────────────────────────────────────────────────────────────────────────────────

/**
 * A pane that floats over the page: the pane that holds the button, the disc of the close button. Android has no backdrop blur for views,
 * so it is the page's color, nearly opaque, with a hairline rim and a soft shadow that lifts it off the page.
 */
@SuppressLint("ViewConstructor")
internal class GlassPane(context: Context, private val theme: SheetTheme, private val radius: Float?, floats: Boolean = false) :
    FrameLayout(context), Themed {
    init {
        if (floats) {
            elevation = theme.dp(14f)
            outlineProvider = ViewOutlineProvider.BACKGROUND
        }
        applyTheme()
    }

    override fun applyTheme() {
        val fill = SheetTheme.withAlpha(androidx.core.graphics.ColorUtils.blendARGB(theme.background, theme.text, if (theme.isDark) 0.08f else 0.03f), 0.97f)
        background = GradientDrawable().apply {
            if (radius == null) shape = GradientDrawable.OVAL else cornerRadius = radius
            setColor(fill)
            setStroke(max(1, theme.dp(0.5f).toInt()), theme.border)
        }
        if (Build.VERSION.SDK_INT >= 28) {
            outlineAmbientShadowColor = 0xFF000000.toInt()
            outlineSpotShadowColor = if (theme.isDark) 0xFF000000.toInt() else 0x66000000
        }
    }
}

/**
 * The page's color fading out downwards: what scrolls under the status bar, the close button and the test badge dissolves into the page
 * instead of running into them. Nearly opaque down past the row of the close button (the first 60 %), then fading to nothing; the screen
 * drives its alpha with the scroll, so at rest there is nothing over the page.
 */
@SuppressLint("ViewConstructor")
internal class FadeView(context: Context, private val theme: SheetTheme) : View(context), Themed {
    private val paint = android.graphics.Paint()

    init {
        importantForAccessibility = IMPORTANT_FOR_ACCESSIBILITY_NO
        applyTheme()
    }

    override fun onSizeChanged(width: Int, height: Int, oldWidth: Int, oldHeight: Int) {
        super.onSizeChanged(width, height, oldWidth, oldHeight)
        applyTheme()
    }

    override fun onDraw(canvas: android.graphics.Canvas) {
        canvas.drawRect(0f, 0f, width.toFloat(), height.toFloat(), paint)
    }

    override fun applyTheme() {
        val color = theme.background
        paint.shader = android.graphics.LinearGradient(
            0f, 0f, 0f, height.coerceAtLeast(1).toFloat(),
            intArrayOf(SheetTheme.withAlpha(color, 0.97f), SheetTheme.withAlpha(color, 0.9f), SheetTheme.withAlpha(color, 0f)),
            floatArrayOf(0f, 0.6f, 1f),
            android.graphics.Shader.TileMode.CLAMP,
        )
        invalidate()
    }
}

// ── Choices ───────────────────────────────────────────────────────────────────────────────────────────────────────

/**
 * One way to pay among several: a balance, a saved card, another card. Its mark leads (the network of a card, the Raïs mark), and the one
 * that is chosen carries a check.
 */
@SuppressLint("ViewConstructor")
internal class ChoiceRow(
    context: Context,
    private val theme: SheetTheme,
    title: String,
    detail: String?,
    latinTitle: Boolean = false,
    mark: View? = null,
) : LinearLayout(context), Themed {
    private val check = ImageView(context)
    private val titleView = Label(context, theme, 16f, 500, digits = true)
    private val detailView = Label(context, theme, 13f, digits = true) { theme.secondary }

    var choiceSelected = false
        set(value) {
            field = value
            applyTheme()
        }

    /** (The platform's constructor may set the enabled and pressed states before this view's own parts exist.) */
    private var built = false

    init {
        orientation = HORIZONTAL
        gravity = Gravity.CENTER_VERTICAL
        minimumHeight = theme.dp(62)
        setPadding(theme.dp(14), theme.dp(12), theme.dp(14), theme.dp(12))
        isClickable = true
        isFocusable = true
        if (Build.VERSION.SDK_INT >= 26) defaultFocusHighlightEnabled = false   // the row's own pressed color shows its focus
        contentDescription = listOfNotNull(title, detail).joinToString(", ")
        // every mark sits in a slot of the same width, so the titles of the rows line up whatever leads them
        val slot = FrameLayout(context)
        if (mark != null) slot.addView(mark, FrameLayout.LayoutParams(WRAP, WRAP, Gravity.CENTER))
        addView(slot, LayoutParams(theme.dp(34), theme.dp(34)))
        titleView.text = title
        titleView.maxLines = 2
        if (latinTitle) titleView.textDirection = TEXT_DIRECTION_LTR
        detailView.text = detail
        detailView.visibility = if (detail == null) GONE else VISIBLE
        val column = LinearLayout(context).apply { orientation = VERTICAL }
        column.addView(titleView, LayoutParams(MATCH, WRAP))
        column.addView(detailView, LayoutParams(MATCH, WRAP).apply { topMargin = theme.dp(2) })
        for (view in listOf(titleView, detailView)) view.importantForAccessibility = IMPORTANT_FOR_ACCESSIBILITY_NO
        addView(column, LayoutParams(0, WRAP, 1f).apply { marginStart = theme.dp(12); marginEnd = theme.dp(12) })
        check.importantForAccessibility = IMPORTANT_FOR_ACCESSIBILITY_NO
        addView(check, LayoutParams(theme.dp(22), theme.dp(22)))
        built = true
        applyTheme()
    }

    override fun applyTheme() {
        check.setImageResource(if (choiceSelected) R.drawable.rais_ic_check_circle else R.drawable.rais_ic_circle)
        ImageViewCompat.setImageTintList(check, ColorStateList.valueOf(if (choiceSelected) theme.primary else theme.tertiary))
        isSelected = choiceSelected
        alpha = if (isEnabled) 1f else 0.45f
        setBackgroundColor(if (isPressed || isFocused) theme.line else 0)
        titleView.applyTheme()
        detailView.applyTheme()
    }

    override fun setEnabled(enabled: Boolean) {
        super.setEnabled(enabled)
        if (built) applyTheme()
    }

    override fun setPressed(pressed: Boolean) {
        super.setPressed(pressed)
        if (built) setBackgroundColor(if (pressed || isFocused) theme.line else 0)
    }

    override fun onFocusChanged(gainFocus: Boolean, direction: Int, previouslyFocusedRect: android.graphics.Rect?) {
        super.onFocusChanged(gainFocus, direction, previouslyFocusedRect)
        if (built) applyTheme()
    }
}

/** Rows in one rounded box with a hairline between them: the ways to pay, the lines of an order. */
@SuppressLint("ViewConstructor")
internal class GroupBox(context: Context, private val theme: SheetTheme) : LinearLayout(context), Themed {
    private val lines = mutableListOf<View>()

    init {
        orientation = VERTICAL
        clipToOutline = true
        outlineProvider = object : ViewOutlineProvider() {
            override fun getOutline(view: View, outline: Outline) = outline.setRoundRect(0, 0, view.width, view.height, theme.cardRadius)
        }
        applyTheme()
    }

    fun add(row: View) {
        if (childCount > 0) {
            val line = View(context)
            lines.add(line)
            line.setBackgroundColor(theme.line)
            addView(line, LayoutParams(MATCH, max(1, theme.dp(1))))
        }
        addView(row, LayoutParams(MATCH, WRAP))
    }

    override fun applyTheme() {
        background = theme.rounded(theme.surface, theme.cardRadius)
        for (line in lines) line.setBackgroundColor(theme.line)
    }
}

/** Labels and their values, one per row (a reference, a date, an amount). */
internal fun rowsCard(context: SheetContext, android: Context, rows: List<Sheet.Row>): GroupBox {
    val theme = context.theme
    val box = GroupBox(android, theme)
    for (row in rows) {
        val label = Label(android, theme, 14f) { theme.secondary }
        label.text = row.label
        val value = Label(android, theme, if (row.mono) 13f else 14f, 500, digits = true)
        if (row.mono) theme.mono(value, 13f, 500)
        value.text = row.value
        value.textAlignment = View.TEXT_ALIGNMENT_VIEW_END   // the far edge of its row in the page's direction, whatever the value's script
        if (row.mono) value.textDirection = View.TEXT_DIRECTION_LTR
        val line = LinearLayout(android).apply {
            orientation = LinearLayout.HORIZONTAL
            isBaselineAligned = true
            setPadding(theme.dp(14), theme.dp(12), theme.dp(14), theme.dp(12))
            importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_YES
            contentDescription = "${row.label}, ${row.value}"
        }
        label.importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_NO
        value.importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_NO
        line.addView(label, LinearLayout.LayoutParams(WRAP, WRAP))
        line.addView(value, LinearLayout.LayoutParams(0, WRAP, 1f).apply { marginStart = theme.dp(16) })
        box.add(line)
    }
    return box
}

// ── Small parts ───────────────────────────────────────────────────────────────────────────────────────────────────

/** The merchant's logo, filling its tile, or its initial until (or instead of) the logo. */
@SuppressLint("ViewConstructor")
internal class AvatarView(context: Context, private val theme: SheetTheme, name: String, sizeDp: Int = 64) : FrameLayout(context), Themed {
    private val initial = Label(context, theme, sizeDp * 0.42f, 600) { theme.secondary }
    private val picture = ImageView(context)
    private val corner = theme.dp(sizeDp * 0.28f)

    init {
        importantForAccessibility = IMPORTANT_FOR_ACCESSIBILITY_NO_HIDE_DESCENDANTS
        clipToOutline = true
        outlineProvider = object : ViewOutlineProvider() {
            override fun getOutline(view: View, outline: Outline) = outline.setRoundRect(0, 0, view.width, view.height, corner)
        }
        initial.text = name.trim().firstOrNull()?.uppercase() ?: "?"
        initial.gravity = Gravity.CENTER
        initial.textAlignment = TEXT_ALIGNMENT_CENTER
        addView(initial, LayoutParams(MATCH, MATCH))
        picture.scaleType = ImageView.ScaleType.CENTER_CROP   // the logo fills its tile
        picture.visibility = GONE
        addView(picture, LayoutParams(MATCH, MATCH))
        applyTheme()
    }

    fun show(bitmap: Bitmap, animated: Boolean = true) {
        picture.setImageBitmap(bitmap)
        reveal(animated)
    }

    fun show(@DrawableRes drawable: Int) {
        picture.setImageResource(drawable)
        reveal(false)
    }

    private fun reveal(animated: Boolean) {
        picture.visibility = VISIBLE
        initial.visibility = GONE
        if (animated) {
            picture.alpha = 0f
            picture.animate().alpha(1f).setDuration(200).start()
        }
    }

    override fun applyTheme() {
        background = theme.rounded(theme.surface, corner, theme.line, theme.dp(1f))
        foreground = theme.rounded(0, corner, theme.line, theme.dp(1f))   // the rim stays over a logo that fills the tile
        initial.applyTheme()
    }
}

/** A short status word in a capsule ("Test mode"). */
@SuppressLint("ViewConstructor")
internal class Chip(context: Context, private val theme: SheetTheme, text: String, private val color: () -> Int) : FrameLayout(context), Themed {
    private val label = Label(context, theme, 12f, 600, color = color)

    init {
        label.text = text
        label.maxLines = 1
        addView(label, LayoutParams(WRAP, WRAP, Gravity.CENTER).apply { setMargins(theme.dp(10), theme.dp(4), theme.dp(10), theme.dp(4)) })
        minimumHeight = theme.dp(24)
        applyTheme()
    }

    override fun applyTheme() {
        // opaque (the soft tone over the page's color): the page scrolls under the chip and must not show through its words
        background = theme.rounded(androidx.core.graphics.ColorUtils.blendARGB(theme.background, color(), 0.14f), theme.dp(12f))
        label.applyTheme()
    }
}

/** A sentence the payer should not miss, in a soft box of its tone: something that went wrong, something to know. */
@SuppressLint("ViewConstructor")
internal class NoticeView(
    context: Context,
    private val theme: SheetTheme,
    private val tone: Tone,
    text: String? = null,
    @DrawableRes icon: Int? = null,
) : LinearLayout(context), Themed {
    enum class Tone { Danger, Neutral }

    private val glyph = Glyph(context, icon ?: if (tone == Tone.Danger) R.drawable.rais_ic_error else R.drawable.rais_ic_info) {
        if (tone == Tone.Danger) theme.danger else theme.secondary
    }
    private val label = Label(context, theme, 14f) { if (tone == Tone.Danger) theme.danger else theme.text }

    var text: String?
        get() = label.text?.toString()
        set(value) {
            label.text = value
        }

    init {
        orientation = HORIZONTAL
        gravity = Gravity.TOP
        setPadding(theme.dp(14), theme.dp(12), theme.dp(14), theme.dp(12))
        label.text = text
        addView(glyph, LayoutParams(theme.dp(17), theme.dp(17)).apply { topMargin = theme.dp(1) })
        addView(label, LayoutParams(0, WRAP, 1f).apply { marginStart = theme.dp(10) })
        applyTheme()
    }

    override fun applyTheme() {
        background = theme.rounded(if (tone == Tone.Danger) theme.soft(theme.danger, 0.10f) else theme.surface, theme.radius)
        glyph.applyTheme()
        label.applyTheme()
    }
}

/** The marks of the two card networks, CIB and Edahabia. [show] keeps only the network of the number being typed. */
@SuppressLint("ViewConstructor")
internal class BrandMarks(context: Context, theme: SheetTheme, sizeDp: Int = 26) : LinearLayout(context) {
    private val cib = mark(context, theme, R.drawable.rais_mark_cib, sizeDp)
    private val edahabia = mark(context, theme, R.drawable.rais_mark_edahabia, sizeDp)

    init {
        orientation = HORIZONTAL
        layoutDirection = LAYOUT_DIRECTION_LTR
        contentDescription = "CIB, Edahabia"
        addView(cib, LayoutParams(theme.dp(sizeDp), theme.dp(sizeDp)))
        addView(edahabia, LayoutParams(theme.dp(sizeDp), theme.dp(sizeDp)).apply { marginStart = theme.dp(6) })
    }

    fun show(brand: CardRules.Brand?) {
        val showCib = brand != CardRules.Brand.Edahabia
        val showEdahabia = brand != CardRules.Brand.Cib
        cib.visibility = if (showCib) VISIBLE else GONE
        edahabia.visibility = if (showEdahabia) VISIBLE else GONE
        (edahabia.layoutParams as LayoutParams).marginStart = if (showCib) resources.displayMetrics.density.times(6).toInt() else 0
        contentDescription = listOfNotNull("CIB".takeIf { showCib }, "Edahabia".takeIf { showEdahabia }).joinToString(", ")
    }

    companion object {
        private fun mark(context: Context, theme: SheetTheme, @DrawableRes id: Int, sizeDp: Int) = ImageView(context).apply {
            setImageResource(id)
            scaleType = ImageView.ScaleType.FIT_CENTER
            importantForAccessibility = IMPORTANT_FOR_ACCESSIBILITY_NO
            val corner = theme.dp(sizeDp * 0.22f)
            background = theme.rounded(0xFFFFFFFF.toInt(), corner, 0x4D808080, theme.dp(0.5f).coerceAtLeast(1f))
            clipToOutline = true
            outlineProvider = object : ViewOutlineProvider() {
                override fun getOutline(view: View, outline: Outline) = outline.setRoundRect(0, 0, view.width, view.height, corner)
            }
        }

        /** One mark, for a card that is already known. */
        fun single(context: Context, theme: SheetTheme, brand: String?, sizeDp: Int = 26): View? {
            val known = CardRules.brandNamed(brand) ?: return null
            return BrandMarks(context, theme, sizeDp).apply { show(known) }
        }
    }
}

/** The sign of an outcome: a check, a cross, a clock, in a soft disc of its tone. */
@SuppressLint("ViewConstructor")
internal class IconDisc(context: Context, private val theme: SheetTheme, @DrawableRes icon: Int, private val color: () -> Int, sizeDp: Int = 64) :
    FrameLayout(context), Themed {
    private val glyph = Glyph(context, icon, color)

    init {
        importantForAccessibility = IMPORTANT_FOR_ACCESSIBILITY_NO_HIDE_DESCENDANTS
        val inner = theme.dp(sizeDp * 0.44f).toInt()
        addView(glyph, LayoutParams(inner, inner, Gravity.CENTER))
        applyTheme()
    }

    override fun applyTheme() {
        background = GradientDrawable().apply {
            shape = GradientDrawable.OVAL
            setColor(theme.soft(color(), 0.13f))
        }
        glyph.applyTheme()
    }
}

/** The icon of an outcome, as the server names it in the page's words. */
@DrawableRes
internal fun outcomeIcon(name: String): Int = when (name) {
    "check" -> R.drawable.rais_ic_check
    "x" -> R.drawable.rais_close
    "clock" -> R.drawable.rais_ic_clock
    else -> R.drawable.rais_ic_exclamation
}

/** An amount as its own picture: the figures large, the currency smaller beside them, on one line whatever the language; it shrinks to fit. */
@SuppressLint("ViewConstructor")
internal class AmountLabel(
    context: Context,
    private val theme: SheetTheme,
    private val number: String,
    private val currency: String,
    private val size: Float,
    spoken: String,
) : TextView(context), Themed {
    private var scale = 1f

    init {
        theme.type(this, size, 700, digits = true)
        maxLines = 1
        gravity = Gravity.CENTER
        textAlignment = TEXT_ALIGNMENT_CENTER
        letterSpacing = -0.6f / size
        contentDescription = spoken
        applyTheme()
    }

    private fun build() {
        val text = SpannableStringBuilder(number)
        if (currency.isNotEmpty()) {
            val start = text.length
            text.append(" ").append(currency)
            text.setSpan(AbsoluteSizeSpan((theme.textPx(size * 0.5f) * scale).toInt()), start, text.length, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
            text.setSpan(ForegroundColorSpan(theme.secondary), start, text.length, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
            val face = if (Build.VERSION.SDK_INT >= 28) android.text.style.TypefaceSpan(theme.typeface(600)) else android.text.style.StyleSpan(android.graphics.Typeface.BOLD)
            text.setSpan(face, start, text.length, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
        }
        setTextSize(android.util.TypedValue.COMPLEX_UNIT_PX, theme.textPx(size) * scale)
        setText(text)
    }

    /** The width of the amount at [factor] of its size, measured without touching the view. */
    private fun widthAt(factor: Float): Float {
        val measure = android.text.TextPaint(paint)
        measure.textSize = theme.textPx(size) * factor
        var width = measure.measureText(number)
        if (currency.isNotEmpty()) {
            width += measure.measureText(" ")
            measure.typeface = theme.typeface(600)
            measure.textSize = theme.textPx(size * 0.5f) * factor
            width += measure.measureText(currency)
        }
        return width
    }

    override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
        val available = MeasureSpec.getSize(widthMeasureSpec) - paddingLeft - paddingRight
        if (MeasureSpec.getMode(widthMeasureSpec) != MeasureSpec.UNSPECIFIED && available > 0) {
            var next = 1f
            while (next > 0.5f && widthAt(next) > available) next -= 0.05f
            if (next != scale) {
                scale = next
                build()
            }
        }
        super.onMeasure(widthMeasureSpec, heightMeasureSpec)
    }

    override fun applyTheme() {
        setTextColor(theme.text)
        build()
    }
}

// ── The bank's code ───────────────────────────────────────────────────────────────────────────────────────────────

/**
 * The bank's one-time code: cells that mirror ONE real text field, so paste, the code suggested by the keyboard from a message, and
 * Backspace all behave as the system makes them.
 */
@SuppressLint("ViewConstructor")
internal class CodeField(context: Context, private val theme: SheetTheme, length: Int, label: String) : FrameLayout(context), Themed {
    private val length = length.coerceIn(4, 8)
    private val input = EditText(context)
    private val cells = mutableListOf<TextView>()
    private val row = LinearLayout(context)

    var onChange: (() -> Unit)? = null
    /** The keyboard's own "done" key, with the code complete. */
    var onDone: (() -> Unit)? = null
    var hasError = false
        set(value) {
            field = value
            applyTheme()
        }

    var code: String
        get() = input.text?.toString() ?: ""
        set(value) {
            input.setText(CardRules.digits(value).take(length))
            input.setSelection(input.text?.length ?: 0)
            render()
        }

    val isComplete: Boolean get() = code.length == length

    var isLocked = false
        set(value) {
            field = value
            input.isEnabled = !value
            alpha = if (value) 0.6f else 1f
        }

    init {
        layoutDirection = LAYOUT_DIRECTION_LTR   // a code reads left to right in every language
        input.inputType = android.text.InputType.TYPE_CLASS_NUMBER
        input.filters = arrayOf(InputFilter.LengthFilter(this.length))
        input.isCursorVisible = false
        input.setTextColor(0)
        input.background = null
        input.contentDescription = label
        input.isLongClickable = true
        input.imeOptions = EditorInfo.IME_ACTION_DONE or EditorInfo.IME_FLAG_NO_EXTRACT_UI
        if (Build.VERSION.SDK_INT >= 26) {
            input.setAutofillHints("smsOTPCode")   // androidx.autofill HintConstants.AUTOFILL_HINT_SMS_OTP
            input.importantForAutofill = IMPORTANT_FOR_AUTOFILL_YES
        }
        input.addTextChangedListener(object : TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) = Unit
            override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) = Unit
            override fun afterTextChanged(editable: Editable) {
                val clean = CardRules.digits(editable.toString()).take(length)
                if (clean != editable.toString()) {
                    editable.replace(0, editable.length, clean)
                    return
                }
                if (hasError) hasError = false
                render()
                onChange?.invoke()
            }
        })
        input.setOnFocusChangeListener { _, _ -> applyTheme() }
        input.setOnEditorActionListener { _, _, _ ->
            if (isComplete) onDone?.invoke()
            true
        }
        addView(input, LayoutParams(MATCH, theme.dp(58)))

        row.orientation = LinearLayout.HORIZONTAL
        row.importantForAccessibility = IMPORTANT_FOR_ACCESSIBILITY_NO_HIDE_DESCENDANTS
        for (index in 0 until this.length) {
            val cell = TextView(context)
            theme.type(cell, 24f, 600, digits = true)
            cell.gravity = Gravity.CENTER
            cells.add(cell)
            val gap = if (index == 0) 0 else if (this.length == 6 && index == 3) theme.dp(18) else theme.dp(8)
            row.addView(cell, LinearLayout.LayoutParams(0, MATCH, 1f).apply { marginStart = gap })
        }
        addView(row, LayoutParams(MATCH, theme.dp(58)))
        row.setOnClickListener { focus() }
        render()
    }

    fun focus() {
        input.requestFocus()
        input.setSelection(input.text?.length ?: 0)
        (context.getSystemService(Context.INPUT_METHOD_SERVICE) as InputMethodManager).showSoftInput(input, InputMethodManager.SHOW_IMPLICIT)
    }

    private fun render() {
        val digits = code
        cells.forEachIndexed { index, cell -> cell.text = if (index < digits.length) digits[index].toString() else "" }
        applyTheme()
    }

    override fun applyTheme() {
        val active = if (input.isFocused) min(code.length, length - 1) else -1
        cells.forEachIndexed { index, cell ->
            val color = if (hasError) theme.danger else if (index == active) theme.primary else theme.border
            cell.background = theme.rounded(theme.surface, theme.radius, color, theme.dp(if (hasError || index == active) 1.5f else 1f))
            cell.setTextColor(theme.text)
        }
    }

    /** A wrong code: the cells say no. */
    fun shake() {
        val offsets = floatArrayOf(0f, -6f, 6f, -4f, 3f, 0f).map { theme.dp(it) }
        android.animation.ObjectAnimator.ofFloat(this, "translationX", *offsets.toFloatArray()).setDuration(320).start()
        haptic(HAPTIC_ERROR)
    }
}
