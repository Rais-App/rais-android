package app.rais.checkout

import app.rais.checkout.internal.sheet.ApiFailure
import app.rais.checkout.internal.sheet.CheckoutApi
import app.rais.checkout.internal.sheet.Location
import app.rais.checkout.internal.sheet.PayView
import app.rais.checkout.internal.sheet.RaisApp
import app.rais.checkout.internal.sheet.ReturnRules
import app.rais.checkout.internal.sheet.Sheet
import app.rais.checkout.internal.sheet.WebSignIn
import app.rais.checkout.internal.sheet.canFoldSaved
import app.rais.checkout.internal.sheet.firstSavedOf
import app.rais.checkout.internal.sheet.foldedAfter
import app.rais.checkout.internal.sheet.initialChoiceOf
import app.rais.checkout.internal.sheet.tappedChoice
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test
import java.io.File
import java.net.ServerSocket
import java.net.SocketTimeoutException

private const val ID = "95855e6f-fcbd-44cb-ac78-5e2c36cf8b0f"

private fun api(origin: String = "https://pay.rais.app"): CheckoutApi {
    val url = "$origin/pay/$ID"
    return CheckoutApi(url, Location.of(url)!!, null)
}

/** "Pay with Raïs" without the Raïs app: the sign-in on the website, its answer, and the payer session it hands over (rais-ios NativeCheckoutTests). */
class SignInWithRaisTest {
    @Test fun theSignInStartsOnTheCheckoutWithASchemeTheServerAccepts() {
        val api = api()
        try {
            // the checkout takes `raiscb-` and 8 to 40 lower-case letters or digits (app/views/payment.py `_NATIVE_SCHEME`): never another app's scheme
            assertTrue(WebSignIn.isAccepted(WebSignIn.DEFAULT_SCHEME))
            assertTrue(WebSignIn.isAccepted("raiscb-" + "a1".repeat(20)))
            for (bad in listOf("raiscb-short", "raiscb-" + "a".repeat(41), "raiscb-UPPERCASE1", "myapp", "rais", "raiscb-abc_defgh", "https", "raiscb-")) {
                assertFalse(bad, WebSignIn.isAccepted(bad))
            }
            assertEquals(
                "https://pay.rais.app/pay/$ID/raisid/start?native=${WebSignIn.DEFAULT_SCHEME}",
                api.signInUrl(WebSignIn.DEFAULT_SCHEME),
            )
        } finally {
            api.invalidate()
        }
    }

    @Test fun theSchemeTheManifestListensOnIsOneTheServerAccepts() {
        val strings = File("src/main/res/values/strings.xml").readText()
        val declared = Regex("""name="rais_callback_scheme"[^>]*>([^<]+)<""").find(strings)!!.groupValues[1]
        assertEquals(WebSignIn.DEFAULT_SCHEME, declared)
        assertTrue(WebSignIn.isAccepted(declared))
        val manifest = File("src/main/AndroidManifest.xml").readText()
        assertTrue("the sign-in's door is declared with that string", manifest.contains("""android:scheme="@string/rais_callback_scheme""""))
        assertTrue(manifest.contains("""android:host="signed-in""""))
        assertFalse("no package queries are needed for the Raïs app", manifest.contains("<queries"))
    }

    @Test fun thePayerSessionTheSignInHandsOverBecomesThePagesOwnCookieForThisPaymentOnly() {
        val api = api()
        try {
            val before = System.currentTimeMillis()
            api.adoptPayerSession("payload.mac")
            val cookie = api.jar.all().first { it.name == "rais_payer" }
            assertEquals("payload.mac", cookie.value)
            assertEquals("/pay/$ID", cookie.path)
            assertEquals("pay.rais.app", cookie.host)
            assertTrue(cookie.secure)
            assertTrue("it lasts 15 minutes", cookie.expiresAt!! in (before + 890_000)..(System.currentTimeMillis() + 910_000))
            val sent = { path: String -> api.jar.header(java.net.URI("https://pay.rais.app$path")) }
            assertEquals("rais_payer=payload.mac", sent("/pay/$ID/sheet"))
            assertNull("another payment never gets it", sent("/pay/95855e6f-fcbd-44cb-ac78-000000000000/sheet"))
            assertNull("nor does the rest of the site", sent("/lang/fr"))
        } finally {
            api.invalidate()
        }
    }

    @Test fun aSessionIsNotKeptByAClosedCheckout() {
        val api = api("http://localhost:8585")
        api.invalidate()
        api.adoptPayerSession("x.y")
        assertTrue(api.jar.all().none { it.name == "rais_payer" })
    }

    @Test fun theAnswerOfTheWebsiteIsReadFieldByField() {
        val scheme = WebSignIn.DEFAULT_SCHEME
        val ok = WebSignIn.parse("$scheme://signed-in?s=abc.def", scheme) as WebSignIn.Outcome.SignedIn
        assertEquals("abc.def", ok.session)
        assertEquals("percent-encoding is undone", "a/b", (WebSignIn.parse("$scheme://signed-in?x=1&s=a%2Fb", scheme) as WebSignIn.Outcome.SignedIn).session)
        assertEquals("the scheme is compared without case", "t", (WebSignIn.parse("${scheme.uppercase()}://signed-in?s=t", scheme) as WebSignIn.Outcome.SignedIn).session)

        assertNull("another scheme is not an answer to this sign-in", WebSignIn.parse("raiscb-someoneelse://signed-in?s=abc", scheme))
        assertNull(WebSignIn.parse("https://pay.rais.app/pay/$ID", scheme))
        assertNull(WebSignIn.parse(null, scheme))
        assertNull(WebSignIn.parse("not a url", scheme))
        for (bad in listOf("$scheme://elsewhere?s=abc", "$scheme://signed-in", "$scheme://signed-in?s=", "$scheme://signed-in?x=1",
            "$scheme://signed-in?s=a%3Bb", "$scheme://signed-in?s=a%0Ab", "$scheme://signed-in?s=" + "a".repeat(5000))) {
            assertTrue(bad, WebSignIn.parse(bad, scheme) is WebSignIn.Outcome.Failed)
        }
    }

    @Test fun aSignInEndsOnceWithWhatHappened() {
        val scheme = WebSignIn.DEFAULT_SCHEME
        val seen = mutableListOf<WebSignIn.Outcome>()
        val flow = WebSignIn(scheme) { seen.add(it) }
        WebSignIn.current = flow
        assertTrue(flow.isPending)
        assertFalse("an answer on another scheme is not taken", WebSignIn.deliver("raiscb-other00000://signed-in?s=x"))
        assertTrue(WebSignIn.deliver("$scheme://signed-in?s=tok"))
        assertFalse(flow.isPending)
        assertNull("a sign-in that ended is no longer waited for", WebSignIn.current)
        assertFalse("a second answer is not taken", WebSignIn.deliver("$scheme://signed-in?s=again"))
        assertEquals(1, seen.size)
        assertEquals("tok", (seen[0] as WebSignIn.Outcome.SignedIn).session)
    }

    @Test fun nothingHappensForAnAnswerWhenNoSignInWaits() {
        WebSignIn.current = null
        assertFalse(WebSignIn.deliver("${WebSignIn.DEFAULT_SCHEME}://signed-in?s=tok"))
    }

    @Test fun aClosedTabIsACancelledSignIn() {
        val seen = mutableListOf<WebSignIn.Outcome>()
        val flow = WebSignIn(WebSignIn.DEFAULT_SCHEME) { seen.add(it) }
        WebSignIn.current = flow
        flow.activityResumed()
        assertTrue("the screen was never left: not a cancel", seen.isEmpty())
        flow.activityPaused()   // the tab opens over the screen
        flow.activityResumed()  // the payer closed it
        assertEquals(listOf(WebSignIn.Outcome.Cancelled), seen)
        assertNull(WebSignIn.current)
    }

    @Test fun anAnswerBeatsTheScreenComingBack() {
        val seen = mutableListOf<WebSignIn.Outcome>()
        val flow = WebSignIn(WebSignIn.DEFAULT_SCHEME) { seen.add(it) }
        WebSignIn.current = flow
        flow.activityPaused()
        assertTrue(flow.handleReturn("${WebSignIn.DEFAULT_SCHEME}://signed-in?s=tok"))
        flow.activityResumed()   // (the checkout is brought back to the front by the answer itself)
        assertEquals(1, seen.size)
        assertTrue(seen[0] is WebSignIn.Outcome.SignedIn)
    }

    @Test fun aCancelledCheckoutIsNotToldAnything() {
        val seen = mutableListOf<WebSignIn.Outcome>()
        val flow = WebSignIn(WebSignIn.DEFAULT_SCHEME) { seen.add(it) }
        WebSignIn.current = flow
        flow.cancel()
        assertNull(WebSignIn.current)
        assertFalse(flow.handleReturn("${WebSignIn.DEFAULT_SCHEME}://signed-in?s=tok"))
        assertTrue(seen.isEmpty())
    }

    @Test fun anOldAnswerIsNotOneThisSignInAskedFor() {
        var now = 1_000L
        val seen = mutableListOf<WebSignIn.Outcome>()
        val flow = WebSignIn(WebSignIn.DEFAULT_SCHEME, { now }) { seen.add(it) }
        now += 11 * 60 * 1000
        flow.handleReturn("${WebSignIn.DEFAULT_SCHEME}://signed-in?s=tok")
        assertEquals(listOf(WebSignIn.Outcome.Cancelled), seen)
    }
}

/** Opening the Raïs app, and coming back from it. */
class RaisAppTest {
    @Test fun onlyARaisAddressIsHandedToTheApp() {
        assertEquals("rais://pay/$ID", RaisApp.target("rais://pay/$ID"))
        assertEquals("rais://pay/$ID", RaisApp.target("  rais://pay/$ID "))
        assertEquals("RAIS://pay/$ID", RaisApp.target("RAIS://pay/$ID"))
        for (other in listOf("https://pay.rais.app/pay/$ID", "http://evil.example/", "javascript:alert(1)", "intent://pay/x#Intent;end", "rais:", "rais://", "", null, "not a url")) {
            assertNull(other, RaisApp.target(other))
        }
        assertEquals("app.rais.wallet", RaisApp.PACKAGE)
    }

    @Test fun backFromTheAppThePaymentIsReadAgainOnlyWhileTheCheckoutLives() {
        assertTrue(ReturnRules.shouldReadAgain(finished = false, leftForApp = true, onPayScreen = true, isBusy = false, apiOpen = true))
        // the crash class: the checkout ended (paid, closed, dismissed) and the screen still comes back to the front
        assertFalse(ReturnRules.shouldReadAgain(finished = true, leftForApp = true, onPayScreen = true, isBusy = false, apiOpen = true))
        assertFalse("its client is closed", ReturnRules.shouldReadAgain(finished = false, leftForApp = true, onPayScreen = true, isBusy = false, apiOpen = false))
        assertFalse("the payer never left", ReturnRules.shouldReadAgain(finished = false, leftForApp = false, onPayScreen = true, isBusy = false, apiOpen = true))
        assertFalse("not on the payment screen", ReturnRules.shouldReadAgain(finished = false, leftForApp = true, onPayScreen = false, isBusy = false, apiOpen = true))
        assertFalse("a payment is in flight", ReturnRules.shouldReadAgain(finished = false, leftForApp = true, onPayScreen = true, isBusy = true, apiOpen = true))
    }
}

/** No call goes out after the checkout ended: the guard that stops the crash iOS had when the payer came back from the Raïs app. */
class ClosedCheckoutTest {
    private fun refused(block: suspend () -> Unit): Boolean = try {
        runBlocking { block() }
        false
    } catch (_: ApiFailure.Connection) {
        true
    }

    @Test fun aClosedClientRefusesEveryCallWithoutSendingAnything() {
        ServerSocket(0).use { server ->
            server.soTimeout = 400
            val api = api("http://127.0.0.1:${server.localPort}")
            api.invalidate()
            assertTrue(api.isClosed)
            assertTrue(refused { api.sheet() })
            assertTrue(refused { api.sheet(fresh = true) })
            assertTrue(refused { api.pay(CheckoutApi.Method.Balance, null) })
            assertTrue(refused { api.register() })
            assertTrue(refused { api.sheetAt("http://127.0.0.1:${server.localPort}/pay/$ID/sheet") })
            assertTrue(refused { api.emailReceipt("a@b.example") })
            runBlocking {
                api.signOut()        // (swallowed, like before: it is a best effort)
                api.cancelCode()
            }
            api.warmUp()
            try {
                server.accept().close()
                fail("a closed checkout reached the network")
            } catch (_: SocketTimeoutException) {
                // nothing connected, as it must be
            }
        }
    }

    @Test fun anEndingCheckoutMayOnlyDropTheBanksStep() {
        ServerSocket(0).use { server ->
            server.soTimeout = 3_000
            val api = api("http://127.0.0.1:${server.localPort}")
            api.beginClosing()
            assertTrue(refused { api.sheet() })
            assertTrue(refused { api.pay(CheckoutApi.Method.Balance, null) })
            val drop = Thread { runBlocking { api.cancelCode() } }
            drop.isDaemon = true
            drop.start()
            val connection = server.accept()   // the one call that is still allowed
            connection.close()
            drop.join(5_000)
            api.invalidate()
        }
    }

    @Test fun aClosedClientIsTheOnlyOneThatRefuses() {
        val api = api("http://localhost:1")
        assertFalse(api.isClosed)
        api.invalidate()
        assertTrue(api.isClosed)
    }
}

/** The saved ways to pay fold away while "Use another card" is chosen (rais-ios `PayView.setSavedFolded`). */
class SavedCardFoldTest {
    private val card = Sheet.Pay.Card("c1", Sheet.Pay.Card.Kind.Wallet, "7215", "01/27", "edahabia", false)
    private val other = Sheet.Pay.Card("c2", Sheet.Pay.Card.Kind.Saved, "1111", "02/28", "cib", true)

    private fun pay(balanceEnough: Boolean? = null, cards: List<Sheet.Pay.Card> = emptyList(), chosen: String? = null) = Sheet.Pay(
        mode = "custom", captcha = false, sessionSeconds = 570.0, otpLength = 6, payer = null,
        balance = balanceEnough?.let { Sheet.Pay.Balance("3,000.00 DA", it, null, null) }, cards = cards, chosen = chosen,
        appUrl = "rais://pay/$ID", save = null, receiptEmail = false, termsUrl = null, otpPending = false,
    )

    @Test fun otherCardFoldsTheSavedOnesAndASavedOneBringsThemBack() {
        val withSaved = pay(balanceEnough = true, cards = listOf(card, other))
        assertTrue(canFoldSaved(withSaved))
        // "Use another card": folded, the whole card form fits
        assertTrue(foldedAfter(PayView.Choice.NewCard, folded = false, canFold = true))
        // picking a saved one hides the card form again and brings the saved ways back
        assertFalse(foldedAfter(PayView.Choice.Balance, folded = true, canFold = true))
        assertFalse(foldedAfter(PayView.Choice.Card(card), folded = true, canFold = true))
        // nothing changes otherwise
        assertTrue(foldedAfter(PayView.Choice.NewCard, folded = true, canFold = true))
        assertFalse(foldedAfter(PayView.Choice.Card(other), folded = false, canFold = true))
        assertFalse(foldedAfter(PayView.Choice.Balance, folded = false, canFold = true))
    }

    @Test fun thereIsNothingToFoldWhenNothingSavedCanBePaidWith() {
        assertFalse(canFoldSaved(pay()))
        assertFalse("a balance that is too low is no way to pay", canFoldSaved(pay(balanceEnough = false)))
        assertTrue(canFoldSaved(pay(balanceEnough = false, cards = listOf(card))))
        assertTrue(canFoldSaved(pay(balanceEnough = true)))
        assertFalse(foldedAfter(PayView.Choice.NewCard, folded = false, canFold = false))
    }

    @Test fun whileFoldedTheOtherCardRowIsTheWayBackToTheSavedOnes() {
        val withSaved = pay(balanceEnough = true, cards = listOf(card, other))
        val first = firstSavedOf(withSaved)
        assertEquals(PayView.Choice.Balance, first)
        assertEquals(first, tappedChoice(PayView.Choice.NewCard, folded = true, firstSaved = first))
        assertEquals("not folded: it is just another card", PayView.Choice.NewCard, tappedChoice(PayView.Choice.NewCard, folded = false, firstSaved = first))
        assertEquals(PayView.Choice.Card(card), tappedChoice(PayView.Choice.Card(card), folded = false, firstSaved = first))
    }

    @Test fun theFirstSavedWayIsTheOneTheScreenStartsWith() {
        assertEquals(PayView.Choice.Balance, initialChoiceOf(pay(balanceEnough = true, cards = listOf(card))))
        assertEquals(PayView.Choice.Card(card), initialChoiceOf(pay(balanceEnough = false, cards = listOf(card, other))))
        assertEquals("the store's own pick", PayView.Choice.Card(other), initialChoiceOf(pay(balanceEnough = true, cards = listOf(card, other), chosen = "c2")))
        assertEquals(PayView.Choice.NewCard, initialChoiceOf(pay()))
        // a balance too low to pay and a saved card: back from the fold lands on the card
        assertEquals(PayView.Choice.Card(card), firstSavedOf(pay(balanceEnough = false, cards = listOf(card))))
        assertEquals(PayView.Choice.NewCard, firstSavedOf(pay()))
    }

    @Test fun everyWayToPayHasItsOwnKey() {
        val keys = listOf(PayView.Choice.Balance, PayView.Choice.Card(card), PayView.Choice.Card(other), PayView.Choice.NewCard).map { it.key }
        assertEquals(keys.size, keys.toSet().size)
        assertEquals("card.c1", PayView.Choice.Card(card).key)
    }
}

/**
 * The keyboard: a tap on a button with the keyboard open must fire on the FIRST tap (iOS closed the keyboard on touch-down, the layout moved
 * and the button never saw its tap). On Android the screen only reacts to a finished tap (a click listener), never to a touch going down:
 * this pins that, because nothing else can be tested without a device.
 */
class KeyboardTapTest {
    private fun sources(): List<File> =
        File("src/main/kotlin/app/rais/checkout/internal").walkTopDown().filter { it.extension == "kt" && it.name != "WebCheckout.kt" }.toList()

    @Test fun nothingActsOnATouchGoingDown() {
        val files = sources()
        assertTrue(files.size > 5)
        for (file in files) {
            val text = file.readText().lines().filterNot { it.trimStart().startsWith("//") || it.trimStart().startsWith("*") || it.trimStart().startsWith("/**") }.joinToString("\n")
            for (forbidden in listOf("ACTION_DOWN", "setOnTouchListener", "dispatchTouchEvent", "onInterceptTouchEvent", "MotionEvent.ACTION_")) {
                assertFalse("${file.name} handles touches itself ($forbidden): a tap must stay a click", text.contains(forbidden))
            }
        }
    }

    @Test fun theKeyboardIsPutAwayByAClickOnEmptySpaceOnly() {
        val checkout = File("src/main/kotlin/app/rais/checkout/internal/sheet/NativeCheckout.kt").readText()
        assertNotNull(Regex("""column\.setOnClickListener \{ hideKeyboard\(\) \}""").find(checkout))
        // the page column is clickable but a control inside it is clickable too and takes its own click first
        val views = File("src/main/kotlin/app/rais/checkout/internal/sheet/SheetViews.kt").readText()
        assertFalse("no control takes the focus from a field by a touch", views.contains("isFocusableInTouchMode = true"))
    }
}
