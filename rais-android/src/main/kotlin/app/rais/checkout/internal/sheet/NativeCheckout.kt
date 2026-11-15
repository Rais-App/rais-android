package app.rais.checkout.internal.sheet

import app.rais.checkout.internal.raisString
import android.annotation.SuppressLint
import android.content.ActivityNotFoundException
import android.content.Context
import android.content.Intent
import android.content.res.ColorStateList
import android.graphics.BitmapFactory
import android.graphics.Rect
import android.graphics.drawable.ColorDrawable
import android.net.Uri
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.view.accessibility.AccessibilityEvent
import android.view.inputmethod.InputMethodManager
import android.widget.FrameLayout
import android.widget.ProgressBar
import android.widget.ScrollView
import androidx.activity.ComponentActivity
import androidx.core.graphics.Insets
import androidx.core.view.ViewCompat
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsAnimationCompat
import androidx.core.view.WindowInsetsCompat
import androidx.lifecycle.lifecycleScope
import app.rais.checkout.R
import app.rais.checkout.RaisCheckout
import app.rais.checkout.internal.CheckoutContract
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.async
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import java.io.File
import java.util.UUID
import kotlin.coroutines.cancellation.CancellationException
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt

/**
 * The checkout, drawn by the app: the payment and the ways to pay it, the bank's code, and what became of the payment. The same screen,
 * flow and rules as rais-ios `NativeCheckoutViewController`.
 *
 * The server keeps every rule and every word (`GET /pay/{id}/sheet` says what to show, the page's own calls pay). This screen reads the
 * checkout, draws it with native views, and reports how it ended. Two payments are not drawn here and go to the web checkout instead,
 * inside this same screen: one whose card must be typed on the bank's own page, and one on a server that has no native checkout yet.
 *
 * **The keyboard.** The window is edge to edge. The page scrolls under everything: nothing above it but the close button (what scrolls
 * under the status bar fades into the page), nothing below it but the floating pane that holds the button. That pane rides on the keyboard
 * (the IME inset, following the keyboard's own animation), the page's bottom room follows the pane, 16 dp more than it covers, and the field
 * being typed in, with what is said under it, is brought fully into view above the pane, once when it takes the focus and again once the
 * keyboard has settled.
 */
internal class NativeCheckout(
    private val activity: ComponentActivity,
    private val host: FrameLayout,
    private val args: CheckoutContract.Args,
    night: Boolean,
    fontProvider: RaisCheckout.FontProvider?,
    private val onFinish: (RaisCheckout.Result) -> Unit,
    /** The payment cannot be drawn here: show [url] as the web checkout, with the cookies this checkout holds. */
    private val onWeb: (url: String, cookies: List<CookieJar.Cookie>) -> Unit,
) {
    private val configuration = args.configuration
    /** The context the checkout's views are made with: the activity's, able to read right to left whatever the app declared. */
    private val ui: Context = RtlCapableContext(activity)
    private val theme = SheetTheme(ui, configuration.appearance, night, fontProvider)
    private val scope: CoroutineScope get() = activity.lifecycleScope
    private var api: CheckoutApi? = null
    private fun dp(value: Int) = theme.dp(value)

    // chrome
    private val root = FrameLayout(ui)
    private val scroll = PageScroll(ui)
    private val stage = Stack(ui, vertical = true)
    private val column = PageColumn(ui, theme)
    private val topFade = FadeView(ui, theme)
    private val bar = GlassPane(ui, theme, theme.dp(30f), floats = true)
    private val barStack = Stack(ui, vertical = true, gap = theme.dp(8))
    private val notice = NoticeView(ui, theme, NoticeView.Tone.Danger)
    private val primary = SheetButton(ui, theme, SheetButton.Kind.Primary, "")
    private val secondary = SheetButton(ui, theme, SheetButton.Kind.Plain, "")
    private val assurance = Stack(ui, vertical = false)
    private val assuranceLabel = Label(ui, theme, 12f) { theme.secondary }
    private val closeButton = FrameLayout(ui)
    private val chipSlot = FrameLayout(ui)
    private val spinner = ProgressBar(ui, null, android.R.attr.progressBarStyleLarge)
    private val failure = Stack(ui, vertical = true)
    private var receiptViewer: ReceiptViewer? = null

    // state
    private enum class Phase { Loading, Pay, Code, Outcome, Failed, Web }
    private enum class Reading { Visibly, BehindThePage, Silently }

    private var phase = Phase.Loading
    private var sheet: Sheet? = null
    private var context: SheetContext? = null
    private var payView: PayView? = null
    private var codeView: CodeView? = null
    private var outcomeView: OutcomeView? = null
    private var retry: (() -> Unit)? = null
    private var isBusy = false
    private var finished = false
    private var loadFailedShowing = false
    /** The last outcome the payer was shown (a failed payment can still be retried, so only the last one counts). */
    private var lastOutcome: Sheet.Outcome.Reported? = null
    private var sessionUntil = 0L
    private var sessionTask: Deferred<Unit>? = null
    private var lastPrepare = 0L
    private var poller: Job? = null
    private var leftForApp = false
    private var webSignIn: WebSignIn? = null
    /** Gives the "Pay with Raïs" row its arrow back once the payer is back from the Raïs app. */
    private var appOpenEnded: (() -> Unit)? = null
    private var receiptFile: File? = null
    private var secondaryWanted = false
    /** The field (with what is said under it) that has the keyboard: brought into view again once the keyboard has settled. */
    private var focused: View? = null

    // insets
    private var bars = Insets.NONE
    private var imeBottom = 0
    private var keyboardUp = false
    private var barBottom = 0
    private var animatingFrom = -1

    // ── Building ──

    fun start() {
        host.addView(root, FrameLayout.LayoutParams(MATCH, MATCH))
        buildStage()
        buildBar()
        buildChrome()
        handleInsets()
        applyWindowColors()
        val location = Location.of(args.url)
        if (location == null) {
            showWeb(args.url, emptyList())
            return
        }
        val api = CheckoutApi(args.url, location, configuration.language.code)
        this.api = api
        load { api.open() }
    }

    private fun buildStage() {
        // (a keyboard or a D-pad can move the focus onto the page itself: the platform's grey focus highlight must not cover the page)
        for (view in listOf(scroll, column, stage, root)) if (android.os.Build.VERSION.SDK_INT >= 26) view.defaultFocusHighlightEnabled = false
        scroll.isVerticalScrollBarEnabled = false
        scroll.overScrollMode = View.OVER_SCROLL_IF_CONTENT_SCROLLS
        scroll.clipToPadding = false
        scroll.isFillViewport = false
        scroll.onScrolled = { y -> topFade.alpha = min(1f, max(0f, y / theme.dp(24f))) }
        scroll.addView(column, FrameLayout.LayoutParams(MATCH, WRAP))
        column.addView(stage, FrameLayout.LayoutParams(MATCH, WRAP, Gravity.CENTER_HORIZONTAL))
        // A tap beside the fields puts the keyboard away. It is a click (a finished tap), never a touch going down: a control (button, row,
        // switch) takes its own click first, and nothing moves under a finger before it is lifted, so a tap on a button with the keyboard
        // open fires on the first tap (KeyboardTapTest pins that no touch handler exists in the sheet).
        column.setOnClickListener { hideKeyboard() }
        root.addView(scroll, FrameLayout.LayoutParams(MATCH, MATCH))
        topFade.alpha = 0f
        root.addView(topFade, FrameLayout.LayoutParams(MATCH, dp(30), Gravity.TOP))
        root.addView(bar, FrameLayout.LayoutParams(MATCH, WRAP, Gravity.BOTTOM or Gravity.CENTER_HORIZONTAL))
    }

    private fun buildBar() {
        primary.tag = "rais.primary"
        primary.onTap { primaryTapped() }
        secondary.tag = "rais.secondary"
        secondary.onTap { secondaryTapped() }
        notice.visibility = View.GONE

        assurance.gravity = Gravity.CENTER
        assurance.add(Glyph(ui, R.drawable.rais_ic_lock) { theme.secondary }, width = dp(11), height = dp(11))
        assuranceLabel.maxLines = 2
        assurance.add(assuranceLabel, before = dp(5))

        barStack.add(notice)
        barStack.add(primary)
        barStack.add(secondary, width = WRAP, gravity = Gravity.CENTER_HORIZONTAL)
        barStack.add(assurance, width = WRAP, gravity = Gravity.CENTER_HORIZONTAL)
        bar.addView(barStack, FrameLayout.LayoutParams(MATCH, WRAP).apply { setMargins(dp(10), dp(10), dp(10), dp(10)) })
        bar.visibility = View.GONE
        bar.addOnLayoutChangeListener { _, _, top, _, bottom, _, oldTop, _, oldBottom -> if (bottom - top != oldBottom - oldTop) updateRoom() }
    }

    private fun buildChrome() {
        closeButton.visibility = if (configuration.showsCloseButton) View.VISIBLE else View.GONE
        closeButton.contentDescription = activity.raisString(R.string.rais_close)
        closeButton.tag = "rais.close"
        closeButton.isClickable = true
        closeButton.addView(GlassPane(ui, theme, null), FrameLayout.LayoutParams(dp(36), dp(36), Gravity.CENTER))
        closeButton.addView(Glyph(ui, R.drawable.rais_close) { theme.text }, FrameLayout.LayoutParams(dp(16), dp(16), Gravity.CENTER))
        closeButton.setOnClickListener { closeTapped() }
        root.addView(closeButton, FrameLayout.LayoutParams(dp(44), dp(44), Gravity.TOP or Gravity.START))
        root.addView(chipSlot, FrameLayout.LayoutParams(WRAP, dp(44), Gravity.TOP or Gravity.END))

        spinner.indeterminateTintList = ColorStateList.valueOf(theme.secondary)
        root.addView(spinner, FrameLayout.LayoutParams(dp(40), dp(40), Gravity.CENTER))

        failure.gravity = Gravity.CENTER_HORIZONTAL
        failure.add(IconDisc(ui, theme, R.drawable.rais_ic_wifi_off, { theme.secondary }), width = dp(64), height = dp(64))
        val title = Label(ui, theme, 17f, 600).centered()
        title.text = activity.raisString(R.string.rais_load_failed_title)
        failure.add(title, before = dp(16))
        val body = Label(ui, theme, 15f) { theme.secondary }.centered()
        body.text = activity.raisString(R.string.rais_load_failed_body)
        failure.add(body, before = dp(8))
        val again = SheetButton(ui, theme, SheetButton.Kind.Secondary, activity.raisString(R.string.rais_retry), R.drawable.rais_ic_refresh)
        again.tag = "rais.retry"
        again.minimumWidth = dp(180)
        again.onTap { retry?.invoke() }
        failure.add(again, before = dp(20), width = WRAP, gravity = Gravity.CENTER_HORIZONTAL)
        failure.visibility = View.GONE
        root.addView(failure, FrameLayout.LayoutParams(MATCH, WRAP, Gravity.CENTER).apply { setMargins(dp(32), 0, dp(32), 0) })
    }

    private fun applyWindowColors() {
        activity.window.setBackgroundDrawable(ColorDrawable(theme.background))
        root.setBackgroundColor(theme.background)
        WindowCompat.getInsetsController(activity.window, root).apply {
            isAppearanceLightStatusBars = !theme.isDark
            isAppearanceLightNavigationBars = !theme.isDark
        }
    }

    /** The device switched between light and dark (with [RaisCheckout.ColorScheme.Automatic]). */
    fun setNight(night: Boolean) {
        if (theme.night == night) return
        theme.night = night
        root.applyThemeDeep()
        spinner.indeterminateTintList = ColorStateList.valueOf(theme.secondary)
        applyWindowColors()
    }

    // ── Insets: the bars, the keyboard, and the pane that rides on it ──

    private fun handleInsets() {
        ViewCompat.setOnApplyWindowInsetsListener(root) { _, insets ->
            bars = insets.getInsets(WindowInsetsCompat.Type.systemBars() or WindowInsetsCompat.Type.displayCutout())
            imeBottom = insets.getInsets(WindowInsetsCompat.Type.ime()).bottom
            val wasUp = keyboardUp
            keyboardUp = insets.isVisible(WindowInsetsCompat.Type.ime()) && imeBottom > bars.bottom
            if (wasUp != keyboardUp) updateBar()
            layoutChrome()
            // without an animation (a hardware keyboard, animations switched off) this is the keyboard's final place
            if (keyboardUp && animatingFrom < 0) root.post { focused?.let(::reveal) }
            WindowInsetsCompat.CONSUMED
        }
        ViewCompat.setWindowInsetsAnimationCallback(
            root,
            object : WindowInsetsAnimationCompat.Callback(DISPATCH_MODE_STOP) {
                private var from = 0
                private var to = 0

                override fun onPrepare(animation: WindowInsetsAnimationCompat) {
                    if (animation.typeMask and WindowInsetsCompat.Type.ime() != 0) {
                        from = barBottom
                        animatingFrom = from
                    }
                }

                override fun onStart(animation: WindowInsetsAnimationCompat, bounds: WindowInsetsAnimationCompat.BoundsCompat): WindowInsetsAnimationCompat.BoundsCompat {
                    if (animation.typeMask and WindowInsetsCompat.Type.ime() != 0) {
                        to = barBottom
                        bar.translationY = (to - from).toFloat()   // the pane starts where it was and rides with the keyboard
                    }
                    return bounds
                }

                override fun onProgress(insets: WindowInsetsCompat, running: MutableList<WindowInsetsAnimationCompat>): WindowInsetsCompat {
                    val ime = running.firstOrNull { it.typeMask and WindowInsetsCompat.Type.ime() != 0 } ?: return insets
                    bar.translationY = (to - from) * (1f - ime.interpolatedFraction)
                    return insets
                }

                override fun onEnd(animation: WindowInsetsAnimationCompat) {
                    if (animation.typeMask and WindowInsetsCompat.Type.ime() == 0) return
                    bar.translationY = 0f
                    animatingFrom = -1
                    // the keyboard has finished rising: the field being typed in is brought into view again, against the final room
                    if (keyboardUp) focused?.let(::reveal)
                }
            },
        )
        ViewCompat.requestApplyInsets(root)
    }

    /** Places what floats over the page for the bars and the keyboard in force. Physical insets, the page's own direction for the sides. */
    private fun layoutChrome() {
        val rtl = root.layoutDirection == View.LAYOUT_DIRECTION_RTL
        val startInset = if (rtl) bars.right else bars.left
        val endInset = if (rtl) bars.left else bars.right
        // (each view gets its parameters again: start and end margins are resolved for the direction only when they are set on the view)
        closeButton.layoutParams = (closeButton.layoutParams as FrameLayout.LayoutParams).apply {
            topMargin = bars.top + dp(6)
            marginStart = startInset + dp(10)
        }
        chipSlot.layoutParams = (chipSlot.layoutParams as FrameLayout.LayoutParams).apply {
            topMargin = bars.top + dp(6)
            marginEnd = endInset + dp(20)
        }
        // (down to the row of the close button and the test chip: what scrolls under them fades out instead of running into them)
        topFade.layoutParams = (topFade.layoutParams as FrameLayout.LayoutParams).apply { height = bars.top + dp(96) }
        // above the keyboard, or just over the navigation bar when there is none: nothing is ever under the keyboard
        barBottom = if (keyboardUp) imeBottom + dp(8) else bars.bottom + if (bars.bottom > 0) dp(6) else dp(10)
        bar.layoutParams = (bar.layoutParams as FrameLayout.LayoutParams).apply {
            bottomMargin = barBottom
            val room = root.width.takeIf { it > 0 } ?: activity.resources.displayMetrics.widthPixels
            width = min(room - bars.left - bars.right - dp(20), dp(500))
        }
        updateRoom()
    }

    /**
     * The page's own margins: it starts beside the close button, under the status bar, and its last line clears the pane of the button
     * (16 dp more than the pane covers: the last field on the page, typed in with the keyboard up, rises clear of the pane with room to spare).
     */
    private fun updateRoom() {
        val top = bars.top + dp(10)
        val bottom = if (bar.visibility != View.VISIBLE) max(bars.bottom, if (keyboardUp) imeBottom else 0)
        else barBottom + bar.height + dp(16)
        if (scroll.paddingTop == top && scroll.paddingBottom == bottom) return
        val restsAtTop = scroll.scrollY == 0
        scroll.setPadding(0, top, 0, bottom)
        if (restsAtTop) scroll.scrollTo(0, 0)
    }

    /** What the pane shows under its button. With the keyboard up it keeps only what acts: the room goes to the fields. */
    private fun updateBar() {
        val showsAssurance = phase == Phase.Pay && !keyboardUp
        assurance.visibility = if (showsAssurance) View.VISIBLE else View.GONE
        secondary.visibility = if (secondaryWanted) View.VISIBLE else View.GONE
        barStack.gapBefore(secondary, dp(2))
    }

    /** Brings a field into view, with what is said under it: its bottom first, then its top if the field is taller than the room. */
    private fun reveal(target: View) {
        root.postDelayed({
            if (target.parent == null || !target.isAttachedToWindow || finished) return@postDelayed
            val rect = Rect()
            target.getDrawingRect(rect)
            scroll.offsetDescendantRectToMyCoords(target, rect)
            rect.inset(0, -dp(16))
            val top = scroll.paddingTop
            val bottom = scroll.paddingBottom
            var offset = scroll.scrollY
            if (rect.bottom > offset + scroll.height - bottom) offset = rect.bottom - scroll.height + bottom
            if (rect.top < offset + top) offset = rect.top - top
            val content = scroll.getChildAt(0)?.height ?: 0
            val limit = max(0, content + top + bottom - scroll.height)
            offset = offset.coerceIn(0, limit)
            if (kotlin.math.abs(offset - scroll.scrollY) > 0) scroll.smoothScrollTo(0, offset)
        }, 80)
    }

    private fun hideKeyboard() {
        val view = root.findFocus() ?: return
        view.clearFocus()
        (activity.getSystemService(Context.INPUT_METHOD_SERVICE) as InputMethodManager).hideSoftInputFromWindow(root.windowToken, 0)
    }

    // ── Reading the checkout ──

    /** Reads the checkout with [read] and shows what it says. A lost connection shows "Can't load" with a way to ask again, the same question. */
    private fun load(reading: Reading = Reading.Visibly, read: suspend () -> Sheet) {
        if (finished) return   // nothing is read for a checkout that has ended
        poller?.cancel()
        if (reading == Reading.Visibly) show(Phase.Loading)
        scope.launch {
            try {
                val sheet = read()
                if (!finished) render(sheet)
            } catch (error: CancellationException) {
                throw error
            } catch (_: ApiFailure.Unsupported) {
                val api = api
                if (!finished && api != null) showWeb(if (api.location.isPlain) args.url else api.location.pageUrl, api.jar.all())
            } catch (_: Exception) {
                if (finished || reading == Reading.Silently) return@launch
                setBusy(false)
                retry = { load(Reading.Visibly, read) }
                show(Phase.Failed)
            }
        }
    }

    private fun reload(reading: Reading = Reading.Visibly) {
        val api = api ?: return
        if (finished) return
        load(reading) { api.sheet() }
    }

    private fun render(sheet: Sheet) {
        if (finished) return
        this.sheet = sheet
        val context = SheetContext(theme, Words(sheet.text), sheet.rtl, sheet.sandbox)
        this.context = context
        setBusy(false)
        showNotice(null)

        chipSlot.removeAllViews()
        if (sheet.sandbox) chipSlot.addView(Chip(ui, theme, context.words["test.mark"]) { theme.warning }, FrameLayout.LayoutParams(WRAP, WRAP, Gravity.CENTER_VERTICAL))

        when (sheet.state) {
            Sheet.State.Pay -> {
                val pay = sheet.pay
                val payment = sheet.payment
                val api = api
                if (pay == null || payment == null || pay.mode == "raw" || api == null) {
                    // the card is typed on the bank's own page: that page is a web page
                    if (api != null) showWeb(api.location.pageUrl, api.jar.all())
                    return
                }
                lastOutcome = null
                api.warmUp()
                val page = PayView(ui, context, sheet, payment, pay)
                page.onChoice = { primary.title = page.buttonTitle }
                page.onCardEditing = { prepareSession() }
                page.onFocus = { view ->
                    focused = view
                    reveal(view)
                }
                configuration.merchantLogo?.let { page.showLogo(it) }
                page.onSignOut = { signOut() }
                page.onOpen = { open(it) }
                page.onOpenApp = { url -> openRaisApp(url) { page.appOpenEnded() } }
                payView = page
                primary.title = page.buttonTitle
                primary.icon = R.drawable.rais_ic_lock
                primary.isEnabled = true
                secondaryWanted = false
                assuranceLabel.text = context.words["pay.encrypted"]
                show(Phase.Pay, page)
                // the app's picture (a drawable, else an https address), else the logo of the merchant's account
                if (configuration.merchantLogo == null) {
                    val candidates = listOfNotNull(app.rais.checkout.internal.CheckoutUrl.httpsImage(configuration.merchantLogoUrl), sheet.merchant?.logoUrl)
                    if (candidates.isNotEmpty()) scope.launch {
                        for (address in candidates) {
                            val bytes = api.image(address) ?: continue
                            val bitmap = kotlinx.coroutines.withContext(Dispatchers.Default) { BitmapFactory.decodeByteArray(bytes, 0, bytes.size) } ?: continue
                            if (payView === page) page.showLogo(bitmap)
                            return@launch
                        }
                    }
                }
            }
            Sheet.State.Result -> {
                val result = sheet.result ?: return showMissing(sheet)
                lastOutcome = result.outcome
                val page = OutcomeView(ui, context, sheet, result)
                page.onViewReceipt = { openReceipt() }
                page.onEmailReceipt = { emailReceipt(it) }
                page.onContact = { address -> mail(address) }
                page.onFocus = { view ->
                    focused = view
                    reveal(view)
                }
                outcomeView = page
                when {
                    result.confirming != null -> {
                        primary.title = context.words["confirming.check_again"]
                        primary.icon = R.drawable.rais_ic_refresh
                        secondaryWanted = false
                    }
                    result.retry -> {
                        primary.title = context.words["result.try_again"]
                        primary.icon = R.drawable.rais_ic_card
                        secondary.title = context.words["js.sdk_done"]
                        secondaryWanted = true
                    }
                    else -> {
                        primary.title = context.words["js.sdk_done"]
                        primary.icon = null
                        secondaryWanted = false
                    }
                }
                primary.isEnabled = true
                show(Phase.Outcome, page)
                when (result.tone) {
                    Sheet.Outcome.Tone.Ok -> root.haptic(HAPTIC_SUCCESS)
                    Sheet.Outcome.Tone.Bad -> root.haptic(HAPTIC_ERROR)
                    Sheet.Outcome.Tone.Neutral -> Unit
                }
                result.confirming?.let(::poll)
            }
            Sheet.State.NotFound -> showMissing(sheet)
        }
    }

    /** A link that names no payment: the server's two sentences, and the way out. */
    private fun showMissing(sheet: Sheet) {
        val context = context ?: return
        val column = Stack(ui, vertical = true)
        column.gravity = Gravity.CENTER_HORIZONTAL
        column.setPadding(0, dp(12), 0, 0)
        column.add(IconDisc(ui, theme, R.drawable.rais_ic_exclamation, { theme.secondary }), width = dp(64), height = dp(64))
        val title = Label(ui, theme, 22f, 700).centered()
        title.text = sheet.title ?: activity.raisString(R.string.rais_invalid_url)
        column.add(title, before = dp(16))
        val lead = Label(ui, theme, 15f) { theme.secondary }.centered()
        lead.text = sheet.lead
        column.add(lead, before = dp(8))
        primary.title = context.words["js.sdk_done"].ifEmpty { activity.raisString(R.string.rais_close) }
        primary.icon = null
        secondaryWanted = false
        show(Phase.Outcome, column)
    }

    // ── Showing a phase ──

    private fun show(new: Phase, page: View? = null) {
        phase = new
        loadFailedShowing = new == Phase.Failed
        failure.visibility = if (new == Phase.Failed) View.VISIBLE else View.GONE
        spinner.visibility = if (new == Phase.Loading) View.VISIBLE else View.GONE
        if (new != Phase.Pay) payView = null
        if (new != Phase.Code) codeView = null
        if (new != Phase.Outcome) outcomeView = null

        hideKeyboard()
        focused = null
        stage.removeAllViews()
        bar.visibility = if (page == null) View.GONE else View.VISIBLE
        if (page == null) {
            updateRoom()
            return
        }
        stage.add(page)
        applyDirection()
        updateBar()
        updateRoom()
        scroll.scrollTo(0, 0)
        topFade.alpha = 0f
        // the new page settles in: a short rise, so a change of step reads as one
        page.alpha = 0f
        page.translationY = theme.dp(10f)
        page.animate().alpha(1f).translationY(0f).setDuration(280).start()
        bar.alpha = 0f
        bar.animate().alpha(1f).setDuration(280).start()
        page.post { page.sendAccessibilityEvent(AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED) }
    }

    /** The words may read the other way than the app (an Arabic checkout in a French app): the screen follows the words. */
    private fun applyDirection() {
        val rtl = context?.rtl ?: return
        val direction = if (rtl) View.LAYOUT_DIRECTION_RTL else View.LAYOUT_DIRECTION_LTR
        if (root.layoutDirection != direction) {
            root.layoutDirection = direction
            layoutChrome()
        }
    }

    private fun showNotice(text: String?) {
        val hidden = text == null
        if (!hidden) {
            notice.text = text
            notice.haptic(HAPTIC_ERROR)
            notice.announceForAccessibility(text)
        }
        notice.visibility = if (hidden) View.GONE else View.VISIBLE
    }

    private fun setBusy(busy: Boolean) {
        isBusy = busy
        primary.isLoading = busy
        secondary.isEnabled = !busy
        closeButton.isEnabled = !busy
        closeButton.alpha = if (busy) 0.4f else 1f
        payView?.isBusy = busy
        codeView?.code?.isLocked = busy
    }

    // ── Paying ──

    private fun primaryTapped() {
        if (isBusy) return
        when (phase) {
            Phase.Pay -> submitPayment()
            Phase.Code -> submitCode()
            Phase.Outcome -> {
                val confirming = sheet?.result?.confirming
                val api = api
                when {
                    confirming != null && api != null -> load { api.sheetAt(confirming.resultUrl) }
                    sheet?.result?.retry == true -> reload()
                    else -> finish()
                }
            }
            else -> Unit
        }
    }

    private fun secondaryTapped() {
        if (isBusy) return
        when (phase) {
            Phase.Code -> cancelCode()
            Phase.Outcome -> finish()
            else -> Unit
        }
    }

    private fun submitPayment(secondTry: Boolean = false) {
        val api = api ?: return
        val payView = payView ?: return
        val context = context ?: return
        val submission = payView.collect() ?: return
        showNotice(null)
        setBusy(true)
        scope.launch {
            try {
                if (submission.method is CheckoutApi.Method.NewCard) ensureSession()
                val step = api.pay(submission.method, submission.email)
                advance(step)
            } catch (error: CancellationException) {
                throw error
            } catch (refusal: Refusal) {
                if (finished) return@launch
                val words = context.words
                when {
                    refusal.code == "awaiting_otp" && !secondTry -> {
                        // an earlier attempt still waits for a code this screen can no longer give: it is dropped, and this one goes on
                        api.cancelCode()
                        setBusy(false)
                        submitPayment(secondTry = true)
                    }
                    (refusal.code == "session_over" || refusal.code == "not_registered") && !secondTry -> {
                        sessionUntil = 0
                        setBusy(false)
                        submitPayment(secondTry = true)
                    }
                    refusal.code in setOf("already_paid", "not_payable", "expired", "confirming") ->
                        reload()   // the payment is no longer what this screen shows: show what it is
                    refusal.code == "cvc_required" -> {
                        setBusy(false)
                        if (!payView.askForSavedCvc()) showNotice(words.describing(refusal))
                    }
                    refusal.code == "cvc_invalid" -> {
                        setBusy(false)
                        if (!payView.showSavedCvcProblem(words.describing(refusal))) showNotice(words.describing(refusal))
                    }
                    else -> {
                        setBusy(false)
                        if (refusal.field == "email" && payView.showEmailProblem(words.describing(refusal))) return@launch
                        if (refusal.code == "session_over" || refusal.code == "not_registered") sessionUntil = 0
                        showNotice(words.describing(refusal))
                    }
                }
            } catch (_: Exception) {
                if (finished) return@launch
                setBusy(false)
                showNotice(context.words["js.connection"])
            }
        }
    }

    private fun advance(step: CheckoutApi.Step) {
        if (finished) return
        val api = api ?: return
        val context = context ?: return
        when (step) {
            is CheckoutApi.Step.Complete -> {
                sessionUntil = 0
                // the button keeps spinning until the outcome is read: the payer never sees the form again in between
                load(Reading.BehindThePage) { api.sheet(order = step.order, fresh = step.order == null) }
            }
            is CheckoutApi.Step.Code -> {
                val page = CodeView(ui, context, step.maskedCard, sheet?.pay?.otpLength ?: 6)
                page.code.onChange = {
                    page.setProblem(null)
                    primary.isEnabled = page.code.isComplete
                }
                page.code.onDone = { if (!isBusy) submitCode() }
                codeView = page
                setBusy(false)
                showNotice(null)
                primary.title = context.words["otp.cta"]
                primary.icon = R.drawable.rais_ic_lock
                primary.isEnabled = false
                secondary.title = context.words["otp.cancel"]
                secondaryWanted = true
                show(Phase.Code, page)
                root.postDelayed({
                    if (codeView === page) {
                        focused = page.code
                        page.code.focus()
                    }
                }, 300)
            }
        }
    }

    /** A session with the bank for a typed card: asked for when the payer starts on the card, renewed when it ran out. */
    private suspend fun ensureSession() {
        if (sessionUntil > System.currentTimeMillis() + 10_000) return
        sessionTask?.let { return it.await() }
        val api = api ?: return
        val seconds = sheet?.pay?.sessionSeconds ?: 570.0
        val task = scope.async {
            api.register()
            sessionUntil = System.currentTimeMillis() + (seconds * 1000).toLong()
        }
        sessionTask = task
        try {
            task.await()
        } finally {
            if (sessionTask === task) sessionTask = null
        }
    }

    private fun prepareSession() {
        val now = System.currentTimeMillis()
        if (phase != Phase.Pay || isBusy || now - lastPrepare < 15_000) return
        lastPrepare = now
        scope.launch { runCatching { ensureSession() } }   // said again when the payer pays
    }

    /** "Pay with Raïs" without the Raïs app: sign in on the Raïs website, then pay from the balance or a saved card here, natively. */
    private fun signInWithRais() {
        val api = api ?: return
        if (finished || webSignIn != null) return
        val scheme = WebSignIn.callbackScheme(activity)
        val flow = WebSignIn(scheme) { outcome ->
            if (finished) return@WebSignIn
            webSignIn = null
            when (outcome) {
                is WebSignIn.Outcome.SignedIn -> {
                    api.adoptPayerSession(outcome.session)
                    load(Reading.Silently) { api.sheet() }
                }
                WebSignIn.Outcome.Cancelled -> Unit
                WebSignIn.Outcome.Failed -> showNotice(context?.words?.get("wallet.sign_in_failed")?.takeIf { it.isNotEmpty() })
            }
        }
        webSignIn = flow
        flow.start(activity, api.signInUrl(scheme))
    }

    private fun signOut() {
        val api = api ?: return
        if (isBusy || finished) return
        show(Phase.Loading)
        scope.launch {
            api.signOut()
            reload()
        }
    }

    // ── The bank's code ──

    private fun submitCode() {
        val api = api ?: return
        val codeView = codeView ?: return
        val context = context ?: return
        if (!codeView.code.isComplete) return
        showNotice(null)
        setBusy(true)
        val code = codeView.code.code
        scope.launch {
            try {
                advance(api.submitCode(code))
            } catch (error: CancellationException) {
                throw error
            } catch (refusal: Refusal) {
                if (finished) return@launch
                setBusy(false)
                if (refusal.retry) {
                    // the bank refused this code but its step goes on
                    codeView.code.code = ""
                    codeView.setProblem(context.words["js.otp_wrong"])
                    primary.isEnabled = false
                    codeView.code.focus()
                } else if (refusal.status == 429 || refusal.code == "in_progress" || refusal.code == "gateway_unreachable") {
                    showNotice(context.words.describing(refusal))   // the step is still there: the same code can be sent again
                } else {
                    // the step is over (refused, expired): back to the ways to pay, with the reason
                    returnToPayment(context.words.describing(refusal))
                }
            } catch (_: Exception) {
                if (finished) return@launch
                setBusy(false)
                showNotice(context.words["js.connection"])
            }
        }
    }

    private fun cancelCode() {
        val api = api ?: return
        if (finished) return
        setBusy(true)
        scope.launch {
            api.cancelCode()
            sessionUntil = 0
            reload()
        }
    }

    private fun returnToPayment(reason: String) {
        val api = api ?: return
        sessionUntil = 0
        show(Phase.Loading)
        scope.launch {
            try {
                val sheet = api.sheet()
                if (finished) return@launch
                render(sheet)
                if (phase == Phase.Pay) showNotice(reason)
            } catch (error: CancellationException) {
                throw error
            } catch (_: Exception) {
                reload()
            }
        }
    }

    // ── After the payment ──

    /** The bank has named no outcome yet: ask every few seconds, for a few minutes, and show the outcome as soon as there is one. */
    private fun poll(confirming: Sheet.Outcome.Confirming) {
        poller?.cancel()
        val api = api ?: return
        poller = scope.launch {
            repeat(45) {
                delay(4_000)
                if (!isActive || finished) return@launch
                if (api.isSettled(confirming.statusUrl)) {
                    if (!isActive) return@launch
                    load(Reading.Silently) { api.sheetAt(confirming.resultUrl) }
                    return@launch
                }
            }
            outcomeView?.stopProgress()
        }
    }

    private fun openReceipt() {
        val api = api ?: return
        val outcomeView = outcomeView ?: return
        val receipt = sheet?.result?.receipt ?: return
        if (outcomeView.isOpeningReceipt) return
        receiptFile?.let { return presentReceipt(it) }
        outcomeView.isOpeningReceipt = true
        val name = "Rais-${sheet?.result?.outcome?.orderNumber ?: sheet?.payment?.id ?: "receipt"}.pdf".replace("/", "-")
        val file = File(File(ui.cacheDir, "rais-receipts"), "${UUID.randomUUID()}/$name")
        scope.launch {
            val saved = try {
                api.downloadReceipt(receipt.url, file)
                true
            } catch (error: CancellationException) {
                throw error
            } catch (_: Exception) {
                false
            }
            if (finished) return@launch
            outcomeView.isOpeningReceipt = false
            if (!saved) return@launch showNotice(context?.words?.get("js.connection"))
            receiptFile = file
            showNotice(null)
            presentReceipt(file)
        }
    }

    private fun presentReceipt(file: File) {
        if (receiptViewer != null) return
        val viewer = ReceiptViewer(ui, theme, file, activity.raisString(R.string.rais_close)) {
            receiptViewer?.let { root.removeView(it) }
            receiptViewer = null
        }
        receiptViewer = viewer
        root.addView(viewer, FrameLayout.LayoutParams(MATCH, MATCH))
        viewer.apply(bars)
    }

    private fun emailReceipt(address: String?) {
        val api = api ?: return
        if (finished) return
        val outcomeView = outcomeView ?: return
        val context = context ?: return
        scope.launch {
            try {
                val sent = api.emailReceipt(address)
                outcomeView.receiptEmailed(sent)
                showNotice(null)
            } catch (error: CancellationException) {
                throw error
            } catch (refusal: Refusal) {
                val text = context.words.describing(refusal)
                if (!outcomeView.receiptEmailFailed(text)) showNotice(text)
            } catch (_: Exception) {
                val text = context.words["js.connection"]
                if (!outcomeView.receiptEmailFailed(text)) showNotice(text)
            }
        }
    }

    /**
     * Hands the payment to the Raïs app (an explicit intent for its package: no `<queries>`, no package lookup; a missing app is an
     * [ActivityNotFoundException]). When the app is not there the same button signs the payer in with Raïs on the website instead.
     */
    private fun openRaisApp(appUrl: String, done: () -> Unit) {
        if (finished) return
        val intent = RaisApp.intent(appUrl)
        var opened = false
        if (intent != null) {
            leftForApp = true
            opened = try {
                activity.startActivity(intent)
                true
            } catch (_: ActivityNotFoundException) {
                false
            } catch (_: SecurityException) {
                false
            }
        }
        if (opened) {
            appOpenEnded = done   // the row keeps its spinner until the payer is back
        } else {
            leftForApp = false
            done()
            signInWithRais()
        }
    }

    /**
     * Back from the Raïs app, where the payer may have paid this very payment: read it again. This runs when the screen comes to the front,
     * which can be after the checkout has ended, so it checks [finished] first and does nothing for an ended checkout.
     */
    fun onResume() {
        if (finished) return
        webSignIn?.activityResumed()
        appOpenEnded?.invoke()
        appOpenEnded = null
        val api = api
        if (!ReturnRules.shouldReadAgain(finished, leftForApp, phase == Phase.Pay, isBusy, api != null && !api.isClosed)) return
        leftForApp = false
        // `fresh`: if the payer did pay it there, this reads "Payment Successful", not "Invoice Already Paid" (it changes words only)
        load(Reading.Silently) { api!!.sheet(fresh = true) }
    }

    /** The screen is being destroyed (finished or not): nothing may call back into it any more. */
    fun onDestroy() {
        webSignIn?.cancel()
        webSignIn = null
        appOpenEnded = null
    }

    /** The screen goes to the background: the Raïs app or the sign-in tab opened over it. */
    fun onPause() {
        if (finished) return
        webSignIn?.activityPaused()
    }

    /** Links (the terms, a top-up, the Raïs app) open outside the checkout, so the payer can always come back. */
    private fun open(url: String) {
        val uri = Uri.parse(url)
        val scheme = uri.scheme?.lowercase()
        if (scheme != "https" && scheme != "rais") return
        try {
            activity.startActivity(Intent(Intent.ACTION_VIEW, uri))
        } catch (_: ActivityNotFoundException) {
            // nothing to open it with
        }
    }

    private fun mail(address: String) {
        try {
            activity.startActivity(Intent(Intent.ACTION_SENDTO, Uri.parse("mailto:" + Uri.encode(address))))
        } catch (_: ActivityNotFoundException) {
            // no mail app
        }
    }

    // ── The web checkout, inside this screen ──

    private fun showWeb(url: String, cookies: List<CookieJar.Cookie>) {
        if (phase == Phase.Web) return
        phase = Phase.Web
        poller?.cancel()
        hideKeyboard()
        host.removeView(root)
        onWeb(url, cookies)
    }

    // ── Leaving ──

    /** The system back button. */
    fun back() {
        receiptViewer?.let {
            it.close()
            return
        }
        if (isBusy) return
        finish()
    }

    private fun closeTapped() {
        if (isBusy) return
        finish()
    }

    fun finish(forced: RaisCheckout.Result? = null) {
        if (finished) return
        finished = true
        poller?.cancel()
        webSignIn?.cancel()
        webSignIn = null
        val result = forced ?: makeResult()
        api?.let { api ->
            val abandon = phase == Phase.Code
            api.beginClosing()   // from here on nothing but dropping the bank's step may be sent
            // (not the screen's scope: it ends with the screen, and a code nobody will type must not hold the payment)
            CoroutineScope(Dispatchers.IO).launch {
                if (abandon) api.cancelCode()
                api.invalidate()
            }
        }
        receiptFile?.parentFile?.deleteRecursively()
        hideKeyboard()
        onFinish(result)
    }

    private fun makeResult(): RaisCheckout.Result {
        val outcome = lastOutcome ?: return if (loadFailedShowing) {
            RaisCheckout.Result.Failed(RaisCheckout.Failure(RaisCheckout.Failure.Code.LoadFailed, activity.raisString(R.string.rais_load_failed_title), null))
        } else {
            RaisCheckout.Result.Cancelled
        }
        return when (outcome.status) {
            "paid" -> RaisCheckout.Result.Completed(RaisCheckout.Payment(outcome.paymentId, outcome.reference, outcome.orderNumber))
            "failed" -> RaisCheckout.Result.Failed(
                RaisCheckout.Failure(RaisCheckout.Failure.Code.Declined, activity.raisString(R.string.rais_declined), outcome.paymentId),
            )
            "expired" -> RaisCheckout.Result.Failed(
                RaisCheckout.Failure(RaisCheckout.Failure.Code.Expired, activity.raisString(R.string.rais_expired), outcome.paymentId),
            )
            else -> RaisCheckout.Result.Cancelled
        }
    }
}

/**
 * The page's scroll view. It never scrolls by itself to a field that takes the focus (the platform would stop at the field's edge, under
 * the pane of the button): the screen brings the whole field, with what is said under it, into view itself.
 */
internal class PageScroll(context: Context) : ScrollView(context) {
    var onScrolled: ((Int) -> Unit)? = null

    override fun onScrollChanged(left: Int, top: Int, oldLeft: Int, oldTop: Int) {
        super.onScrollChanged(left, top, oldLeft, oldTop)
        onScrolled?.invoke(top)
    }

    override fun requestChildRectangleOnScreen(child: View, rectangle: Rect, immediate: Boolean): Boolean = false

    override fun computeScrollDeltaToGetChildRectOnScreen(rect: Rect?): Int = 0
}

/** The page's column: never wider than a phone (480 dp), 20 dp from the edges, in the middle of a tablet or a landscape screen. */
@SuppressLint("ViewConstructor")
internal class PageColumn(context: Context, private val theme: SheetTheme) : FrameLayout(context) {
    init {
        isClickable = true
        isFocusable = false
    }

    override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
        val width = MeasureSpec.getSize(widthMeasureSpec)
        val inner = min(width - theme.dp(40), theme.dp(480)).coerceAtLeast(0)
        val side = ((width - inner) / 2f).roundToInt()
        setPadding(side, 0, width - inner - side, theme.dp(20))
        super.onMeasure(widthMeasureSpec, heightMeasureSpec)
    }
}

internal fun ViewGroup.childrenDeep(): Sequence<View> = sequence {
    for (index in 0 until childCount) {
        val child = getChildAt(index)
        yield(child)
        if (child is ViewGroup) yieldAll(child.childrenDeep())
    }
}

/**
 * Arabic reads right to left whatever the app around the checkout supports. Android lays views out right to left only in an app that
 * declares `android:supportsRtl="true"`; an SDK must not change that for the whole app (its own screens would start mirroring), so the
 * checkout's views alone are made with a context that says the app supports it. The screen then follows the sheet's own `rtl`.
 */
internal class RtlCapableContext(base: Context) : android.content.ContextWrapper(base) {
    private val info = android.content.pm.ApplicationInfo(base.applicationInfo).apply {
        flags = flags or android.content.pm.ApplicationInfo.FLAG_SUPPORTS_RTL
    }

    override fun getApplicationInfo(): android.content.pm.ApplicationInfo = info
}
