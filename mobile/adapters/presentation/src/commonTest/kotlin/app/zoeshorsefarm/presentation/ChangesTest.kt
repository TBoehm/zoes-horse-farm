package app.zoeshorsefarm.presentation

import kotlin.test.Test
import kotlin.test.assertEquals

class ChangesTest {
    private val changes = Changes()

    @Test
    fun callsEveryListenerInTheOrderTheyWereAdded() {
        val log = mutableListOf<String>()
        changes.listen { log += "a" }
        changes.listen { log += "b" }
        changes.fire()
        assertEquals(listOf("a", "b"), log)
    }

    @Test
    fun anUnsubscribedListenerIsNotCalledAnyMore() {
        val log = mutableListOf<String>()
        val offA = changes.listen { log += "a" }
        changes.listen { log += "b" }
        offA()
        offA() // twice is harmless
        changes.fire()
        assertEquals(listOf("b"), log)
    }

    @Test
    fun aListenerMayUnsubscribeItselfAndOthersWhileItIsCalled() {
        val log = mutableListOf<String>()
        lateinit var offA: () -> Unit
        lateinit var offB: () -> Unit
        offA =
            changes.listen {
                log += "a"
                offA()
                offB()
            }
        offB = changes.listen { log += "b" }
        changes.fire()
        // the call in progress still reaches every listener of the snapshot, the next one none
        assertEquals(listOf("a", "b"), log)
        changes.fire()
        assertEquals(listOf("a", "b"), log)
    }

    @Test
    fun clearDropsEveryListener() {
        var calls = 0
        changes.listen { calls++ }
        changes.clear()
        changes.fire()
        assertEquals(0, calls)
    }

    @Test
    fun theSameListenerAddedTwiceIsCalledTwice() {
        var calls = 0
        val listener: () -> Unit = { calls++ }
        changes.listen(listener)
        changes.listen(listener)
        changes.fire()
        assertEquals(2, calls)
    }
}
