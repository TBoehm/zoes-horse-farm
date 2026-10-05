package app.zoeshorsefarm.domain.horse

// Horse name (rule 43): trimmed, 1-16 characters; without a custom name the language default
// applies.

const val NAME_MAX_LENGTH = 16

// The characters JavaScript's String.trim removes (WhiteSpace and LineTerminator of ECMAScript),
// as code points: tab..CR, space, NBSP, Ogham space, en quad..hair space, LS, PS, narrow NBSP,
// medium math space, ideographic space and the byte order mark.
private val TRIMMED_RANGES =
    listOf(0x09..0x0D, 0x20..0x20, 0xA0..0xA0, 0x1680..0x1680, 0x2000..0x200A, 0x2028..0x2029) +
        listOf(0x202F..0x202F, 0x205F..0x205F, 0x3000..0x3000, 0xFEFF..0xFEFF)

private fun isTrimmed(c: Char): Boolean = TRIMMED_RANGES.any { c.code in it }

/** Number of characters as the player sees them: surrogate pairs count once. */
private fun codePointCount(s: String): Int {
    var count = 0
    var i = 0
    while (i < s.length) {
        val isPair = s[i].isHighSurrogate() && i + 1 < s.length && s[i + 1].isLowSurrogate()
        i += if (isPair) 2 else 1
        count++
    }
    return count
}

/** Returns the cleaned name, or null if it is invalid. */
fun cleanName(input: String?): String? {
    if (input == null) return null
    val name = input.trim(::isTrimmed)
    val length = codePointCount(name)
    return if (length in 1..NAME_MAX_LENGTH) name else null
}
