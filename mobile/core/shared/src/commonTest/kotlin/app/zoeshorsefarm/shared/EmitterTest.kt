package app.zoeshorsefarm.shared

import kotlin.test.Test
import kotlin.test.assertEquals

class EmitterTest {
    @Test
    fun deliversThePayloadToEveryHandlerOfTheEventTypeOnly() {
        val emitter = createEmitter<Any?>()
        val a = mutableListOf<Any?>()
        val b = mutableListOf<Any?>()
        val other = mutableListOf<Any?>()
        emitter.on("x") { a.add(it) }
        emitter.on("x") { b.add(it) }
        emitter.on("y") { other.add(it) }
        emitter.emit("x", 1)
        assertEquals(listOf<Any?>(1), a)
        assertEquals(listOf<Any?>(1), b)
        assertEquals(emptyList(), other)
    }

    @Test
    fun ignoresAnEventWithoutHandlers() {
        createEmitter<Any?>().emit("nobody", 1)
    }

    @Test
    fun callsAHandlerOncePerRegistrationTheSameFunctionTwiceCountsOnce() {
        val emitter = createEmitter<Any?>()
        var calls = 0
        val fn: (Any?) -> Unit = { calls += 1 }
        emitter.on("x", fn)
        emitter.on("x", fn)
        emitter.emit("x", null)
        assertEquals(1, calls)
    }

    @Test
    fun stopsDeliveringAfterUnsubscribeAndUnsubscribingTwiceIsHarmless() {
        val emitter = createEmitter<Any?>()
        val seen = mutableListOf<Any?>()
        val off = emitter.on("x") { seen.add(it) }
        emitter.emit("x", 1)
        off()
        off()
        emitter.emit("x", 2)
        assertEquals(listOf<Any?>(1), seen)
    }

    @Test
    fun onlyRemovesItsOwnHandler() {
        val emitter = createEmitter<Any?>()
        val seen = mutableListOf<String>()
        val off = emitter.on("x") { seen.add("a") }
        emitter.on("x") { seen.add("b") }
        off()
        emitter.emit("x", null)
        assertEquals(listOf("b"), seen)
    }

    @Test
    fun worksOnASnapshotWhileEmittingRemovingOrAddingHandlersAffectsTheNextEmit() {
        val emitter = createEmitter<Any?>()
        val seen = mutableListOf<String>()
        lateinit var offSecond: () -> Unit
        emitter.on("x") {
            seen.add("first")
            offSecond()
            emitter.on("x") { seen.add("late") }
        }
        offSecond = emitter.on("x") { seen.add("second") }
        emitter.emit("x", null)
        assertEquals(listOf("first", "second"), seen)
        seen.clear()
        emitter.emit("x", null)
        assertEquals(listOf("first", "late"), seen)
    }

    @Test
    fun allowsAHandlerToUnsubscribeItselfWhileEmitting() {
        val emitter = createEmitter<Any?>()
        var calls = 0
        lateinit var off: () -> Unit
        off =
            emitter.on("x") {
                calls += 1
                off()
            }
        emitter.emit("x", null)
        emitter.emit("x", null)
        assertEquals(1, calls)
    }
}
