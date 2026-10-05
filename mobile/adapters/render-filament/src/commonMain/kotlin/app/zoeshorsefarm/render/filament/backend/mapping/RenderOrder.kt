package app.zoeshorsefarm.render.filament.backend.mapping

import app.zoeshorsefarm.render.filament.mesh.RenderableOptions
import kotlin.math.max
import kotlin.math.min

/**
 * three.js `renderOrder` as Filament draw order. Filament has eight coarse priorities (0 draws
 * first, default 4) that are applied separately to opaque and transparent objects, and a blend
 * order for transparent primitives. A `renderOrder` of -10 (the sky) therefore becomes priority 0,
 * 2 (dust, glow) priority 6 and 3 and above priority 7; among the transparent objects the exact
 * `renderOrder` decides through the global blend order. Objects with the same order are sorted by
 * distance, as in three.js.
 */
object RenderOrder {
    private const val BLEND_BIAS = 64
    private const val MAX_BLEND_ORDER = 32767

    fun priority(renderOrder: Int): Int =
        max(
            0,
            min(
                RenderableOptions.MAX_PRIORITY,
                RenderableOptions.DEFAULT_PRIORITY + renderOrder,
            ),
        )

    fun blendOrder(renderOrder: Int): Int = max(0, min(MAX_BLEND_ORDER, BLEND_BIAS + renderOrder))
}
