package app.zoeshorsefarm.view3d.horse

import app.zoeshorsefarm.application.GraphicsLevel
import app.zoeshorsefarm.domain.sim.Gait
import app.zoeshorsefarm.domain.sim.HopView
import app.zoeshorsefarm.domain.sim.Horse
import app.zoeshorsefarm.domain.sim.JumpPhase
import app.zoeshorsefarm.domain.sim.JumpView
import app.zoeshorsefarm.domain.sim.RefusalType
import app.zoeshorsefarm.domain.sim.RefusalView
import com.sun.management.ThreadMXBean
import java.lang.management.ManagementFactory
import kotlin.test.Test
import kotlin.test.assertTrue

private const val FRAME = 1.0 / 60
private const val CYCLE_FRAMES = 60 * 44
private const val WARM_UP_CYCLES = 3
private const val MEASURED_CYCLES = 6

// The JVM measures the bytes this thread allocated. The grazing horses pick a new spot now and then
// (a few small objects per event), so the limit is an average per frame; a regression that boxes a
// number per call costs about 130 bytes per frame.
private const val ALLOWED_BYTES_PER_FRAME = 16

private fun allocatedBytes(block: () -> Unit): Long {
    val threads = ManagementFactory.getThreadMXBean() as ThreadMXBean
    val before = threads.currentThreadAllocatedBytes
    block()
    return threads.currentThreadAllocatedBytes - before
}

/** Drives one [Horse] through every gait, turns, a jump, a hop, a refusal and a rein-back (no allocation). */
private class RideDriver {
    val state = Horse()
    private val jump = JumpView()
    private val hop = HopView()
    private val refusal = RefusalView()

    private fun set(
        gait: Gait,
        speed: Double,
        turn: Double = 0.0,
    ) {
        state.gait = gait
        state.speed = speed
        state.turnRate = turn
        state.jump = null
        state.hop = null
        state.refusal = null
        state.y = 0.0
    }

    /** Sets the state for [frame] of the cycle (44 s). */
    fun drive(frame: Int) {
        val t = frame * FRAME
        if (t < 24) driveFirstHalf(t) else driveSecondHalf(t)
    }

    private fun driveFirstHalf(t: Double) {
        when {
            t < 4 -> set(Gait.HALT, 0.0)

            // long enough for the idle gestures
            t < 8 -> set(Gait.WALK, 1.5, if (t < 6) 0.5 else -0.5)

            t < 12 -> set(Gait.TROT, 3.2, if (t < 10) -0.6 else 0.6)

            t < 17 -> set(Gait.CANTER, 6.0, if (t < 14) 0.5 else -0.5)

            t < 18 -> driveJump(t - 17, 1.0)

            t < 21 -> set(Gait.TROT, 3.4)

            t < 21.4 -> driveHop((t - 21) / 0.4)

            else -> set(Gait.CANTER, 6.0)
        }
    }

    private fun driveSecondHalf(t: Double) {
        when {
            t < 25 -> driveRefusal(t - 24)
            t < 29 -> set(Gait.HALT, 0.0)
            t < 32 -> set(Gait.BACK, -0.5)
            t < 35 -> set(Gait.HALT, 0.0, 1.4)
            t < 38 -> set(Gait.WALK, 1.5)
            else -> set(Gait.HALT, 0.0)
        }
    }

    private fun driveJump(
        t: Double,
        total: Double,
    ) {
        val s = t / total
        set(Gait.CANTER, 6.0)
        jump.phase =
            when {
                s < 0.2 -> JumpPhase.TAKEOFF
                s < 0.75 -> JumpPhase.FLIGHT
                else -> JumpPhase.LANDING
            }
        jump.progress =
            when (jump.phase) {
                JumpPhase.TAKEOFF -> s / 0.2
                JumpPhase.FLIGHT -> (s - 0.2) / 0.55
                JumpPhase.LANDING -> (s - 0.75) / 0.25
            }
        state.jump = jump
        state.y = 0.9 * kotlin.math.sin(Math.PI * s)
    }

    private fun driveHop(p: Double) {
        set(Gait.TROT, 3.4)
        hop.progress = p
        state.hop = hop
        state.y = 0.2 * kotlin.math.sin(Math.PI * p)
    }

    private fun driveRefusal(p: Double) {
        set(if (p < 0.5) Gait.CANTER else Gait.HALT, kotlin.math.max(0.0, 5.0 - 5.0 * p))
        refusal.type = RefusalType.STOP
        refusal.progress = p
        state.refusal = refusal
    }
}

class HorseViewAllocationTest {
    @Test
    fun aRideThroughEveryGaitJumpAndRefusalDoesNotAllocatePerFrame() {
        val horse = createHorse(quality = GraphicsLevel.MEDIUM)
        val driver = RideDriver()
        // warm up with every gait: the JIT hides the boxing of a code path that has not run yet
        repeat(WARM_UP_CYCLES) {
            for (f in 0 until CYCLE_FRAMES) {
                driver.drive(f)
                horse.update(FRAME, driver.state)
            }
        }
        val allocated =
            allocatedBytes {
                repeat(MEASURED_CYCLES) {
                    for (f in 0 until CYCLE_FRAMES) {
                        driver.drive(f)
                        horse.update(FRAME, driver.state)
                    }
                }
            }
        val frames = MEASURED_CYCLES * CYCLE_FRAMES
        assertTrue(allocated < ALLOWED_BYTES_PER_FRAME * frames, "allocated $allocated bytes in $frames frames")
        horse.dispose()
    }

    @Test
    fun grazingHorsesDoNotAllocatePerFrame() {
        val area = PaddockArea(x = 40.0, z = -20.0, width = 22.0, depth = 14.0, rotation = -0.4)
        val paddock = createGrazingHorses(area, GraphicsLevel.MEDIUM, count = 2)
        repeat(60 * 200) { paddock.update(1.0 / 30) }
        val frames = 60 * 600
        val allocated = allocatedBytes { repeat(frames) { paddock.update(1.0 / 30) } }
        assertTrue(allocated < ALLOWED_BYTES_PER_FRAME * frames, "allocated $allocated bytes in $frames frames")
        paddock.dispose()
    }
}
