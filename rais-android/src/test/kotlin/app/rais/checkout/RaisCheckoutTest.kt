package app.rais.checkout

import app.rais.checkout.internal.CheckoutUrl
import app.rais.checkout.internal.PageMessage
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class CheckoutUrlTest {
    private fun trusted(url: String, extra: List<String> = emptyList()) = CheckoutUrl.isTrusted(url, extra)

    @Test fun raisHostsAreTrustedOverHttps() {
        assertTrue(trusted("https://pay.rais.app/pay/95855e6f-fcbd-44cb-ac78-5e2c36cf8b0f"))
        assertTrue(trusted("https://rais.app/x"))
        assertTrue(trusted("https://sandbox.pay.rais.app/x"))
    }

    @Test fun plainHttpAndLookalikesAreRefused() {
        assertFalse(trusted("http://pay.rais.app/pay/x"))
        assertFalse(trusted("https://rais.app.evil.example/pay/x"))
        assertFalse(trusted("https://evilrais.app/pay/x"))
        assertFalse(trusted("https://example.com/pay/x"))
        assertFalse(trusted("myapp://pay/x"))
        assertFalse(trusted("not a url"))
        assertFalse(CheckoutUrl.isTrusted(null, emptyList()))
    }

    @Test fun loopbackAndTheEmulatorHostAreAllowedForLocalTesting() {
        assertTrue(trusted("http://localhost:8585/pay/x"))
        assertTrue(trusted("http://127.0.0.1:8585/pay/x"))
        assertTrue(trusted("http://10.0.2.2:8585/pay/x"))
    }

    @Test fun aLocalNetworkServerIsTrustedOverHttpOnlyWhenListed() {
        assertFalse(trusted("http://192.168.1.20:8585/pay/x"))
        assertTrue(trusted("http://192.168.1.20:8585/pay/x", listOf("192.168.1.20")))
        assertTrue(trusted("http://10.1.2.3:8585/pay/x", listOf("10.1.2.3")))
        assertTrue(trusted("http://172.20.0.5:8585/pay/x", listOf("172.20.0.5")))
        assertFalse(trusted("http://172.32.0.5:8585/pay/x", listOf("172.32.0.5")))
        assertTrue(trusted("http://100.101.102.103:8585/pay/x", listOf("100.101.102.103")))
        assertFalse(trusted("http://100.128.0.1:8585/pay/x", listOf("100.128.0.1")))
        assertTrue(trusted("http://macbook.local:8585/pay/x", listOf("macbook.local")))
        assertTrue(trusted("http://jack.tail1234.ts.net:8585/pay/x", listOf("jack.tail1234.ts.net")))
        assertTrue(trusted("http://JACK.tail1234.ts.net:8585/pay/x", listOf("jack.TAIL1234.ts.net")))
        assertFalse(trusted("http://jack.tail1234.ts.net:8585/pay/x"))
        assertFalse(trusted("http://example.com/pay/x", listOf("example.com")))
        assertFalse(trusted("http://8.8.8.8/pay/x", listOf("8.8.8.8")))
        assertFalse(trusted("http://192.168.1.20.evil.example/pay/x", listOf("192.168.1.20")))
        assertFalse(trusted("http://192.168.1.300/pay/x", listOf("192.168.1.300")))
        assertTrue("a listed private host over https too", trusted("https://192.168.1.20:8443/pay/x", listOf("192.168.1.20")))
    }

    @Test fun anAppsLogoIsLoadedOnlyFromHttps() {
        assertEquals("https://cdn.example.test/logo.png", CheckoutUrl.httpsImage("https://cdn.example.test/logo.png"))
        assertEquals("https://cdn.example.test/logo.png", CheckoutUrl.httpsImage(" https://cdn.example.test/logo.png "))
        assertEquals("HTTPS://cdn.example.test/l.png", CheckoutUrl.httpsImage("HTTPS://cdn.example.test/l.png"))
        for (other in listOf("http://cdn.example.test/logo.png", "http://10.0.2.2:8585/logo.png", "file:///sdcard/logo.png",
            "content://media/1", "data:image/png;base64,AAAA", "javascript:alert(1)", "https:///logo.png", "logo.png", "", null)) {
            assertNull(other, CheckoutUrl.httpsImage(other))
        }
    }

    @Test fun privateNetworks() {
        for (host in listOf("10.0.2.2", "192.168.0.1", "172.16.0.1", "172.31.255.255", "100.64.0.1", "box.local", "a.b.ts.net")) {
            assertTrue(host, CheckoutUrl.isPrivateNetwork(host))
        }
        for (host in listOf("8.8.8.8", "172.15.0.1", "100.63.0.1", "1.2.3", "1.2.3.4.5", "ts.net.example", "-1.0.0.0", "pay.rais.app")) {
            assertFalse(host, CheckoutUrl.isPrivateNetwork(host))
        }
    }

    @Test fun additionalHostsAreExactMatches() {
        assertTrue(trusted("https://pay.staging.example/p", listOf("pay.staging.example")))
        assertFalse(trusted("https://other.staging.example/p", listOf("pay.staging.example")))
    }

    @Test fun anOriginIsSchemeHostAndNonDefaultPort() {
        assertEquals("https://pay.rais.app", CheckoutUrl.origin("https://pay.rais.app:443/pay/a"))
        assertEquals("http://localhost:8585", CheckoutUrl.origin("http://localhost:8585/pay/a?x=1"))
        assertNull(CheckoutUrl.origin("myapp://pay"))
        assertTrue(CheckoutUrl.isSameOrigin("https://pay.rais.app/payment/confirm?x=1", "https://pay.rais.app/pay/a"))
        assertFalse(CheckoutUrl.isSameOrigin("https://rais.app/terms", "https://pay.rais.app/pay/a"))
        assertFalse(CheckoutUrl.isSameOrigin(null, "https://pay.rais.app/pay/a"))
    }

    @Test fun aLanguageGoesThroughTheSitesLanguageSwitch() {
        val checkout = "https://pay.rais.app/pay/95855e6f-fcbd-44cb-ac78-5e2c36cf8b0f"
        assertEquals(checkout, CheckoutUrl.entry(checkout, null))
        assertEquals(
            "https://pay.rais.app/lang/fr?next=%2Fpay%2F95855e6f%2Dfcbd%2D44cb%2Dac78%2D5e2c36cf8b0f",
            CheckoutUrl.entry(checkout, "fr"),
        )
        assertEquals(checkout, CheckoutUrl.entry(checkout, "xx"))
        assertEquals("http://localhost:8585/lang/ar?next=%2Fpay%2Fabc%3Fx%3D1%26y%3D2", CheckoutUrl.entry("http://localhost:8585/pay/abc?x=1&y=2", "ar"))
    }
}

class PageMessageTest {
    private val id = "95855E6F-FCBD-44CB-AC78-5E2C36CF8B0F"

    @Test fun aResultIsParsedAndTheIdIsLowercased() {
        val message = PageMessage.parse("""{"type":"result","status":"paid","paymentId":"$id","reference":"order-1","orderNumber":"RS1"}""")
        assertEquals(PageMessage.Result(PageMessage.Status.Paid, id.lowercase(), "order-1", "RS1"), message)
    }

    @Test fun malformedMessagesAreIgnored() {
        assertNull(PageMessage.parse("not json"))
        assertNull(PageMessage.parse(null))
        assertNull(PageMessage.parse("""{"type":"result","status":"paid","paymentId":"not-a-uuid"}"""))
        assertNull(PageMessage.parse("""{"type":"result","status":"teleported","paymentId":"$id"}"""))
        assertNull(PageMessage.parse("""{"type":"launch-missiles"}"""))
        assertNull(PageMessage.parse("""{"status":"paid"}"""))
    }

    @Test fun optionalFieldsAreBoundedAndNullsAreNulls() {
        val long = "x".repeat(500)
        assertEquals(
            PageMessage.Result(PageMessage.Status.Failed, id.lowercase(), null, null),
            PageMessage.parse("""{"type":"result","status":"failed","paymentId":"$id","reference":"$long","orderNumber":null}"""),
        )
    }

    @Test fun readyKnowsPayPagesFromResultPages() {
        assertEquals(PageMessage.Ready("pay", false), PageMessage.parse("""{"type":"ready","page":"pay"}"""))
        assertEquals(PageMessage.Ready("result", false), PageMessage.parse("""{"type":"ready"}"""))
        assertEquals(PageMessage.Ready("pay", true), PageMessage.parse("""{"type":"ready","page":"pay","dir":"rtl"}"""))
        assertEquals(PageMessage.Ready("result", false), PageMessage.parse("""{"type":"ready","dir":"sideways"}"""))
        assertEquals(PageMessage.Done, PageMessage.parse("""{"type":"done"}"""))
        assertEquals(PageMessage.Close, PageMessage.parse("""{"type":"close"}"""))
    }
}

class ConfigurationTest {
    @Test fun defaultsAreComplete() {
        val configuration = RaisCheckout.Configuration()
        assertTrue(configuration.showsCloseButton)
        assertEquals(RaisCheckout.Language.Automatic, configuration.language)
        assertEquals(RaisCheckout.ColorScheme.Automatic, configuration.appearance.colorScheme)
        assertNull(configuration.appearance.cornerRadiusDp)
        assertEquals("the native checkout is the default", RaisCheckout.Style.Native, configuration.style)
        assertNull(configuration.merchantLogo)
        assertNull(configuration.merchantLogoUrl)
        assertNull(configuration.appearance.fontProvider)
    }

    @Test fun theIntentOfAFrameworkBridgeHandsTheFontProviderOverToo() {
        val provider = RaisCheckout.FontProvider { null }
        val args = app.rais.checkout.internal.CheckoutContract.Args(
            "https://pay.rais.app/pay/95855e6f-fcbd-44cb-ac78-5e2c36cf8b0f",
            RaisCheckout.Configuration(appearance = RaisCheckout.Appearance(fontProvider = provider)),
        )
        app.rais.checkout.internal.CheckoutContract.createIntent(object : android.content.ContextWrapper(null) {}, args)
        assertTrue("createIntent (RaisCheckout.createIntent, present) leaves the provider for the screen", 
            app.rais.checkout.internal.CheckoutContract.Handoff.font(args.token) === provider)
        app.rais.checkout.internal.CheckoutContract.Handoff.release(args.token)
        assertNull(app.rais.checkout.internal.CheckoutContract.Handoff.font(args.token))
    }

    @Test fun theFontProviderIsNotPartOfTheSerializedConfiguration() {
        val configuration = RaisCheckout.Configuration(
            appearance = RaisCheckout.Appearance(fontProvider = { null }),
            style = RaisCheckout.Style.Web,
        )
        val bytes = java.io.ByteArrayOutputStream().also { java.io.ObjectOutputStream(it).use { out -> out.writeObject(configuration) } }.toByteArray()
        val back = java.io.ObjectInputStream(bytes.inputStream()).use { it.readObject() } as RaisCheckout.Configuration
        assertEquals(RaisCheckout.Style.Web, back.style)
        assertNull("code does not travel in an intent: the screen finds it by the checkout's token", back.appearance.fontProvider)
    }

    @Test fun languageCodes() {
        assertNull(RaisCheckout.Language.Automatic.code)
        assertEquals("ar", RaisCheckout.Language.Arabic.code)
    }
}
