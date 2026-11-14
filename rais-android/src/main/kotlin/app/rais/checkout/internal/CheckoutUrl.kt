package app.rais.checkout.internal

import org.json.JSONObject
import java.net.URI
import java.util.Locale
import java.util.UUID

/**
 * Which addresses may be shown as a checkout, and which origin may talk to the app through the bridge.
 *
 * The bridge is a door from a web page into the app. It opens for the checkout page only, never for a bank's 3-D Secure page that the
 * same WebView navigates to on the way, so the rule is the same for both: the origin the SDK was given, and only if it is trusted.
 */
internal object CheckoutUrl {
    private val LOOPBACK = setOf("localhost", "127.0.0.1", "::1", "[::1]", "10.0.2.2") // 10.0.2.2: the Android emulator's name for the host Mac

    private fun parse(value: String?): URI? = try {
        value?.let { URI(it) }?.takeIf { it.scheme != null && it.host != null }
    } catch (_: Exception) {
        null
    }

    /** `rais.app` and its subdomains over https, plus loopback addresses for local testing, plus what the integrator added. */
    fun isTrusted(url: String?, additionalHosts: List<String>): Boolean {
        val uri = parse(url) ?: return false
        val scheme = uri.scheme.lowercase(Locale.ROOT)
        val host = uri.host.lowercase(Locale.ROOT)
        if (host in LOOPBACK) return scheme == "http" || scheme == "https"
        val listed = additionalHosts.any { it.lowercase(Locale.ROOT) == host }
        // A development server on the local network (a phone talking to the developer's computer) has no certificate: plain http is
        // accepted for a private address, and only when the app listed that very address itself.
        if (scheme == "http") return listed && isPrivateNetwork(host)
        if (scheme != "https") return false
        if (host == "rais.app" || host.endsWith(".rais.app")) return true
        return listed
    }

    /**
     * A literal address of a private network (10/8, 172.16/12, 192.168/16, and 100.64/10 for a mesh VPN), a `.local` name, or a
     * machine's name on a Tailscale network (`*.ts.net`: reachable only from inside that network, and encrypted by it).
     */
    fun isPrivateNetwork(host: String): Boolean {
        val name = host.lowercase(Locale.ROOT)
        if (name.endsWith(".local") || name.endsWith(".ts.net")) return true
        val parts = name.split(".")
        if (parts.size != 4) return false
        val numbers = parts.map { part -> part.takeIf { it.isNotEmpty() && it.length <= 3 && it.all(Char::isDigit) }?.toInt() ?: return false }
        if (numbers.any { it > 255 }) return false
        val (a, b) = numbers
        return a == 10 || (a == 192 && b == 168) || (a == 172 && b in 16..31) || (a == 100 && b in 64..127)
    }

    /** [value] when it is an https address with a host (a logo the app names), else `null`: nothing else is ever loaded as a picture. */
    fun httpsImage(value: String?): String? {
        val uri = parse(value?.trim()) ?: return null
        return if (uri.scheme.equals("https", ignoreCase = true) && uri.host.isNotEmpty()) value?.trim() else null
    }

    /** `scheme://host[:port]`, the form the WebView's origin rules take. Null for anything that is not an http(s) address. */
    fun origin(url: String?): String? {
        val uri = parse(url) ?: return null
        val scheme = uri.scheme.lowercase(Locale.ROOT)
        if (scheme != "http" && scheme != "https") return null
        val port = if (uri.port == -1 || uri.port == defaultPort(scheme)) "" else ":${uri.port}"
        return "$scheme://${uri.host.lowercase(Locale.ROOT)}$port"
    }

    fun isSameOrigin(candidate: String?, of: String?): Boolean {
        val a = origin(candidate) ?: return false
        return a == origin(of)
    }

    private fun defaultPort(scheme: String): Int = if (scheme == "https") 443 else 80

    /**
     * The address to load first. Without a language it is the checkout itself; with one it is the site's own language switch
     * (`/lang/fr?next=/pay/…`), which keeps the choice in a cookie for the whole checkout (the bank's return included) and then lands on
     * the same payment.
     */
    fun entry(checkout: String, languageCode: String?): String {
        if (languageCode == null || languageCode !in setOf("en", "fr", "ar")) return checkout
        val uri = parse(checkout) ?: return checkout
        val origin = origin(checkout) ?: return checkout
        val next = (uri.rawPath ?: "/") + (uri.rawQuery?.let { "?$it" } ?: "")
        val escaped = buildString {
            for (byte in next.toByteArray(Charsets.UTF_8)) {
                val c = byte.toInt().toChar()
                if (c in 'a'..'z' || c in 'A'..'Z' || c in '0'..'9') append(c) else append('%').append("%02X".format(byte.toInt() and 0xff))
            }
        }
        return "$origin/lang/$languageCode?next=$escaped"
    }
}

/** What the page tells the app. Messages come from the checkout page only (see [CheckoutUrl]), and are validated field by field. */
internal sealed interface PageMessage {
    /**
     * The page is on screen and ready. [page] is `pay` for the payment form, `result` for a page that says how a payment ended; [rtl] is
     * whether the page reads right to left (Arabic), whatever the language of the app: the close button goes to the page's start edge.
     */
    data class Ready(val page: String, val rtl: Boolean) : PageMessage

    /** A payment reached an end the page shows to the payer. The payer may still retry a failed one. */
    data class Result(val status: Status, val paymentId: String, val reference: String?, val orderNumber: String?) : PageMessage

    /** The payer pressed the page's own "Done" / "Return" button: leave the checkout with the last result. */
    data object Done : PageMessage

    /** The payer pressed the page's own back link: leave the checkout. */
    data object Close : PageMessage

    enum class Status { Paid, Failed, Cancelled, Expired;
        companion object {
            fun of(raw: String?): Status? = entries.firstOrNull { it.name.equals(raw, ignoreCase = true) }
        }
    }

    companion object {
        fun parse(text: String?): PageMessage? {
            val json = try { JSONObject(text ?: return null) } catch (_: Exception) { return null }
            return when (json.optString("type")) {
                "ready" -> Ready(if (json.optString("page") == "pay") "pay" else "result", json.optString("dir") == "rtl")
                "done" -> Done
                "close" -> Close
                "result" -> {
                    val status = Status.of(json.optString("status")) ?: return null
                    val id = json.optString("paymentId")
                    if (id.length != 36 || runCatching { UUID.fromString(id) }.isFailure) return null
                    Result(status, id.lowercase(Locale.ROOT), bounded(json, "reference"), bounded(json, "orderNumber"))
                }
                else -> null
            }
        }

        private fun bounded(json: JSONObject, key: String): String? {
            if (json.isNull(key)) return null
            val value = json.optString(key)
            return value.takeIf { it.isNotEmpty() && it.length <= 200 }
        }
    }
}
