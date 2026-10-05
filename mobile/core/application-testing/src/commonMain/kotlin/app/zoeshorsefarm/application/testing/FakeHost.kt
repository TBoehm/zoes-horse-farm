package app.zoeshorsefarm.application.testing

import app.zoeshorsefarm.application.modes.ModeHost

/** Test double for the host that the ride session gives to a mode (tests/support/test-host.js). */
class FakeHost : ModeHost {
    val feedback = mutableListOf<String>()
    val rebuildIn = mutableListOf<Pair<String, Double>>()
    val rebuildNow = mutableListOf<String>()
    val cancelRebuild = mutableListOf<String>()

    override fun feedback(key: String) {
        feedback.add(key)
    }

    override fun rebuildIn(
        elementId: String,
        seconds: Double,
    ) {
        rebuildIn.add(elementId to seconds)
    }

    override fun rebuildNow(elementId: String) {
        rebuildNow.add(elementId)
    }

    override fun cancelRebuild(elementId: String) {
        cancelRebuild.add(elementId)
    }
}
