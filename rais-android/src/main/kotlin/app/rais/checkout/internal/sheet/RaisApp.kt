package app.rais.checkout.internal.sheet

import android.content.Intent
import android.net.Uri
import java.net.URI

/** The Raïs app, the way an app opens another one on Android: an explicit intent, with nothing to declare in the manifest. */
internal object RaisApp {
    /** The Android package of the Raïs app. */
    const val PACKAGE = "app.rais.wallet"

    /**
     * The address that opens the Raïs app on this payment (`rais://pay/<payment id>`, as the checkout words it in `app_url`), or `null` when
     * [appUrl] is not a `rais://` address: nothing else is ever handed to another app.
     */
    fun target(appUrl: String?): String? {
        val text = appUrl?.trim() ?: return null
        val uri = try {
            URI(text)
        } catch (_: Exception) {
            return null
        }
        if (!uri.scheme.equals("rais", ignoreCase = true) || uri.host.isNullOrEmpty()) return null
        return text
    }

    /**
     * The intent that opens the Raïs app. It names the app's package, so a look-alike that also claims `rais://` never gets the payment, and
     * it needs no `<queries>` entry: starting an activity is not a package lookup (a missing app throws `ActivityNotFoundException`).
     */
    fun intent(appUrl: String?): Intent? {
        val target = target(appUrl) ?: return null
        return Intent(Intent.ACTION_VIEW, Uri.parse(target)).setPackage(PACKAGE)
    }
}

/** When the checkout reads the payment again after the payer was away (in the Raïs app), and when it must not. */
internal object ReturnRules {
    /**
     * Back from the Raïs app: read the payment again only on the payment screen, for a payer who really left, with nothing in flight, and
     * above all **never after the checkout ended** (its network client is closed then, and a screen can still come to the front).
     */
    fun shouldReadAgain(finished: Boolean, leftForApp: Boolean, onPayScreen: Boolean, isBusy: Boolean, apiOpen: Boolean): Boolean =
        !finished && leftForApp && onPayScreen && !isBusy && apiOpen
}
