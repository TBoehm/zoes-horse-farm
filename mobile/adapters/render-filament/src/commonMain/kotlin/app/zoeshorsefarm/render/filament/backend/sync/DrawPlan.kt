package app.zoeshorsefarm.render.filament.backend.sync

import app.zoeshorsefarm.render.filament.backend.device.MaterialInstanceHandle
import app.zoeshorsefarm.render.filament.mesh.PrimitiveRange

/** The draw calls of a mesh: `bindings[i]` draws `ranges[i]`. */
internal class DrawPlan(
    val ranges: List<PrimitiveRange>,
    val bindings: List<MaterialBinding>,
) {
    val instances: List<MaterialInstanceHandle> get() = bindings.map { it.instance }

    val isEmpty: Boolean get() = ranges.isEmpty()

    /** True if both plans draw the same ranges with the same material instances. */
    fun sameAs(other: DrawPlan): Boolean {
        if (ranges.size != other.ranges.size) return false
        for (i in ranges.indices) {
            if (ranges[i] != other.ranges[i] || bindings[i] !== other.bindings[i]) return false
        }
        return true
    }
}
