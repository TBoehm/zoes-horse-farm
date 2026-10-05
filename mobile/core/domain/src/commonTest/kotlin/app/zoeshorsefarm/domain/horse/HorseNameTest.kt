package app.zoeshorsefarm.domain.horse

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class HorseNameTest {
    // Horse name (rule 43)

    @Test
    fun trimsLeadingAndTrailingWhitespace() {
        assertEquals("Luna", cleanName("  Luna "))
    }

    @Test
    fun rejectsEmptyAndTooLongNames() {
        assertNull(cleanName(""))
        assertNull(cleanName("    "))
        assertNull(cleanName("A".repeat(17)))
        assertNull(cleanName(null))
    }

    @Test
    fun allows1To16Characters() {
        assertEquals("A", cleanName("A"))
        assertEquals("B".repeat(16), cleanName("B".repeat(16)))
        assertEquals("Äpfelchen 🐴", cleanName("Äpfelchen 🐴"))
    }

    @Test
    fun countsCharactersAsCodePointsNotUtf16Units() {
        // 16 emoji are 32 UTF-16 units but 16 characters; 17 are too many
        assertEquals("🐴".repeat(16), cleanName("🐴".repeat(16)))
        assertNull(cleanName("🐴".repeat(17)))
    }

    @Test
    fun trimsTheSameWhitespaceAsTheWebApp() {
        // no-break space, ideographic space and the byte order mark count as whitespace in JavaScript
        assertEquals("Luna", cleanName(" 　Luna﻿ "))
        // a control character that JavaScript does not trim stays
        assertEquals("\u001FLuna", cleanName("\u001FLuna"))
    }

    @Test
    fun theLimitIs16() {
        assertEquals(16, NAME_MAX_LENGTH)
    }
}
