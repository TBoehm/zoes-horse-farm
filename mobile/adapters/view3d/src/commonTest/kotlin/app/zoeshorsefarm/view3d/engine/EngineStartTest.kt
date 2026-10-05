package app.zoeshorsefarm.view3d.engine

import app.zoeshorsefarm.application.GraphicsLevel
import app.zoeshorsefarm.scene.render.ShadowType
import app.zoeshorsefarm.scene.render.ToneMapping
import app.zoeshorsefarm.view3d.quality.DeviceInfo
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertSame
import kotlin.test.assertTrue

// Creation of the engine: saved level, memory budget, antialiasing, renderer setup, first compile.

class EngineStartTest {
    @Test
    fun startsAtTheSavedLevelWithItsPresetApplied() {
        val rig = EngineRig(GraphicsLevel.HIGH)
        assertEquals(GraphicsLevel.HIGH, rig.engine.level)
        assertEquals(2.0, rig.fake.pixelRatio)
        assertTrue(rig.fake.shadowsEnabled)
    }

    @Test
    fun aLowLevelStartsWithoutShadowsAtPixelRatioOne() {
        val rig = EngineRig(GraphicsLevel.LOW)
        assertFalse(rig.fake.shadowsEnabled)
        assertEquals(1.0, rig.fake.pixelRatio)
    }

    @Test
    fun configuresTheBackendLikeTheWebRenderer() {
        val rig = EngineRig()
        assertEquals(ToneMapping.ACES_FILMIC, rig.fake.toneMapping)
        assertEquals(ShadowType.PCF, rig.fake.shadowType)
    }

    @Test
    fun compilesTheShadersOfTheFirstLevelBeforeTheFirstFrame() {
        val rig = EngineRig(GraphicsLevel.MEDIUM)
        assertEquals(1, rig.fake.compileCount)
        assertFalse(rig.engine.settling)
    }

    @Test
    fun putsTheHorseIntoTheWorld() {
        val rig = EngineRig()
        assertSame(rig.engine.world.scene, rig.engine.horse.group.parent)
    }

    @Test
    fun fitsAHighLevelToASmallMemoryBudgetAndSaysSo() {
        val rig = EngineRig(GraphicsLevel.HIGH, budgetMB = 100)
        val d = rig.engine.diagnostics()
        assertNotNull(d.ratioCap)
        assertTrue(rig.fake.pixelRatio < 2.0)
        assertEquals(GraphicsLevel.HIGH, rig.engine.level) // the level keeps its name
        assertEquals(100.0, d.gpuBudgetMB)
    }

    @Test
    fun dropsAntialiasingWhenTheBudgetCannotCarryIt() {
        val rig = EngineRig(GraphicsLevel.HIGH, budgetMB = 30)
        assertFalse(rig.engine.antialias)
        val d = rig.engine.diagnostics()
        assertTrue(d.antialiasDropped)
        assertFalse(d.antialias)
    }

    @Test
    fun keepsAntialiasingWhenTheBudgetIsBigEnough() {
        val rig = EngineRig(GraphicsLevel.MEDIUM)
        assertTrue(rig.engine.antialias)
        assertFalse(rig.engine.diagnostics().antialiasDropped)
    }

    @Test
    fun aLowLevelNeverWantsAntialiasing() {
        val rig = EngineRig(GraphicsLevel.LOW)
        assertFalse(rig.engine.antialias)
        assertFalse(rig.engine.diagnostics().antialiasDropped)
    }

    @Test
    fun usesTheContextAttributeTheHostSaysItHas() {
        val plan = planStartup(GraphicsLevel.MEDIUM, ViewSize(800.0, 400.0, 2.0), contextAntialias = false)
        assertTrue(plan.antialiasChosen)
        assertFalse(plan.contextAntialias)
    }

    @Test
    fun planStartupAnswersBeforeABackendExists() {
        val view = ViewSize(800.0, 400.0, 2.0)
        val big = planStartup(GraphicsLevel.HIGH, view, DeviceInfo(totalMemoryGiB = 16.0))
        assertTrue(big.antialiasChosen)
        assertEquals(1024, big.budgetMB)
        val small = planStartup(GraphicsLevel.HIGH, view, DeviceInfo(totalMemoryGiB = 2.0, isTouch = true))
        assertEquals(96, small.budgetMB) // 2 GiB at 40 MiB each, at least 96
        assertEquals(GraphicsLevel.HIGH, small.firstPreset.level)
        val forced = planStartup(GraphicsLevel.HIGH, view, DeviceInfo(), gpuBudgetOverrideMB = 77)
        assertEquals(77, forced.budgetMB)
    }

    @Test
    fun diagnosticsDescribeTheDeviceTheLevelAndTheSurface() {
        val rig = EngineRig(GraphicsLevel.MEDIUM, auto = true)
        val d = rig.engine.diagnostics()
        assertEquals("fake gpu", d.gpu)
        assertEquals("Test GPU", d.budgetGpu)
        assertEquals(GraphicsLevel.MEDIUM, d.level)
        assertTrue(d.auto)
        assertEquals(2.0, d.devicePixelRatio)
        assertEquals(1.5, d.pixelRatio)
        assertEquals(0, d.stagesPending)
        assertEquals(0, d.contextLost)
        assertNull(d.lastChange)
        assertSame(d, rig.engine.diagnostics()) // the same object, refilled
    }

    @Test
    fun diagnosticsShowTheDrawingBufferOnceTheSurfaceIsFitted() {
        val rig = EngineRig(GraphicsLevel.MEDIUM)
        rig.run()
        val d = rig.engine.diagnostics()
        assertEquals(1200, d.bufferWidth) // 800 x 1.5
        assertEquals(600, d.bufferHeight)
        assertEquals(rig.fake.capabilities.maxTextureSize, d.maxTextureSize)
    }

    @Test
    fun reportsALastCrashAsTheLastChangeAtTheStart() {
        val crash =
            app.zoeshorsefarm.view3d.quality.StartupCrash(
                crashed = true,
                auto = true,
                level = GraphicsLevel.HIGH,
            )
        val rig = EngineRig(GraphicsLevel.LOW, auto = true, startupCrash = crash)
        assertEquals(
            ChangeKind.CRASH,
            rig.engine
                .diagnostics()
                .lastChange
                ?.kind,
        )
        val manual =
            app.zoeshorsefarm.view3d.quality.StartupCrash(
                crashed = true,
                auto = false,
                level = GraphicsLevel.HIGH,
            )
        assertNull(EngineRig(startupCrash = manual).engine.diagnostics().lastChange)
    }
}
