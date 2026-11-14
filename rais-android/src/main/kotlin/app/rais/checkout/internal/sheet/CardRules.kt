package app.rais.checkout.internal.sheet

import java.text.Normalizer
import java.util.Calendar
import java.util.Date
import java.util.GregorianCalendar

/**
 * How the card fields read, write and judge what is typed. The same rules as the hosted page's script (and as rais-ios `CardRules`), so a
 * card is accepted or refused the same way everywhere. A check returns the key of the server's sentence about the problem ([Sheet.text]),
 * or `null` when there is none.
 */
internal object CardRules {
    const val PAN_LENGTH = 16      // CIB and Edahabia numbers have 16 digits: once they are all there, the next field takes over
    const val PAN_MAXIMUM = 19
    const val CVC_LENGTH = 3

    enum class Brand { Cib, Edahabia }

    /** The digits of a value, also when the keyboard wrote them as Arabic-Indic (٠-٩), Persian (۰-۹) or full-width digits. */
    fun digits(value: String): String = buildString {
        for (char in value) {
            when (char) {
                in '0'..'9' -> append(char)
                in '٠'..'٩' -> append('0' + (char - '٠'))
                in '۰'..'۹' -> append('0' + (char - '۰'))
                in '０'..'９' -> append('0' + (char - '０'))
            }
        }
    }

    /** "6280581110007215" → "6280 5811 1000 7215". */
    fun formatPan(value: String): String = grouped(digits(value).take(PAN_MAXIMUM))

    /** "1227" → "12 / 27"; a first digit above 1 is a month already ("5" → "05 / "). */
    fun formatExpiry(value: String): String {
        var number = digits(value).take(4)
        if (number.length == 1 && number[0] > '1') number = "0$number"
        if (number.length < 2) return number
        return number.take(2) + " / " + number.drop(2)
    }

    fun formatCvc(value: String): String = digits(value).take(CVC_LENGTH)

    /** 6280 is Edahabia, the rest of the 62 range CIB (as the server's own detection says). */
    fun brand(number: String): Brand? = when {
        number.startsWith("6280") -> Brand.Edahabia
        number.startsWith("62") -> Brand.Cib
        else -> null
    }

    /** A brand as the server names a saved card's. */
    fun brandNamed(name: String?): Brand? = when (name?.lowercase()) {
        "cib" -> Brand.Cib
        "edahabia", "dahabia" -> Brand.Edahabia
        else -> null
    }

    fun luhn(number: String): Boolean {
        if (number.isEmpty() || !number.all { it in '0'..'9' }) return false
        var sum = 0
        var alternate = false
        for (char in number.reversed()) {
            var digit = char - '0'
            if (alternate) {
                digit *= 2
                if (digit > 9) digit -= 9
            }
            sum += digit
            alternate = !alternate
        }
        return sum % 10 == 0
    }

    /** A name as the bank reads it: no accents, single spaces. */
    fun cleanName(value: String): String {
        val plain = Normalizer.normalize(value, Normalizer.Form.NFD).filter { it !in '̀'..'ͯ' }
        return plain.split(Regex("[\\p{Z}\\s]+")).filter { it.isNotEmpty() }.joinToString(" ")
    }

    /** The card as the bank masks it ("628058******7215"), in groups of four with dots: "6280 58•• •••• 7215". */
    fun formatMasked(value: String?): String {
        val shown = (value ?: "").mapNotNull { char ->
            when {
                char in '0'..'9' -> char
                char in "*•xX" -> '•'
                else -> null
            }
        }.joinToString("")
        return grouped(shown)
    }

    private fun grouped(text: String): String = buildString {
        text.forEachIndexed { index, char ->
            if (index > 0 && index % 4 == 0) append(' ')
            append(char)
        }
    }

    // ── Checks ──
    // `sandbox`: a test payment. The test gateway has cards that are expired or have a wrong check digit on purpose, and they must reach it
    // to be refused there, as a real answer.

    fun checkPan(value: String, sandbox: Boolean): String? {
        val number = digits(value)
        if (number.isEmpty()) return "js.pan_required"
        if (number.length < 13 || number.length > PAN_MAXIMUM) return "js.pan_invalid"
        if (!sandbox && !luhn(number)) return "js.pan_invalid"
        return null
    }

    fun checkExpiry(value: String, sandbox: Boolean, now: Date = Date()): String? {
        val number = digits(value)
        if (number.isEmpty()) return "js.expiry_required"
        if (number.length != 4) return "js.expiry_invalid"
        val month = number.take(2).toInt()
        val short = number.takeLast(2).toInt()
        if (month < 1 || month > 12) return "js.expiry_invalid"
        if (sandbox) return null
        val today = GregorianCalendar().apply { time = now }
        val year = 2000 + short
        val thisYear = today.get(Calendar.YEAR)
        val thisMonth = today.get(Calendar.MONTH) + 1
        if (year < thisYear || (year == thisYear && month < thisMonth)) return "js.expiry_past"
        return null
    }

    fun checkCvc(value: String): String? {
        val code = value.trim()
        return if (code.length == CVC_LENGTH && code.all { it in '0'..'9' }) null else "js.cvc_invalid"
    }

    private val NAME = Regex("^[A-Za-z][A-Za-z0-9$)(\\-. ]{1,25}$")

    fun checkName(value: String): String? {
        val name = cleanName(value)
        if (name.isEmpty()) return "js.name_required"
        return if (NAME.matches(name)) null else "js.name_invalid"
    }

    private val EMAIL = Regex("^[^@\\s<>()\\[\\]\\\\,;:\"]{1,64}@[^@\\s<>()\\[\\]\\\\,;:\"]+\\.[^@\\s<>()\\[\\]\\\\,;:\"]{2,}$")

    /** The receipt address is optional: only what is typed is checked (the server is the authority and answers a wrong one too). */
    fun checkEmail(value: String): String? {
        val address = value.trim()
        if (address.isEmpty()) return null
        return if (address.length <= 254 && EMAIL.matches(address)) null else "js.email_invalid"
    }
}

/** The card a payer typed, ready to be sent. Lives only for the request that sends it. */
internal class TypedCard(val pan: String, val cvc: String, val expiryMonth: String, val expiryYear: String, val holder: String)
