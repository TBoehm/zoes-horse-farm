package app.zoeshorsefarm.render.filament.mesh

import app.zoeshorsefarm.render.filament.resources.GpuResourceTracker
import io.github.erkko68.filament.Engine

/**
 * The GPU side of a pool of particles (see [SpriteQuads]): a mesh of `capacity` quads and the
 * [update] that moves them. Draw [mesh] with a `SPRITE` material.
 */
class SpriteBatch(
    engine: Engine,
    tracker: GpuResourceTracker,
    label: String,
    val capacity: Int,
) {
    val mesh: GpuMesh = GpuMesh.upload(engine, tracker, label, SpriteQuads.meshData(capacity))

    private val positions = FloatArray(capacity * QUAD_VERTICES * 3)
    private val puffs = FloatArray(capacity * QUAD_VERTICES * 2)

    // one ring per attribute: both are rewritten every frame and Filament releases a slot only a
    // frame or two later, so a shared ring of three would run dry every other frame
    private val positionRing = UploadRing(RING_SLOTS)
    private val puffRing = UploadRing(RING_SLOTS)

    /** How often an upload had to use a one shot array because the driver still held every slot. */
    val overflowCount: Int get() = positionRing.overflowCount + puffRing.overflowCount

    /**
     * Sets the first `count` particles: `centers` xyz each, `sizes` in metres, `opacities` 0..1. The
     * particles above `count` keep their last values: pass `capacity` and zero sizes for the unused.
     */
    fun update(
        centers: FloatArray,
        sizes: FloatArray,
        opacities: FloatArray,
        count: Int,
    ) {
        require(count in 0..capacity) { "count $count is outside 0..$capacity" }
        SpriteQuads.expandCenters(centers, count, positions)
        SpriteQuads.expandPuffs(sizes, opacities, count, puffs)
        mesh.updateFloats(VertexSemantic.POSITION, positions, count * QUAD_VERTICES * 3, positionRing)
        mesh.updateFloats(VertexSemantic.CUSTOM1, puffs, count * QUAD_VERTICES * 2, puffRing)
    }

    fun destroy() = mesh.destroy()

    private companion object {
        const val QUAD_VERTICES = 4
        const val RING_SLOTS = 4
    }
}
