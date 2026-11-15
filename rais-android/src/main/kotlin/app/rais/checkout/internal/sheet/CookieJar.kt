package app.rais.checkout.internal.sheet

import java.net.HttpCookie
import java.net.URI
import java.util.Locale

/**
 * The cookies of ONE checkout, in memory, as a browser keeps them: who is paying, the bank's step in progress, the robot check that was
 * passed (HttpOnly cookies scoped to the payment's own path). Nothing is written to disk and nothing is shared with the app's other
 * connections or with the system's WebView cookie store; everything is forgotten when the checkout closes.
 *
 * Deliberately small: the checkout talks to one site, so a cookie belongs to the host that set it (a `Domain` attribute widens it to
 * that domain's subdomains), and a path matches as RFC 6265 says.
 */
internal class CookieJar(private val clock: () -> Long = System::currentTimeMillis) {
    data class Cookie(
        val name: String,
        val value: String,
        val host: String,
        val includeSubdomains: Boolean,
        val path: String,
        val secure: Boolean,
        /** Milliseconds since the epoch, or `null` for a cookie of this session. */
        val expiresAt: Long?,
    )

    private val cookies = mutableListOf<Cookie>()

    /** Keeps what a response from [url] set (its `Set-Cookie` headers). */
    @Synchronized
    fun receive(url: URI, setCookieHeaders: List<String>) {
        val host = url.host?.lowercase(Locale.ROOT) ?: return
        for (header in setCookieHeaders) {
            val parsed = try {
                HttpCookie.parse(header)
            } catch (_: Exception) {
                continue
            }
            for (cookie in parsed) {
                val domain = cookie.domain?.lowercase(Locale.ROOT)?.trimStart('.')
                // a site sets cookies for itself or a parent domain of itself, never for another site
                if (domain != null && domain != host && !host.endsWith(".$domain")) continue
                val path = cookie.path?.takeIf { it.startsWith("/") } ?: defaultPath(url)
                val maxAge = cookie.maxAge
                val entry = Cookie(
                    name = cookie.name,
                    value = cookie.value ?: "",
                    host = domain ?: host,
                    includeSubdomains = domain != null,
                    path = path,
                    secure = cookie.secure,
                    expiresAt = if (maxAge < 0) null else clock() + maxAge * 1000,
                )
                cookies.removeAll { it.name == entry.name && it.host == entry.host && it.path == entry.path }
                if (maxAge != 0L) cookies.add(entry)
            }
        }
    }

    /** Sets a cookie directly (the language the app asked for, kept the way the site keeps it). */
    @Synchronized
    fun set(host: String, name: String, value: String, path: String = "/", secure: Boolean = false, maxAgeSeconds: Long? = null) {
        val lower = host.lowercase(Locale.ROOT)
        cookies.removeAll { it.name == name && it.host == lower && it.path == path }
        cookies.add(Cookie(name, value, lower, false, path, secure, maxAgeSeconds?.let { clock() + it * 1000 }))
    }

    /** The `Cookie` header for a request to [url], or `null` when no cookie applies. Longer paths first, as browsers send them. */
    @Synchronized
    fun header(url: URI): String? {
        val matching = matching(url)
        return if (matching.isEmpty()) null else matching.joinToString("; ") { "${it.name}=${it.value}" }
    }

    @Synchronized
    fun matching(url: URI): List<Cookie> {
        val host = url.host?.lowercase(Locale.ROOT) ?: return emptyList()
        val path = url.rawPath?.takeIf { it.isNotEmpty() } ?: "/"
        val https = url.scheme.equals("https", ignoreCase = true)
        val now = clock()
        cookies.removeAll { it.expiresAt != null && it.expiresAt <= now }
        return cookies.filter { cookie ->
            (cookie.host == host || (cookie.includeSubdomains && host.endsWith(".${cookie.host}"))) &&
                pathMatches(path, cookie.path) && (!cookie.secure || https)
        }.sortedByDescending { it.path.length }
    }

    /** Everything held, for the web checkout that takes over in the same screen. */
    @Synchronized
    fun all(): List<Cookie> {
        val now = clock()
        return cookies.filter { it.expiresAt == null || it.expiresAt > now }
    }

    companion object {
        fun pathMatches(requestPath: String, cookiePath: String): Boolean {
            if (requestPath == cookiePath) return true
            if (!requestPath.startsWith(cookiePath)) return false
            return cookiePath.endsWith("/") || requestPath[cookiePath.length] == '/'
        }

        private fun defaultPath(url: URI): String {
            val path = url.rawPath
            if (path.isNullOrEmpty() || !path.startsWith("/")) return "/"
            val last = path.lastIndexOf('/')
            return if (last <= 0) "/" else path.substring(0, last)
        }
    }
}
