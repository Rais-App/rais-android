package app.rais.example

import android.graphics.Color
import android.os.Bundle
import android.text.InputType
import android.view.Gravity
import android.view.ViewGroup.LayoutParams.MATCH_PARENT
import android.view.ViewGroup.LayoutParams.WRAP_CONTENT
import android.widget.Button
import android.widget.CheckBox
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.SeekBar
import android.widget.TextView
import androidx.activity.ComponentActivity
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import app.rais.checkout.RaisCheckout

/**
 * Every option of the SDK in one screen. Your server creates the payment; here you paste the `checkout_url` it returned.
 *
 * For automated runs the screen also reads intent extras (the iOS Example's `-rais-…` launch arguments):
 * `--es url <checkout url>`, `--es scheme light|dark|automatic`, `--es primary '#5b5bd6'`, `--ei radius 12`, `--es lang en|fr|ar`,
 * `--es style native|web`, `--es font default|system|serif|monospace`, `--es hosts 192.168.1.20,mac.local` (additional trusted hosts),
 * `--es logo https://…/logo.png` (merchantLogoUrl), `--ez present true` (present at launch), `--ei autoclose 5` (close the checkout from code that many seconds after presenting).
 *
 *     adb shell am start -n app.rais.example/.MainActivity --es url http://10.0.2.2:8585/pay/<id> --es lang fr --es scheme dark --ez present true
 */
class MainActivity : ComponentActivity() {
    private lateinit var url: EditText
    private lateinit var outcome: TextView
    private var scheme = RaisCheckout.ColorScheme.Automatic
    private var language = RaisCheckout.Language.Automatic
    private var style = RaisCheckout.Style.Native
    private var font = RaisCheckout.FontFamily.Default
    // 10.0.2.2 is the emulator's name for the computer it runs on (a private address: plain http is accepted for it because it is listed)
    private var hosts = listOf("10.0.2.2")
    private var logo: String? = null
    private lateinit var customPrimary: CheckBox
    private lateinit var customRadius: CheckBox
    private lateinit var radius: SeekBar
    private lateinit var closeButton: CheckBox
    private var primary = 0xFF5B5BD6.toInt()

    // Created before the activity starts, like any activity-result registration.
    private val checkout = RaisCheckout(this) { result ->
        outcome.text = when (result) {
            is RaisCheckout.Result.Completed -> "Paid ${result.payment.id} ${result.payment.reference.orEmpty()}"
            RaisCheckout.Result.Cancelled -> "Cancelled"
            is RaisCheckout.Result.Failed -> "Failed (${result.failure.code}): ${result.failure.message}"
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val night = (resources.configuration.uiMode and android.content.res.Configuration.UI_MODE_NIGHT_MASK) ==
            android.content.res.Configuration.UI_MODE_NIGHT_YES
        val ink = if (night) Color.WHITE else Color.BLACK
        val page = if (night) 0xFF121212.toInt() else Color.WHITE

        fun label(text: String) = TextView(this).apply { this.text = text; setTextColor(ink); textSize = 13f; setPadding(0, dp(16), 0, dp(4)) }
        fun choice(name: String, options: List<String>, select: (Int) -> Unit) = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            options.forEachIndexed { index, option ->
                addView(Button(context).apply { text = option; isAllCaps = false; contentDescription = "$name $option"; setOnClickListener { select(index) } })
            }
        }

        url = EditText(this).apply { hint = "https://pay.rais.app/pay/…"; setTextColor(ink); setHintTextColor(Color.GRAY); inputType = InputType.TYPE_TEXT_VARIATION_URI; contentDescription = "url" }
        customPrimary = CheckBox(this).apply { text = "Custom accent color"; setTextColor(ink) }
        customRadius = CheckBox(this).apply { text = "Custom corner radius"; setTextColor(ink) }
        radius = SeekBar(this).apply { max = 24; progress = 6 }
        closeButton = CheckBox(this).apply { text = "Close button"; isChecked = true; setTextColor(ink) }
        outcome = TextView(this).apply { text = "No checkout yet"; setTextColor(ink); contentDescription = "outcome" }
        val pay = Button(this).apply { text = "Pay"; isAllCaps = false; contentDescription = "pay"; setOnClickListener { present() } }

        val column = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(20), dp(20), dp(20), dp(20))
            addView(TextView(context).apply { text = "Raïs SDK"; setTextColor(ink); textSize = 28f })
            addView(label("Checkout URL (from POST /payments)")); addView(url)
            addView(label("Color scheme")); addView(choice("scheme", listOf("Auto", "Light", "Dark")) { scheme = RaisCheckout.ColorScheme.entries[it] })
            addView(customPrimary); addView(customRadius); addView(radius)
            addView(label("Language")); addView(choice("language", listOf("Auto", "en", "fr", "ar")) { language = RaisCheckout.Language.entries[it] })
            addView(label("Style")); addView(choice("style", listOf("Native", "Web")) { style = RaisCheckout.Style.entries[it] })
            addView(label("Font")); addView(choice("font", listOf("Raïs", "System", "Serif", "Mono")) { font = RaisCheckout.FontFamily.entries[it] })
            addView(closeButton)
            addView(pay, LinearLayout.LayoutParams(MATCH_PARENT, WRAP_CONTENT).apply { topMargin = dp(16) })
            addView(outcome, LinearLayout.LayoutParams(MATCH_PARENT, WRAP_CONTENT).apply { topMargin = dp(12) })
        }
        setContentView(ScrollView(this).apply { setBackgroundColor(page); addView(column) })
        // edge to edge on every version: keep the content below the status bar
        ViewCompat.setOnApplyWindowInsetsListener(column) { v, insets ->
            val bars = insets.getInsets(WindowInsetsCompat.Type.systemBars())
            v.setPadding(dp(20) + bars.left, dp(20) + bars.top, dp(20) + bars.right, dp(20) + bars.bottom)
            insets
        }

        intent.getStringExtra("url")?.let { url.setText(it) }
        when (intent.getStringExtra("scheme")) { "light" -> scheme = RaisCheckout.ColorScheme.Light; "dark" -> scheme = RaisCheckout.ColorScheme.Dark }
        intent.getStringExtra("lang")?.let { code -> RaisCheckout.Language.entries.firstOrNull { it.name.equals(code, true) || it.name.take(2).equals(code, true) }?.let { language = it } }
        intent.getStringExtra("primary")?.let { runCatching { primary = Color.parseColor(it); customPrimary.isChecked = true } }
        if (intent.hasExtra("radius")) { radius.progress = intent.getIntExtra("radius", 6); customRadius.isChecked = true }
        when (intent.getStringExtra("style")) { "web" -> style = RaisCheckout.Style.Web; "native" -> style = RaisCheckout.Style.Native }
        intent.getStringExtra("font")?.let { name -> RaisCheckout.FontFamily.entries.firstOrNull { it.name.equals(name, true) }?.let { font = it } }
        logo = intent.getStringExtra("logo")
        intent.getStringExtra("hosts")?.let { list -> hosts = hosts + list.split(",").map { it.trim() }.filter { it.isNotEmpty() } }
        if (intent.getBooleanExtra("present", false)) window.decorView.postDelayed({ present() }, 600)
        if (intent.hasExtra("autoclose")) window.decorView.postDelayed({ checkout.dismiss() }, 600L + intent.getIntExtra("autoclose", 5) * 1000L)
    }

    private fun present() {
        val colors = if (customPrimary.isChecked) RaisCheckout.Colors(primary = primary) else RaisCheckout.Colors()
        val appearance = RaisCheckout.Appearance(
            colorScheme = scheme,
            colorsLight = colors,
            colorsDark = colors,
            cornerRadiusDp = if (customRadius.isChecked) radius.progress.toFloat() else null,
            fontFamily = font,
        )
        checkout.present(
            url.text.toString().trim(),
            RaisCheckout.Configuration(
                appearance = appearance,
                language = language,
                showsCloseButton = closeButton.isChecked,
                additionalTrustedHosts = hosts,
                style = style,
                merchantLogoUrl = logo,
            ),
        )
    }

    private fun dp(value: Int) = (value * resources.displayMetrics.density).toInt()
}
