package app.zoeshorsefarm.presentation

import app.zoeshorsefarm.application.testing.ManualClock
import kotlin.test.Test
import kotlin.test.assertEquals

class UiSchedulerTest {
    private val clock = ManualClock()
    private val scheduler = UiScheduler(clock)
    private val log = mutableListOf<String>()

    private fun advance(ms: Long) {
        clock.advance(ms)
        scheduler.tick()
    }

    @Test
    fun runsATaskOnlyOnceItsDelayIsOver() {
        scheduler.postDelayed(100) { log += "a" }
        advance(99)
        assertEquals(emptyList(), log)
        advance(1)
        assertEquals(listOf("a"), log)
        advance(1000)
        assertEquals(listOf("a"), log)
    }

    @Test
    fun runsDueTasksInTheOrderOfTheirDueTimeAndFifoOnTies() {
        scheduler.postDelayed(300) { log += "c" }
        scheduler.postDelayed(100) { log += "a1" }
        scheduler.postDelayed(100) { log += "a2" }
        scheduler.postDelayed(200) { log += "b" }
        advance(1000)
        assertEquals(listOf("a1", "a2", "b", "c"), log)
    }

    @Test
    fun aCanceledTaskNeverRuns() {
        val task = scheduler.postDelayed(100) { log += "a" }
        task.cancel()
        advance(200)
        assertEquals(emptyList(), log)
        assertEquals(0, scheduler.pending)
    }

    @Test
    fun aTaskThatSchedulesCountsFromItsOwnDueTimeNotFromTheLateTick() {
        scheduler.postDelayed(100) {
            log += "first"
            scheduler.postDelayed(100) { log += "second" }
        }
        advance(250) // a late tick: the second task is due at 200 and runs in the same tick
        assertEquals(listOf("first", "second"), log)
    }

    @Test
    fun aTaskScheduledOutsideATickCountsFromNow() {
        advance(500)
        scheduler.postDelayed(100) { log += "a" }
        advance(99)
        assertEquals(emptyList(), log)
        advance(1)
        assertEquals(listOf("a"), log)
    }

    @Test
    fun aTaskCanCancelAnotherOneThatIsDueAtTheSameTime() {
        lateinit var other: UiTask
        scheduler.postDelayed(100) {
            log += "first"
            other.cancel()
        }
        other = scheduler.postDelayed(100) { log += "second" }
        advance(100)
        assertEquals(listOf("first"), log)
    }

    @Test
    fun clearDropsEveryTask() {
        scheduler.postDelayed(100) { log += "a" }
        scheduler.clear()
        advance(200)
        assertEquals(emptyList(), log)
    }

    @Test
    fun tellsWhenATaskIsPostedAndWhetherItIsIdle() {
        var posted = 0
        scheduler.onPosted = { posted++ }
        assertEquals(true, scheduler.isIdle)

        val task = scheduler.postDelayed(100) { log += "a" }
        assertEquals(1, posted)
        assertEquals(false, scheduler.isIdle)

        task.cancel()
        assertEquals(false, scheduler.isIdle) // dropped by the next tick
        advance(1)
        assertEquals(true, scheduler.isIdle)
    }
}
