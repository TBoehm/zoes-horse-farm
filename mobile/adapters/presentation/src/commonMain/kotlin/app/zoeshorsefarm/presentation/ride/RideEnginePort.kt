package app.zoeshorsefarm.presentation.ride

import app.zoeshorsefarm.application.CameraMode
import app.zoeshorsefarm.application.GraphicsLevel
import app.zoeshorsefarm.application.modes.CourseLines

/**
 * What the ride screen model needs from the 3D side (web: `getEngine(ctx)`, the governor, the low
 * frame-rate hint and the crash guard hints). The view adapter implements it; the model decides when
 * to call it. Everything that draws (world, horse, camera, shadows, the frame loop itself) stays
 * behind this port.
 */
interface RideEnginePort {
    /** The graphics level in use (shown in the frame-rate display, decides about hints). */
    val graphicsLevel: GraphicsLevel

    /** The graphics device is lost right now. */
    val contextLost: Boolean

    /** Calls [listener] when the device is lost; returns the function that unsubscribes. */
    fun onContextLost(listener: () -> Unit): () -> Unit

    /** Calls [listener] when the device is back; returns the function that unsubscribes. */
    fun onContextRestored(listener: () -> Unit): () -> Unit

    /** The saved camera mode at the start of a ride. */
    fun setCameraMode(mode: CameraMode)

    /** The camera button: switches the camera and returns the new mode (the model saves it). */
    fun toggleCamera(): CameraMode

    /** Start and finish lines with their translated labels (null lines: free mode, nothing drawn). */
    fun showLines(
        lines: CourseLines?,
        startLabel: String,
        finishLabel: String,
    )

    /**
     * The ride was (re)started: dress the horse with the saved look, put it on the start pose, snap
     * the camera, and forget the running measurements ("Start again" is a new ride, rule 39).
     */
    fun onRideRestarted()

    /** Pause, menus and device changes interrupt the measuring of the graphics governor and the hint. */
    fun interruptMeasuring()

    /**
     * One frame of the low frame-rate hint (rule 4): true exactly once when a manual level is too
     * high for the device. [measuring] is false while nothing may be concluded from the frames.
     */
    fun lowFpsHintFrame(
        rawDt: Double,
        measuring: Boolean,
    ): Boolean

    /** After a lost device with a manual level above low: true once (consumed). */
    fun takeGraphicsHint(): Boolean

    /** After an unexpected end of the previous run (crash guard): true once (consumed). */
    fun takeCrashHint(): Boolean

    /** The device does not come back: restart the 3D view (web: reload the page). */
    fun reloadGraphics()
}
