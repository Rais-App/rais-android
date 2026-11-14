package app.rais.checkout.internal.sheet

import org.json.JSONArray
import org.json.JSONObject

/**
 * The checkout as data: what `GET /pay/{id}/sheet` answers. The server decides which state a payment is in, writes every amount and every
 * sentence in the payer's language, and the screen draws it. Nothing here is computed on the phone.
 *
 * The same contract as rais-ios `Sheet` (Sources/Rais/Internal/Native/SheetModels.swift): what is required there is required here, what
 * is optional there is optional here, and keys the server adds later are ignored.
 */
internal data class Sheet(
    val state: State,
    val language: String,
    val rtl: Boolean,
    val sandbox: Boolean,
    val payment: Payment?,
    val merchant: Merchant?,
    val text: Map<String, String>,
    val pay: Pay?,
    val result: Outcome?,
    /** `not_found` only. */
    val title: String?,
    val lead: String?,
) {
    enum class State(val key: String) {
        /** The payment can be paid: [pay] says how. */
        Pay("pay"),

        /** The payment is paid, closed, blocked or being confirmed: [result] says what became of it. */
        Result("result"),

        /** The link names no payment. */
        NotFound("not_found"),
    }

    data class Payment(
        val id: String,
        val amount: Long,
        val amountText: String,
        val amountNumber: String,
        val currency: String,
        val description: String?,
        val lines: List<Line>,
        val adjustmentText: String?,
        val details: List<Row>,
    )

    data class Line(val name: String, val description: String?, val quantity: String?, val totalText: String)

    /** One label and its value in a list of details. */
    data class Row(val label: String, val value: String, val mono: Boolean)

    data class Merchant(val name: String, val logoUrl: String?, val website: String?)

    data class Pay(
        /** `custom`: Raïs takes the card (this screen). `raw`: the bank's own page takes it (shown in the web checkout). */
        val mode: String,
        val captcha: Boolean,
        val sessionSeconds: Double,
        val otpLength: Int,
        val payer: Payer?,
        val balance: Balance?,
        val cards: List<Card>,
        val chosen: String?,
        val appUrl: String?,
        val save: Save?,
        val receiptEmail: Boolean,
        val termsUrl: String?,
        val otpPending: Boolean,
    ) {
        data class Payer(val firstName: String)

        data class Balance(val text: String, val enough: Boolean, val topupUrl: String?, val shortText: String?)

        data class Card(
            val id: String,
            /** `wallet`: a card of the payer's Raïs account. `saved`: a card the store keeps for its customer. */
            val kind: Kind,
            val last4: String,
            val expiry: String,
            val brand: String?,
            val needsCvc: Boolean,
        ) {
            enum class Kind { Wallet, Saved }
        }

        data class Save(val label: String, val checked: Boolean)
    }

    /** What became of a payment, already worded: the screen draws it as it is. */
    data class Outcome(
        val tone: Tone,
        val icon: String,
        val title: String,
        val lead: String?,
        val note: String?,
        val rows: List<Row>,
        val support: String?,
        val receipt: Receipt?,
        val retry: Boolean,
        val contactEmail: String?,
        val outcome: Reported?,
        val confirming: Confirming?,
    ) {
        enum class Tone { Ok, Bad, Neutral }

        data class Receipt(val url: String, val mail: Mail?) {
            data class Mail(val sent: Boolean, val to: String?, val known: String?)
        }

        /** How the payment ended, for the app that opened the checkout. */
        data class Reported(val status: String, val paymentId: String, val reference: String?, val orderNumber: String?)

        data class Confirming(val statusUrl: String, val resultUrl: String)
    }

    companion object {
        /** The sheet in [json], or `null` when it is not one this screen can draw (the web checkout then takes over). */
        fun parse(json: String): Sheet? = try {
            read(JSONObject(json))
        } catch (_: Exception) {
            null
        }

        private fun read(box: JSONObject): Sheet {
            val stateKey = box.getString("state")
            val state = State.entries.firstOrNull { it.key == stateKey } ?: throw IllegalArgumentException("state $stateKey")
            val text = LinkedHashMap<String, String>()
            box.optJSONObject("text")?.let { table ->
                for (key in table.keys()) if (!table.isNull(key)) text[key] = table.get(key).toString()
            }
            return Sheet(
                state = state,
                language = box.str("language") ?: "en",
                rtl = box.bool("rtl") ?: false,
                sandbox = box.bool("sandbox") ?: false,
                payment = box.obj("payment")?.let(::payment),
                merchant = box.obj("merchant")?.let(::merchant),
                text = text,
                pay = box.obj("pay")?.let(::pay),
                result = box.obj("result")?.let(::outcome),
                title = box.str("title"),
                lead = box.str("lead"),
            )
        }

        private fun payment(box: JSONObject): Payment {
            val amountText = box.need("amount_text")
            return Payment(
                id = box.need("id"),
                amount = box.getLong("amount"),
                amountText = amountText,
                amountNumber = box.str("amount_number") ?: amountText,
                currency = box.str("currency") ?: "",
                description = box.str("description"),
                lines = box.list("lines") { Line(it.need("name"), it.str("description"), it.str("quantity"), it.need("total_text")) },
                adjustmentText = box.str("adjustment_text"),
                details = box.list("details", ::row),
            )
        }

        private fun row(box: JSONObject) = Row(box.need("label"), box.need("value"), box.bool("mono") ?: false)

        private fun merchant(box: JSONObject) = Merchant(box.need("name"), box.str("logo_url"), box.str("website"))

        private fun pay(box: JSONObject) = Pay(
            mode = box.str("mode") ?: "custom",
            captcha = box.bool("captcha") ?: false,
            sessionSeconds = if (box.isNull("session_seconds")) 570.0 else box.getDouble("session_seconds"),
            otpLength = if (box.isNull("otp_length")) 6 else box.getInt("otp_length"),
            payer = box.obj("payer")?.let { Pay.Payer(it.need("first_name")) },
            balance = box.obj("balance")?.let {
                Pay.Balance(it.need("text"), it.bool("enough") ?: false, it.str("topup_url"), it.str("short_text"))
            },
            cards = box.list("cards") {
                val kind = when (it.getString("kind")) {
                    "wallet" -> Pay.Card.Kind.Wallet
                    "saved" -> Pay.Card.Kind.Saved
                    else -> throw IllegalArgumentException("card kind")
                }
                Pay.Card(it.need("id"), kind, it.need("last4"), it.need("expiry"), it.str("brand"), it.getBoolean("needs_cvc"))
            },
            chosen = box.str("chosen"),
            appUrl = box.str("app_url"),
            save = box.obj("save")?.let { Pay.Save(it.need("label"), it.getBoolean("checked")) },
            receiptEmail = box.bool("receipt_email") ?: false,
            termsUrl = box.str("terms_url"),
            otpPending = box.bool("otp_pending") ?: false,
        )

        private fun outcome(box: JSONObject) = Outcome(
            tone = when (box.str("tone")) {
                "ok" -> Outcome.Tone.Ok
                "bad" -> Outcome.Tone.Bad
                else -> Outcome.Tone.Neutral
            },
            icon = box.str("icon") ?: "alert",
            title = box.need("title"),
            lead = box.str("lead"),
            note = box.str("note"),
            rows = box.list("rows", ::row),
            support = box.str("support"),
            // (as on iOS: a receipt or a confirmation this version cannot read is left out, not a reason to refuse the whole answer)
            receipt = runCatching {
                box.obj("receipt")?.let { receipt ->
                    Outcome.Receipt(
                        receipt.need("url"),
                        receipt.obj("mail")?.let { Outcome.Receipt.Mail(it.getBoolean("sent"), it.str("to"), it.str("known")) },
                    )
                }
            }.getOrNull(),
            retry = box.bool("retry") ?: false,
            contactEmail = box.str("contact_email"),
            outcome = box.obj("outcome")?.let {
                Outcome.Reported(it.need("status"), it.need("payment_id"), it.str("reference"), it.str("order_number"))
            },
            confirming = runCatching {
                box.obj("confirming")?.let { Outcome.Confirming(it.need("status_url"), it.need("result_url")) }
            }.getOrNull(),
        )

        // ── reading JSON as the iOS decoder does ──
        // (Android's org.json answers "null" for optString of a JSON null: every optional value goes through these.)

        private fun JSONObject.str(key: String): String? = if (isNull(key)) null else get(key) as? String ?: throw IllegalArgumentException(key)

        private fun JSONObject.need(key: String): String = str(key) ?: throw IllegalArgumentException(key)

        private fun JSONObject.bool(key: String): Boolean? = if (isNull(key)) null else getBoolean(key)

        private fun JSONObject.obj(key: String): JSONObject? = if (isNull(key)) null else getJSONObject(key)

        private fun <T> JSONObject.list(key: String, item: (JSONObject) -> T): List<T> {
            if (isNull(key)) return emptyList()
            val array: JSONArray = getJSONArray(key)
            return (0 until array.length()).map { item(array.getJSONObject(it)) }
        }
    }
}

/**
 * The server's words for this payer ([Sheet.text]): `words["pay.card_number"]`, and `words.fill("js.session", "time" to "9:41")` for a
 * sentence with something only the screen knows.
 */
internal class Words(private val table: Map<String, String>) {
    operator fun get(key: String): String = table[key] ?: ""

    fun fill(key: String, vararg values: Pair<String, String>): String =
        values.fold(this[key]) { text, (name, value) -> text.replace("{$name}", value) }

    /**
     * What the server said about a call it refused: our own words in the payer's language whenever it sent a code, else the sentence the
     * bank's gateway wrote, as it came.
     */
    fun describing(refusal: Refusal): String {
        val code = refusal.code
        if (code != null) table["js.code.$code"]?.takeIf { it.isNotEmpty() }?.let { return it }
        if (code == null && !refusal.text.isNullOrEmpty()) return refusal.text
        return this["js.generic"]
    }
}

/**
 * A call the server answered with a refusal. [code] names it (the screen words it from [Sheet.text]); without a code, [message] is a
 * sentence the bank's gateway wrote. [field] is the form field it is about (`email`); [retry]: the bank refused this code but its step goes
 * on, another code can be tried.
 */
internal class Refusal(
    val status: Int,
    val code: String?,
    /** The sentence the gateway wrote, when it sent no code. */
    val text: String?,
    val field: String?,
    val retry: Boolean,
) : Exception("refused: ${code ?: status}")
