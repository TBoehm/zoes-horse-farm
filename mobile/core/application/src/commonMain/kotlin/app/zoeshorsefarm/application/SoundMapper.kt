package app.zoeshorsefarm.application

import app.zoeshorsefarm.domain.sim.SimEvent

// Sound commands of a ride: which sim events are heard (the audio adapter plays the names).
// No platform audio here.

// Shared result for the (common) steps without any sound: no allocation per frame.
private val NO_SOUNDS: List<RideCommand.Sound> = emptyList()

class SoundMapper {
    // elements whose rail-down sound already played in the current jump
    private val railSounded = HashSet<String>()

    /** Sound commands for the events of one step (a jump makes at most one rail-down sound). */
    fun commandsFor(events: List<SimEvent>): List<RideCommand.Sound> {
        var commands: ArrayList<RideCommand.Sound>? = null
        for (i in events.indices) {
            val sound =
                when (val e = events[i]) {
                    is SimEvent.Takeoff -> {
                        railSounded.remove(e.elementId)
                        RideSound.TAKEOFF
                    }

                    is SimEvent.RailDown -> {
                        if (railSounded.add(e.elementId)) RideSound.RAIL_DOWN else null
                    }

                    is SimEvent.Landed -> {
                        RideSound.LANDING
                    }

                    else -> {
                        null
                    }
                }
            if (sound != null) {
                val list = commands ?: ArrayList<RideCommand.Sound>().also { commands = it }
                list.add(RideCommand.Sound(sound))
            }
        }
        return commands ?: NO_SOUNDS
    }

    fun reset() {
        railSounded.clear()
    }
}
