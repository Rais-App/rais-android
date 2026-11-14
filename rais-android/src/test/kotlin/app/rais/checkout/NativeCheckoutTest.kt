package app.rais.checkout

import app.rais.checkout.internal.sheet.CardRules
import app.rais.checkout.internal.sheet.CheckoutApi
import app.rais.checkout.internal.sheet.CookieJar
import app.rais.checkout.internal.sheet.Location
import app.rais.checkout.internal.sheet.ProofOfWork
import app.rais.checkout.internal.sheet.Refusal
import app.rais.checkout.internal.sheet.Sheet
import app.rais.checkout.internal.sheet.Words
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.net.URI
import java.util.GregorianCalendar

/**
 * The parts of the native checkout that are rules, not drawing: where an address points, the card fields, the proof of work, the answer of
 * `GET /pay/{id}/sheet`, the cookies. The same cases as rais-ios Tests/RaisTests/NativeCheckoutTests.swift, and more.
 */
class LocationTest {
    private val id = "95855e6f-fcbd-44cb-ac78-5e2c36cf8b0f"

    @Test fun aPaymentAddressNamesItsSiteAndItsPayment() {
        val plain = Location.of("https://pay.rais.app/pay/${id.uppercase()}")!!
        assertEquals("https://pay.rais.app", plain.origin)
        assertEquals(id, plain.paymentId)
        assertTrue(plain.isPlain)
        assertEquals("https://pay.rais.app/pay/$id", plain.pageUrl)

        val session = Location.of("http://localhost:8585/pay/$id/raisid?a=token&card=1")!!
        assertEquals("http://localhost:8585", session.origin)
        assertFalse("the address that identifies the payer is visited once before the checkout is read", session.isPlain)

        assertEquals("http://10.0.2.2:8585", Location.of("http://10.0.2.2:8585/pay/$id")!!.origin)
        assertEquals("https://pay.rais.app", Location.of("https://pay.rais.app:443/pay/$id")!!.origin)
    }

    @Test fun anAddressThatIsNotAPaymentGoesToTheWebCheckout() {
        for (other in listOf("https://pay.rais.app/", "https://pay.rais.app/pay/not-a-payment", "https://pay.rais.app/lang/fr?next=%2Fpay%2F$id", "not a url")) {
            assertNull(other, Location.of(other))
        }
    }

    @Test fun theQueryOfARedirectIsRead() {
        assertEquals("c 1", CheckoutApi.query("x=1&card=c%201", "card"))
        assertEquals("ORD-7", CheckoutApi.query("orderId=ORD-7&lang=fr", "orderId"))
        assertNull(CheckoutApi.query("orderIdx=1", "orderId"))
        assertNull(CheckoutApi.query(null, "card"))
    }
}

class CardRulesTest {
    @Test fun cardNumbersAreWrittenInGroupsAndReadInAnyDigits() {
        assertEquals("6280 5811 1000 7215", CardRules.formatPan("6280581110007215"))
        assertEquals("6280 5811", CardRules.formatPan("٦٢٨٠ ٥٨١١"))
        assertEquals("6280", CardRules.formatPan("۶۲۸۰"))
        assertEquals("12", CardRules.formatPan("１２"))
        assertEquals(19, CardRules.formatPan("1".repeat(30)).count { it.isDigit() })
        assertEquals(CardRules.Brand.Edahabia, CardRules.brand("6280581110007215"))
        assertEquals(CardRules.Brand.Cib, CardRules.brand("6219000000000000"))
        assertNull(CardRules.brand("4111"))
        assertEquals("6280 58•• •••• 7215", CardRules.formatMasked("628058******7215"))
        assertEquals("", CardRules.formatMasked(null))
        assertEquals(CardRules.Brand.Edahabia, CardRules.brandNamed("Dahabia"))
        assertEquals(CardRules.Brand.Cib, CardRules.brandNamed("CIB"))
        assertNull(CardRules.brandNamed("visa"))
    }

    @Test fun expiryIsWrittenAsMonthAndYear() {
        assertEquals("1", CardRules.formatExpiry("1"))
        assertEquals("05 / ", CardRules.formatExpiry("5"))
        assertEquals("12 / ", CardRules.formatExpiry("12"))
        assertEquals("12 / 27", CardRules.formatExpiry("1227"))
        assertEquals("01 / 27", CardRules.formatExpiry("01 / 27"))
        assertEquals("01 / 27", CardRules.formatExpiry("012799"))
        assertEquals("373", CardRules.formatCvc("3 7 3 9"))
    }

    @Test fun theChecksAreThePagesOwn() {
        assertEquals("js.pan_required", CardRules.checkPan("", sandbox = false))
        assertEquals("a wrong check digit", "js.pan_invalid", CardRules.checkPan("6280 5811 1000 7216", sandbox = false))
        assertNull("a test payment lets the gateway refuse it", CardRules.checkPan("6280 5811 1000 7216", sandbox = true))
        assertNull(CardRules.checkPan("6280 5811 1000 7215", sandbox = false))
        assertEquals("js.pan_invalid", CardRules.checkPan("6280 58", sandbox = true))
        val now = GregorianCalendar(2026, 10, 13).time   // 13 November 2026
        assertEquals("js.expiry_past", CardRules.checkExpiry("10 / 26", sandbox = false, now = now))
        assertNull(CardRules.checkExpiry("11 / 26", sandbox = false, now = now))
        assertEquals("js.expiry_invalid", CardRules.checkExpiry("13 / 27", sandbox = false, now = now))
        assertEquals("js.expiry_invalid", CardRules.checkExpiry("1 / 2", sandbox = false, now = now))
        assertEquals("js.expiry_required", CardRules.checkExpiry("", sandbox = false, now = now))
        assertNull(CardRules.checkExpiry("10 / 26", sandbox = true, now = now))
        assertNull(CardRules.checkExpiry("01 / 27", sandbox = false, now = now))
        assertEquals("js.cvc_invalid", CardRules.checkCvc("12"))
        assertEquals("js.cvc_invalid", CardRules.checkCvc("١٢٣"))
        assertNull(CardRules.checkCvc("123"))
        assertEquals("js.name_required", CardRules.checkName(" "))
        assertEquals("js.name_invalid", CardRules.checkName("أمينة"))
        assertNull(CardRules.checkName("Amina  Bénali"))
        assertEquals("Amina Benali", CardRules.cleanName("Amina  Bénali"))
        assertEquals("js.name_invalid", CardRules.checkName("A".repeat(27)))
        assertNull(CardRules.checkEmail(""))
        assertNull(CardRules.checkEmail("amina@example.dz"))
        assertEquals("js.email_invalid", CardRules.checkEmail("amina@example"))
        assertEquals("js.email_invalid", CardRules.checkEmail("a b@example.test"))
    }

    @Test fun luhn() {
        assertTrue(CardRules.luhn("6280581110007215"))
        assertFalse(CardRules.luhn("6280581110007216"))
        assertFalse(CardRules.luhn(""))
    }
}

class ProofOfWorkTest {
    /** The values the server's own implementation gives (app/services/captcha.py): an answer found here is accepted there. */
    @Test fun theProofOfWorkIsTheServers() {
        assertEquals(440_920_331L, ProofOfWork.fnv1a("abc"))
        assertEquals("8539570754e5fd8b81c9ec01357bd685", ProofOfWork.prng("token1", 32))
        assertEquals("4bb7", ProofOfWork.prng("token1d", 4))
        assertEquals(137_677L, ProofOfWork.solveOne("54c084d8cf2f7855249cc0a72d87a283", "f24e"))
        assertEquals(137_677L, ProofOfWork.solve("tok.sig", 3, 32, 4)?.get(2))
        assertNull(ProofOfWork.solve("tok.sig", 0, 32, 4))
        assertNull(ProofOfWork.solveOne("salt", "zz"))
    }
}

class SheetTest {
    private val id = "95855e6f-fcbd-44cb-ac78-5e2c36cf8b0f"

    private fun fixture(name: String): String = javaClass.classLoader!!.getResource("sheets/$name")!!.readText()

    @Test fun theSheetIsReadAsTheServerWritesIt() {
        val json = """
        {"v":1,"state":"pay","language":"ar","rtl":true,"sandbox":true,
         "payment":{"id":"$id","amount":450000,"amount_text":"4,500.00 DA","amount_number":"4,500.00","currency":"DA","description":null,
                    "lines":[{"name":"Tea","description":null,"quantity":"2 × 2,000.00 DA","total_text":"4,000.00 DA"}],"adjustment_text":null,
                    "details":[{"label":"Reference","value":"REF-7","mono":true}]},
         "merchant":{"name":"Dar El Baraka","logo_url":"https://pay.rais.app/logos/x.png","website":"baraka.example"},
         "text":{"pay.card_number":"Card number","js.session":"Session expires in {time}","js.code.expired":"This payment link has expired."},
         "pay":{"mode":"custom","captcha":true,"session_seconds":570,"otp_length":6,"payer":{"first_name":"Amina"},
                "balance":{"text":"3,000.00 DA","enough":false,"topup_url":"https://rais.app/dashboard/top-up","short_text":"Too low"},
                "cards":[{"id":"c1","kind":"wallet","last4":"7215","expiry":"01/27","brand":"edahabia","needs_cvc":false}],
                "chosen":"c1","app_url":null,"save":{"label":"Save this card","checked":true},"receipt_email":true,
                "terms_url":"https://rais.app/terms","otp_pending":false,"something_new":42}}
        """
        val sheet = Sheet.parse(json)!!
        assertEquals(Sheet.State.Pay, sheet.state)
        assertTrue(sheet.rtl)
        assertTrue(sheet.sandbox)
        assertNull("a JSON null is no description (not the word \"null\")", sheet.payment?.description)
        assertEquals("4,000.00 DA", sheet.payment?.lines?.first()?.totalText)
        assertNull(sheet.payment?.lines?.first()?.description)
        assertEquals(true, sheet.payment?.details?.first()?.mono)
        assertEquals("https://pay.rais.app/logos/x.png", sheet.merchant?.logoUrl)
        assertEquals(Sheet.Pay.Card.Kind.Wallet, sheet.pay?.cards?.first()?.kind)
        assertEquals(false, sheet.pay?.balance?.enough)
        assertEquals(true, sheet.pay?.captcha)
        assertEquals("Amina", sheet.pay?.payer?.firstName)
        assertEquals("c1", sheet.pay?.chosen)
        assertNull(sheet.pay?.appUrl)
        assertEquals(true, sheet.pay?.save?.checked)
        val words = Words(sheet.text)
        assertEquals("the keys of the words are kept as the server names them", "Card number", words["pay.card_number"])
        assertEquals("Session expires in 9:41", words.fill("js.session", "time" to "9:41"))
        assertEquals("", words["not.there"])
        assertEquals("This payment link has expired.", words.describing(Refusal(409, "expired", "x", null, false)))
        assertEquals("Carte bloquée", words.describing(Refusal(200, null, "Carte bloquée", null, false)))
    }

    @Test fun aResultCarriesWhatTheAppIsTold() {
        val json = """
        {"v":1,"state":"result","language":"en","rtl":false,"sandbox":false,"text":{},
         "payment":{"id":"$id","amount":1000,"amount_text":"10.00 DA"},"merchant":{"name":"Shop","logo_url":null,"website":null},
         "result":{"tone":"ok","icon":"check","title":"Payment Successful","lead":"Approved","note":null,"rows":[{"label":"Amount","value":"10.00 DA"}],
                   "support":null,"receipt":{"url":"https://pay.rais.app/pay/$id/receipt.pdf","mail":{"sent":false,"to":null,"known":"a***@example.dz"}},
                   "retry":false,"contact_email":null,"outcome":{"status":"paid","payment_id":"$id","reference":"REF-7","order_number":"RS1"}}}
        """
        val sheet = Sheet.parse(json)!!
        val result = sheet.result!!
        assertEquals("10.00 DA", sheet.payment?.amountNumber)   // (no amount_number: the amount's text)
        assertEquals(Sheet.Outcome.Tone.Ok, result.tone)
        assertEquals("paid", result.outcome?.status)
        assertEquals("RS1", result.outcome?.orderNumber)
        assertEquals("a***@example.dz", result.receipt?.mail?.known)
        assertNull(result.receipt?.mail?.to)
        assertNull(result.confirming)
        assertFalse(result.rows.first().mono)
    }

    @Test fun whatThisVersionCannotReadIsLeftOutOrRefused() {
        val confirming = """
        {"state":"result","result":{"tone":"sideways","title":"Confirming","receipt":{"mail":{}},
         "confirming":{"status_url":"http://localhost:8585/payment/status?orderId=x","result_url":"http://localhost:8585/pay/$id/sheet?order=x"}}}
        """
        val result = Sheet.parse(confirming)!!.result!!
        assertEquals("an unknown tone is neutral", Sheet.Outcome.Tone.Neutral, result.tone)
        assertEquals("alert", result.icon)
        assertNull("a receipt it cannot read is left out", result.receipt)
        assertEquals("http://localhost:8585/pay/$id/sheet?order=x", result.confirming?.resultUrl)
        assertEquals("en", Sheet.parse(confirming)!!.language)

        assertNull("an unknown state is not drawn", Sheet.parse("""{"state":"teleport"}"""))
        assertNull("a card of an unknown kind is not drawn", Sheet.parse("""{"state":"pay","pay":{"cards":[{"id":"x","kind":"other","last4":"1","expiry":"1","needs_cvc":false}]}}"""))
        assertNull("a payment without its amount is not drawn", Sheet.parse("""{"state":"pay","payment":{"id":"x"}}"""))
        assertNull(Sheet.parse("<html>Not Found</html>"))
        assertNull(Sheet.parse(""))
    }

    @Test fun theLocalServersAnswersAreRead() {
        for (language in listOf("en", "fr", "ar")) {
            val sheet = Sheet.parse(fixture("pay-$language.json"))!!
            assertEquals(Sheet.State.Pay, sheet.state)
            assertEquals(language, sheet.language)
            assertEquals(language == "ar", sheet.rtl)
            assertTrue(sheet.sandbox)
            assertEquals("custom", sheet.pay?.mode)
            assertEquals(6, sheet.pay?.otpLength)
            assertTrue(sheet.pay!!.cards.isEmpty())
            assertNull(sheet.pay?.save)
            assertFalse(sheet.pay!!.receiptEmail)
            assertEquals(570.0, sheet.pay!!.sessionSeconds, 0.0)
            assertEquals(2, sheet.payment?.details?.size)
            val words = Words(sheet.text)
            for (key in listOf("pay.card_number", "pay.expiry", "pay.cvc", "pay.name", "pay.cta", "pay.encrypted", "otp.title", "otp.cta", "otp.cancel",
                "test.mark", "js.sdk_done", "sheet.view_receipt", "result.email_send", "js.connection", "js.code.session_over")) {
                assertTrue("$language: $key", words[key].isNotEmpty())
            }
        }
        assertEquals("Payer par carte", Words(Sheet.parse(fixture("pay-fr.json"))!!.text)["pay.title"])
        val missing = Sheet.parse(fixture("not-found.json"))!!
        assertEquals(Sheet.State.NotFound, missing.state)
        assertNotNull(missing.title)
        assertNull(missing.payment)
    }
}

class CookieJarTest {
    private var now = 1_000_000L
    private val jar = CookieJar { now }
    private val page = URI("http://10.0.2.2:8585/pay/abc/process")

    @Test fun cookiesAreKeptForTheirHostAndPath() {
        jar.receive(URI("http://10.0.2.2:8585/pay/abc/captcha/redeem"), listOf("rais_cap=h1; HttpOnly; Max-Age=1800; Path=/pay/abc; SameSite=lax"))
        jar.receive(URI("http://10.0.2.2:8585/pay/abc/sheet"), listOf("rais_payer=p; Path=/"))
        assertEquals("rais_cap=h1; rais_payer=p", jar.header(page))
        assertEquals("rais_payer=p", jar.header(URI("http://10.0.2.2:8585/pay/abcd/process")))
        assertNull("another site never receives them", jar.header(URI("http://10.0.2.3:8585/pay/abc/process")))
    }

    @Test fun aCookieIsReplacedRemovedOrExpires() {
        jar.receive(page, listOf("a=1; Path=/pay/abc; Max-Age=10"))
        jar.receive(page, listOf("a=2; Path=/pay/abc; Max-Age=10"))
        assertEquals("a=2", jar.header(page))
        now += 11_000
        assertNull("it expired", jar.header(page))
        jar.receive(page, listOf("b=1; Path=/"))
        jar.receive(page, listOf("b=; Path=/; Max-Age=0"))
        assertNull("the server removed it", jar.header(page))
    }

    @Test fun theDefaultPathIsTheRequestsDirectory() {
        jar.receive(URI("http://localhost:8585/pay/abc/sheet"), listOf("s=1"))
        assertEquals("s=1", jar.header(URI("http://localhost:8585/pay/abc/otp")))
        assertNull(jar.header(URI("http://localhost:8585/lang/fr")))
    }

    @Test fun secureCookiesTravelOverHttpsOnlyAndDomainsStayWithinTheSite() {
        val https = URI("https://pay.rais.app/pay/abc/process")
        jar.receive(https, listOf("s=1; Secure; Path=/", "d=1; Domain=rais.app; Path=/", "evil=1; Domain=example.com; Path=/"))
        assertEquals("s=1; d=1", jar.header(https))
        assertEquals("d=1", jar.header(URI("http://pay.rais.app/pay/abc/process")))
        assertEquals("d=1", jar.header(URI("https://rais.app/x")))
        assertNull(jar.header(URI("https://example.com/")))
    }

    @Test fun aLanguageIsSetLikeTheSiteSetsIt() {
        jar.set("LOCALHOST", "rais-locale", "ar")
        assertEquals("rais-locale=ar", jar.header(URI("http://localhost:8585/pay/abc/sheet")))
        assertEquals(1, jar.all().size)
    }

    @Test fun pathMatching() {
        assertTrue(CookieJar.pathMatches("/pay/abc", "/pay/abc"))
        assertTrue(CookieJar.pathMatches("/pay/abc/otp", "/pay/abc"))
        assertTrue(CookieJar.pathMatches("/pay/abc/otp", "/"))
        assertFalse(CookieJar.pathMatches("/pay/abcd", "/pay/abc"))
        assertFalse(CookieJar.pathMatches("/", "/pay"))
    }
}
