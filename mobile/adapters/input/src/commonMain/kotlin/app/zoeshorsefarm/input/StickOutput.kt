package app.zoeshorsefarm.input

/** Result of [mapStick]: [steer] +1 right, [throttle] +1 faster. */
data class StickOutput(
    val steer: Double,
    val throttle: Double,
) {
    companion object {
        val NEUTRAL = StickOutput(steer = 0.0, throttle = 0.0)
    }
}
