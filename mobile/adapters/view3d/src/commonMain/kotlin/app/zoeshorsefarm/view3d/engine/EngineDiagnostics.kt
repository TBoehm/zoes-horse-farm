package app.zoeshorsefarm.view3d.engine

import app.zoeshorsefarm.application.GraphicsLevel
import app.zoeshorsefarm.view3d.quality.PixelRatioCap
import app.zoeshorsefarm.view3d.quality.ShadowMapCap

// The data of the debug box (web: the `diag` object of `engine.js`). Pure data: the engine fills one
// instance again at every call, the box turns it into text (DebugDisplay.kt).

/** Why the automatic changed the level last (debug box: "last change"). */
enum class ChangeKind { UP, DOWN, LOSS, CRASH }

/** The last automatic level change; [fps] is the average frame rate that caused an up or down step. */
data class LevelChange(
    val kind: ChangeKind,
    val fps: Double? = null,
)

/** One error of the error log for the debug box: seconds since the start and the shortened message. */
data class DebugError(
    val atS: Double,
    val message: String,
)

/**
 * What the debug box shows about the 3D side. [Engine.diagnostics] returns the same instance every
 * time, refilled; do not keep it. Times are seconds since the app started, null = never happened.
 */
class EngineDiagnostics {
    /** GPU name of the real device, empty while it is lost or unknown. */
    var gpu: String = ""

    /** GPU name the memory budget was based on. */
    var budgetGpu: String = ""
    var level: GraphicsLevel = GraphicsLevel.LOW
    var auto: Boolean = false
    var devicePixelRatio: Double = 1.0
    var pixelRatio: Double = 1.0
    var bufferWidth: Int = 0
    var bufferHeight: Int = 0
    var maxTextureSize: Int = 0
    var contextLost: Int = 0
    var contextRestored: Int = 0
    var lostAtS: Double? = null
    var restoredAtS: Double? = null
    var stagesPending: Int = 0
    var gpuEstimateMB: Double = 0.0
    var gpuBudgetMB: Double = 0.0
    var ratioCap: PixelRatioCap? = null
    var shadowCap: ShadowMapCap? = null
    var sceneryCapped: Boolean = false
    var antialias: Boolean = false

    /** The level wanted antialiasing, but the budget said no. */
    var antialiasDropped: Boolean = false
    var blockedLevels: List<GraphicsLevel> = emptyList()
    var leftLevels: List<GraphicsLevel> = emptyList()
    var lastChange: LevelChange? = null
}
