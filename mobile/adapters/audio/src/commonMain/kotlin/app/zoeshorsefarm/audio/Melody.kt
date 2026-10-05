// Menu melody as data: 16 bars in C major, 100 bpm, eighth-note grid (8 steps per bar).
// Melody, bass, light arpeggio accompaniment and a quiet brush tick on beats 2 and 4.
package app.zoeshorsefarm.audio

const val BPM = 100
const val STEPS_PER_BAR = 8
const val STEP_SECONDS = 60.0 / BPM / 2

private val SEMITONES = mapOf('C' to 0, 'D' to 2, 'E' to 4, 'F' to 5, 'G' to 7, 'A' to 9, 'B' to 11)
private val NOTE_PATTERN = Regex("^([A-G])([#b]?)(-?[0-9])$")

/** 'C5' -> 72 (C4 = 60), with '#' and 'b'. */
fun parseNote(name: String): Int {
    val m = requireNotNull(NOTE_PATTERN.matchEntire(name)) { "Invalid note: $name" }
    val accidental =
        when (m.groupValues[2]) {
            "#" -> 1
            "b" -> -1
            else -> 0
        }
    val octave = m.groupValues[3].toInt()
    return (octave + 1) * 12 + SEMITONES.getValue(m.groupValues[1][0]) + accidental
}

/** A note or rest of a bar. [midi] is null for a rest. */
data class BarNote(
    val start: Int,
    val steps: Int,
    val midi: Int?,
)

/** 'E5:2 G5:2 -:4' -> notes with their start step; "-" is a rest. */
fun parseBar(text: String): List<BarNote> {
    val notes = mutableListOf<BarNote>()
    var start = 0
    for (token in text.trim().split(Regex("\\s+"))) {
        val (name, len) = token.split(':')
        val steps = len.toInt()
        notes += BarNote(start, steps, if (name == "-") null else parseNote(name))
        start += steps
    }
    return notes
}

/** bass: root and fifth (MIDI); arp: two chord tones for the accompaniment. */
private class Chord(
    val bass: IntArray,
    val arp: IntArray,
)

private val CHORDS =
    mapOf(
        "C" to Chord(intArrayOf(48, 43), intArrayOf(64, 67)),
        "Am" to Chord(intArrayOf(45, 52), intArrayOf(60, 64)),
        "F" to Chord(intArrayOf(41, 48), intArrayOf(57, 60)),
        "G" to Chord(intArrayOf(43, 50), intArrayOf(59, 62)),
        "Dm" to Chord(intArrayOf(50, 45), intArrayOf(57, 62)),
    )

/** Per bar: melody and chord(s); two chords = one per half bar. */
class Bar(
    val melody: String,
    val chords: String,
)

val BARS =
    listOf(
        Bar("E5:2 G5:2 E5:1 D5:1 C5:2", "C"),
        Bar("A4:2 C5:2 E5:3 D5:1", "Am"),
        Bar("C5:2 A4:2 F4:2 A4:2", "F"),
        Bar("D5:3 B4:1 G4:2 -:2", "G"),
        Bar("E5:2 G5:2 E5:1 D5:1 C5:2", "C"),
        Bar("E5:2 D5:2 C5:2 A4:2", "Am"),
        Bar("D5:2 F5:2 D5:2 A4:2", "Dm"),
        Bar("D5:2 B4:2 G4:4", "G"),
        Bar("A5:2 C6:2 A5:2 F5:2", "F"),
        Bar("E5:2 G5:2 C6:4", "C"),
        Bar("A5:2 F5:2 D5:2 F5:2", "Dm"),
        Bar("G5:2 F5:2 D5:4", "G"),
        Bar("E5:2 G5:2 E5:1 D5:1 C5:2", "C"),
        Bar("A4:2 C5:2 E5:2 D5:2", "Am"),
        Bar("F5:2 E5:2 D5:2 B4:2", "F G"),
        Bar("C5:6 -:2", "C"),
    )

val LOOP_STEPS = BARS.size * STEPS_PER_BAR

enum class MusicVoice { Melody, Bass, Arp, Tick }

/** A note that starts on a loop step: [midi] pitch (0 for the tick), [steps] length in steps. */
class MusicEvent(
    val voice: MusicVoice,
    val midi: Int,
    val steps: Int,
)

/** One list of events per step of the loop. */
fun buildLoop(): List<List<MusicEvent>> {
    val steps = List(LOOP_STEPS) { mutableListOf<MusicEvent>() }
    BARS.forEachIndexed { b, bar ->
        val base = b * STEPS_PER_BAR
        for (n in parseBar(bar.melody)) {
            if (n.midi != null) steps[base + n.start] += MusicEvent(MusicVoice.Melody, n.midi, n.steps)
        }
        val names = bar.chords.split(' ')
        val first = CHORDS.getValue(names[0])
        val second = CHORDS.getValue(names.getOrElse(1) { names[0] })
        val isLast = b == BARS.size - 1

        steps[base] += MusicEvent(MusicVoice.Bass, first.bass[0], if (isLast) 6 else 3)
        if (!isLast) {
            val midi = if (second === first) second.bass[1] else second.bass[0]
            steps[base + 4] += MusicEvent(MusicVoice.Bass, midi, 3)
            val arpSteps = listOf(1 to first, 3 to first, 5 to second, 7 to second)
            for ((i, chord) in arpSteps) {
                steps[base + i] += MusicEvent(MusicVoice.Arp, chord.arp[if (i % 4 == 1) 0 else 1], 2)
            }
        }
        steps[base + 2] += MusicEvent(MusicVoice.Tick, 0, 1)
        steps[base + 6] += MusicEvent(MusicVoice.Tick, 0, 1)
    }
    return steps
}
