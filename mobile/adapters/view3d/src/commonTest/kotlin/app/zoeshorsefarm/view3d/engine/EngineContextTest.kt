package app.zoeshorsefarm.view3d.engine

import app.zoeshorsefarm.application.CrashGuardState
import app.zoeshorsefarm.application.GraphicsLevel
import app.zoeshorsefarm.application.Settings
import app.zoeshorsefarm.application.testing.FakeStore
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

// Loss of the graphics device (rule 4): events, the level rule, the pixel ratio, the hints, the
// bookkeeping for the debug box and the restore watchdog.

class EngineContextTest {
    @Test
    fun announcesTheLossAndTheRestoreAndStopsDrawingInBetween() {
        val rig = EngineRig(GraphicsLevel.LOW)
        rig.run()
        val events = ArrayList<String>()
        rig.engine.onContextLost { events.add("lost") }
        rig.engine.onContextRestored { events.add("restored") }
        rig.frames(2)
        rig.fake.simulateContextLoss()
        assertTrue(rig.engine.contextLost)
        val calls = rig.calls.size
        rig.frames(3)
        assertEquals(2, rig.fake.renderCount)
        assertEquals(calls + 3, rig.calls.size) // the screen's own frame still runs
        rig.fake.simulateContextRestore()
        assertFalse(rig.engine.contextLost)
        rig.frames(1)
        assertEquals(3, rig.fake.renderCount)
        assertEquals(listOf("lost", "restored"), events)
    }

    @Test
    fun aListenerCanUnsubscribe() {
        val rig = EngineRig()
        var count = 0
        val off = rig.engine.onContextLost { count++ }
        rig.fake.simulateContextLoss()
        off()
        rig.fake.simulateContextRestore()
        rig.fake.simulateContextLoss()
        assertEquals(1, count)
    }

    @Test
    fun recompilesTheShadersAfterTheRestore() {
        val rig = EngineRig(GraphicsLevel.MEDIUM)
        val before = rig.fake.compileCount
        rig.fake.simulateContextLoss()
        rig.fake.simulateContextRestore()
        assertEquals(before + 1, rig.fake.compileCount)
    }

    @Test
    fun aLossInTheForegroundSendsTheAutomaticToLowAndSavesIt() {
        val rig = EngineRig(GraphicsLevel.MEDIUM, auto = true)
        rig.fake.simulateContextLoss()
        assertEquals(GraphicsLevel.LOW, rig.engine.level)
        assertEquals(GraphicsLevel.LOW, rig.settings.get().graphicsLevel)
        assertTrue(rig.settings.get().graphicsAuto)
        assertFalse(rig.engine.takeGraphicsHint())
        assertEquals(listOf(GraphicsLevel.MEDIUM), rig.crashGuard.blockedLevels()) // not to climb back to
        assertEquals(
            ChangeKind.LOSS,
            rig.engine
                .diagnostics()
                .lastChange
                ?.kind,
        )
    }

    @Test
    fun theLowerLevelIsAppliedWhileTheDeviceIsLostIncludingThePixelRatio() {
        val rig = EngineRig(GraphicsLevel.HIGH, auto = true)
        assertEquals(2.0, rig.fake.pixelRatio)
        rig.fake.simulateContextLoss()
        assertEquals(1.0, rig.fake.pixelRatio) // a restored device is made at the small size
        assertFalse(rig.fake.shadowsEnabled)
        assertFalse(rig.engine.settling)
    }

    @Test
    fun aManualLevelStaysAndThePlayerGetsTheHintOnce() {
        val rig = EngineRig(GraphicsLevel.HIGH)
        rig.fake.simulateContextLoss()
        assertEquals(GraphicsLevel.HIGH, rig.engine.level)
        assertTrue(rig.engine.takeGraphicsHint())
        assertFalse(rig.engine.takeGraphicsHint()) // consumed
        assertEquals(listOf(GraphicsLevel.HIGH), rig.crashGuard.blockedLevels())
        assertNull(rig.engine.diagnostics().lastChange) // the level did not change
    }

    @Test
    fun aLossAtLowGivesNoHint() {
        val rig = EngineRig(GraphicsLevel.LOW)
        rig.fake.simulateContextLoss()
        assertFalse(rig.engine.takeGraphicsHint())
    }

    @Test
    fun aLossInTheBackgroundSaysNothingAboutTheDevice() {
        val rig = EngineRig(GraphicsLevel.MEDIUM, auto = true)
        rig.engine.setVisible(false)
        rig.now += 60
        rig.fake.simulateContextLoss()
        assertEquals(GraphicsLevel.MEDIUM, rig.engine.level)
        assertTrue(rig.crashGuard.blockedLevels().isEmpty())
        assertFalse(rig.engine.takeGraphicsHint())
    }

    @Test
    fun aLossRightAfterTheAppCameBackSaysNothingEither() {
        val rig = EngineRig(GraphicsLevel.MEDIUM, auto = true)
        rig.engine.setVisible(false)
        rig.now += 10
        rig.engine.setVisible(true)
        rig.now += 1
        rig.fake.simulateContextLoss()
        assertEquals(GraphicsLevel.MEDIUM, rig.engine.level)
        rig.fake.simulateContextRestore()
        rig.now += 5
        rig.fake.simulateContextLoss()
        assertEquals(GraphicsLevel.LOW, rig.engine.level)
    }

    @Test
    fun countsLossesAndRestoresWithTheTimeSinceTheStart() {
        val rig = EngineRig(GraphicsLevel.LOW)
        rig.now = 31.0
        rig.fake.simulateContextLoss()
        rig.now = 35.0
        rig.fake.simulateContextRestore()
        val d = rig.engine.diagnostics()
        assertEquals(1, d.contextLost)
        assertEquals(1, d.contextRestored)
        assertEquals(31.0, d.lostAtS)
        assertEquals(35.0, d.restoredAtS)
    }

    @Test
    fun theGpuNameIsEmptyWhileLostAndTheBudgetNameStaysForTheBox() {
        val rig = EngineRig(GraphicsLevel.LOW)
        rig.fake.simulateContextLoss()
        val d = rig.engine.diagnostics()
        assertEquals("Test GPU", d.budgetGpu)
    }

    @Test
    fun objectsThatLivedOnTheLostDeviceAreNotReleasedWithGpuCalls() {
        val rig = EngineRig(GraphicsLevel.MEDIUM)
        rig.run()
        rig.frames(2)
        var geometry: app.zoeshorsefarm.scene.geometry.Geometry? = null
        rig.engine.horse.group.traverse { node ->
            if (geometry == null && node is app.zoeshorsefarm.scene.graph.SkinnedMesh) geometry = node.geometry
        }
        val body = checkNotNull(geometry)
        rig.fake.simulateContextLoss()
        rig.fake.simulateContextRestore()
        rig.engine.horse.setQuality(GraphicsLevel.LOW) // replaces the horse's geometry
        assertEquals(0, body.disposeCount)
    }

    @Test
    fun handsOutTheCrashHintOnceAfterAnUncleanEndOfTheLastRun() {
        val store =
            FakeStore(
                Settings(graphicsLevel = GraphicsLevel.HIGH, graphicsAuto = false),
                crashGuard = CrashGuardState(hintPending = true),
            )
        val rig = EngineRig(store = store)
        assertTrue(rig.engine.takeCrashHint())
        assertFalse(rig.engine.takeCrashHint())
    }

    @Test
    fun theRestoreWatchdogFiresWhenTheDeviceDoesNotComeBack() {
        val rig = EngineRig()
        rig.run()
        var timeouts = 0
        val watchdog = rig.engine.restoreWatchdog { timeouts++ }
        watchdog.start()
        rig.frames(60 * 7)
        assertEquals(0, timeouts)
        rig.frames(60 * 2)
        assertEquals(1, timeouts)
    }

    @Test
    fun theRestoreWatchdogStaysQuietWhenTheDeviceIsBack() {
        val rig = EngineRig()
        rig.run()
        var timeouts = 0
        val watchdog = rig.engine.restoreWatchdog { timeouts++ }
        watchdog.start()
        rig.frames(60 * 3)
        watchdog.cancel()
        rig.frames(60 * 10)
        assertEquals(0, timeouts)
    }
}
