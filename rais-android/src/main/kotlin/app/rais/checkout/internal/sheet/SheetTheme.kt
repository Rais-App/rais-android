package app.rais.checkout.internal.sheet

import android.content.Context
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.os.Build
import android.util.TypedValue
import android.view.View
import android.view.ViewGroup
import android.widget.TextView
import app.rais.checkout.RaisCheckout
import kotlin.math.min
import kotlin.math.roundToInt

/**
 * The look of the native checkout: Raïs' own palette (the hosted page's tokens, light and dark, the same values as rais-ios `SheetTheme`),
 * with whatever the app set in [RaisCheckout.Appearance] on top. Colors are read for the palette on screen ([night]); when the device
 * switches between light and dark the screen flips [night] and asks every [Themed] view to paint itself again.
 */
internal class SheetTheme(
    context: Context,
    private val appearance: RaisCheckout.Appearance,
    var night: Boolean,
    private val fontProvider: RaisCheckout.FontProvider?,
) {
    private val density = context.resources.displayMetrics.density
    /** The device's text size, up to one and a half times ours (as on iOS: larger would break the layout of a payment screen). */
    private val textScale = min(context.resources.configuration.fontScale, 1.5f)
    private val palette get() = if (night) appearance.colorsDark else appearance.colorsLight
    private fun pick(light: Long, dark: Long): Int = (if (night) dark else light).toInt()

    val background: Int get() = palette.background ?: pick(0xFFFFFFFF, 0xFF0A0A0A)
    val text: Int get() = palette.text ?: pick(0xFF17181A, 0xFFFFFFFF)
    val primary: Int get() = palette.primary ?: pick(0xFF17181A, 0xFFFFFFFF)
    val onPrimary: Int get() = palette.onPrimary ?: run {
        val color = primary
        val white = (0.299 * Color.red(color) + 0.587 * Color.green(color) + 0.114 * Color.blue(color)) / 255
        if (white > 0.6) 0xFF0A0A0A.toInt() else Color.WHITE
    }
    /** Fields, cards and rows on the background. */
    val surface: Int get() = palette.surface ?: pick(0xFFF7F7F6, 0xFF151517)
    val secondary: Int get() = palette.textSecondary ?: pick(0xFF5C5B56, 0xFF9A9A9A)
    /** Hints and placeholders. */
    val tertiary: Int get() = withAlpha(text, 0.42f)
    /** Hairlines between rows. */
    val line: Int get() = withAlpha(text, 0.10f)
    /** The border of fields and choices. */
    val border: Int get() = palette.border ?: withAlpha(text, 0.16f)
    val danger: Int get() = palette.danger ?: pick(0xFFC4202E, 0xFFFF7B86)
    val success: Int get() = palette.success ?: pick(0xFF147A39, 0xFF4ADE80)
    val warning: Int get() = pick(0xFF9A5D06, 0xFFFBBF24)

    /** The corner radius of buttons and fields, in pixels; cards are a little rounder. */
    val radius: Float = dp((appearance.cornerRadiusDp ?: 12f).coerceIn(0f, 24f))
    val cardRadius: Float get() = if (radius == 0f) 0f else radius + dp(4f)

    val isDark: Boolean get() = Color.luminance(background) < 0.5f

    /** [color] as a soft fill behind text of the same color (a notice, the disc of an icon). */
    fun soft(color: Int, amount: Float = 0.12f): Int = withAlpha(color, amount)

    fun dp(value: Float): Float = value * density
    fun dp(value: Int): Int = (value * density).roundToInt()

    // ── Type ──

    private val baseFamily: Typeface = when (appearance.fontFamily) {
        RaisCheckout.FontFamily.Default, RaisCheckout.FontFamily.System -> Typeface.SANS_SERIF
        RaisCheckout.FontFamily.Serif -> Typeface.SERIF
        RaisCheckout.FontFamily.Monospace -> Typeface.MONOSPACE
    }
    private val faces = HashMap<Int, Typeface>()

    /** The typeface of the app's chosen family for [weight] (400 regular, 500 medium, 600 semibold, 700 bold). */
    fun typeface(weight: Int): Typeface = faces.getOrPut(weight) {
        fontProvider?.typeface(weight) ?: weighted(baseFamily, weight)
    }

    private fun weighted(family: Typeface, weight: Int): Typeface = when {
        Build.VERSION.SDK_INT >= 28 -> Typeface.create(family, weight, false)
        weight >= 600 -> Typeface.create(family, Typeface.BOLD)
        weight >= 500 && family == Typeface.SANS_SERIF -> Typeface.create("sans-serif-medium", Typeface.NORMAL)
        else -> Typeface.create(family, Typeface.NORMAL)
    }

    /** Sets the type of [view]: [size] in points as on iOS (dp here), grown with the device's text size. `digits`: figures of equal width. */
    fun type(view: TextView, size: Float, weight: Int = 400, digits: Boolean = false) {
        view.typeface = typeface(weight)
        view.setTextSize(TypedValue.COMPLEX_UNIT_PX, textPx(size))
        view.fontFeatureSettings = if (digits) "tnum" else null
        view.includeFontPadding = false
    }

    /** Letters and figures of equal width: a reference, an order number. */
    fun mono(view: TextView, size: Float, weight: Int = 400) {
        view.typeface = weighted(Typeface.MONOSPACE, weight)
        view.setTextSize(TypedValue.COMPLEX_UNIT_PX, textPx(size))
        view.includeFontPadding = false
    }

    fun textPx(size: Float): Float = size * density * textScale

    /** A rounded fill, with an optional border. */
    fun rounded(fill: Int, radius: Float, strokeColor: Int = 0, strokeWidth: Float = 0f): GradientDrawable = GradientDrawable().apply {
        shape = GradientDrawable.RECTANGLE
        cornerRadius = radius
        setColor(fill)
        if (strokeWidth > 0f) setStroke(strokeWidth.roundToInt().coerceAtLeast(1), strokeColor)
    }

    companion object {
        fun withAlpha(color: Int, alpha: Float): Int =
            Color.argb((alpha * 255).roundToInt().coerceIn(0, 255), Color.red(color), Color.green(color), Color.blue(color))
    }
}

/** A view that paints itself from the [SheetTheme]: called when it is built and whenever the device switches between light and dark. */
internal interface Themed {
    fun applyTheme()
}

/** Paints this view and everything in it again (the palette changed). */
internal fun View.applyThemeDeep() {
    (this as? Themed)?.applyTheme()
    if (this is ViewGroup) for (index in 0 until childCount) getChildAt(index).applyThemeDeep()
}
