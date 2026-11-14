package app.rais.checkout.internal

import android.graphics.Color
import app.rais.checkout.RaisCheckout
import org.json.JSONObject

/** What the page receives before any of its own script runs, and the colors the native screen paints behind it. */
internal object PagePayload {
    // tokens.css: --bg
    private const val LIGHT_BACKGROUND = 0xFFFFFFFF.toInt()
    private const val DARK_BACKGROUND = 0xFF0A0A0A.toInt()

    fun isNight(appearance: RaisCheckout.Appearance, systemNight: Boolean): Boolean = when (appearance.colorScheme) {
        RaisCheckout.ColorScheme.Light -> false
        RaisCheckout.ColorScheme.Dark -> true
        RaisCheckout.ColorScheme.Automatic -> systemNight
    }

    private fun colors(appearance: RaisCheckout.Appearance, night: Boolean): RaisCheckout.Colors =
        if (night) appearance.colorsDark else appearance.colorsLight

    /** The color behind the page: what the page will show, so nothing flashes while it loads. */
    fun background(appearance: RaisCheckout.Appearance, night: Boolean): Int =
        colors(appearance, night).background ?: if (night) DARK_BACKGROUND else LIGHT_BACKGROUND

    /** `#rrggbb`, or `#rrggbbaa` when not opaque. */
    fun hex(color: Int): String {
        val base = "#%02x%02x%02x".format(Color.red(color), Color.green(color), Color.blue(color))
        return if (Color.alpha(color) < 255) base + "%02x".format(Color.alpha(color)) else base
    }

    /** Always an explicit `light` or `dark`: "automatic" is decided here, so the page and the SDK can never disagree about the palette. */
    fun build(configuration: RaisCheckout.Configuration, night: Boolean): JSONObject {
        val appearance = configuration.appearance
        val palette = colors(appearance, night)
        val colorMap = JSONObject()
        listOf(
            "primary" to palette.primary, "onPrimary" to palette.onPrimary, "background" to palette.background,
            "surface" to palette.surface, "text" to palette.text, "textSecondary" to palette.textSecondary,
            "border" to palette.border, "danger" to palette.danger, "success" to palette.success,
        ).forEach { (name, value) -> if (value != null) colorMap.put(name, hex(value)) }

        val look = JSONObject()
        if (colorMap.length() > 0) look.put("colors", colorMap)
        appearance.cornerRadiusDp?.let { look.put("cornerRadius", it.coerceIn(0f, 24f).toDouble()) }
        if (appearance.fontFamily != RaisCheckout.FontFamily.Default) look.put("fontFamily", appearance.fontFamily.key)

        return JSONObject()
            .put("platform", "android")
            .put("version", app.rais.checkout.BuildConfig.RAIS_SDK_VERSION)
            .put("theme", if (night) "dark" else "light")
            .put("chrome", if (configuration.showsCloseButton) "native-close" else "none")
            .put("appearance", look)
    }
}
