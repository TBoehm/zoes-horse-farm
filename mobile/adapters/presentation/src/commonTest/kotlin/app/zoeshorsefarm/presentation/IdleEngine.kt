package app.zoeshorsefarm.presentation

import app.zoeshorsefarm.application.CameraMode
import app.zoeshorsefarm.application.GraphicsLevel
import app.zoeshorsefarm.application.modes.CourseLines
import app.zoeshorsefarm.presentation.ride.RideEnginePort

/** An engine that does nothing and never loses its device. */
class IdleEngine : RideEnginePort {
    override val graphicsLevel = GraphicsLevel.LOW
    override val contextLost = false

    override fun onContextLost(listener: () -> Unit): () -> Unit = {}

    override fun onContextRestored(listener: () -> Unit): () -> Unit = {}

    override fun canHintLowerLevel(auto: Boolean) = !auto && graphicsLevel != GraphicsLevel.LOW

    override fun startRestoreWatchdog(onTimeout: () -> Unit) = Unit

    override fun cancelRestoreWatchdog() = Unit

    override fun setCameraMode(mode: CameraMode) = Unit

    override fun toggleCamera(): CameraMode = CameraMode.FOLLOW

    override fun showLines(
        lines: CourseLines?,
        startLabel: String,
        finishLabel: String,
    ) = Unit

    override fun onRideRestarted() = Unit

    override fun interruptMeasuring() = Unit

    override fun lowFpsHintFrame(
        rawDt: Double,
        measuring: Boolean,
    ) = false

    override fun takeGraphicsHint() = false

    override fun takeCrashHint() = false

    override fun reloadGraphics() = Unit
}
