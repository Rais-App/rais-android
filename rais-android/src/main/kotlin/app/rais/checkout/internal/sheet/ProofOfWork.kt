package app.rais.checkout.internal.sheet

import java.security.MessageDigest
import java.util.stream.IntStream

/**
 * The checkout's robot check, done by the phone instead of the payer.
 *
 * The hosted page shows a checkbox (Cap, trycap.dev) that makes the browser solve a small proof of work before a card may be submitted. It
 * is a cost per attempt, not a question to a person, so a native screen pays the same cost silently: it asks for the same challenge, finds
 * the same numbers and redeems them. The server's rule is untouched and the payer sees nothing.
 *
 * A challenge is `count` puzzles derived from its token. Puzzle `n` (from 1) has a salt, `prng(token + n, size)`, and a target,
 * `prng(token + n + "d", difficulty)`: the answer is the smallest number whose decimal text, appended to the salt, has a SHA-256 that
 * starts with the target (in hex). The same arithmetic as rais-ios `ProofOfWork` and the server's app/services/captcha.py.
 */
internal object ProofOfWork {
    /** The 32-bit FNV-1a hash that seeds the generator, code point by code point (as an unsigned number). */
    fun fnv1a(text: String): Long {
        var hash = -0x7ee3623b   // 2166136261
        var index = 0
        while (index < text.length) {
            val point = text.codePointAt(index)
            hash = (hash xor point) * 16_777_619
            index += Character.charCount(point)
        }
        return hash.toLong() and 0xFFFF_FFFFL
    }

    /** Cap's xorshift generator: [length] hex digits that depend only on [seed]. */
    fun prng(seed: String, length: Int): String {
        var state = fnv1a(seed).toInt()
        val out = StringBuilder()
        while (out.length < length) {
            state = state xor (state shl 13)
            state = state xor (state ushr 17)
            state = state xor (state shl 5)
            out.append(String.format("%08x", state))
        }
        return out.substring(0, length)
    }

    /**
     * The answers of a whole challenge, in order. Runs on every core; call it off the main thread. `null` when a puzzle has no answer within
     * the numbers the server accepts, which does not happen with a genuine challenge.
     */
    fun solve(token: String, count: Int, size: Int, difficulty: Int): List<Long>? {
        if (count !in 1..256 || size !in 1..128 || difficulty !in 1..8) return null
        val answers = LongArray(count) { -1 }
        IntStream.range(0, count).parallel().forEach { index ->
            val salt = prng("$token${index + 1}", size)
            val target = prng("$token${index + 1}d", difficulty)
            answers[index] = solveOne(salt, target) ?: -1
        }
        return if (answers.any { it < 0 }) null else answers.toList()
    }

    fun solveOne(salt: String, target: String, limit: Long = 200_000_000): Long? {
        val nibbles = target.map { char ->
            when (char) {
                in '0'..'9' -> char - '0'
                in 'a'..'f' -> char - 'a' + 10
                else -> return null
            }
        }
        val prefix = salt.toByteArray(Charsets.UTF_8)
        val message = prefix.copyOf(prefix.size + 20)
        val digits = ByteArray(20)
        val digest = MessageDigest.getInstance("SHA-256")
        var nonce = 0L
        while (nonce < limit) {
            // the decimal text of the nonce, after the salt
            var rest = nonce
            var length = 0
            do {
                digits[length++] = (48 + (rest % 10)).toByte()
                rest /= 10
            } while (rest > 0)
            for (position in 0 until length) message[prefix.size + position] = digits[length - 1 - position]
            digest.update(message, 0, prefix.size + length)
            val hash = digest.digest()
            var matches = true
            for (position in nibbles.indices) {
                val byte = hash[position / 2].toInt() and 0xFF
                val nibble = if (position % 2 == 0) byte ushr 4 else byte and 0x0F
                if (nibble != nibbles[position]) {
                    matches = false
                    break
                }
            }
            if (matches) return nonce
            nonce++
        }
        return null
    }
}
