package app.zoeshorsefarm.domain.progress

import app.zoeshorsefarm.domain.course.Faults
import app.zoeshorsefarm.domain.course.RideResult
import kotlin.test.Test
import kotlin.test.assertContains
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotEquals
import kotlin.test.assertSame

class BadgesTest {
    private val now = "2026-10-03T12:00:00.000Z"
    private val later = "2026-10-04T12:00:00.000Z"

    private fun fresh() = sanitizeProgress(null)

    private fun result(
        total: Int = 3,
        courseId: Int = 1,
        stars: Int = if (total == 0) 3 else 2,
        cleanOxer: Boolean = false,
        cleanCombination: Boolean = false,
    ) = RideResult(
        courseId = courseId,
        timeCs = 6000,
        faults = Faults(knockdowns = total, refusals = 0, timeFaults = 0, total = total),
        stars = stars,
        cleanOxer = cleanOxer,
        cleanCombination = cleanCombination,
    )

    /** Like the caller: apply the ride, then check the ride-end badges. */
    private fun finishRide(
        progress: Progress,
        res: RideResult,
        at: String = now,
    ): BadgeCheck {
        val applied = applyFinishedRide(progress, res)
        return checkRideEndBadges(applied.progress, res, at)
    }

    private fun withCourses(vararg stars: Int?): Progress {
        val courses = LinkedHashMap<String, CourseBest>()
        stars.forEachIndexed { i, s ->
            if (s != null) courses[(i + 1).toString()] = CourseBest(if (s == 3) 0 else 2, 5000, s)
        }
        return fresh().copy(courses = courses)
    }

    // ---- BADGES ----

    @Test
    fun containsThe8BadgesInTheOrderOfRule49() {
        assertEquals(
            listOf("firstJump", "jumpMouse", "clean", "oxerPro", "comboPro", "allOpen", "starRider", "busy"),
            BADGES.map { it.id },
        )
        assertEquals(BADGES.map { it.id }, BADGE_IDS)
    }

    @Test
    fun awardTimingAndI18nKeysAreCorrect() {
        for (b in BADGES) {
            assertEquals("badge.${b.id}.name", b.nameKey)
            assertEquals("badge.${b.id}.condition", b.conditionKey)
        }
        assertEquals(listOf("firstJump", "jumpMouse"), BADGES.filter { it.award == BadgeAward.INSTANT }.map { it.id })
        assertEquals(6, BADGES.count { it.award == BadgeAward.RIDE_END })
    }

    @Test
    fun courseIdsAreTheSaveFileKeys1To5() {
        assertEquals(listOf("1", "2", "3", "4", "5"), COURSE_IDS)
        assertEquals(5, COURSE_COUNT)
    }

    // ---- Instant badges ----

    @Test
    fun firstJumpIsAwardedAfterTheFirstCountedJumpNotBefore() {
        assertEquals(emptyList(), checkInstantBadges(fresh(), now).awarded)
        val r = checkInstantBadges(addJump(fresh()), now)
        assertEquals(listOf("firstJump"), r.awarded)
        assertEquals(mapOf("firstJump" to now), r.progress.badges)
    }

    @Test
    fun jumpMouseAtExactly100NotAt99() {
        val p99 = fresh().copy(jumps = 99, badges = mapOf("firstJump" to now))
        assertEquals(emptyList(), checkInstantBadges(p99, later).awarded)
        val r = checkInstantBadges(addJump(p99), later)
        assertEquals(listOf("jumpMouse"), r.awarded)
        assertEquals(mapOf("firstJump" to now, "jumpMouse" to later), r.progress.badges)
    }

    @Test
    fun instantIsAwardedOnlyOnceSecondFulfilmentAwardsNothingAndKeepsTheDate() {
        val first = checkInstantBadges(fresh().copy(jumps = 1), now)
        val second = checkInstantBadges(addJump(first.progress), later)
        assertEquals(emptyList(), second.awarded)
        assertEquals(now, second.progress.badges["firstJump"])
        assertEquals(first.progress.badges, second.progress.badges)
    }

    @Test
    fun catchesUpBothFromAnOldSaveWith150JumpsOnTheNextJump() {
        val old = fresh().copy(jumps = 150)
        val r = checkInstantBadges(addJump(old), now)
        assertEquals(listOf("firstJump", "jumpMouse"), r.awarded)
    }

    @Test
    fun instantDoesNotAwardRideEndBadges() {
        val p = fresh().copy(jumps = 500, finishedRides = 50, unlocked = 5)
        assertEquals(listOf("firstJump", "jumpMouse"), checkInstantBadges(p, now).awarded)
    }

    @Test
    fun instantDoesNotMutateTheInput() {
        val p = fresh().copy(jumps = 5)
        val r = checkInstantBadges(p, now)
        assertEquals(emptyMap(), p.badges)
        assertNotEquals(p, r.progress)
    }

    @Test
    fun returnsTheSameObjectWhenNothingIsAwarded() {
        val p = fresh()
        assertSame(p, checkInstantBadges(p, now).progress)
    }

    // ---- Ride-end badges ----

    @Test
    fun cleanRideWith0Faults() {
        val r = finishRide(fresh(), result(total = 0))
        assertContains(r.awarded, "clean")
        assertEquals(now, r.progress.badges["clean"])
    }

    @Test
    fun cleanNotAwardedWithFaultsAndNo3StarCourse() {
        assertFalse("clean" in finishRide(fresh(), result(total = 1)).awarded)
    }

    @Test
    fun cleanAStored3StarCourseIsEnoughCatchUp() {
        val old = withCourses(null, 3)
        val r = finishRide(old, result(courseId = 3, total = 6, stars = 1))
        assertEquals(listOf("clean"), r.awarded)
    }

    @Test
    fun cleanA3StarEntryForACourseOutside1To5DoesNotCount() {
        val stray = fresh().copy(courses = mapOf("7" to CourseBest(0, 5000, 3)))
        assertFalse("clean" in finishRide(stray, result(total = 4, stars = 2)).awarded)
    }

    @Test
    fun oxerProOnlyFromTheRideResult() {
        assertEquals(listOf("oxerPro"), finishRide(fresh(), result(cleanOxer = true)).awarded)
        assertEquals(emptyList(), finishRide(fresh(), result(cleanOxer = false)).awarded)
    }

    @Test
    fun combinationProOnlyFromTheRideResult() {
        assertEquals(listOf("comboPro"), finishRide(fresh(), result(cleanCombination = true)).awarded)
        assertEquals(emptyList(), finishRide(fresh(), result(cleanCombination = false)).awarded)
    }

    @Test
    fun oxerProAndCombinationProAreNotDerivedFromStoredData() {
        val old = withCourses(3, 3, 3, 3, 3).copy(unlocked = 5, jumps = 900, finishedRides = 40)
        val r = finishRide(old, result(courseId = 5, total = 0))
        assertFalse("oxerPro" in r.awarded)
        assertFalse("comboPro" in r.awarded)
    }

    @Test
    fun allOpenAtUnlocked5OrMoreNotAt4() {
        val p3 = fresh().copy(unlocked = 3)
        assertFalse("allOpen" in finishRide(p3, result(courseId = 3)).awarded)
        // Riding course 4 unlocks 5 and awards in the same call
        val r = finishRide(fresh().copy(unlocked = 4), result(courseId = 4))
        assertContains(r.awarded, "allOpen")
    }

    @Test
    fun starRiderAll5CoursesWith3StarsNotWith4() {
        val four = withCourses(3, 3, 3, 3, null)
        assertFalse("starRider" in finishRide(four, result(courseId = 1, total = 3)).awarded)
        val r = finishRide(four, result(courseId = 5, total = 0))
        assertContains(r.awarded, "starRider")
    }

    @Test
    fun starRiderA2StarCoursePreventsAwarding() {
        val p = withCourses(3, 3, 2, 3, 3)
        assertFalse("starRider" in finishRide(p, result(courseId = 2, total = 5)).awarded)
    }

    @Test
    fun busyAt10FinishedRidesNotAt9() {
        val nine = fresh().copy(finishedRides = 8)
        assertFalse("busy" in finishRide(nine, result()).awarded)
        val ten = fresh().copy(finishedRides = 9)
        assertContains(finishRide(ten, result()).awarded, "busy")
    }

    @Test
    fun catchesUpBusyFromAnOldSaveWith10RidesOnTheNextRide() {
        val old = fresh().copy(finishedRides = 10)
        assertEquals(listOf("busy"), finishRide(old, result()).awarded)
    }

    @Test
    fun rideEndIsAwardedOnlyOnceSecondFulfilmentAwardsNothing() {
        val first = finishRide(fresh(), result(total = 0, cleanOxer = true, cleanCombination = true))
        assertEquals(listOf("clean", "oxerPro", "comboPro"), first.awarded)
        val second =
            finishRide(first.progress, result(total = 0, cleanOxer = true, cleanCombination = true), later)
        assertEquals(emptyList(), second.awarded)
        assertEquals(first.progress.badges, second.progress.badges)
    }

    @Test
    fun awardsSeveralAtOnceInRule49Order() {
        val p = withCourses(3, 3, 3, 3, 3).copy(unlocked = 5, finishedRides = 20)
        val r = finishRide(p, result(courseId = 5, total = 0, cleanOxer = true))
        assertEquals(listOf("clean", "oxerPro", "allOpen", "starRider", "busy"), r.awarded)
    }

    @Test
    fun actsOnTheFreshlyAppliedResultOrderApplyFinishedRideFirst() {
        // 10th ride: finishedRides only becomes 10 through applyFinishedRide
        var p = fresh().copy(finishedRides = 9)
        p = applyFinishedRide(p, result()).progress
        assertEquals(listOf("busy"), checkRideEndBadges(p, result(), now).awarded)
    }

    @Test
    fun rideEndDoesNotMutateTheInput() {
        val p = fresh().copy(finishedRides = 10)
        checkRideEndBadges(p, result(), now)
        assertEquals(emptyMap(), p.badges)
    }

    @Test
    fun rideEndDoesNotAwardInstantBadges() {
        val p = fresh().copy(jumps = 500)
        assertEquals(emptyList(), finishRide(p, result()).awarded)
    }

    @Test
    fun aMissingRideResultAwardsOnlyWhatTheStoredDataSays() {
        val p = fresh().copy(unlocked = 5)
        assertEquals(listOf("allOpen"), checkRideEndBadges(p, null, now).awarded)
    }
}
