package app.zoeshorsefarm.application

import app.zoeshorsefarm.application.RideSound.LANDING
import app.zoeshorsefarm.application.RideSound.RAIL_DOWN
import app.zoeshorsefarm.application.RideSound.TAKEOFF
import app.zoeshorsefarm.domain.sim.SimEvent
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertSame

class RideSoundsTest {
    private fun takeoff(
        id: String,
        dir: Int = 1,
    ) = SimEvent.Takeoff(id, dir, self = false, risk = 0.0)

    private fun railDown(
        id: String,
        rail: Int = 0,
        dir: Int = 1,
    ) = SimEvent.RailDown(id, rail, dir)

    private fun sounds(commands: List<RideCommand.Sound>) = commands.map { it.sound }

    @Test
    fun mapsTakeoffLandingAndRailDownEventsToSoundCommandsInEventOrder() {
        val mapper = SoundMapper()
        val out =
            mapper.commandsFor(
                listOf(
                    takeoff("a"),
                    railDown("a"),
                    SimEvent.Landed("a", 1, knocked = true),
                    SimEvent.Hop,
                    SimEvent.Swerve("a"),
                ),
            )
        assertEquals(listOf(RideCommand.Sound(TAKEOFF), RideCommand.Sound(RAIL_DOWN), RideCommand.Sound(LANDING)), out)
    }

    @Test
    fun returnsOneSharedEmptyListWhenNoSoundIsDue() {
        val mapper = SoundMapper()
        val a = mapper.commandsFor(listOf(SimEvent.Hop))
        assertEquals(0, a.size)
        assertSame(a, mapper.commandsFor(emptyList()))
    }

    @Test
    fun playsAtMostOneRailDownSoundPerElementPerJump() {
        val mapper = SoundMapper()
        val out = mapper.commandsFor(listOf(takeoff("o"), railDown("o", rail = 0), railDown("o", rail = 1)))
        assertEquals(listOf(TAKEOFF, RAIL_DOWN), sounds(out))
    }

    @Test
    fun dedupesAcrossStepsOfTheSameJumpButSoundsAgainAtTheNextJump() {
        val mapper = SoundMapper()
        assertEquals(listOf(TAKEOFF), sounds(mapper.commandsFor(listOf(takeoff("o")))))
        assertEquals(listOf(RAIL_DOWN), sounds(mapper.commandsFor(listOf(railDown("o", rail = 0)))))
        assertEquals(emptyList(), sounds(mapper.commandsFor(listOf(railDown("o", rail = 1)))))
        mapper.commandsFor(listOf(takeoff("o", dir = -1)))
        assertEquals(listOf(RAIL_DOWN), sounds(mapper.commandsFor(listOf(railDown("o", rail = 1, dir = -1)))))
    }

    @Test
    fun keepsASeparateCountForEachElement() {
        val mapper = SoundMapper()
        val out = mapper.commandsFor(listOf(railDown("a"), railDown("b")))
        assertEquals(listOf(RAIL_DOWN, RAIL_DOWN), sounds(out))
    }

    @Test
    fun forgetsEverythingOnReset() {
        val mapper = SoundMapper()
        mapper.commandsFor(listOf(railDown("a")))
        mapper.reset()
        assertEquals(listOf(RAIL_DOWN), sounds(mapper.commandsFor(listOf(railDown("a")))))
    }

    @Test
    fun theSoundsKeepTheNamesTheAudioAdapterPlays() {
        assertEquals(listOf("takeoff", "landing", "railDown", "finishSignal"), RideSound.entries.map { it.id })
    }
}
