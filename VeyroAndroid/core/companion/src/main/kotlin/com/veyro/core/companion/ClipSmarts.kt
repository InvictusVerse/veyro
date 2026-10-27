package com.veyro.core.companion

/**
 * What a copied text is, so the "Copied on PC" notification can offer the right action
 * (ADR-043): Open for a link, Call for a phone number, Email for an address, Copy code for a
 * one-time code. Pure and local: nothing is looked up, nothing leaves the phone.
 */
public object ClipSmarts {

    public enum class Kind { LINK, PHONE, EMAIL, CODE, TEXT }

    /** [value] is what the action works on: the URL, the number, the address or the code. */
    public data class Result(val kind: Kind, val value: String)

    private val LINK = Regex("""(?i)\bhttps?://[^\s<>"']+""")
    private val WWW = Regex("""(?i)(?<![\w/.@])www\.[a-z0-9-]+(\.[a-z0-9-]+)+[^\s<>"']*""")
    private val EMAIL = Regex("""(?i)[a-z0-9._%+-]+@[a-z0-9-]+(\.[a-z0-9-]+)*\.[a-z]{2,}""")
    private val PHONE = Regex("""\+?\(?[0-9][0-9 ().\-]{5,22}[0-9]""")
    private val CODE_HINT = Regex("""(?i)\b(code|otp|verification|verify|passcode|pin|one[- ]time)\b""")
    private val CODE = Regex("""(?<![\d-])\d{4,8}(?![\d-])""")
    private val TRAILING = charArrayOf('.', ',', ')', ']', '"', '\'', ';', ':', '!', '?')

    public fun classify(text: String?): Result {
        val trimmed = text?.trim().orEmpty()
        if (trimmed.isEmpty()) return Result(Kind.TEXT, "")

        // A link anywhere wins: a shared title plus URL is still "a link".
        LINK.find(trimmed)?.let { return Result(Kind.LINK, it.value.trimEnd(*TRAILING)) }
        WWW.find(trimmed)?.let { return Result(Kind.LINK, "https://" + it.value.trimEnd(*TRAILING)) }

        if (EMAIL.matches(trimmed)) return Result(Kind.EMAIL, trimmed)

        if (PHONE.matches(trimmed)) {
            val digits = trimmed.count { it.isDigit() }
            // Short all-digit strings are codes, not numbers.
            val plainDigits = trimmed.all { it.isDigit() }
            if (digits in 7..15 && !(plainDigits && digits <= 8 && !trimmed.startsWith("0"))) {
                return Result(Kind.PHONE, trimmed.filter { it.isDigit() || it == '+' })
            }
        }

        if (trimmed.length <= 8 && trimmed.all { it.isDigit() } && trimmed.length >= 4) {
            return Result(Kind.CODE, trimmed)
        }
        if (trimmed.length <= 300 && CODE_HINT.containsMatchIn(trimmed)) {
            CODE.find(trimmed)?.let { return Result(Kind.CODE, it.value) }
        }
        return Result(Kind.TEXT, trimmed)
    }
}
