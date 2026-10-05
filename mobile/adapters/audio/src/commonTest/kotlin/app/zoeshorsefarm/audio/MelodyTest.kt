package app.zoeshorsefarm.audio

import kotlin.test.Test
import kotlin.test.assertContains
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

class MelodyTest {
    @Test
    fun parseNoteConvertsNoteNamesToMidi() {
        assertEquals(60, parseNote("C4"))
        assertEquals(69, parseNote("A4"))
        assertEquals(72, parseNote("C5"))
        assertEquals(66, parseNote("F#4"))
        assertEquals(58, parseNote("Bb3"))
    }

    @Test
    fun parseNoteRejectsInvalidInput() {
        assertFailsWith<IllegalArgumentException> { parseNote("H4") }
    }

    @Test
    fun parseBarReadsNotesAndRestsWithTheirStartStep() {
        assertEquals(
            listOf(
                BarNote(start = 0, steps = 2, midi = 76),
                BarNote(start = 2, steps = 2, midi = null),
                BarNote(start = 4, steps = 4, midi = 72),
            ),
            parseBar("E5:2 -:2 C5:4"),
        )
    }

    @Test
    fun melodyDataHasSixteenBarsOfEightStepsAt100Bpm() {
        assertEquals(16, BARS.size)
        assertEquals(16 * STEPS_PER_BAR, LOOP_STEPS)
        assertEquals(100, BPM)
        assertEquals(0.3, STEP_SECONDS, 1e-9)
    }

    @Test
    fun everyMelodyBarFillsExactlyEightSteps() {
        for ((i, bar) in BARS.withIndex()) {
            val total = parseBar(bar.melody).sumOf { it.steps }
            assertEquals(STEPS_PER_BAR, total, "bar ${i + 1}")
        }
    }

    @Test
    fun melodyNotesLieInASingableRange() {
        for (bar in BARS) {
            for (n in parseBar(bar.melody)) {
                val midi = n.midi ?: continue
                assertTrue(midi in 65..84)
            }
        }
    }

    @Test
    fun melodyEndsOnTheRootNoteC() {
        val last = assertNotNull(parseBar(BARS.last().melody)[0].midi)
        assertEquals(0, last % 12)
    }

    private val steps = buildLoop()
    private val events = steps.flatten()

    @Test
    fun buildLoopHasOneEntryPerStep() {
        assertEquals(LOOP_STEPS, steps.size)
    }

    @Test
    fun buildLoopContainsAllVoicesWithValidValues() {
        assertEquals(
            setOf(MusicVoice.Arp, MusicVoice.Bass, MusicVoice.Melody, MusicVoice.Tick),
            events.map { it.voice }.toSet(),
        )
        for (e in events) assertTrue(e.steps > 0)
    }

    @Test
    fun everyBarStartsWithBassAndMelodyExceptRests() {
        for (b in BARS.indices) {
            val first = steps[b * STEPS_PER_BAR].map { it.voice }
            assertContains(first, MusicVoice.Bass)
            assertContains(first, MusicVoice.Melody)
        }
    }

    @Test
    fun bassIsLowAndAccompanimentBelowTheMelody() {
        for (e in events) {
            if (e.voice == MusicVoice.Bass) assertTrue(e.midi < 55)
            if (e.voice == MusicVoice.Arp) assertTrue(e.midi < 70)
        }
    }
}
