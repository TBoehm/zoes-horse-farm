package app.zoeshorsefarm.audio

import com.sun.management.ThreadMXBean
import java.lang.management.ManagementFactory
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicReference
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

class JvmAudioTest {
    @Test
    fun floatToPcm16InterleavesLittleEndianAndClips() {
        val left = floatArrayOf(0f, 1f, -1f, 2f)
        val right = floatArrayOf(0.5f, -0.5f, -3f, 0f)
        val out = ByteArray(16)
        assertEquals(16, floatToPcm16(left, right, 4, out))

        fun sample(index: Int) =
            ((out[index * 2 + 1].toInt() shl 8) or (out[index * 2].toInt() and 0xff)).toShort().toInt()
        assertEquals(listOf(0, 16383, 32767, -16383, -32767, -32767, 32767, 0), (0 until 8).map(::sample))
    }

    @Test
    fun theJvmSchedulerRunsADelayedTaskOnceAndHonoursCancel() {
        val scheduler = JvmScheduler()
        val ran = AtomicInteger()
        val latch = CountDownLatch(1)
        scheduler.postDelayed(20) {
            ran.incrementAndGet()
            latch.countDown()
        }
        scheduler.postDelayed(20) { ran.addAndGet(100) }.cancel()
        assertTrue(latch.await(2, TimeUnit.SECONDS))
        Thread.sleep(80)
        assertEquals(1, ran.get())
    }

    @Test
    fun theFacadeSurvivesTheGameThreadAndTheAudioThreadRunningAtTheSameTime() {
        val output = FakePcmOutput()
        val audio = Audio(AudioSettings(), AudioPlatform({ output }, ManualScheduler()))
        audio.setMusicWanted(true)
        audio.unlock()
        val failure = AtomicReference<Throwable?>(null)
        val stop = AtomicInteger(0)
        val audioThread =
            Thread {
                try {
                    while (stop.get() == 0) output.pump(0.05)
                } catch (e: Throwable) {
                    failure.set(e)
                }
            }
        audioThread.start()
        try {
            repeat(400) { i ->
                audio.sfx.hoof("canter")
                audio.sfx.landing()
                audio.setVolumes(sfxVolume = (i % 10) / 10.0, musicVolume = (i % 7) / 7.0)
                if (i % 50 == 0) audio.setPaused(i % 100 == 0)
                if (i % 80 == 0) audio.setMusicWanted(i % 160 != 0)
                audio.getState()
                Thread.sleep(1)
            }
        } finally {
            stop.set(1)
            audioThread.join(5000)
        }
        assertNull(failure.get())
        assertTrue(audio.getState().sfxCounts.hoof > 0)
    }

    @Test
    fun renderingABlockDoesNotAllocate() {
        val engine = AudioEngine(44100, AudioSettings(), false, AudioLock())
        engine.startMusic()
        engine.playSfx { v, out, t -> SfxVoices.railDown(v, out, t) }
        val left = FloatArray(512)
        val right = FloatArray(512)
        // warm up: the JIT compiles the render path and the melody has started all its voice types
        repeat(600) {
            engine.render(left, right, 512)
        }
        val threads = ManagementFactory.getThreadMXBean() as ThreadMXBean
        val before = threads.currentThreadAllocatedBytes
        repeat(400) { engine.render(left, right, 512) }
        val allocated = threads.currentThreadAllocatedBytes - before
        // 400 blocks of 512 frames = 4.6 s of music with a few hundred notes started on the way
        assertTrue(allocated < 16 * 1024, "allocated $allocated bytes while rendering")
    }
}
