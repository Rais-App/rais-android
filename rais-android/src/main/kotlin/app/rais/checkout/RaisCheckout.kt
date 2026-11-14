package app.rais.checkout

import android.content.Context
import android.content.Intent
import android.graphics.Typeface
import androidx.activity.result.ActivityResultCaller
import androidx.activity.result.ActivityResultLauncher
import androidx.annotation.ColorInt
import androidx.annotation.DrawableRes
import app.rais.checkout.internal.CheckoutActivity
import app.rais.checkout.internal.CheckoutContract
import app.rais.checkout.internal.CheckoutUrl
import java.io.Serializable

/**
 * The Raïs payment page, inside your app.
 *
 * Your **server** creates the payment (`POST /payments` with your secret key) and returns its `checkout_url` to the app. The app never
 * holds a Raïs key. Create the checkout **before** your activity or fragment is started (in `onCreate`, or as a property), exactly as
 * you would register for an activity result, then call [present] when the payer taps "Pay":
 *
 * ```kotlin
 * class CartActivity : ComponentActivity() {
 *     private val checkout = RaisCheckout(this) { result ->
 *         when (result) {
 *             is RaisCheckout.Result.Completed -> // paid: now ask YOUR server for the order's real status
 *             RaisCheckout.Result.Cancelled -> // the payer left
 *             is RaisCheckout.Result.Failed -> // declined, expired, or the page could not load
 *         }
 *     }
 *
 *     fun onPayTapped(checkoutUrl: String) = checkout.present(checkoutUrl)
 * }
 * ```
 */
public class RaisCheckout(caller: ActivityResultCaller, private val callback: ResultCallback) {

    /** Receives how the checkout ended, once, on the main thread, after its screen has closed. */
    public fun interface ResultCallback {
        public fun onResult(result: Result)
    }

    private val launcher: ActivityResultLauncher<CheckoutContract.Args> =
        caller.registerForActivityResult(CheckoutContract) { callback.onResult(it) }

    /**
     * Shows the checkout.
     *
     * @param checkoutUrl the `checkout_url` of a payment your server created.
     * @param configuration appearance and behavior. The default is complete.
     */
    public fun present(checkoutUrl: String, configuration: Configuration = Configuration()) {
        if (!CheckoutUrl.isTrusted(checkoutUrl, configuration.additionalTrustedHosts)) {
            callback.onResult(Result.Failed(Failure(Failure.Code.InvalidUrl, "This is not a valid Raïs checkout address.", null)))
            return
        }
        launcher.launch(CheckoutContract.Args(checkoutUrl, configuration))
    }

    /**
     * Closes the checkout from code, as if the payer had pressed close: the callback receives the outcome so far (a payment the payer saw
     * succeed is still [Result.Completed]). Does nothing when no checkout is on screen.
     */
    public fun dismiss() {
        CheckoutActivity.dismissCurrent()
    }

    /** How the checkout looks and behaves. Every property has a default: `Configuration()` is a complete configuration. */
    public data class Configuration(
        /** Colors, corner radius, font and light/dark mode of the checkout page. */
        val appearance: Appearance = Appearance(),
        /** The language of the page. [Language.Automatic] lets Raïs choose from the payment and the device; the payer can still switch. */
        val language: Language = Language.Automatic,
        /**
         * Shows the SDK's close button over the top corner of the page. Leave it on unless you provide your own way out: a payer must
         * always be able to leave the checkout (the system back button always works).
         */
        val showsCloseButton: Boolean = true,
        /**
         * The URL scheme of your app (for example `"myapp"`), when the payment's `success_url` / `cancel_url` are deep links into your app.
         * The SDK then finishes with the result carried by that link instead of navigating to it. Optional: without it the result
         * still arrives from the page itself.
         */
        val returnUrlScheme: String? = null,
        /**
         * Hosts, besides `rais.app` and its subdomains, that may be loaded as the checkout (a staging environment, for example).
         * Loopback addresses (`localhost`, `127.0.0.1`, and `10.0.2.2` for the Android emulator) are always allowed for local testing.
         */
        val additionalTrustedHosts: List<String> = emptyList(),
        /**
         * How the checkout is drawn: by the SDK's own native controls ([Style.Native], the default), or as the hosted web page in a WebView
         * ([Style.Web]). The native screen falls back to the web page by itself for a payment it cannot draw (a card typed on the bank's
         * own page, a server without a native checkout).
         */
        val style: Style = Style.Native,
        /**
         * A drawable of your app shown above the name of who is paid, instead of the logo that merchant set on its Raïs account. Optional:
         * without it the checkout shows the account's own logo, or the first letter of its name when it has none. Square pictures look best.
         */
        @DrawableRes val merchantLogo: Int? = null,
        /**
         * The address of a picture shown above the name of who is paid, instead of the logo that merchant set on its Raïs account: for an
         * app that has the picture only as a URL (React Native, Flutter). `https` only; any other address is ignored. Loaded by the SDK
         * without cookies, as the merchant's own logo is; when it cannot be loaded the account's logo (or initial) shows. [merchantLogo]
         * wins when both are given.
         */
        val merchantLogoUrl: String? = null,
    ) : Serializable

    /** How the checkout is drawn. */
    public enum class Style {
        /**
         * Native controls: the payment, the card fields, the bank's code and the result are drawn by the SDK, and there is no robot check
         * to tick. A payment whose card must be typed on the bank's own page is shown as that page, in the same screen.
         */
        Native,

        /** The hosted checkout page in a WebView, as in version 1.0. */
        Web,
    }

    /**
     * Your app's own typeface for the native checkout: return the [Typeface] for a weight (100 to 900: 400 regular, 500 medium, 600
     * semibold, 700 bold), or `null` to keep [Appearance.fontFamily] for that weight. Sizes stay the SDK's and follow the device's font
     * size. Called on the main thread.
     */
    public fun interface FontProvider {
        public fun typeface(weight: Int): Typeface?
    }

    /** The language of the checkout page. */
    public enum class Language(internal val code: String?) {
        Automatic(null),
        English("en"),
        French("fr"),
        Arabic("ar"),
    }

    /**
     * The look of the checkout page, in the spirit of Stripe's `PaymentSheet.Appearance`.
     *
     * Everything is optional. A property you leave `null` keeps Raïs' own value, which already has a light and a dark palette, so you
     * can change just the accent color, or everything.
     *
     * ```kotlin
     * val appearance = RaisCheckout.Appearance(
     *     colorsLight = RaisCheckout.Colors(primary = 0xFF5B5BD6.toInt()),
     *     colorsDark = RaisCheckout.Colors(primary = 0xFF8B8BFF.toInt()),
     *     cornerRadiusDp = 12f,
     * )
     * ```
     */
    public data class Appearance(
        /** Which palette the page uses. */
        val colorScheme: ColorScheme = ColorScheme.Automatic,
        /** The colors in the light palette. */
        val colorsLight: Colors = Colors(),
        /** The colors in the dark palette. */
        val colorsDark: Colors = Colors(),
        /** The corner radius of buttons and input fields, in dp (0 to 24). Cards and dialogs follow it proportionally. */
        val cornerRadiusDp: Float? = null,
        val fontFamily: FontFamily = FontFamily.Default,
        /**
         * The app's own typeface for the native checkout (see [FontProvider]); `null` keeps [fontFamily]. It is handed to the checkout's
         * screen in memory: if Android recreates that screen after killing the app's process, the checkout uses [fontFamily] again.
         */
        @Transient val fontProvider: FontProvider? = null,
    ) : Serializable

    /** Which palette the page uses. */
    public enum class ColorScheme {
        /** Follows the device, live (the default). */
        Automatic,
        Light,
        Dark,
    }

    /**
     * The typeface of the checkout. Only fonts that exist on every device can be named here (the web page cannot see your app's font
     * files); for your own typeface in the native checkout, give [Appearance.fontProvider].
     */
    public enum class FontFamily(internal val key: String) {
        /** Raïs' own typeface on the web page (Inter, and 29LT Bukra for Arabic); the device's sans-serif in the native checkout. */
        Default("default"),

        /** The device's system font. */
        System("system"),
        Serif("serif"),
        Monospace("monospace"),
    }

    /** The colors of one palette, as `Color` ints (`0xAARRGGBB`). `null` keeps Raïs' value. */
    public data class Colors(
        /** The pay button and the accents of the page. */
        @ColorInt val primary: Int? = null,
        /** The text and icons on top of [primary]. When `null`, black or white is chosen for the best contrast. */
        @ColorInt val onPrimary: Int? = null,
        /** The page background. */
        @ColorInt val background: Int? = null,
        /** Cards and panels on top of the background. */
        @ColorInt val surface: Int? = null,
        /** The main text. */
        @ColorInt val text: Int? = null,
        /** Labels, hints and secondary text. */
        @ColorInt val textSecondary: Int? = null,
        /** The border of the input fields. */
        @ColorInt val border: Int? = null,
        /** Errors and the failure state. */
        @ColorInt val danger: Int? = null,
        /** The success state. */
        @ColorInt val success: Int? = null,
    ) : Serializable

    /** How the checkout ended. */
    public sealed class Result : Serializable {
        /**
         * The payer paid. This is what the page showed, not proof: **confirm the payment on your server** (signed webhook, or
         * `GET /payments/{id}`) before you ship anything.
         */
        public data class Completed(val payment: Payment) : Result()

        /** The payer left without paying, or chose to go back. */
        public data object Cancelled : Result() {
            private fun readResolve(): Any = Cancelled
        }

        /** The checkout could not be shown, or the payment was declined or expired. */
        public data class Failed(val failure: Failure) : Result()
    }

    /** The payment the payer paid. */
    public data class Payment(
        /** The Raïs payment id (the `id` of the payment your server created). */
        val id: String,
        /** The `reference` you gave the payment, when you gave one. */
        val reference: String?,
        /** The Raïs order number, as printed on the receipt. */
        val orderNumber: String?,
    ) : Serializable

    /** Why the checkout did not complete. */
    public data class Failure(val code: Code, val message: String, val paymentId: String?) : Serializable {
        public enum class Code {
            /** The URL is not a Raïs checkout address (not https, or not a trusted host). */
            InvalidUrl,

            /** The page could not be loaded (no network, server unreachable, bad certificate). */
            LoadFailed,

            /** The bank declined the payment, or it ended in an error. */
            Declined,

            /** The payment expired before it was paid. */
            Expired,

            /** This device's WebView is too old to show the checkout safely: update "Android System WebView". */
            UnsupportedWebView,
        }
    }

    public companion object {
        /** The version of this SDK. */
        public const val VERSION: String = BuildConfig.RAIS_SDK_VERSION

        /**
         * For framework integrations (React Native, Flutter, Capacitor…) that start activities themselves: the intent that shows the
         * checkout. Start it for a result and give that result to [parseResult]. An app uses [RaisCheckout] instead.
         */
        public fun createIntent(context: Context, checkoutUrl: String, configuration: Configuration = Configuration()): Intent =
            CheckoutContract.createIntent(context, CheckoutContract.Args(checkoutUrl, configuration))

        /** The [Result] carried by what the checkout's activity returned (see [createIntent]). */
        public fun parseResult(resultCode: Int, data: Intent?): Result = CheckoutContract.parseResult(resultCode, data)

        /** Whether [checkoutUrl] can be shown as a checkout with [configuration] (https on `rais.app`, a trusted host, or loopback). */
        public fun isValid(checkoutUrl: String, configuration: Configuration = Configuration()): Boolean =
            CheckoutUrl.isTrusted(checkoutUrl, configuration.additionalTrustedHosts)
    }
}
