package app.rais.checkout.internal.sheet

import app.rais.checkout.RaisCheckout
import app.rais.checkout.internal.CheckoutUrl
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.IOException
import java.io.InputStream
import java.net.HttpURLConnection
import java.net.URI
import java.net.URL
import java.net.URLEncoder
import java.util.Locale
import java.util.UUID
import kotlin.coroutines.cancellation.CancellationException

/**
 * Where a checkout address points: the site, the payment, and whether the address is more than the payment's own page (the app's
 * checkout session, which first identifies the payer and then redirects there).
 */
internal data class Location(val origin: String, val paymentId: String, val isPlain: Boolean) {
    /** The payment's own page, without anything the address carried. */
    val pageUrl: String get() = "$origin/pay/$paymentId"

    companion object {
        fun of(url: String): Location? {
            val uri = try {
                URI(url)
            } catch (_: Exception) {
                return null
            }
            val origin = CheckoutUrl.origin(url) ?: return null
            val path = (uri.rawPath ?: "").split("/").filter { it.isNotEmpty() }
            if (path.size < 2 || path[0] != "pay" || path[1].length != 36) return null
            if (runCatching { UUID.fromString(path[1]) }.isFailure) return null
            return Location(origin, path[1].lowercase(Locale.ROOT), path.size == 2)
        }
    }
}

/** A call that got no answer the screen can use. */
internal sealed class ApiFailure : Exception() {
    /** No answer: no network, a timeout, a server that cannot be reached. */
    class Connection : ApiFailure()

    /** The server has no native checkout for this address (an older server, or an address that is not a payment's): show the web checkout. */
    class Unsupported : ApiFailure()
}

/**
 * The calls of the native checkout: the very ones the hosted page's script makes, to the same addresses, with the same cookies.
 *
 * The checkout keeps its state on the server and hands the payer's side of it out as HttpOnly cookies scoped to the payment (who is
 * paying, the bank's step in progress, the robot check that was passed). A private, in-memory [CookieJar] holds them here exactly as a
 * browser would, and forgets everything when the checkout closes: nothing of a payment stays on the device.
 */
internal class CheckoutApi(private val checkoutUrl: String, location: Location, languageCode: String?) {
    /** What a payment call led to. */
    sealed class Step {
        /** The attempt is over, paid or not: [order] names it at the bank (`null` for a payment from a Raïs balance). */
        data class Complete(val order: String?) : Step()

        /** The bank asks for the code it sent to the cardholder. */
        data class Code(val maskedCard: String?) : Step()
    }

    sealed class Method {
        class NewCard(val card: TypedCard, val save: Boolean) : Method()
        class Card(val card: Sheet.Pay.Card, val cvc: String?) : Method()
        object Balance : Method()
    }

    @Volatile var location: Location = location
        private set

    val jar = CookieJar()
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    /** A card of the payer to start from, named by the app's checkout session. */
    private var preselectedCard: String? = null
    private var entered = false

    /** The robot check is on for this payment ([Sheet.Pay.captcha]): payment calls need a pass. */
    @Volatile var needsPass = false
    @Volatile private var passUntil = 0L
    private val passLock = Mutex()

    init {
        // A language the app asked for is the payer's own choice, kept the way the site keeps it (the `rais-locale` cookie): it then decides
        // the words of the screen, of the bank's refusals and of the receipt alike. Without one the server chooses, from the payment and the device.
        if (languageCode != null) URI(location.origin).host?.let { jar.set(it, "rais-locale", languageCode) }
    }

    fun invalidate() {
        scope.cancel()
    }

    // ── Reading the checkout ──

    /**
     * The checkout, read for the first time. An address that is more than the payment's page is visited once (it is single use): it sets
     * the cookie that says who is paying and redirects to the page, which is not loaded.
     */
    suspend fun open(): Sheet {
        if (!location.isPlain && !entered) {
            val answer = send(checkoutUrl, accept = "text/html")
            entered = true
            answer.header("location")?.let { target ->
                preselectedCard = runCatching { query(URI(target).rawQuery, "card") }.getOrNull()
            }
        }
        return sheet()
    }

    /** [order]: the attempt that just ended at the bank, to be confirmed. [fresh]: a payment that was just made from a balance. */
    suspend fun sheet(order: String? = null, fresh: Boolean = false): Sheet {
        val query = buildList {
            preselectedCard?.let { add("card=" + encode(it)) }
            order?.let { add("order=" + encode(it)) }
            if (fresh) add("fresh=1")
        }
        return sheetAt(location.pageUrl + "/sheet" + if (query.isEmpty()) "" else "?" + query.joinToString("&"))
    }

    suspend fun sheetAt(address: String): Sheet {
        if (!CheckoutUrl.isSameOrigin(address, location.origin)) throw ApiFailure.Unsupported()
        val answer = send(address)
        // the site moved this payer to its checkout host (a permanent redirect that was followed): the calls go there from now on
        CheckoutUrl.origin(answer.finalUrl)?.let { final ->
            if (!CheckoutUrl.isSameOrigin(final, location.origin)) location = location.copy(origin = final)
        }
        val sheet = if (answer.status == 200 || answer.status == 404) Sheet.parse(answer.text()) else null
        if (sheet == null) {
            if (answer.status >= 500 || answer.status == 429) throw ApiFailure.Connection()
            throw ApiFailure.Unsupported()
        }
        needsPass = sheet.pay?.captcha ?: false
        return sheet
    }

    // ── Paying ──

    /** A payment session with the bank for a card that is about to be typed. It lasts about ten minutes. */
    suspend fun register() {
        val answer = call("register", needsPass = true)
        if (answer.json.optString("status") != "ok") throw refusal(answer)
    }

    suspend fun pay(method: Method, email: String?): Step {
        val body = JSONObject()
        if (!email.isNullOrEmpty()) body.put("email", email)
        val path = when (method) {
            is Method.NewCard -> {
                body.put("pan", method.card.pan)
                body.put("cvc", method.card.cvc)
                body.put("expiry_month", method.card.expiryMonth)
                body.put("expiry_year", method.card.expiryYear)
                body.put("cardholder_name", method.card.holder)
                body.put("save_card", method.save)
                "process"
            }
            is Method.Card -> {
                if (!method.cvc.isNullOrEmpty()) body.put("cvc", method.cvc)
                when (method.card.kind) {
                    Sheet.Pay.Card.Kind.Wallet -> {
                        body.put("card_id", method.card.id)
                        "process-wallet-card"
                    }
                    Sheet.Pay.Card.Kind.Saved -> {
                        body.put("saved_card_id", method.card.id)
                        "process-saved"
                    }
                }
            }
            Method.Balance -> "process-wallet"
        }
        return step(call(path, body, needsPass = true))
    }

    suspend fun submitCode(code: String): Step = step(call("otp", JSONObject().put("otp", code)))

    /** Abandons the bank's step that waits for its code, so the payer can start over. Moves no money. */
    suspend fun cancelCode() {
        runCatching { call("otp/cancel") }
    }

    /** "Not you?": forget who was paying. */
    suspend fun signOut() {
        runCatching { call("raisid/signout") }
    }

    private fun step(answer: Answer): Step = when (answer.json.optString("status")) {
        "complete" -> {
            val target = answer.json.str("redirect")
            Step.Complete(target?.let { runCatching { query(URI(it).rawQuery, "orderId") }.getOrNull() })
        }
        "otp_required" -> Step.Code(answer.json.str("masked_pan"))
        else -> throw refusal(answer)
    }

    // ── After the payment ──

    /** Has the bank named an outcome for the payment that is being confirmed? */
    suspend fun isSettled(statusUrl: String): Boolean {
        if (!CheckoutUrl.isSameOrigin(statusUrl, location.origin)) return false
        return try {
            JSONObject(send(statusUrl).text()).optString("state") == "done"
        } catch (error: CancellationException) {
            throw error
        } catch (_: Exception) {
            false
        }
    }

    /** E-mails the receipt: to the address the payment has, else to [address]. Returns the masked address it went (or had already gone) to. */
    suspend fun emailReceipt(address: String?): String {
        val body = JSONObject()
        if (!address.isNullOrEmpty()) body.put("email", address)
        val answer = call("receipt-email", body)
        val status = answer.json.optString("status")
        if (status != "sent" && status != "already_sent") throw refusal(answer)
        return answer.json.str("email") ?: ""
    }

    /** The PDF receipt, written to [file]. */
    suspend fun downloadReceipt(address: String, file: File) {
        if (!CheckoutUrl.isSameOrigin(address, location.origin)) throw ApiFailure.Unsupported()
        val answer = send(address, accept = "application/pdf", limit = 25_000_000)
        val pdf = answer.body
        if (answer.status != 200 || pdf.size < 4 || String(pdf, 0, 4, Charsets.US_ASCII) != "%PDF") throw ApiFailure.Connection()
        withContext(Dispatchers.IO) {
            file.parentFile?.mkdirs()
            file.writeBytes(pdf)
        }
    }

    /** The bytes of the merchant's logo. From the checkout's own site with its session; from the merchant's own https address with nothing. */
    suspend fun image(address: String): ByteArray? {
        val own = CheckoutUrl.isSameOrigin(address, location.origin)
        if (!own && !address.lowercase(Locale.ROOT).startsWith("https://")) return null
        return try {
            val answer = send(address, accept = "image/*", limit = 4_000_000, timeoutMs = 15_000, cookies = own)
            answer.body.takeIf { answer.status == 200 && it.isNotEmpty() }
        } catch (error: CancellationException) {
            throw error
        } catch (_: Exception) {
            null
        }
    }

    // ── The robot check ──

    /** Gets the pass ready before the payer needs it. Safe to call often. */
    fun warmUp() {
        if (!needsPass) return
        scope.launch { ensurePass() }
    }

    private suspend fun ensurePass(): Boolean = passLock.withLock {
        if (passUntil > System.currentTimeMillis() + 30_000) return@withLock true
        obtainPass()
    }

    private suspend fun obtainPass(): Boolean {
        val challenge = try {
            call("captcha/challenge")
        } catch (error: CancellationException) {
            throw error
        } catch (_: Exception) {
            return false
        }
        val token = challenge.json.str("token") ?: return false
        val shape = challenge.json.optJSONObject("challenge") ?: return false
        val count = shape.optInt("c", -1)
        val size = shape.optInt("s", -1)
        val difficulty = shape.optInt("d", -1)
        val answers = withContext(Dispatchers.Default) { ProofOfWork.solve(token, count, size, difficulty) } ?: return false
        val redeemed = try {
            call("captcha/redeem", JSONObject().put("token", token).put("solutions", JSONArray(answers)))
        } catch (error: CancellationException) {
            throw error
        } catch (_: Exception) {
            return false
        }
        if (!redeemed.json.optBoolean("success", false)) return false
        val expires = redeemed.json.optDouble("expires", Double.NaN)
        passUntil = if (expires.isNaN()) System.currentTimeMillis() + 600_000 else expires.toLong()
        return true
    }

    // ── Plumbing ──

    private class Answer(val status: Int, val json: JSONObject)

    class Response(val status: Int, val body: ByteArray, private val headers: Map<String, List<String>>, val finalUrl: String) {
        fun header(name: String): String? = headers[name.lowercase(Locale.ROOT)]?.firstOrNull()
        fun text(): String = String(body, Charsets.UTF_8)
    }

    /**
     * A POST of the payment's own endpoints. With [needsPass] the robot check is passed first, and passed again (once) when the server says
     * the pass ran out.
     */
    private suspend fun call(path: String, body: JSONObject? = null, needsPass: Boolean = false): Answer {
        if (needsPass && this.needsPass) ensurePass()
        var answer = post(path, body)
        if (needsPass && answer.json.optString("code") == "captcha_required") {
            this.needsPass = true
            passUntil = 0
            if (ensurePass()) answer = post(path, body)
        }
        return answer
    }

    private suspend fun post(path: String, body: JSONObject?): Answer {
        val response = send("${location.pageUrl}/$path", method = "POST", body = (body ?: JSONObject()).toString().toByteArray())
        val json = try {
            JSONObject(response.text())
        } catch (_: Exception) {
            JSONObject()
        }
        return Answer(response.status, json)
    }

    private fun refusal(answer: Answer) = Refusal(
        status = answer.status,
        code = answer.json.str("code"),
        text = answer.json.str("message"),
        field = answer.json.str("field"),
        retry = answer.json.optBoolean("retry", false),
    )

    /**
     * One request, with this checkout's cookies. Redirects are answers here, not instructions: the address that identifies the payer redirects
     * to a page this screen does not load, and a payment call never redirects. Only a permanent move of the same request (307/308: the site
     * sending a payer to its checkout host) is followed, to the same site or to an https one.
     */
    private suspend fun send(
        address: String,
        method: String = "GET",
        body: ByteArray? = null,
        accept: String = "application/json",
        limit: Int = 2_000_000,
        timeoutMs: Int = 75_000,   // a card call waits for the bank
        cookies: Boolean = true,
    ): Response = withContext(Dispatchers.IO) {
        var current = address
        var hops = 0
        while (true) {
            val uri = try {
                URI(current)
            } catch (_: Exception) {
                throw ApiFailure.Unsupported()
            }
            val connection = try {
                URL(current).openConnection() as HttpURLConnection
            } catch (_: Exception) {
                throw ApiFailure.Connection()
            }
            try {
                connection.instanceFollowRedirects = false
                connection.useCaches = false
                connection.connectTimeout = 15_000
                connection.readTimeout = timeoutMs
                connection.requestMethod = method
                connection.setRequestProperty("User-Agent", USER_AGENT)
                connection.setRequestProperty("Accept", accept)
                connection.setRequestProperty("Accept-Language", Locale.getDefault().toLanguageTag())
                if (cookies) jar.header(uri)?.let { connection.setRequestProperty("Cookie", it) }
                if (body != null) {
                    connection.doOutput = true
                    connection.setRequestProperty("Content-Type", "application/json")
                    connection.setFixedLengthStreamingMode(body.size)
                    connection.outputStream.use { it.write(body) }
                }
                val status = connection.responseCode
                val headers = HashMap<String, List<String>>()
                for ((name, values) in connection.headerFields) if (name != null) headers[name.lowercase(Locale.ROOT)] = values
                if (cookies) headers["set-cookie"]?.let { jar.receive(uri, it) }
                val bytes = (if (status >= 400) connection.errorStream else connection.inputStream)?.use { read(it, limit) } ?: ByteArray(0)
                val target = headers["location"]?.firstOrNull()
                if ((status == 307 || status == 308) && target != null && hops < 5) {
                    val next = uri.resolve(target).toString()
                    if (CheckoutUrl.isSameOrigin(next, current) || next.lowercase(Locale.ROOT).startsWith("https://")) {
                        current = next
                        hops++
                        continue
                    }
                }
                if (!isActive) throw CancellationException()
                return@withContext Response(status, bytes, headers, current)
            } catch (error: CancellationException) {
                throw error
            } catch (error: ApiFailure) {
                throw error
            } catch (_: IOException) {
                throw ApiFailure.Connection()
            } catch (_: RuntimeException) {
                throw ApiFailure.Connection()
            } finally {
                connection.disconnect()
            }
        }
        @Suppress("UNREACHABLE_CODE")
        throw ApiFailure.Connection()   // (never reached: the loop returns or throws)
    }

    private fun read(stream: InputStream, limit: Int): ByteArray {
        val out = ByteArrayOutputStream()
        val buffer = ByteArray(16 * 1024)
        while (true) {
            val count = stream.read(buffer)
            if (count < 0) break
            out.write(buffer, 0, count)
            if (out.size() > limit) throw IOException("answer too large")
        }
        return out.toByteArray()
    }

    companion object {
        val USER_AGENT = "RaisSDK/${RaisCheckout.VERSION} (Android; native)"

        private fun encode(value: String) = URLEncoder.encode(value, "UTF-8")

        fun query(rawQuery: String?, name: String): String? = rawQuery?.split("&")?.firstNotNullOfOrNull { pair ->
            val key = pair.substringBefore("=")
            if (key == name) java.net.URLDecoder.decode(pair.substringAfter("=", ""), "UTF-8") else null
        }

        private fun JSONObject.str(key: String): String? = if (isNull(key)) null else optString(key).takeIf { it.isNotEmpty() }
    }
}
