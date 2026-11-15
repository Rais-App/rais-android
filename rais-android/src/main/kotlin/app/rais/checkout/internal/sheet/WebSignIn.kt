package app.rais.checkout.internal.sheet

import android.app.Activity
import android.content.ActivityNotFoundException
import android.content.Intent
import android.net.Uri
import android.os.Bundle
import app.rais.checkout.R
import app.rais.checkout.internal.CheckoutActivity
import java.net.URI
import java.net.URLDecoder

/**
 * "Sign in with Raïs" for a payer who does not have the Raïs app: the Raïs website's own sign-in (password, Google, whatever the payer uses
 * there), shown in a Custom Tab that shares the browser's sign-ins. The checkout hands the answer back on a scheme only this SDK declares
 * (`<scheme>://signed-in?s=<payer session>`, see `rais_callback_scheme`); nothing is typed into the app and the app never sees a password.
 * The same flow as rais-ios `WebSignIn`.
 *
 * Android has no sign-in session that reports a closed tab, so a payer who closes the tab without signing in is noticed when the checkout's
 * screen is in front again with the answer still missing ([activityPaused] / [activityResumed]).
 */
internal class WebSignIn(
    /** The scheme this sign-in listens on. */
    val scheme: String,
    private val clock: () -> Long = System::currentTimeMillis,
    private val onOutcome: (Outcome) -> Unit,
) {
    sealed class Outcome {
        /** The payer session of this payment, to keep as the page's own cookie. */
        class SignedIn(val session: String) : Outcome()
        object Cancelled : Outcome()
        object Failed : Outcome()
    }

    private val startedAt = clock()
    private var left = false
    private var done = false

    /** Whether this sign-in is still waiting for an answer. */
    val isPending: Boolean get() = !done

    /** The answer arrived on [url] (the address the browser handed to the app). Returns whether this sign-in took it. */
    fun handleReturn(url: String?): Boolean {
        if (done) return false
        // the visit to the website's sign-in lasts ten minutes at the server: an older answer is not one this sign-in asked for
        if (clock() - startedAt > LIFETIME_MS) {
            finish(Outcome.Cancelled)
            return true   // (it was this sign-in's: the checkout comes back to the front all the same)
        }
        val outcome = parse(url, scheme) ?: return false
        finish(outcome)
        return true
    }

    /** The checkout's screen went to the background (the tab opened over it). */
    fun activityPaused() {
        left = true
    }

    /** The checkout's screen is in front again. With no answer by now, the payer closed the tab. */
    fun activityResumed() {
        if (done || !left) return
        finish(Outcome.Cancelled)
    }

    fun cancel() {
        done = true
        if (current === this) current = null
    }

    fun failed() = finish(Outcome.Failed)

    private fun finish(outcome: Outcome) {
        if (done) return
        done = true
        if (current === this) current = null
        onOutcome(outcome)
    }

    /** Opens [url] in a Custom Tab (the browser's own page when none is installed). */
    fun start(activity: Activity, url: String) {
        current = this
        val intent = Intent(Intent.ACTION_VIEW, Uri.parse(url))
        // What androidx.browser's CustomTabsIntent adds, without the library: an (unconnected) session extra makes the browser show it as a tab
        intent.putExtras(Bundle().apply { putBinder(CUSTOM_TAB_SESSION, null) })
        try {
            activity.startActivity(intent)
        } catch (_: ActivityNotFoundException) {
            failed()
        } catch (_: SecurityException) {
            failed()
        }
    }

    companion object {
        private const val CUSTOM_TAB_SESSION = "android.support.customtabs.extra.SESSION"
        private const val LIFETIME_MS = 10 * 60 * 1000L

        /** The sign-in that waits for the browser's answer, if any: the one of this process's open checkout. */
        @Volatile
        internal var current: WebSignIn? = null

        /** What the checkout accepts as a callback scheme (app/views/payment.py `_NATIVE_SCHEME`): `raiscb-` and 8 to 40 lower-case letters or digits. */
        private val ACCEPTED = Regex("^raiscb-[a-z0-9]{8,40}$")

        /** The scheme this SDK declares in the manifest (an app may set its own through the string `rais_callback_scheme`). */
        fun callbackScheme(activity: Activity): String {
            val declared = activity.getString(R.string.rais_callback_scheme).lowercase()
            return if (isAccepted(declared)) declared else DEFAULT_SCHEME
        }

        const val DEFAULT_SCHEME = "raiscb-androidsdk"

        fun isAccepted(scheme: String): Boolean = ACCEPTED.matches(scheme)

        /** Hands the browser's answer to the waiting sign-in. Returns whether there was one that took it. */
        fun deliver(url: String?): Boolean = current?.handleReturn(url) ?: false

        /**
         * What the checkout's answer says: `<scheme>://signed-in?s=<session>` is a sign-in; anything else on that scheme is a failure; an
         * address on another scheme, or none, is not an answer to this sign-in (`null`).
         */
        fun parse(url: String?, scheme: String): Outcome? {
            val uri = try {
                URI(url ?: return null)
            } catch (_: Exception) {
                return null
            }
            if (!uri.scheme.equals(scheme, ignoreCase = true)) return null
            if (!"signed-in".equals(uri.host, ignoreCase = true)) return Outcome.Failed
            val value = uri.rawQuery?.split("&")?.firstNotNullOfOrNull { pair ->
                if (pair.substringBefore("=") == "s") URLDecoder.decode(pair.substringAfter("=", ""), "UTF-8") else null
            }
            // (a payer session is a short signed token: a longer or odd one is not one)
            if (value.isNullOrEmpty() || value.length > 4096 || value.any { it.isWhitespace() || it.isISOControl() || it == ';' || it == ',' }) return Outcome.Failed
            return Outcome.SignedIn(value)
        }
    }
}

/**
 * Where the browser lands when the Raïs website ends a native sign-in on the SDK's scheme. It takes the answer for the sign-in that waits for
 * it (see [WebSignIn.current]) and brings the checkout's screen back to the front, which closes the tab above it. With no sign-in waiting
 * (a stale link, another app's idea) it does nothing at all.
 */
internal class SignInReturnActivity : Activity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val taken = WebSignIn.deliver(intent?.dataString)
        if (taken) CheckoutActivity.bringToFront(this)
        finish()
    }
}
