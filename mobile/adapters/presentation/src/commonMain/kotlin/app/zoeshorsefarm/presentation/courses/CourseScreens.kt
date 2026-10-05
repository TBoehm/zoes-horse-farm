package app.zoeshorsefarm.presentation.courses

import app.zoeshorsefarm.application.AidKind
import app.zoeshorsefarm.application.FaultRow
import app.zoeshorsefarm.application.FinishedParams
import app.zoeshorsefarm.application.HorseSection
import app.zoeshorsefarm.application.ResultSummary
import app.zoeshorsefarm.application.canStart
import app.zoeshorsefarm.application.displayName
import app.zoeshorsefarm.application.getCourse
import app.zoeshorsefarm.application.listCourses
import app.zoeshorsefarm.application.modes.RideModeId
import app.zoeshorsefarm.application.summarizeResult
import app.zoeshorsefarm.domain.course.Course
import app.zoeshorsefarm.presentation.AppContext
import app.zoeshorsefarm.presentation.nav.Route
import app.zoeshorsefarm.presentation.nav.ScreenModel
import app.zoeshorsefarm.presentation.profile.badgeIcon
import app.zoeshorsefarm.presentation.settings.ToggleRow

// Course selection, prestart map and results (rules 26, 35, 55). Display only: which courses are
// open, the stars and the result figures come from the application layer.

/** One card of the course selection. A locked card ([open] false) cannot be selected. */
class CourseCard(
    val id: Int,
    val open: Boolean,
    val stars: Int,
    val title: String,
    val obstaclesText: String,
    /** The accessibility text of the stars, or null for a locked course (the lock is shown instead). */
    val starsLabel: String?,
    /** "Locked", "Not ridden yet" or the best result. */
    val bestText: String,
)

class CourseSelectModel(
    private val ctx: AppContext,
) : ScreenModel {
    override val music = true

    val title: String get() = ctx.t("courses.title")
    val backLabel: String get() = ctx.t("common.back")

    val cards: List<CourseCard> =
        listCourses(ctx.store).map { course ->
            val best = course.best
            CourseCard(
                id = course.id,
                open = course.open,
                stars = course.stars,
                title = ctx.t("courses.number", mapOf("n" to course.id)),
                obstaclesText = ctx.t("courses.obstacles", mapOf("count" to course.obstacleCount)),
                starsLabel = if (course.open) ctx.t("courses.starsLabel", mapOf("count" to course.stars)) else null,
                bestText =
                    when {
                        !course.open -> {
                            ctx.t("courses.locked")
                        }

                        best != null -> {
                            ctx.t(
                                "courses.best",
                                mapOf("faults" to best.faults, "time" to formatCs(best.timeCs, ctx.i18n.lang)),
                            )
                        }

                        else -> {
                            ctx.t("courses.noBest")
                        }
                    },
            )
        }

    /** A tap on a card: an open course goes to its prestart map. */
    fun select(id: Int) {
        if (cards.any { it.id == id && it.open }) ctx.navigator.go(Route.Prestart(id))
    }

    fun back() = ctx.navigator.go(Route.Menu)
}

/**
 * The map before a course ride. A locked or unknown course cannot be started, not even by a forced
 * screen change: the model redirects to the selection then (rule 35).
 */
class PrestartModel(
    private val ctx: AppContext,
    courseId: Int,
) : ScreenModel {
    private val course: Course? = if (canStart(ctx.store, courseId)) getCourse(courseId) else null

    override val redirect: Route? = if (course == null) Route.CourseSelect else null
    override val music = true

    val title: String get() = ctx.t("prestart.title", mapOf("n" to course?.id))
    val allowedTimeText: String get() = ctx.t("prestart.allowedTime", mapOf("seconds" to course?.allowedTimeS))
    val planLabel: String get() = ctx.t("prestart.planLabel")
    val goLabel: String get() = ctx.t("prestart.go")
    val backLabel: String get() = ctx.t("common.back")

    /** The jump aid "in courses" (rule 42): changes the saved setting. */
    val aid =
        ToggleRow(
            name = "aidCourse",
            labelText = { ctx.t("prestart.aid") },
            state = { ctx.settings.get().aidCourse },
            onChange = { ctx.settings.setAid(AidKind.COURSE, it) },
        )

    /** The plan for a drawing area of [width] x [height] pixels; null when the course cannot be started. */
    fun plan(
        width: Double,
        height: Double,
    ): CoursePlan? =
        course?.let {
            buildCoursePlan(it, width, height, ctx.t("prestart.legendStart"), ctx.t("prestart.legendFinish"))
        }

    /** "Go": the start signal plays only now (rule 26), then the ride begins. */
    fun go() {
        val id = course?.id ?: return
        ctx.sound.startSignal()
        ctx.navigator.go(Route.Ride(RideModeId.COURSE, id))
    }

    fun back() = ctx.navigator.go(Route.CourseSelect)
}

/** A row of the result table. [field] is `time`, `knockdowns`, `refusals`, `timeFaults` or `total`. */
class ResultRow(
    val field: String,
    val label: String,
    val value: String,
)

/** A badge the ride awarded. */
class ResultBadge(
    val id: String,
    val name: String,
    val icon: String,
)

// The finish signal plays first; the melody of the results screen starts after it
private const val RESULTS_MUSIC_DELAY_MS = 1500L

class ResultsModel(
    private val ctx: AppContext,
    finished: FinishedParams,
) : ScreenModel {
    private val summary: ResultSummary = summarizeResult(ctx.store, finished)

    override val music = true
    override val musicDelayMs = RESULTS_MUSIC_DELAY_MS

    val courseId: Int get() = summary.courseId
    val title: String get() = ctx.t("results.title")

    val horseText: String
        get() {
            val name = displayName(ctx.store.get(HorseSection), ctx.t("horse.defaultName"))
            return ctx.t("results.horse", mapOf("name" to name))
        }

    val stars: Int get() = summary.stars
    val starsLabel: String get() = ctx.t("courses.starsLabel", mapOf("count" to summary.stars))

    val newBestText: String? get() = if (summary.isNewBest) ctx.t("results.newBest") else null

    val unlockedText: String?
        get() = summary.unlockedCourse?.let { ctx.t("results.unlocked", mapOf("n" to it)) }

    val newBadgesTitle: String get() = ctx.t("results.newBadges")

    val badges: List<ResultBadge> =
        summary.badges.map { ResultBadge(it.id, ctx.t(it.nameKey), badgeIcon(it.id)) }

    val rows: List<ResultRow> =
        summary.rows.let { rows ->
            listOf(
                ResultRow("time", ctx.t("results.time"), formatCs(summary.timeCs, ctx.i18n.lang)),
                ResultRow("knockdowns", ctx.t("results.knockdowns"), pointsText(rows.knockdowns)),
                ResultRow("refusals", ctx.t("results.refusals"), pointsText(rows.refusals)),
                ResultRow("timeFaults", ctx.t("results.timeFaults"), rows.timeFaults.toString()),
                ResultRow("total", ctx.t("results.total"), rows.total.toString()),
            )
        }

    /** The following course when it is open, else null (the "Next course" button is left out). */
    val nextCourse: Int? get() = summary.nextCourse

    val againLabel: String get() = ctx.t("results.again")
    val nextLabel: String get() = ctx.t("results.next")
    val toSelectLabel: String get() = ctx.t("results.toSelect")

    // "3 x 4 = 12" from the figures of the result summary; nothing to show for 0
    private fun pointsText(row: FaultRow): String =
        if (row.count > 0) {
            ctx.t("results.points", mapOf("count" to row.count, "each" to row.each, "points" to row.points))
        } else {
            "0"
        }

    fun again() = ctx.navigator.go(Route.Prestart(summary.courseId))

    fun next() {
        summary.nextCourse?.let { ctx.navigator.go(Route.Prestart(it)) }
    }

    fun toSelect() = ctx.navigator.go(Route.CourseSelect)
}
