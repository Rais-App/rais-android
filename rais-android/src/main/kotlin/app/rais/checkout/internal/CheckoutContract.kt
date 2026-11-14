package app.rais.checkout.internal

import android.content.Context
import android.content.Intent
import android.os.Build
import androidx.activity.result.contract.ActivityResultContract
import app.rais.checkout.RaisCheckout
import java.io.Serializable
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap

/** The activity-result contract between [RaisCheckout] and its screen. */
internal object CheckoutContract : ActivityResultContract<CheckoutContract.Args, RaisCheckout.Result>() {
    const val EXTRA_ARGS = "app.rais.checkout.args"
    const val EXTRA_RESULT = "app.rais.checkout.result"

    /** [token] names what cannot travel in an intent (the app's [RaisCheckout.FontProvider]): see [Handoff]. */
    data class Args(val url: String, val configuration: RaisCheckout.Configuration, val token: String = UUID.randomUUID().toString()) :
        Serializable

    override fun createIntent(context: Context, input: Args): Intent {
        // (the same path for present() and for RaisCheckout.createIntent, which framework bridges start themselves)
        input.configuration.appearance.fontProvider?.let { Handoff.put(input.token, it) }
        val intent = Intent(context, CheckoutActivity::class.java)
        intent.putExtra(EXTRA_ARGS, input)
        return intent
    }

    /**
     * The parts of a configuration that are code, not data: an intent is serialized on its way to the checkout's screen, so the screen
     * finds them here, in memory, by the checkout's token.
     */
    object Handoff {
        private val fonts = ConcurrentHashMap<String, RaisCheckout.FontProvider>()

        fun put(token: String, provider: RaisCheckout.FontProvider) {
            fonts[token] = provider
        }

        fun font(token: String): RaisCheckout.FontProvider? = fonts[token]

        fun release(token: String) {
            fonts.remove(token)
        }
    }

    /** The payer left some other way (the system closed the screen): the same as pressing close. */
    override fun parseResult(resultCode: Int, intent: Intent?): RaisCheckout.Result =
        intent?.serializable(EXTRA_RESULT) ?: RaisCheckout.Result.Cancelled

    @Suppress("DEPRECATION", "UNCHECKED_CAST")
    inline fun <reified T : Serializable> Intent.serializable(key: String): T? =
        if (Build.VERSION.SDK_INT >= 33) getSerializableExtra(key, T::class.java) else getSerializableExtra(key) as? T
}
