package app.rais.checkout.internal

import android.content.Intent
import android.content.res.Configuration
import android.graphics.Color
import android.os.Build
import android.os.Bundle
import android.widget.FrameLayout
import androidx.activity.ComponentActivity
import androidx.activity.OnBackPressedCallback
import androidx.core.view.WindowCompat
import app.rais.checkout.R
import app.rais.checkout.RaisCheckout
import app.rais.checkout.internal.sheet.CookieJar
import app.rais.checkout.internal.sheet.NativeCheckout
import java.lang.ref.WeakReference

/**
 * The checkout's screen. It shows the native checkout ([NativeCheckout], fed by `GET /pay/{id}/sheet`), or the hosted page in a WebView
 * ([WebCheckout]) when the app asks for [RaisCheckout.Style.Web] or when the native checkout hands a payment over (a card typed on the bank's
 * own page, a server without a native checkout). Either way the app receives one [RaisCheckout.Result], once.
 */
internal class CheckoutActivity : ComponentActivity() {
    private lateinit var args: CheckoutContract.Args
    private lateinit var root: FrameLayout
    private var native: NativeCheckout? = null
    private var web: WebCheckout? = null
    private var finished = false
    /** The SDK's own strings in the checkout's language (null: the phone's language). See `raisString`. */
    internal var localizedResources: android.content.res.Resources? = null

    private fun systemNight() = (resources.configuration.uiMode and Configuration.UI_MODE_NIGHT_MASK) == Configuration.UI_MODE_NIGHT_YES

    override fun onCreate(savedInstanceState: Bundle?) {
        // Before the window exists: WebView reads the theme's light/dark to answer a page's `prefers-color-scheme` (the Raïs page is told
        // its theme explicitly; a bank's 3-D Secure page is not), so the theme follows the scheme this checkout shows.
        val early = with(CheckoutContract) { intent.serializable<CheckoutContract.Args>(CheckoutContract.EXTRA_ARGS) }
        val darkTheme = early?.let { PagePayload.isNight(it.configuration.appearance, systemNight()) } ?: systemNight()
        // The SDK's own words (close, "Can't load", retry: what is said before the payment's details arrive) in the language the app chose,
        // not the phone's: the page itself is in that language too.
        early?.configuration?.language?.code?.let { code ->
            val wanted = Configuration(resources.configuration).apply { setLocale(java.util.Locale(code)) }
            localizedResources = createConfigurationContext(wanted).resources
        }
        setTheme(if (darkTheme) R.style.Theme_Rais_Checkout else R.style.Theme_Rais_Checkout_Light)
        super.onCreate(savedInstanceState)
        val given = early
        if (given == null || !CheckoutUrl.isTrusted(given.url, given.configuration.additionalTrustedHosts)) {
            return finishWith(failure(RaisCheckout.Failure.Code.InvalidUrl, raisString(R.string.rais_invalid_url)))
        }
        args = given
        current = WeakReference(this)

        WindowCompat.setDecorFitsSystemWindows(window, false)
        @Suppress("DEPRECATION")
        window.statusBarColor = Color.TRANSPARENT
        @Suppress("DEPRECATION")
        window.navigationBarColor = Color.TRANSPARENT
        if (Build.VERSION.SDK_INT >= 29) window.isNavigationBarContrastEnforced = false
        root = FrameLayout(this)
        setContentView(root)

        onBackPressedDispatcher.addCallback(this, object : OnBackPressedCallback(true) {
            override fun handleOnBackPressed() {
                web?.leave() ?: native?.back()
            }
        })

        // The web page keeps its own state across a re-creation of this screen; the native checkout reads the payment again.
        if (args.configuration.style == RaisCheckout.Style.Web || savedInstanceState?.getBoolean(STATE_WEB) == true) {
            showWeb(args.url, emptyList(), savedInstanceState)
        } else {
            val screen = NativeCheckout(
                activity = this,
                host = root,
                args = args,
                night = darkTheme,
                fontProvider = args.configuration.appearance.fontProvider ?: CheckoutContract.Handoff.font(args.token),
                onFinish = ::finishWith,
                onWeb = { url, cookies -> showWeb(url, cookies, null) },
            )
            native = screen
            screen.start()
        }
    }

    private fun showWeb(url: String, cookies: List<CookieJar.Cookie>, savedState: Bundle?) {
        if (web != null || finished) return
        val screen = WebCheckout(this, root, args, url, cookies, ::finishWith)
        web = screen
        if (!screen.start(savedState)) {
            finishWith(failure(RaisCheckout.Failure.Code.UnsupportedWebView, raisString(R.string.rais_webview_unsupported)))
        }
    }

    override fun onSaveInstanceState(outState: Bundle) {
        super.onSaveInstanceState(outState)
        web?.let {
            outState.putBoolean(STATE_WEB, true)
            it.saveState(outState)
        }
    }

    override fun onResume() {
        super.onResume()
        if (!finished) native?.onResume()
    }

    override fun onPause() {
        if (!finished) native?.onPause()
        super.onPause()
    }

    override fun onConfigurationChanged(newConfig: Configuration) {
        super.onConfigurationChanged(newConfig)
        if (!::args.isInitialized) return
        web?.onConfigurationChanged(newConfig)
        val night = (newConfig.uiMode and Configuration.UI_MODE_NIGHT_MASK) == Configuration.UI_MODE_NIGHT_YES
        native?.setNight(PagePayload.isNight(args.configuration.appearance, night))
    }

    override fun onDestroy() {
        native?.onDestroy()
        web?.destroy()
        if (current?.get() === this) current = null
        if (isFinishing && ::args.isInitialized) CheckoutContract.Handoff.release(args.token)
        super.onDestroy()
    }

    /** The app closed the checkout from code ([RaisCheckout.dismiss]): as if the payer had pressed close. */
    private fun dismissFromApp() {
        web?.leave() ?: native?.finish() ?: finishWith(RaisCheckout.Result.Cancelled)
    }

    private fun failure(code: RaisCheckout.Failure.Code, message: String) = RaisCheckout.Result.Failed(RaisCheckout.Failure(code, message, null))

    private fun finishWith(result: RaisCheckout.Result) {
        if (finished) return
        finished = true
        setResult(RESULT_OK, Intent().putExtra(CheckoutContract.EXTRA_RESULT, result))
        finish()
    }

    companion object {
        private const val STATE_WEB = "app.rais.checkout.web"
        private var current: WeakReference<CheckoutActivity>? = null

        /**
         * Brings the open checkout back in front of what was started over it (the browser tab of "Sign in with Raïs"). The checkout is
         * found in memory: with none open (the process restarted meanwhile) nothing is started, so no empty screen can appear.
         */
        fun bringToFront(from: android.content.Context) {
            val open = current?.get()?.takeIf { !it.isFinishing && !it.finished } ?: return
            val intent = Intent(from, CheckoutActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_SINGLE_TOP)
            open.startActivity(intent)
        }

        fun dismissCurrent() {
            current?.get()?.takeIf { !it.isFinishing }?.dismissFromApp()
        }
    }
}

/** One of the SDK's own strings, in the language the checkout was opened in (the phone's when the app left it automatic). */
internal fun android.app.Activity.raisString(@androidx.annotation.StringRes id: Int): String =
    ((this as? CheckoutActivity)?.localizedResources ?: resources).getString(id)
