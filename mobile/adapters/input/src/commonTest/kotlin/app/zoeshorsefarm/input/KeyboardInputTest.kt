package app.zoeshorsefarm.input

import app.zoeshorsefarm.platform.GameKey
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class KeyboardInputTest {
    private val idle = InputState()

    // keyboard input (rule 8)

    @Test
    fun mapsWasdAndArrowKeysToSteerAndThrottle() {
        val kb = KeyboardInput()
        kb.onKeyDown(GameKey.KEY_D)
        kb.onKeyDown(GameKey.ARROW_UP)
        kb.poll().let {
            assertEquals(1.0, it.steer)
            assertEquals(1.0, it.throttle)
        }
        kb.onKeyUp(GameKey.KEY_D)
        kb.onKeyDown(GameKey.ARROW_LEFT)
        kb.onKeyUp(GameKey.ARROW_UP)
        kb.onKeyDown(GameKey.KEY_S)
        kb.poll().let {
            assertEquals(-1.0, it.steer)
            assertEquals(-1.0, it.throttle)
        }
    }

    @Test
    fun oppositeKeysCancelEachOther() {
        val kb = KeyboardInput()
        kb.onKeyDown(GameKey.KEY_A)
        kb.onKeyDown(GameKey.ARROW_RIGHT)
        kb.onKeyDown(GameKey.KEY_W)
        kb.onKeyDown(GameKey.ARROW_DOWN)
        assertEquals(idle, kb.poll())
    }

    @Test
    fun jumpPauseAndCameraAreEdgesThatPollConsumes() {
        val kb = KeyboardInput()
        kb.onKeyDown(GameKey.SPACE)
        kb.onKeyDown(GameKey.ESCAPE)
        kb.onKeyDown(GameKey.KEY_C)
        kb.poll().let {
            assertTrue(it.jump)
            assertTrue(it.pause)
            assertTrue(it.camera)
        }
        assertEquals(idle, kb.poll())
    }

    @Test
    fun clearEdgesDropsPendingEdgesUsedWhenTheRideResumes() {
        val kb = KeyboardInput()
        kb.onKeyDown(GameKey.SPACE)
        kb.onKeyDown(GameKey.KEY_C)
        kb.clearEdges()
        kb.poll().let {
            assertFalse(it.jump)
            assertFalse(it.camera)
        }
    }

    @Test
    fun consumesHandledKeysWhileActiveAndReportsUnknownCodesAsNoGameKey() {
        val kb = KeyboardInput()
        assertTrue(kb.onKeyDown(GameKey.SPACE))
        assertTrue(kb.onKeyDown(GameKey.ARROW_DOWN))
        assertNull(GameKey.fromCode("KeyX"))
        assertNull(GameKey.fromCode("Enter"))
    }

    @Test
    fun aRepeatedKeyDownIsConsumedButAddsNoSecondEdge() {
        val kb = KeyboardInput()
        kb.onKeyDown(GameKey.SPACE)
        kb.poll()
        assertTrue(kb.onKeyDown(GameKey.SPACE, repeat = true))
        assertFalse(kb.poll().jump)
    }

    @Test
    fun everyGameKeyHasAUniqueWebCodeThatRoundTrips() {
        assertEquals(
            GameKey.entries.size,
            GameKey.entries
                .map { it.code }
                .toSet()
                .size,
        )
        for (key in GameKey.entries) assertEquals(key, GameKey.fromCode(key.code))
        assertEquals("Space", GameKey.SPACE.code)
        assertEquals("ShiftLeft", GameKey.SHIFT_LEFT.code)
    }

    // keys it must not consume

    @Test
    fun doesNothingWhileTheRideIsNotActiveAndDoesNotConsumeKeys() {
        var active = false
        val kb = KeyboardInput(isActive = { active })
        assertFalse(kb.onKeyDown(GameKey.SPACE))
        assertFalse(kb.onKeyDown(GameKey.ESCAPE))
        assertFalse(kb.onKeyDown(GameKey.KEY_W))
        active = true
        kb.poll().let {
            assertFalse(it.jump)
            assertFalse(it.pause)
            assertEquals(0.0, it.throttle)
        }
    }

    @Test
    fun releasingAKeyAlwaysWorksEvenWhileInactive() {
        var active = true
        val kb = KeyboardInput(isActive = { active })
        kb.onKeyDown(GameKey.KEY_W)
        active = false
        kb.onKeyUp(GameKey.KEY_W)
        active = true
        assertEquals(0.0, kb.poll().throttle)
    }

    // gallop latch (rule 9)

    @Test
    fun shiftHeldGivesGallopAndALatchNeedsReleaseAndANewPress() {
        val kb = KeyboardInput()
        kb.onKeyDown(GameKey.SHIFT_LEFT)
        assertTrue(kb.poll().gallop)
        kb.latchGallop()
        assertFalse(kb.poll().gallop)
        kb.onKeyUp(GameKey.SHIFT_LEFT)
        assertFalse(kb.poll().gallop)
        kb.onKeyDown(GameKey.SHIFT_LEFT)
        assertTrue(kb.poll().gallop)
    }

    @Test
    fun latchingWithoutShiftHeldDoesNothing() {
        val kb = KeyboardInput()
        kb.latchGallop()
        kb.onKeyDown(GameKey.SHIFT_RIGHT)
        assertTrue(kb.poll().gallop)
    }

    @Test
    fun latchOnNextShiftPressCatchesTheShiftPressThatFollowsInTheSameEvent() {
        val kb = KeyboardInput()
        kb.latchGallop(onNextShiftPress = true)
        kb.onKeyDown(GameKey.SHIFT_LEFT)
        assertFalse(kb.poll().gallop)
        kb.onKeyUp(GameKey.SHIFT_LEFT)
        kb.onKeyDown(GameKey.SHIFT_LEFT)
        assertTrue(kb.poll().gallop)
    }

    @Test
    fun thePendingLatchExpiresWithTheNextPoll() {
        val kb = KeyboardInput()
        kb.latchGallop(onNextShiftPress = true)
        kb.poll()
        kb.onKeyDown(GameKey.SHIFT_LEFT)
        assertTrue(kb.poll().gallop)
    }

    @Test
    fun thePendingLatchExpiresWithAnyOtherGameKey() {
        val kb = KeyboardInput()
        kb.latchGallop(onNextShiftPress = true)
        kb.onKeyDown(GameKey.KEY_W)
        kb.onKeyDown(GameKey.SHIFT_LEFT)
        assertTrue(kb.poll().gallop)
    }

    @Test
    fun releasingOneShiftKeyWhileTheOtherIsHeldKeepsTheLatch() {
        val kb = KeyboardInput()
        kb.onKeyDown(GameKey.SHIFT_LEFT)
        kb.onKeyDown(GameKey.SHIFT_RIGHT)
        kb.latchGallop()
        kb.onKeyUp(GameKey.SHIFT_LEFT)
        assertTrue(kb.shiftHeld)
        assertFalse(kb.poll().gallop)
    }

    @Test
    fun aFocusLossReleasesEverything() {
        val kb = KeyboardInput()
        kb.onKeyDown(GameKey.SHIFT_LEFT)
        kb.onKeyDown(GameKey.KEY_W)
        kb.latchGallop()
        kb.onFocusLost()
        kb.poll().let {
            assertFalse(it.gallop)
            assertEquals(0.0, it.throttle)
        }
        kb.onKeyDown(GameKey.SHIFT_LEFT)
        assertTrue(kb.poll().gallop)
    }
}
