package app.zoeshorsefarm.domain.horse

// Horse name (rule 43): trimmed, 1-16 characters; without a custom name the language default
// applies.

const val NAME_MAX_LENGTH = 16

// The characters JavaScript's String.trim removes (WhiteSpace and LineTerminator of ECMAScript).
private fun isTrimmed(c: Char): Boolean =
    c in '\u0009'..'\u000D' ||
        c == ' ' ||
        c == ' ' ||
        c == ' ' ||
        c in ' '..' ' ||
        c == ' ' ||
        c == ' ' ||
        c == ' ' ||
        c == ' ' ||
        c == '　' ||
        c == '﻿'

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
