package app.zoeshorsefarm.presentation.courses

import app.zoeshorsefarm.application.modes.CourseHud
import app.zoeshorsefarm.domain.course.RunPhase
import app.zoeshorsefarm.i18n.I18n

// Course HUD (SRT-004): the plain-data HUD model of the course mode as texts and flags for the chips.
// The ride screen owns the instance; the UI draws [CourseHudState] and the time.

/**
 * What the HUD chips show, except the time (see [CourseHudPresenter.timeText]). [notice] is null when
 * no notice is shown.
 */
data class CourseHudState(
    val timeVisible: Boolean,
    val timeWarning: Boolean,
    val allowedText: String,
    val faultsText: String,
    val faultsVisible: Boolean,
    val nextText: String,
    val notice: String?,
)

/**
 * Turns the HUD model of a course ride into [CourseHudState]. It runs every frame and must not
 * allocate then: the model changes every frame in its time only, so the state object is rebuilt only
 * when another value or the language changed (not when the time did), and the time is kept as a
 * number ([timeCs]) that the UI formats when it draws ([timeText] caches the text of the last value).
 */
class CourseHudPresenter(
    private val i18n: I18n,
) {
    val timeLabel: String get() = i18n.t("hud.time")
    val allowedLabel: String get() = i18n.t("hud.allowed")
    val faultsLabel: String get() = i18n.t("hud.faults")
    val nextLabel: String get() = i18n.t("hud.next")

    /** The ride time in hundredths of a second as of the last [update]; changes every frame. */
    var timeCs: Int = 0
        private set

    private var timeTextCs = Int.MIN_VALUE
    private var timeTextLang = i18n.lang
    private var timeTextCache = ""

    /** The ride time as `mm:ss,hh`; formatted again only when [timeCs] or the language changed. */
    val timeText: String
        get() {
            if (timeCs != timeTextCs || i18n.lang != timeTextLang) {
                timeTextCs = timeCs
                timeTextLang = i18n.lang
                timeTextCache = formatCs(timeCs, i18n.lang)
            }
            return timeTextCache
        }

    private var state: CourseHudState? = null
    private var phase: RunPhase? = null
    private var allowedS = 0
    private var faults = 0
    private var overTime = false
    private var next = ""
    private var missing: Int? = null
    private var lang = i18n.lang

    /** The state for the model of this frame (the model is reused by the course mode: read, do not keep). */
    fun update(model: CourseHud): CourseHudState =
        render(
            model.phase,
            model.timeCs,
            model.allowedS,
            model.faults,
            model.overTime,
            model.nextLabel,
            model.missingHint,
        )

    @Suppress("LongParameterList") // one value per HUD field
    fun render(
        phase: RunPhase,
        timeCs: Int,
        allowedS: Int,
        faults: Int,
        overTime: Boolean,
        nextLabel: String,
        missingHint: Int?,
    ): CourseHudState {
        this.timeCs = timeCs
        val cached = state
        if (cached != null && same(phase, allowedS, faults, overTime, nextLabel, missingHint)) return cached
        this.phase = phase
        this.allowedS = allowedS
        this.faults = faults
        this.overTime = overTime
        this.next = nextLabel
        this.missing = missingHint
        this.lang = i18n.lang
        val riding = phase != RunPhase.PRESTART
        val notice =
            when {
                phase == RunPhase.PRESTART -> i18n.t("ride.prestartHint")
                missingHint != null -> i18n.t("hud.missing", mapOf("n" to missingHint))
                else -> null
            }
        return CourseHudState(
            timeVisible = riding,
            timeWarning = overTime,
            allowedText = i18n.t("hud.allowedValue", mapOf("seconds" to allowedS)),
            faultsText = faults.toString(),
            faultsVisible = riding,
            nextText = if (nextLabel == FINISH_LABEL) i18n.t("hud.finish") else nextLabel,
            notice = notice,
        ).also { state = it }
    }

    @Suppress("LongParameterList") // compares the cached fields with the new ones
    private fun same(
        phase: RunPhase,
        allowedS: Int,
        faults: Int,
        overTime: Boolean,
        nextLabel: String,
        missingHint: Int?,
    ) = phase == this.phase &&
        allowedS == this.allowedS &&
        faults == this.faults &&
        overTime == this.overTime &&
        nextLabel == this.next &&
        missingHint == this.missing &&
        i18n.lang == lang

    private companion object {
        const val FINISH_LABEL = "finish"
    }
}
