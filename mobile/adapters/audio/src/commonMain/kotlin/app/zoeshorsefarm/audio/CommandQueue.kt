@file:OptIn(ExperimentalAtomicApi::class)

package app.zoeshorsefarm.audio

import kotlin.concurrent.atomics.AtomicInt
import kotlin.concurrent.atomics.ExperimentalAtomicApi

/**
 * Lock-free single-producer/single-consumer queue of small commands from the game thread to the
 * audio thread. The audio thread never waits: [drain] only reads two atomics and the preallocated
 * slots, nothing is allocated per command. A command is a kind, an int argument and two doubles.
 *
 * Exactly one thread may call [offer] at a time (the facade serialises the game thread) and exactly
 * one thread may call [drain]. A full queue refuses the command and counts it in [dropped].
 */
internal class CommandQueue(
    capacity: Int,
) {
    private val size = roundUpToPowerOfTwo(capacity)
    private val mask = size - 1
    private val kinds = IntArray(size)
    private val args = IntArray(size)
    private val firsts = DoubleArray(size)
    private val seconds = DoubleArray(size)

    // Counters only grow (and wrap in Int arithmetic); the slot is the counter and the mask.
    private val written = AtomicInt(0)
    private val read = AtomicInt(0)
    private val droppedCount = AtomicInt(0)

    /** Commands that were refused because the queue was full (debug counter). */
    val dropped: Int get() = droppedCount.load()

    /** Producer: stores a command. Returns false (and counts it) when the queue is full. */
    fun offer(
        kind: Int,
        arg: Int,
        first: Double,
        second: Double,
    ): Boolean {
        val w = written.load()
        if (w - read.load() >= size) {
            droppedCount.store(droppedCount.load() + 1) // only the producer writes it
            return false
        }
        val slot = w and mask
        kinds[slot] = kind
        args[slot] = arg
        firsts[slot] = first
        seconds[slot] = second
        written.store(w + 1) // publishes the slot to the consumer
        return true
    }

    /** Consumer: hands every queued command to [handler] in order. Does not allocate. */
    inline fun drain(handler: (kind: Int, arg: Int, first: Double, second: Double) -> Unit) {
        var r = readPosition()
        val end = writtenPosition()
        while (r != end) {
            handler(kindAt(r), argAt(r), firstAt(r), secondAt(r))
            r++
        }
        finishRead(r)
    }

    // Accessors for the inline drain (public-API inline functions may not touch private members)
    fun readPosition(): Int = read.load()

    fun writtenPosition(): Int = written.load()

    fun kindAt(position: Int): Int = kinds[position and mask]

    fun argAt(position: Int): Int = args[position and mask]

    fun firstAt(position: Int): Double = firsts[position and mask]

    fun secondAt(position: Int): Double = seconds[position and mask]

    fun finishRead(position: Int) = read.store(position)

    private fun roundUpToPowerOfTwo(n: Int): Int {
        var p = 1
        while (p < n) p = p shl 1
        return p
    }
}
