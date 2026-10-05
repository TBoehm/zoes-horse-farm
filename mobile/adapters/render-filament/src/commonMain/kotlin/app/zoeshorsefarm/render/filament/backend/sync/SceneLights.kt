package app.zoeshorsefarm.render.filament.backend.sync

import app.zoeshorsefarm.scene.graph.DirectionalLight
import app.zoeshorsefarm.scene.graph.HemisphereLight

/** The lights the walk of a frame found (visible ones only); reused every frame. */
class SceneLights {
    /** The first visible directional light: Filament has one sun. */
    var sun: DirectionalLight? = null
        private set

    /** The visible hemisphere lights; their light adds up in the ambient light. */
    val hemispheres = ArrayList<HemisphereLight>(2)

    internal fun clear() {
        sun = null
        hemispheres.clear()
    }

    internal fun add(light: DirectionalLight) {
        if (sun == null) sun = light
    }

    internal fun add(light: HemisphereLight) {
        hemispheres += light
    }
}
