package app.zoeshorsefarm.application.modes

// Mode factory: the ride screen asks for a mode by id and gets a DOM-free strategy.

/** The mode of the given [id]; a course mode rides [courseId] (unknown or null: the first course). */
fun createRideMode(
    id: RideModeId = RideModeId.FREE,
    courseId: Int? = null,
): RideMode =
    when (id) {
        RideModeId.FREE -> FreeMode()
        RideModeId.COURSE -> CourseMode(courseId)
    }

/**
 * The mode with the stored id (null: free mode).
 * @throws IllegalArgumentException for an unknown id
 */
fun createRideMode(
    id: String?,
    courseId: Int? = null,
): RideMode {
    val mode = if (id == null) RideModeId.FREE else RideModeId.fromId(id)
    require(mode != null) { "Unknown ride mode: $id" }
    return createRideMode(mode, courseId)
}
