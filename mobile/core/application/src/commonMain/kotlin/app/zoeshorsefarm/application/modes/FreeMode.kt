package app.zoeshorsefarm.application.modes

import app.zoeshorsefarm.application.Settings
import app.zoeshorsefarm.domain.course.FREE_LAYOUT
import app.zoeshorsefarm.domain.course.Highlight
import app.zoeshorsefarm.domain.sim.ALWAYS_REFUSE
import app.zoeshorsefarm.domain.sim.SimApproach
import app.zoeshorsefarm.domain.sim.SimEvent
import app.zoeshorsefarm.domain.sim.SimRules
import app.zoeshorsefarm.domain.sim.TUNING
import app.zoeshorsefarm.domain.sim.Tuning

/**
 * Free mode (rule 41): fixed layout, no scoring, feedback on knockdown/refusal, fallen rails are
 * rebuilt after `tuning.rebuildDelayS`, both jump directions count. No store access: the ride
 * session passes the settings it needs.
 */
class FreeMode(
    private val tuning: Tuning = TUNING,
) : RideMode {
    private val start = RideStart(FREE_LAYOUT.startPose)
    private val aid = AidTarget("", 1)

    override val id = RideModeId.FREE
    override val obstacles = FREE_LAYOUT.obstacles
    override val flags = false
    override val lines: CourseLines? = null
    override val quitLabelKey = "pause.toMenu"
    override val quitScreen = "menu"
    override val rules: SimRules = ALWAYS_REFUSE
    override val highlight: Highlight? = null
    override val finishMarked = false

    override fun startPose() = start

    override fun onRestart() = Unit

    override fun onEvents(
        events: List<SimEvent>,
        host: ModeHost,
    ) {
        for (e in events) {
            if (e is SimEvent.RailDown) host.rebuildIn(e.elementId, tuning.rebuildDelayS)
            if (e is SimEvent.Refusal) host.feedback(FEEDBACK_REFUSAL)
            if (e is SimEvent.Landed && e.knocked) host.feedback(FEEDBACK_KNOCKDOWN)
        }
    }

    /** A free ride never ends by itself. */
    override fun update(
        dt: Double,
        frame: RideFrame,
        host: ModeHost,
    ): RideFinish? = null

    /** Jump aid in front of the element that is currently approached (rule 42). */
    override fun aidTarget(
        approach: SimApproach?,
        settings: Settings,
    ): AidTarget? {
        if (!settings.aidFree || approach == null) return null
        aid.elementId = approach.elementId
        aid.dir = approach.dir
        return aid
    }
}
