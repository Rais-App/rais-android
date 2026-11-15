# Raïs for Android

The Raïs checkout, inside your Android app, drawn with native views: the payment and its details, the card fields (CIB and Edahabia), saved cards and the payer's Raïs balance, the bank's confirmation code, and the result with its receipt. No web page, no robot check to tick. Light and dark mode, English, French and Arabic (right to left), an appearance you can customize, and a typed result for your app. The same screen as the [iOS SDK](https://rais.app/sdk/ios).

- Android 7.0+ (API 24), Kotlin or Java, Views or Jetpack Compose
- Depends only on AndroidX (`activity`, `core`, `webkit`) and the coroutines `activity` already brings; no Raïs key in your app
- Documentation: <https://rais.app/sdk/android> · API reference: <https://rais.app/docs/api> · Changelog: [CHANGELOG.md](CHANGELOG.md)

## How it works

```
your app ──(1) "pay for order 1042"──▶ your server ──(2) POST /payments (secret key)──▶ Raïs
your app ◀─(3) checkout_url ───────── your server
your app ──(4) checkout.present(checkoutUrl)──▶ the payer pays in the checkout screen
your app ◀─(5) Completed / Cancelled / Failed
your app ──(6) "what is the status of order 1042?"──▶ your server (webhook / GET /payments/{id})
```

Your **server** creates the payment and owns the amount and the secret key. The app only receives the `checkout_url`. The result in step 5 is what the payer saw, not proof of payment: ship the order on step 6.

## Install

```kotlin
// build.gradle.kts (app)
dependencies {
    implementation("app.rais:rais-android:1.0.0")
}
```

The SDK adds to your manifest by itself: the `INTERNET` permission, the checkout's activity (not exported), a small exported activity that receives the answer of "Pay with Raïs" when the payer signs in on the website (see [Paying with Raïs](#paying-with-raïs); it does nothing unless a sign-in of your running app is waiting for it), and a file provider of its own (to share the PDF receipt). There is no `<queries>` entry and nothing for you to configure.

## Show the checkout

Create `RaisCheckout` **before** your activity or fragment is started (in `onCreate`, or as a property), exactly as you register for any activity result, then call `present` when the payer taps Pay:

```kotlin
class CartActivity : ComponentActivity() {
    private val checkout = RaisCheckout(this) { result ->
        when (result) {
            is RaisCheckout.Result.Completed -> askMyServerAbout(result.payment.id)   // paid: payment.reference, payment.orderNumber
            RaisCheckout.Result.Cancelled -> Unit                                      // the payer closed the checkout
            is RaisCheckout.Result.Failed -> show(result.failure.message)              // code: InvalidUrl, LoadFailed, Declined, Expired, UnsupportedWebView
        }
    }

    private fun onPayTapped(checkoutUrl: String) = checkout.present(checkoutUrl)   // the checkout_url from your server
}
```

In a `Fragment`: `private val checkout = RaisCheckout(this) { … }`, the same. `checkout.dismiss()` closes the checkout from code, as if the payer had pressed close.

| Result | When | What to do |
|---|---|---|
| `Completed(payment)` | the payer paid and saw the receipt. `payment.id` is the payment's id, `payment.reference` is the `reference` you gave it (`null` when you gave none), `payment.orderNumber` is on the receipt | ask **your server** for the order's real status |
| `Cancelled` | the payer closed the checkout (or went back to your app) without paying | nothing was charged; offer to try again |
| `Failed` with `Declined` | the payer saw the bank's refusal (or an error) and left; `failure.paymentId` names the payment | show `failure.message`; your server knows the final state |
| `Failed` with `Expired` | the payment expired before it was paid | create a new payment |
| `Failed` with `LoadFailed` | the checkout could not be loaded and the payer closed the screen | check the connection; offer to try again |
| `Failed` with `InvalidUrl` | `checkoutUrl` is not an `https` address on `rais.app` (or a trusted host) | check the `checkout_url` your server sent |
| `Failed` with `UnsupportedWebView` | the checkout had to be shown as a web page (`Style.Web`, or a payment the native screen hands over) and this device's WebView is older than version 86 | ask the user to update "Android System WebView" |

### Jetpack Compose

Android only lets an activity register for a result before it is started, so create `RaisCheckout` as a property of the activity (not inside a composable, which may first run later, for example on a navigation destination) and hand `present` down:

```kotlin
class CartActivity : ComponentActivity() {
    private val checkout = RaisCheckout(this) { result -> /* … */ }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent { CartScreen(onPay = { checkoutUrl -> checkout.present(checkoutUrl) }) }
    }
}

@Composable
fun CartScreen(onPay: (String) -> Unit) {
    Button(onClick = { onPay(checkoutUrlFromYourServer) }) { Text("Pay") }
}
```

## Customize it

Everything is optional. What you leave `null` keeps Raïs' value, which already has a light and a dark palette.

```kotlin
val configuration = RaisCheckout.Configuration(
    appearance = RaisCheckout.Appearance(
        colorScheme = RaisCheckout.ColorScheme.Automatic,                       // Light, Dark, or follow the device (default)
        colorsLight = RaisCheckout.Colors(primary = 0xFF5B5BD6.toInt()),        // the pay button and the accents
        colorsDark = RaisCheckout.Colors(primary = 0xFF8B8BFF.toInt()),
        cornerRadiusDp = 12f,                                                   // buttons and fields (0–24)
        fontFamily = RaisCheckout.FontFamily.System,                            // Default, System, Serif, Monospace
        fontProvider = { weight -> ResourcesCompat.getFont(context, if (weight >= 600) R.font.brand_bold else R.font.brand) },
    ),
    language = RaisCheckout.Language.French,                                    // Automatic (default), English, French, Arabic
    merchantLogo = R.drawable.store_logo,                                       // instead of the logo of the merchant's Raïs account
    // or: merchantLogoUrl = "https://cdn.example.com/store.png",               // https only (React Native, Flutter)
)
checkout.present(checkoutUrl, configuration)
```

| `Colors` | What it colors |
|---|---|
| `primary` | the pay button, the accents, the switch and the focus ring of the fields |
| `onPrimary` | text on `primary` (when `null`, black or white for the best contrast) |
| `background`, `surface` | the page, and cards and fields on it |
| `text`, `textSecondary` | main text, and labels and hints |
| `border` | the border of the fields |
| `danger`, `success` | errors, and the success state |

Colors are `Color` ints (`0xAARRGGBB`). Give `colorsLight` and `colorsDark` separately; the SDK picks the palette on screen and repaints the open checkout when the device switches between light and dark.

`fontProvider` gives the native checkout your app's own typeface: return a `Typeface` for a weight (400 regular, 500 medium, 600 semibold, 700 bold), or `null` to keep `fontFamily`. Sizes stay the SDK's and follow the device's font size (up to 1.5×). The provider is code, so it is handed to the checkout's screen in memory: if Android recreates that screen after killing your process, `fontFamily` is used.

| `Configuration` | Default | |
|---|---|---|
| `appearance` | untouched | colors, `cornerRadiusDp`, `fontFamily`, `fontProvider`, `colorScheme` |
| `style` | `Native` | `Web` shows the hosted checkout page in a WebView instead |
| `language` | `Automatic` | from the payment and the device |
| `merchantLogo` | `null` | a drawable shown instead of the merchant's Raïs logo (square looks best) |
| `merchantLogoUrl` | `null` | the same as an address, for apps that only have a URL: `https` only (anything else is ignored); `merchantLogo` wins when both are given, and the account's logo shows when it cannot be loaded |
| `showsCloseButton` | `true` | the system back button always lets the payer leave; keep it unless you draw your own |
| `returnUrlScheme` | `null` | your app's URL scheme, when the payment's `success_url` / `cancel_url` are deep links (web checkout) |
| `additionalTrustedHosts` | empty | extra hosts allowed as a checkout (staging, or your computer on the local network); `rais.app` and loopback are always allowed |

## What the payer sees

- **The payment**: the store (its logo, filling its tile), the amount, the description, and "View details" for the order's lines, the reference and the validity. A test payment carries a "Test mode" badge.
- **The ways to pay**: a card typed now (number, expiry, CVC, name: formatted as typed, the network's mark following the number, checked before anything is sent), the cards a store keeps for its customer, and, for a payer the checkout has identified, their Raïs balance and the cards of their Raïs account. The optional receipt address and "save this card" are there when the payment calls for them.
- **Pay with Raïs**, first on the screen when the store can be paid from a Raïs wallet (see below), then the other ways to pay. Choosing **Use another card** folds the saved ways to pay (balance, saved cards) away so the whole card form fits on screen; that row then reads **Use a saved card** and brings them back, and picking one of them hides the card form again.
- **The bank's code** (3-D Secure), in six boxes, with the keyboard's own code suggestion and paste.
- **The result**: paid, refused (with the bank's reason), expired, cancelled, or still being confirmed with the bank (the screen keeps asking). A paid payment offers its PDF receipt (viewed in the checkout, shared or printed from there) and the receipt by e-mail.

The words, the amounts and every rule come from Raïs' server (`GET /pay/{id}/sheet`), in the payer's language, so the screen always says what the hosted page says. Arabic reads right to left even if your app does not declare `android:supportsRtl` (the checkout's own views are laid out right to left; your screens are untouched). A payment whose card must be typed on the bank's own page (`mode: "raw"`) is shown as that page, in the same screen, and so is any payment on a server that has no native checkout: the SDK falls back by itself, keeping the payer's session.

### Paying with Raïs

When the store can be paid from a Raïs wallet, the screen leads with a **Pay with Raïs** button, above the card form. There is nothing to configure and nothing to add to your manifest:

- If the payer has the **Raïs app** (`app.rais.wallet`), it opens on this payment (`rais://pay/<payment id>`, an explicit intent for that package: no `<queries>` entry, no package lookup). They confirm with their fingerprint or face, from their balance or a card of their Raïs account, and the app offers **Back to <your app>**: it opens the payment's `success_url`. The checkout screen reads the payment again as soon as it is in front of the payer, so it shows "Payment Successful" by itself. Give the payment a `success_url` that opens your app (a URL scheme or an App Link you handle) to get that button.
- If they **do not have the app**, the same button signs them in with Raïs on the Raïs website, in a Custom Tab (it shares the browser's sign-ins), and the payer then pays from their balance or a saved card in this very screen. The website hands the answer back to the SDK on the scheme `raiscb-androidsdk` (declared by the SDK's own manifest entry; the app never sees a password). If two of your apps carry the SDK on the same device, give one of them its own scheme by overriding the string `rais_callback_scheme` in its resources (`raiscb-` followed by 8 to 40 lower-case letters or digits, the only shape the checkout accepts). Closing the tab without signing in simply returns to the screen.
- A payment can be paid from a Raïs wallet only when it is a live payment of an ordinary store (a test payment never offers it), and only while the wallet payments switch is on.

Your server must still confirm a payment with the API or the signed webhook; the screen, the result and the redirects are for the payer.

## Security

- The checkout address must be `https://…rais.app` (or a host you list in `additionalTrustedHosts`). Plain `http` is accepted for loopback (`localhost`, `127.0.0.1`, and `10.0.2.2`, the emulator's name for your computer) and, when you list it, for a private-network address (10.x, 172.16–31.x, 192.168.x, 100.64–127.x, `*.local`, `*.ts.net`), so you can test against a development server; your debug build must allow cleartext traffic to it. Public hosts always need https.
- **Cleartext for a development host is your app's decision, not the SDK's.** Android blocks plain `http` for every app from API 28 unless the app allows it, and the SDK does not change that for your app. For a debug build, allow it for the one host only (`res/xml/network_security_config.xml` in `src/debug`, named by `android:networkSecurityConfig` on `<application>` in the debug manifest), and list the same host in `additionalTrustedHosts`:

  ```xml
  <network-security-config>
      <domain-config cleartextTrafficPermitted="true">
          <domain includeSubdomains="false">192.168.1.20</domain>   <!-- your computer, or macbook.local, or a *.ts.net name -->
      </domain-config>
  </network-security-config>
  ```

  (`android:usesCleartextTraffic="true"` also works for a throwaway build, but allows every host.) Without it the checkout shows "Can't load the payment page" for that host. Never ship it in a release build.
- Card details go from the fields straight to Raïs over TLS, in the one request that pays. They are never written to disk, never logged, and never handed to your app: the result carries the payment's id, reference and order number only.
- The native checkout keeps its cookies (who is paying, the bank's step in progress) in memory, for that checkout only: not in your app's other connections and not in the WebView's store. When it hands a payment over to the web checkout, that payment's cookies go with it, and the web checkout clears them when it closes.
- The server's robot check still applies: the SDK solves its proof of work in the background, so a payer has nothing to tick and a script gains nothing by pretending to be the SDK.
- Links (the terms, a top-up) open outside the checkout, so the payer can always come back. In the web checkout the page can talk to the app only from the checkout's own origin (`WebViewCompat.addWebMessageListener` with an origin rule), TLS errors are never bypassed, and the system's algorithmic dark mode is switched off for the page.
- If you want the payment to run outside your app's process entirely, use Custom Tabs instead (see the [mobile guide](https://rais.app/docs/mobile)).

## The keyboard

The checkout is edge to edge and owns the keyboard's insets. The page scrolls under everything; the pay button sits in a floating pane that rides on the keyboard (following its animation), and the page's bottom room follows the pane with 16 dp to spare. The field being typed in, with what is said under it, is brought fully into view above the pane when it takes the focus and again once the keyboard has settled: the last field on the page, the six boxes of the bank's code and the receipt e-mail form (field, note and Send button) included. A tap on a button, a row or a switch while the keyboard is open fires on the **first** tap: the screen puts the keyboard away only for a finished tap on empty space (a click), never when a finger goes down, so the layout never moves under a tap. This works the same on every Android version, including apps that target Android 15 (SDK 35), where `adjustResize` alone no longer resizes edge-to-edge windows. In the web checkout the WebView is placed above the keyboard the same way, so the page never jumps.

## For framework integrations

`RaisCheckout.createIntent(context, checkoutUrl, configuration)` and `RaisCheckout.parseResult(resultCode, data)` let a framework that starts activities itself (React Native, Flutter, Capacitor) show the checkout, with `startActivityForResult`; the configuration's `fontProvider` reaches the screen this way too. A bridge passes the store's picture as `merchantLogoUrl`. Apps use `RaisCheckout`.

## Try it

The `example` module has every option on one screen. For automated runs it also reads intent extras (the iOS Example's `-rais-…` launch arguments): `url`, `scheme` (light|dark|automatic), `lang` (en|fr|ar), `style` (native|web), `font`, `primary`, `radius`, `hosts` (extra trusted hosts, comma separated; `10.0.2.2` is always listed), `logo` (`merchantLogoUrl`), `present` (present at launch) and `autoclose` (seconds, then `dismiss()`).

```sh
./gradlew :example:installDebug
adb shell am start -n app.rais.example/.MainActivity --es url http://10.0.2.2:8585/pay/<id> --es lang ar --es scheme dark --ez present true
```

The unit tests (sheet parsing against real answers of the server, card rules, the proof of work, the cookie jar, the address rules) run on the JVM:

```sh
./gradlew :rais-android:testDebugUnitTest
```

## License

MIT. See [LICENSE](LICENSE).
