package app.zoeshorsefarm.domain.progress

import app.zoeshorsefarm.domain.course.Faults
import app.zoeshorsefarm.domain.course.RideResult
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertSame

class ProgressTest {
    private fun fresh() = sanitizeProgress(null)

    private fun ride(
        courseId: Int,
        total: Int,
        timeCs: Int,
        stars: Int? = null,
    ) = RideResult(
        courseId = courseId,
        timeCs = timeCs,
        faults = Faults(knockdowns = total, refusals = 0, timeFaults = 0, total = total),
        stars =
            stars ?: (
                if (total == 0) {
                    3
                } else if (total <= 4) {
                    2
                } else {
                    1
                }
            ),
        cleanOxer = false,
        cleanCombination = false,
    )

    private fun entry(
        faults: Any?,
        timeCs: Any?,
        stars: Any?,
    ): Map<String, Any?> = mapOf("faults" to faults, "timeCs" to timeCs, "stars" to stars)

    // ---- sanitizeProgress ----

    @Test
    fun returnsDefaultsForMissingOrCorruptData() {
        for (raw in listOf(null, "x", 42, emptyList<Any?>(), true)) {
            assertEquals(PROGRESS_DEFAULTS, sanitizeProgress(raw), "raw = $raw")
        }
    }

    @Test
    fun keepsReadableFieldsAndReplacesCorruptOnesIndividually() {
        val p =
            sanitizeProgress(
                mapOf(
                    "unlocked" to 3,
                    "courses" to mapOf("1" to entry(2, 5000, 2)),
                    "jumps" to "viel",
                    "finishedRides" to 7,
                    "badges" to emptyList<Any?>(),
                ),
            )
        assertEquals(
            Progress(
                unlocked = 3,
                courses = mapOf("1" to CourseBest(2, 5000, 2)),
                jumps = 0,
                finishedRides = 7,
            ),
            p,
        )
    }

    @Test
    fun clampsUnlockedTo1To5AndRoundsDown() {
        assertEquals(1, sanitizeProgress(mapOf("unlocked" to 0)).unlocked)
        assertEquals(1, sanitizeProgress(mapOf("unlocked" to -4)).unlocked)
        assertEquals(5, sanitizeProgress(mapOf("unlocked" to 9)).unlocked)
        assertEquals(2, sanitizeProgress(mapOf("unlocked" to 2.9)).unlocked)
        assertEquals(1, sanitizeProgress(mapOf("unlocked" to Double.NaN)).unlocked)
        assertEquals(1, sanitizeProgress(mapOf("unlocked" to "4")).unlocked)
        assertEquals(5, sanitizeProgress(mapOf("unlocked" to 1e300)).unlocked)
    }

    @Test
    fun dropsInvalidEntriesOfCourses1To5UnknownKeysAreKeptSeeBelow() {
        val p =
            sanitizeProgress(
                mapOf(
                    "courses" to
                        mapOf(
                            "1" to entry(0, 100, 3),
                            "2" to entry(-1, 100, 2),
                            "3" to entry(1, "x", 2),
                            "4" to entry(1, 100, 4),
                            "5" to null,
                            "6" to entry(0, 100, 3),
                            "0" to entry(0, 100, 3),
                            "foo" to entry(0, 100, 3),
                        ),
                ),
            )
        assertEquals(listOf("1"), p.courses.keys.toList())
    }

    @Test
    fun validatesBadgeDatesButKeepsUnknownIds() {
        val p =
            sanitizeProgress(
                mapOf(
                    "badges" to
                        mapOf(
                            "firstJump" to "2026-10-03T10:00:00.000Z",
                            "jumpMouse" to "kein Datum",
                            "clean" to 12345,
                            "future" to "beliebig",
                        ),
                ),
            )
        assertEquals(mapOf("firstJump" to "2026-10-03T10:00:00.000Z"), p.badges)
        assertEquals(mapOf("future" to "beliebig"), p.unknownBadges)
    }

    @Test
    fun keepsUnknownFieldsUnchanged() {
        val p = sanitizeProgress(mapOf("jumps" to 3, "later" to mapOf("a" to listOf(1, 2)), "note" to "hi"))
        assertEquals(mapOf("a" to listOf(1, 2)), p.unknown["later"])
        assertEquals("hi", p.unknown["note"])
        assertEquals(3, p.jumps)
    }

    @Test
    fun roundsCountersToWholeNonNegativeNumbers() {
        val p = sanitizeProgress(mapOf("jumps" to 4.7, "finishedRides" to -2))
        assertEquals(4, p.jumps)
        assertEquals(0, p.finishedRides)
    }

    @Test
    fun doesNotMutateTheInput() {
        val raw = mapOf("courses" to mapOf("1" to entry(0, 1, 3)), "later" to listOf(1))
        val copy = mapOf("courses" to mapOf("1" to entry(0, 1, 3)), "later" to listOf(1))
        sanitizeProgress(raw)
        assertEquals(copy, raw)
    }

    @Test
    fun keepsUnknownKeysInCoursesUntouchedRule47ButStillCleansCourses1To5() {
        val future = mapOf("faults" to "x", "note" to listOf(1, 2))
        val p =
            sanitizeProgress(
                mapOf(
                    "courses" to
                        mapOf(
                            "1" to entry(2, 5000, 2),
                            "2" to entry(-1, 5000, 2),
                            "6" to future,
                            "extra" to "text",
                        ),
                ),
            )
        assertEquals(mapOf("1" to CourseBest(2, 5000, 2)), p.courses)
        assertEquals(mapOf("6" to future, "extra" to "text"), p.unknownCourses)
        assertSame(future, p.unknownCourses["6"])
    }

    @Test
    fun keepsFutureFieldsOfACourseEntryUntouched() {
        val p =
            sanitizeProgress(
                mapOf("courses" to mapOf("1" to (entry(2, 5000.9, 2) + ("replay" to listOf(1, 2))))),
            )
        assertEquals(CourseBest(2, 5000, 2, extra = mapOf("replay" to listOf(1, 2))), p.courses["1"])
    }

    @Test
    fun keepsAnUnknownCoursesKeyNamedProtoAsPlainData() {
        val p =
            sanitizeProgress(
                mapOf("courses" to mapOf("__proto__" to mapOf("x" to 1), "1" to entry(0, 1, 3))),
            )
        assertEquals(listOf("1"), p.courses.keys.toList())
        assertEquals(mapOf("x" to 1), p.unknownCourses["__proto__"])
    }

    @Test
    fun survivesKeysSuchAsProtoFromJson() {
        val p =
            sanitizeProgress(
                mapOf("badges" to mapOf("__proto__" to mapOf("x" to 1)), "__proto__" to mapOf("y" to 2)),
            )
        assertEquals(mapOf("y" to 2), p.unknown["__proto__"])
        assertEquals(mapOf("x" to 1), p.unknownBadges["__proto__"])
    }

    @Test
    fun aSanitizedProgressSurvivesAJsonTreeRoundTrip() {
        val p =
            sanitizeProgress(
                mapOf(
                    "unlocked" to 4,
                    "courses" to mapOf("2" to (entry(4, 6000, 2) + ("replay" to "r")), "9" to "future"),
                    "jumps" to 12,
                    "finishedRides" to 3,
                    "badges" to mapOf("firstJump" to "2026-10-03T10:00:00.000Z", "later" to 1),
                    "theme" to "dark",
                ),
            )
        assertEquals(p, sanitizeProgress(p.toTree()))
        val tree = p.toTree()
        assertEquals("dark", tree["theme"])
        assertEquals(4, tree["unlocked"])
        assertEquals(setOf("2", "9"), (tree["courses"] as Map<*, *>).keys)
    }

    // ---- applyFinishedRide ----

    @Test
    fun firstFinishedRideIsAlwaysABestAndStoresFaultsTimeStars() {
        val r = applyFinishedRide(fresh(), ride(1, 8, 7000))
        assertEquals(true, r.isNewBest)
        assertEquals(CourseBest(8, 7000, 1), r.progress.courses["1"])
    }

    @Test
    fun replacesTheBestOnlyIfBetter() {
        var p = applyFinishedRide(fresh(), ride(1, 4, 6000)).progress
        val worse = applyFinishedRide(p, ride(1, 4, 6001))
        assertEquals(false, worse.isNewBest)
        assertEquals(CourseBest(4, 6000, 2), worse.progress.courses["1"])
        val better = applyFinishedRide(worse.progress, ride(1, 4, 5999))
        assertEquals(true, better.isNewBest)
        val best = assertNotNull(better.progress.courses["1"])
        assertEquals(4, best.faults)
        assertEquals(5999, best.timeCs)
        p = applyFinishedRide(better.progress, ride(1, 1, 9000)).progress
        val fewer = assertNotNull(p.courses["1"])
        assertEquals(1, fewer.faults)
        assertEquals(9000, fewer.timeCs)
    }

    @Test
    fun bestStarsAreTheMaximumEvenIfTheRideWasWorse() {
        var p = applyFinishedRide(fresh(), ride(1, 0, 6000)).progress
        p = applyFinishedRide(p, ride(1, 9, 5000)).progress
        val c1 = assertNotNull(p.courses["1"])
        assertEquals(3, c1.stars)
        assertEquals(0, c1.faults)
        assertEquals(6000, c1.timeCs)
        var q = applyFinishedRide(fresh(), ride(2, 9, 5000)).progress
        q = applyFinishedRide(q, ride(2, 2, 9000)).progress
        assertEquals(2, assertNotNull(q.courses["2"]).stars)
    }

    @Test
    fun bestResultAndStarsImproveIndependently() {
        // faster with the same faults: stars stay
        var p = applyFinishedRide(fresh(), ride(3, 2, 6000)).progress
        p = applyFinishedRide(p, ride(3, 2, 5000)).progress
        assertEquals(CourseBest(2, 5000, 2), p.courses["3"])
    }

    @Test
    fun everyFinishedRideUnlocksTheNextCourseRule37() {
        val r = applyFinishedRide(fresh(), ride(1, 12, 9000))
        assertEquals(2, r.unlockedCourse)
        assertEquals(2, r.progress.unlocked)
    }

    @Test
    fun unlocksNothingIfTheNextCourseIsAlreadyOpen() {
        val p = fresh().copy(unlocked = 4)
        val r = applyFinishedRide(p, ride(2, 0, 5000))
        assertNull(r.unlockedCourse)
        assertEquals(4, r.progress.unlocked)
    }

    @Test
    fun unlocksExactlyOneMoreWhenTheNewestCourseIsReached() {
        val r = applyFinishedRide(fresh().copy(unlocked = 3), ride(3, 0, 5000))
        assertEquals(4, r.unlockedCourse)
        assertEquals(4, r.progress.unlocked)
    }

    @Test
    fun capsAt5() {
        val r = applyFinishedRide(fresh().copy(unlocked = 5), ride(5, 0, 5000))
        assertNull(r.unlockedCourse)
        assertEquals(5, r.progress.unlocked)
        val r4 = applyFinishedRide(fresh().copy(unlocked = 4), ride(4, 0, 5000))
        assertEquals(5, r4.unlockedCourse)
        assertEquals(5, r4.progress.unlocked)
    }

    @Test
    fun countsFinishedRides() {
        var p = fresh()
        for (i in 0 until 3) p = applyFinishedRide(p, ride(1, 0, 5000 + i)).progress
        assertEquals(3, p.finishedRides)
    }

    @Test
    fun leavesOtherFieldsUntouchedAndKeepsTheFieldsOfAnExistingCourseEntry() {
        val previous = CourseBest(5, 9000, 1, extra = mapOf("replay" to "r"))
        val p =
            fresh().copy(
                jumps = 12,
                unknown = mapOf("extra" to mapOf("a" to 1)),
                badges = mapOf("firstJump" to "2026-01-01"),
                courses = mapOf("1" to previous),
            )
        val r = applyFinishedRide(p, ride(1, 0, 5000))
        assertEquals(12, r.progress.jumps)
        assertEquals(mapOf("extra" to mapOf("a" to 1)), r.progress.unknown)
        assertEquals(mapOf("firstJump" to "2026-01-01"), r.progress.badges)
        assertEquals(CourseBest(0, 5000, 3, extra = mapOf("replay" to "r")), r.progress.courses["1"])
        // the input is immutable
        assertEquals(previous, p.courses["1"])
    }

    @Test
    fun ignoresAnInvalidCourseNumber() {
        val p = fresh()
        val r = applyFinishedRide(p, ride(9, 0, 5000))
        assertEquals(AppliedRide(p, isNewBest = false, unlockedCourse = null), r)
        assertSame(p, r.progress)
    }

    // ---- addJump ----

    @Test
    fun addJumpIncrementsOnlyJumpsImmutably() {
        val p = fresh()
        val q = addJump(p)
        assertEquals(1, q.jumps)
        assertEquals(0, p.jumps)
        assertEquals(2, addJump(q).jumps)
    }

    // ---- resetProgress ----

    @Test
    fun resetsOnlyTheFieldsFromRule48() {
        val p =
            Progress(
                unlocked = 4,
                courses = mapOf("1" to CourseBest(0, 5000, 3)),
                jumps = 120,
                finishedRides = 9,
                badges = mapOf("firstJump" to "2026-10-03T10:00:00.000Z"),
                unknownBadges = mapOf("future" to "x"),
                unknownCourses = mapOf("6" to "y"),
                unknown = mapOf("someFutureField" to mapOf("keep" to true)),
            )
        val r = resetProgress(p)
        assertEquals(Progress(unknown = mapOf("someFutureField" to mapOf("keep" to true))), r)
        assertEquals(120, p.jumps)
    }

    // ---- date validation ----

    @Test
    fun acceptsIsoDatesAndRejectsOtherText() {
        for (ok in listOf(
            "2026-10-03T10:00:00.000Z",
            "2026-10-03",
            "2026-10",
            "2026",
            "2026-10-03T10:00",
            "2026-10-03 10:00:00",
            "2026-10-03T10:00:00+02:00",
            "2026-10-03T10:00:00+0200",
            "2026-10-03T24:00:00Z",
        )) {
            assertEquals(true, isValidIsoDate(ok), ok)
        }
        for (bad in listOf(
            "",
            "x",
            "kein Datum",
            "2026-13-01",
            "2026-10-32",
            "2026-10-03T25:00:00Z",
            "2026-10-03T10:60:00Z",
            "2026-10-03T10:00:00Zjunk",
        )) {
            assertEquals(false, isValidIsoDate(bad), bad)
        }
    }
}
