package app.rais.checkout.internal

import android.annotation.SuppressLint
import android.app.AlertDialog
import android.content.Intent
import android.content.res.ColorStateList
import android.content.res.Configuration
import android.graphics.Color
import android.graphics.drawable.ColorDrawable
import android.graphics.drawable.GradientDrawable
import android.net.Uri
import android.net.http.SslError
import android.os.Bundle
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.view.ViewGroup.LayoutParams.MATCH_PARENT
import android.view.ViewGroup.LayoutParams.WRAP_CONTENT
import android.webkit.CookieManager
import android.webkit.JsResult
import android.webkit.RenderProcessGoneDetail
import android.webkit.SslErrorHandler
import android.webkit.WebChromeClient
import android.webkit.WebResourceError
import android.webkit.WebResourceRequest
import android.webkit.WebSettings
import android.webkit.WebView
import android.webkit.WebViewClient
import android.widget.Button
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.ProgressBar
import android.widget.TextView
import androidx.activity.ComponentActivity
import androidx.core.view.ViewCompat
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsAnimationCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.widget.ImageViewCompat
import androidx.webkit.ScriptHandler
import androidx.webkit.WebSettingsCompat
import androidx.webkit.WebViewCompat
import androidx.webkit.WebViewFeature
import app.rais.checkout.R
import app.rais.checkout.RaisCheckout
import app.rais.checkout.internal.sheet.CookieJar
import org.json.JSONObject
import java.util.Locale
import kotlin.math.max

/**
 * The hosted checkout in a WebView: the whole checkout when the app asks for [RaisCheckout.Style.Web], and otherwise the payments the
 * native checkout hands over (a card typed on the bank's own page, a server without a native checkout), with the cookies the native
 * checkout already holds (who is paying), inside the same screen.
 *
 * **The keyboard.** The reason a hosted page jumps inside an app is a disagreement about who moves what when the keyboard opens: the
 * WebView and the page each try to bring the field into view, and each answers the other's move. Here the native side decides, once:
 * the window is edge to edge and this screen puts the WebView *above* the keyboard itself (the IME inset becomes the WebView's bottom
 * margin), so the keyboard never overlaps it, and the page is simply a shorter window onto which it scrolls the focused field. The new
 * size is applied once, at the start of the keyboard's animation, not on each of its frames.
 */
internal class WebCheckout(
    private val activity: ComponentActivity,
    private val host: FrameLayout,
    private val args: CheckoutContract.Args,
    /** The address to open (the checkout, or the payment's own page when the native checkout hands it over). */
    private val startUrl: String,
    /** What the native checkout holds for this payment, put in the WebView's cookie store before the page loads. */
    private val seedCookies: List<CookieJar.Cookie>,
    private val onFinish: (RaisCheckout.Result) -> Unit,
) {
    private lateinit var root: FrameLayout
    private lateinit var webView: WebView
    private lateinit var closeButton: FrameLayout
    private lateinit var closeGlyph: ImageView
    private lateinit var closeDisc: View
    private lateinit var spinner: ProgressBar
    private lateinit var failureView: LinearLayout
    private var scriptHandler: ScriptHandler? = null
    private var finished = false
    private var lastOutcome: PageMessage.Result? = null
    private var loadFailedShowing = false   // the "Can't load the payment page" screen is what the payer sees
    private var night = false
    private var closeOnRight = false   // the page's start edge: left for English and French, right for Arabic (the page says which)
    private var bars = androidx.core.graphics.Insets.NONE

    private val configuration get() = args.configuration
    private val checkoutUrl get() = args.url
    private fun dp(value: Int) = (value * activity.resources.displayMetrics.density).toInt()
    private fun systemNight() = (activity.resources.configuration.uiMode and Configuration.UI_MODE_NIGHT_MASK) == Configuration.UI_MODE_NIGHT_YES

    /** Builds the screen in [host] and loads the checkout. `false` when this device's WebView cannot show it safely. */
    fun start(savedState: Bundle?): Boolean {
        night = PagePayload.isNight(configuration.appearance, systemNight())
        buildViews()
        if (!buildWebView()) return false
        applyColors()
        handleInsets()
        if (savedState != null && webView.restoreState(savedState) != null) return true
        clearCheckoutCookies()
        if (seedCookies.isEmpty()) load() else seed()
        return true
    }

    fun saveState(outState: Bundle) {
        if (::webView.isInitialized) webView.saveState(outState)
    }

    fun onConfigurationChanged(newConfig: Configuration) {
        val next = PagePayload.isNight(configuration.appearance, (newConfig.uiMode and Configuration.UI_MODE_NIGHT_MASK) == Configuration.UI_MODE_NIGHT_YES)
        if (next == night) return
        night = next
        applyColors()
        publishAppearance()
    }

    fun destroy() {
        if (::webView.isInitialized) {
            webView.stopLoading()
            webView.webViewClient = WebViewClient()
            (webView.parent as? ViewGroup)?.removeView(webView)
            webView.destroy()
        }
    }

    /** The system back button, or the app closing the checkout from code. */
    fun leave() = finishWith(makeResult())

    /** The native checkout's cookies, in the WebView's store, then the page. */
    private fun seed() {
        if (seedCookies.isEmpty()) return
        val manager = CookieManager.getInstance()
        val origin = CheckoutUrl.origin(startUrl) ?: return load()
        for (cookie in seedCookies) {
            val attributes = buildString {
                append(cookie.name).append('=').append(cookie.value).append("; Path=").append(cookie.path)
                if (cookie.secure) append("; Secure")
                if (cookie.includeSubdomains) append("; Domain=").append(cookie.host)
            }
            manager.setCookie(origin + cookie.path, attributes)
        }
        manager.flush()
        load()
    }

    // ── Views ────────────────────────────────────────────────────────────────────────────────────────────────────────

    private fun buildViews() {
        root = FrameLayout(activity)
        host.addView(root, FrameLayout.LayoutParams(MATCH_PARENT, MATCH_PARENT))

        spinner = ProgressBar(activity).apply { isIndeterminate = true }
        failureView = failureView()
        closeButton = FrameLayout(activity).apply {
            contentDescription = activity.raisString(R.string.rais_close)
            visibility = if (configuration.showsCloseButton) View.VISIBLE else View.GONE
            setOnClickListener { leave() }
        }
        closeDisc = View(activity).apply { background = GradientDrawable().apply { shape = GradientDrawable.OVAL } }
        closeGlyph = ImageView(activity).apply { setImageResource(R.drawable.rais_close); importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_NO }
        closeButton.addView(closeDisc, FrameLayout.LayoutParams(dp(32), dp(32), Gravity.CENTER))
        closeButton.addView(closeGlyph, FrameLayout.LayoutParams(dp(18), dp(18), Gravity.CENTER))
    }

    @SuppressLint("SetJavaScriptEnabled")
    private fun buildWebView(): Boolean {
        // The page's configuration and its messages need these two; without them the bridge could not be limited to the checkout's own
        // origin, and a door into the app that any page can open is not offered. (WebView 86+, on every maintained device.)
        val canInject = WebViewFeature.isFeatureSupported(WebViewFeature.WEB_MESSAGE_LISTENER)
        if (!canInject) return false

        // In a debuggable build of the host app the checkout can be inspected from chrome://inspect (never in a release build).
        if ((activity.applicationInfo.flags and android.content.pm.ApplicationInfo.FLAG_DEBUGGABLE) != 0) WebView.setWebContentsDebuggingEnabled(true)

        webView = WebView(activity).apply {
            overScrollMode = View.OVER_SCROLL_NEVER
            isVerticalScrollBarEnabled = false
            settings.apply {
                javaScriptEnabled = true
                domStorageEnabled = true
                allowFileAccess = false
                allowContentAccess = false
                setSupportMultipleWindows(false)   // a bank's window.open stays in this same WebView
                javaScriptCanOpenWindowsAutomatically = true
                mixedContentMode = WebSettings.MIXED_CONTENT_NEVER_ALLOW
                setGeolocationEnabled(false)
                userAgentString = "$userAgentString RaisSDK/${RaisCheckout.VERSION} (Android)"
            }
            // The page has its own dark palette: the system must not darken it a second time.
            if (WebViewFeature.isFeatureSupported(WebViewFeature.ALGORITHMIC_DARKENING)) {
                WebSettingsCompat.setAlgorithmicDarkeningAllowed(settings, false)
            }
            setDownloadListener { url, _, _, _, _ -> openExternally(Uri.parse(url)) }   // the receipt: the system's viewer and downloads
            webViewClient = client
            webChromeClient = chrome
        }

        val rule = CheckoutUrl.origin(startUrl) ?: return false
        WebViewCompat.addWebMessageListener(webView, "RaisBridge", setOf(rule)) { _, message, _, isMainFrame, _ ->
            if (isMainFrame) PageMessage.parse(message.data)?.let(::onPageMessage)
        }
        installBootstrapScript(rule)

        root.addView(webView, FrameLayout.LayoutParams(MATCH_PARENT, MATCH_PARENT))
        root.addView(failureView, FrameLayout.LayoutParams(MATCH_PARENT, WRAP_CONTENT, Gravity.CENTER))
        root.addView(spinner, FrameLayout.LayoutParams(WRAP_CONTENT, WRAP_CONTENT, Gravity.CENTER))
        root.addView(closeButton, FrameLayout.LayoutParams(dp(44), dp(44), Gravity.TOP or Gravity.LEFT))
        return true
    }

    private fun failureView(): LinearLayout {
        val title = TextView(activity).apply {
            text = activity.raisString(R.string.rais_load_failed_title); textSize = 17f; gravity = Gravity.CENTER; setTypeface(typeface, android.graphics.Typeface.BOLD)
            tag = "title"
        }
        val body = TextView(activity).apply { text = activity.raisString(R.string.rais_load_failed_body); textSize = 14f; gravity = Gravity.CENTER; tag = "body" }
        val retry = Button(activity).apply { text = activity.raisString(R.string.rais_retry); setOnClickListener { load() } }
        return LinearLayout(activity).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER
            visibility = View.GONE
            setPadding(dp(32), 0, dp(32), 0)
            addView(title, LinearLayout.LayoutParams(MATCH_PARENT, WRAP_CONTENT))
            addView(body, LinearLayout.LayoutParams(MATCH_PARENT, WRAP_CONTENT).apply { topMargin = dp(8) })
            addView(retry, LinearLayout.LayoutParams(WRAP_CONTENT, WRAP_CONTENT).apply { topMargin = dp(16) })
        }
    }

    private fun applyColors() {
        val background = PagePayload.background(configuration.appearance, night)
        val dark = Color.luminance(background) < 0.5f
        activity.window.setBackgroundDrawable(ColorDrawable(background))
        root.setBackgroundColor(background)
        webView.setBackgroundColor(background)
        WindowCompat.getInsetsController(activity.window, root).apply {
            isAppearanceLightStatusBars = !dark
            isAppearanceLightNavigationBars = !dark
        }
        val ink = if (dark) Color.WHITE else Color.BLACK
        // The close button floats over a page that scrolls under it: an opaque disc (the page's color with a little ink in it) reads as a
        // button and hides what passes beneath, where a see-through one let the text show through.
        (closeDisc.background as GradientDrawable).setColor(androidx.core.graphics.ColorUtils.blendARGB(background, ink, 0.10f))
        ImageViewCompat.setImageTintList(closeGlyph, ColorStateList.valueOf(Color.argb(160, Color.red(ink), Color.green(ink), Color.blue(ink))))
        failureView.findViewWithTag<TextView>("title").setTextColor(ink)
        failureView.findViewWithTag<TextView>("body").setTextColor(Color.argb(170, Color.red(ink), Color.green(ink), Color.blue(ink)))
        spinner.indeterminateTintList = ColorStateList.valueOf(Color.argb(200, Color.red(ink), Color.green(ink), Color.blue(ink)))
    }

    // ── The keyboard ─────────────────────────────────────────────────────────────────────────────────────────────────

    private fun handleInsets() {
        ViewCompat.setOnApplyWindowInsetsListener(root) { _, insets ->
            bars = insets.getInsets(WindowInsetsCompat.Type.systemBars() or WindowInsetsCompat.Type.displayCutout())
            val keyboard = insets.getInsets(WindowInsetsCompat.Type.ime())
            (webView.layoutParams as FrameLayout.LayoutParams).apply {
                setMargins(bars.left, bars.top, bars.right, max(bars.bottom, keyboard.bottom))
            }
            webView.requestLayout()
            placeCloseButton()
            insets
        }
        // At the start of the keyboard's animation the system hands this screen the FINAL size once; while it runs, the frames in between
        // are not passed down. The WebView is resized once, not fifteen times, and nothing moves but the keyboard's own slide.
        ViewCompat.setWindowInsetsAnimationCallback(
            root,
            object : WindowInsetsAnimationCompat.Callback(DISPATCH_MODE_STOP) {
                override fun onProgress(insets: WindowInsetsCompat, runningAnimations: MutableList<WindowInsetsAnimationCompat>) = insets
            },
        )
        ViewCompat.requestApplyInsets(root)
    }

    /** Over the top corner at the page's start edge, below the status bar. Physical sides: the page chooses which, not the app's direction. */
    private fun placeCloseButton() {
        (closeButton.layoutParams as FrameLayout.LayoutParams).apply {
            gravity = Gravity.TOP or (if (closeOnRight) Gravity.RIGHT else Gravity.LEFT)
            setMargins(if (closeOnRight) 0 else bars.left + dp(6), bars.top + dp(6), if (closeOnRight) bars.right + dp(6) else 0, 0)
        }
        closeButton.requestLayout()
    }

    // ── The page's configuration ─────────────────────────────────────────────────────────────────────────────────────

    /** Only the checkout's own origin receives the configuration: the script does nothing on a bank's page. */
    private fun bootstrapSource(origin: String): String =
        "(function(){try{if(activity.window.location.origin!==${JSONObject.quote(origin)})return;" +
            "activity.window.__RAIS_SDK__=${PagePayload.build(configuration, night)};}catch(e){}})();"

    private fun installBootstrapScript(origin: String) {
        scriptHandler?.remove()
        scriptHandler = if (WebViewFeature.isFeatureSupported(WebViewFeature.DOCUMENT_START_SCRIPT)) {
            WebViewCompat.addDocumentStartJavaScript(webView, bootstrapSource(origin), setOf(origin))
        } else {
            null   // onPageStarted does it (below), a little later than the page's first script on a very old WebView
        }
    }

    /** The device went from light to dark (or back) while the checkout is open. */
    private fun publishAppearance() {
        val origin = CheckoutUrl.origin(startUrl) ?: return
        installBootstrapScript(origin)
        webView.evaluateJavascript("activity.window.RaisSDK&&activity.window.RaisSDK.update(${PagePayload.build(configuration, night)});", null)
    }

    // ── Loading ──────────────────────────────────────────────────────────────────────────────────────────────────────

    private fun load() {
        loadFailedShowing = false
        failureView.visibility = View.GONE
        webView.visibility = View.VISIBLE
        spinner.visibility = View.VISIBLE
        webView.loadUrl(CheckoutUrl.entry(startUrl, configuration.language.code))
    }

    private val client = object : WebViewClient() {
        override fun shouldOverrideUrlLoading(view: WebView, request: WebResourceRequest): Boolean {
            val uri = request.url
            val scheme = uri.scheme?.lowercase(Locale.ROOT).orEmpty()
            val own = configuration.returnUrlScheme?.lowercase(Locale.ROOT)
            if (!own.isNullOrEmpty() && scheme == own) {
                handleReturnLink(uri)
                return true
            }
            return when (scheme) {
                "http", "https" -> {
                    // A link on the checkout page that leads elsewhere (the terms, the Raïs site) opens in the browser: inside this
                    // WebView the payer would have no way back. Bank pages and 3-D Secure redirects are never "links on the checkout page".
                    val fromCheckout = CheckoutUrl.isSameOrigin(view.url, startUrl)
                    if (request.isForMainFrame && request.hasGesture() && !request.isRedirect && fromCheckout &&
                        !CheckoutUrl.isSameOrigin(uri.toString(), startUrl)
                    ) {
                        if (scheme == "https") openExternally(uri)
                        true
                    } else {
                        false
                    }
                }
                "about", "blob", "data" -> false
                else -> true   // tel:, mailto:, intent:, app links: never opened by a payment page
            }
        }

        override fun onPageStarted(view: WebView, url: String?, favicon: android.graphics.Bitmap?) {
            spinner.visibility = View.VISIBLE
            if (scriptHandler == null) CheckoutUrl.origin(startUrl)?.let { view.evaluateJavascript(bootstrapSource(it), null) }
        }

        override fun onPageFinished(view: WebView, url: String?) {
            spinner.visibility = View.GONE
        }

        override fun onReceivedError(view: WebView, request: WebResourceRequest, error: WebResourceError) {
            if (request.isForMainFrame) loadFailed()
        }

        override fun onReceivedSslError(view: WebView, handler: SslErrorHandler, error: SslError) {
            handler.cancel()   // a payment page with a bad certificate is never "continued anyway"
            loadFailed()
        }

        override fun onRenderProcessGone(view: WebView, detail: RenderProcessGoneDetail): Boolean {
            activity.recreate()   // Android reclaimed the page's process while the app was in the background: start the checkout again
            return true
        }
    }

    private val chrome = object : WebChromeClient() {
        override fun onJsAlert(view: WebView, url: String?, message: String?, result: JsResult): Boolean {
            AlertDialog.Builder(activity).setMessage(message)
                .setPositiveButton(android.R.string.ok) { _, _ -> result.confirm() }.setOnCancelListener { result.cancel() }.show()
            return true
        }

        override fun onJsConfirm(view: WebView, url: String?, message: String?, result: JsResult): Boolean {
            AlertDialog.Builder(activity).setMessage(message)
                .setPositiveButton(android.R.string.ok) { _, _ -> result.confirm() }
                .setNegativeButton(android.R.string.cancel) { _, _ -> result.cancel() }.setOnCancelListener { result.cancel() }.show()
            return true
        }
    }

    private fun loadFailed() {
        loadFailedShowing = true
        webView.visibility = View.INVISIBLE   // else Chromium's own "Webpage not available" page shows behind our message
        spinner.visibility = View.GONE
        failureView.visibility = View.VISIBLE
    }

    private fun openExternally(uri: Uri) {
        if (uri.scheme?.lowercase(Locale.ROOT) != "https") return
        try { activity.startActivity(Intent(Intent.ACTION_VIEW, uri)) } catch (_: Exception) { /* no browser installed: nothing to open */ }
    }

    // ── The page's messages and the way out ──────────────────────────────────────────────────────────────────────────

    private fun onPageMessage(message: PageMessage) {
        activity.runOnUiThread {
            when (message) {
                is PageMessage.Ready -> {
                    loadFailedShowing = false
                    spinner.visibility = View.GONE
                    if (closeOnRight != message.rtl) { closeOnRight = message.rtl; placeCloseButton() }
                    if (message.page == "pay") lastOutcome = null   // the payer is paying (again): an earlier outcome no longer counts
                }
                is PageMessage.Result -> lastOutcome = message
                PageMessage.Done, PageMessage.Close -> leave()
            }
        }
    }

    /** The payment's `success_url` / `cancel_url` is a deep link into the app (`returnUrlScheme`): its query carries the result. */
    private fun handleReturnLink(link: Uri) {
        val status = PageMessage.Status.of(link.getQueryParameter("status"))
        val id = link.getQueryParameter("payment_id")
        if (status != null && id != null) {
            val parsed = PageMessage.parse(
                JSONObject().put("type", "result").put("status", status.name.lowercase(Locale.ROOT)).put("paymentId", id)
                    .put("reference", link.getQueryParameter("reference") ?: JSONObject.NULL)
                    .put("orderNumber", link.getQueryParameter("order_number") ?: JSONObject.NULL).toString(),
            )
            if (parsed is PageMessage.Result) lastOutcome = parsed
        }
        leave()
    }


    private fun makeResult(): RaisCheckout.Result {
        // Nothing was shown but the "Can't load the payment page" screen: tell the app that, not that the payer chose to leave.
        val outcome = lastOutcome ?: return if (loadFailedShowing) {
            failure(RaisCheckout.Failure.Code.LoadFailed, activity.raisString(R.string.rais_load_failed_title), null)
        } else {
            RaisCheckout.Result.Cancelled
        }
        return when (outcome.status) {
            PageMessage.Status.Paid -> RaisCheckout.Result.Completed(RaisCheckout.Payment(outcome.paymentId, outcome.reference, outcome.orderNumber))
            PageMessage.Status.Failed -> failure(RaisCheckout.Failure.Code.Declined, activity.raisString(R.string.rais_declined), outcome.paymentId)
            PageMessage.Status.Expired -> failure(RaisCheckout.Failure.Code.Expired, activity.raisString(R.string.rais_expired), outcome.paymentId)
            PageMessage.Status.Cancelled -> RaisCheckout.Result.Cancelled
        }
    }

    private fun failure(code: RaisCheckout.Failure.Code, message: String, paymentId: String?) =
        RaisCheckout.Result.Failed(RaisCheckout.Failure(code, message, paymentId))

    /**
     * Android keeps WebView cookies for the whole app, across checkouts (iOS gives each checkout a fresh store). So that nothing of a
     * payment stays on the device, and so that "automatic" language means the device's and not what an earlier checkout left behind, the
     * cookies of the checkout's own host are removed when a checkout starts and when it ends. Within one checkout they stay: the bank's
     * return and the payer's language choice depend on them.
     */
    private fun clearCheckoutCookies() {
        val origin = CheckoutUrl.origin(startUrl) ?: return
        val manager = CookieManager.getInstance()
        val paths = listOfNotNull("/", sheetPagePath(startUrl) ?: sheetPagePath(checkoutUrl))
        for (path in paths) {
            manager.getCookie(origin + path)?.split(";")?.map { it.substringBefore("=").trim() }?.filter { it.isNotEmpty() }?.forEach { name ->
                for (scope in paths) manager.setCookie(origin + scope, "$name=; Max-Age=0; Path=$scope")
            }
        }
        manager.flush()
    }

    /** `/pay/<id>` of a payment address: the path the checkout's own cookies are scoped to. */
    private fun sheetPagePath(url: String): String? = app.rais.checkout.internal.sheet.Location.of(url)?.let { "/pay/${it.paymentId}" }

    private fun finishWith(result: RaisCheckout.Result) {
        if (finished) return
        finished = true
        clearCheckoutCookies()
        onFinish(result)
    }
}
