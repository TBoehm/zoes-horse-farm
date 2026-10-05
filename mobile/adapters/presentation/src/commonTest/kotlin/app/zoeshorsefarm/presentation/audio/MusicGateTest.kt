package app.zoeshorsefarm.presentation.audio

import app.zoeshorsefarm.presentation.ManualScheduler
import kotlin.test.Test
import kotlin.test.assertEquals

class MusicGateTest {
    private val scheduler = ManualScheduler()
    private val calls = mutableListOf<Boolean>()
    private val gate = MusicGate(scheduler.ui) { calls += it }

    @Test
    fun passesTheMusicWishOfAScreenOnImmediately() {
        gate.onScreen(music = true)
        gate.onScreen(music = false)
        assertEquals(listOf(true, false), calls)
    }

    @Test
    fun startsTheMusicAfterTheDelayOfTheScreenNotBefore() {
        gate.onScreen(music = false)
        calls.clear()
        gate.onScreen(music = true, musicDelayMs = 1500)
        scheduler.advance(1499)
        assertEquals(emptyList(), calls)
        scheduler.advance(1)
        assertEquals(listOf(true), calls)
    }

    @Test
    fun leavingTheScreenBeforeTheDelayIsOverCancelsTheStart() {
        gate.onScreen(music = true, musicDelayMs = 1500)
        scheduler.advance(500)
        gate.onScreen(music = false)
        scheduler.advance(5000)
        assertEquals(listOf(false), calls)
    }

    @Test
    fun aNewerScreenReplacesAPendingDelayedStart() {
        gate.onScreen(music = true, musicDelayMs = 1500)
        scheduler.advance(500)
        gate.onScreen(music = true)
        assertEquals(listOf(true), calls)
        scheduler.advance(5000)
        assertEquals(listOf(true), calls)
    }

    @Test
    fun musicThatAlreadyPlaysIsNotDelayedAgain() {
        gate.onScreen(music = true)
        calls.clear()
        gate.onScreen(music = true, musicDelayMs = 1500)
        assertEquals(listOf(true), calls)
    }

    @Test
    fun disposeCancelsAPendingStart() {
        gate.onScreen(music = true, musicDelayMs = 1500)
        gate.dispose()
        scheduler.advance(5000)
        assertEquals(emptyList(), calls)
    }
}
