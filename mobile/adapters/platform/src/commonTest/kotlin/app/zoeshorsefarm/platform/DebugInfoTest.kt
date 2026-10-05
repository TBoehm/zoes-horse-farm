package app.zoeshorsefarm.platform

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class DescribeErrorTest {
    @Test
    fun `names a throwable with its message`() {
        assertEquals("IllegalStateException: boom", describeError(IllegalStateException("boom")))
    }

    @Test
    fun `names a throwable without a message by its type`() {
        assertEquals("IllegalStateException", describeError(IllegalStateException()))
    }

    @Test
    fun `passes strings through and prints other values`() {
        assertEquals("plain", describeError("plain"))
        assertEquals("42", describeError(42))
        assertEquals("null", describeError(null))
    }

    @Test
    fun `survives values that cannot be turned into text`() {
        val bad =
            object {
                override fun toString(): String = throw IllegalStateException("no")
            }
        assertEquals("unprintable error", describeError(bad))
    }
}

class ErrorLogTest {
    @Test
    fun `keeps the last entries with their time and newest last`() {
        var t = 0.0
        val log =
            ErrorLog(max = 3, now = {
                t += 1.5
                t
            })
        log.add("first")
        log.add("second")
        assertEquals(listOf(ErrorEntry(1.5, "first"), ErrorEntry(3.0, "second")), log.entries)
        assertEquals(2, log.count)
    }

    @Test
    fun `drops the oldest entry beyond the limit but keeps counting`() {
        val log = ErrorLog(max = 2, now = { 0.0 })
        for (m in listOf("a", "b", "c", "d")) log.add(m)
        assertEquals(listOf("c", "d"), log.entries.map { it.message })
        assertEquals(4, log.count)
    }

    @Test
    fun `shortens very long messages`() {
        val log = ErrorLog(max = 1, maxLength = 10, now = { 0.0 })
        log.add("x".repeat(50))
        assertEquals(10, log.entries[0].message.length)
        assertTrue(log.entries[0].message.endsWith("…"))
    }

    @Test
    fun `keeps the last 5 by default`() {
        val log = ErrorLog(now = { 0.0 })
        for (i in 0 until 8) log.add("e$i")
        assertEquals(listOf("e3", "e4", "e5", "e6", "e7"), log.entries.map { it.message })
    }

    @Test
    fun `records a throwable as its description`() {
        val log = ErrorLog(now = { 0.0 })
        log.record(IllegalArgumentException("bad"))
        assertEquals("IllegalArgumentException: bad", log.entries.single().message)
    }
}

class DebugTextTest {
    @Test
    fun `formats memory sizes in whole megabytes`() {
        assertEquals("unknown", formatMemory(null))
        assertEquals("3072 MB", formatMemory(3L * 1024 * 1024 * 1024))
        assertEquals("1 MB", formatMemory(1024L * 1024 + 5))
    }

    @Test
    fun `describes the device on one line`() {
        val info =
            DeviceInfo(
                totalMemoryBytes = 4L * 1024 * 1024 * 1024,
                cores = 6,
                touch = true,
                screenWidthPx = 1170,
                screenHeightPx = 2532,
                gpuName = "Apple GPU",
            )
        assertEquals("4096 MB, 6 cores, touch, 1170x2532 px, Apple GPU", describeDevice(info))
    }

    @Test
    fun `leaves out what is unknown`() {
        val info =
            DeviceInfo(
                totalMemoryBytes = null,
                cores = 4,
                touch = false,
                screenWidthPx = null,
                screenHeightPx = null,
                gpuName = null,
            )
        assertEquals("memory unknown, 4 cores, no touch, screen unknown, GPU unknown", describeDevice(info))
    }
}
