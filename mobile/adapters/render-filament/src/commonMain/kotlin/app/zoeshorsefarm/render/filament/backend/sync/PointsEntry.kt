package app.zoeshorsefarm.render.filament.backend.sync

import app.zoeshorsefarm.render.filament.backend.device.RenderableHandle
import app.zoeshorsefarm.render.filament.backend.device.SpriteBatchHandle
import app.zoeshorsefarm.render.filament.backend.mapping.MaterialMapping
import app.zoeshorsefarm.render.filament.backend.mapping.RenderOrder
import app.zoeshorsefarm.render.filament.mesh.Aabb
import app.zoeshorsefarm.render.filament.mesh.RenderableOptions
import app.zoeshorsefarm.render.filament.mesh.SpriteQuads
import app.zoeshorsefarm.scene.GpuObject
import app.zoeshorsefarm.scene.geometry.FloatAttribute
import app.zoeshorsefarm.scene.geometry.Geometry
import app.zoeshorsefarm.scene.graph.Points
import app.zoeshorsefarm.scene.material.PointsMaterial
import kotlin.math.max

/**
 * Draws a `Points` object (the hoof dust) as camera facing quads: Filament cannot set a point size, so
 * the batch has one quad per vertex and the material moves its corners apart (see `SpriteQuads`).
 *
 * The geometry's `position` attribute gives the centres, `aSize` the diameter in metres and `aAlpha`
 * the opacity of each particle (three.js sizes them in pixels through a camera scale uniform: the same
 * thing seen from the camera). Missing `aSize` takes the material's `size` in metres, a missing
 * `aAlpha` is 1. The batch is rewritten when one of the three attributes is marked `needsUpdate`.
 */
internal class PointsEntry(
    private val points: Points,
) : DrawEntry(points),
    MaterialUser {
    private var batch: SpriteBatchHandle? = null
    private var batchGeometry: Geometry? = null
    private var renderable: RenderableHandle? = null
    private var binding: MaterialBinding? = null
    private var bindingVersion = -1
    private var shown = false
    private val transform = TransformCache()
    private val box = Aabb()
    private var positionVersion = -1
    private var sizeVersion = -1
    private var alphaVersion = -1
    private var constantSizes = FloatArray(0)
    private var constantAlphas = FloatArray(0)
    private var culling = true
    private var renderOrder = 0
    private var fog = true
    private var booked: MaterialBinding? = null
    private var bookedBits = -1

    override fun update(
        ctx: SyncContext,
        visible: Boolean,
    ) {
        val position = if (visible) positionsOf(points.geometry) else null
        if (position == null) {
            hide()
            return
        }
        val live = batchFor(ctx, position.count)
        val current = bindingFor(ctx)
        if (current == null) {
            hide()
            return
        }
        feed(live, points.geometry, position)
        val drawable = renderable ?: build(ctx, live, current)
        current.sync(ctx.frame)
        ctx.noteWind(current.wind)
        syncFlags(drawable, current)
        if (transform.refresh(points.matrixWorld)) drawable.setTransform(transform.current)
        book(ctx, current)
        if (!shown) {
            drawable.setVisible(true)
            shown = true
        }
        ctx.countPoints()
    }

    override fun onDisposed(
        ctx: SyncContext,
        resource: GpuObject,
    ) {
        if (resource === batchGeometry) dropBatch(ctx)
    }

    override fun onMaterialReset() {
        dropRenderable()
        binding = null
        bindingVersion = -1
    }

    override fun destroy(ctx: SyncContext) {
        dropBatch(ctx)
        dropRenderable()
    }

    private fun positionsOf(geometry: Geometry): FloatAttribute? {
        val position = geometry.getAttribute("position") as? FloatAttribute ?: return null
        val ok = position.itemSize == 3 && position.count in 1..SpriteQuads.MAX_CAPACITY
        return if (ok) position else null
    }

    private fun batchFor(
        ctx: SyncContext,
        count: Int,
    ): SpriteBatchHandle {
        val existing = batch
        if (existing != null && existing.capacity == count && batchGeometry === points.geometry) return existing
        dropBatch(ctx)
        val created = ctx.device.createSpriteBatch(points.name.ifEmpty { "points" }, count)
        batch = created
        batchGeometry = points.geometry
        points.geometry.addDisposeListener(ctx.disposeListener)
        positionVersion = -1
        sizeVersion = -1
        alphaVersion = -1
        constantSizes = FloatArray(0)
        constantAlphas = FloatArray(0)
        return created
    }

    private fun bindingFor(ctx: SyncContext): MaterialBinding? {
        val material = points.material
        val existing = binding
        if (existing != null && existing.material === material && bindingVersion == material.version) return existing
        val spec = MaterialMapping.specFor(points, material, null)
        if (spec == null) {
            ctx.log.warn(
                "points material '${material.name}' (${material.type}) has no Filament shader and is not drawn",
            )
            return null
        }
        dropRenderable()
        bindingVersion = material.version
        binding = ctx.materials.bind(material, spec, null)
        return binding
    }

    private fun feed(
        live: SpriteBatchHandle,
        geometry: Geometry,
        position: FloatAttribute,
    ) {
        val count = position.count
        val sizeAttribute =
            (geometry.getAttribute("aSize") as? FloatAttribute)?.takeIf {
                it.itemSize == 1 &&
                    it.count == count
            }
        val alphaAttribute =
            (geometry.getAttribute("aAlpha") as? FloatAttribute)?.takeIf {
                it.itemSize == 1 &&
                    it.count == count
            }
        val dirty =
            position.version != positionVersion ||
                (sizeAttribute?.version ?: NONE) != sizeVersion ||
                (alphaAttribute?.version ?: NONE) != alphaVersion
        if (!dirty) return
        positionVersion = position.version
        sizeVersion = sizeAttribute?.version ?: NONE
        alphaVersion = alphaAttribute?.version ?: NONE
        val sizes =
            sizeAttribute?.array ?: constant(count, (points.material as? PointsMaterial)?.size?.toFloat() ?: 1f, true)
        val alphas = alphaAttribute?.array ?: constant(count, 1f, false)
        live.update(position.array, sizes, alphas, count)
        updateBounds(position, sizes, count)
    }

    private fun constant(
        count: Int,
        value: Float,
        sizes: Boolean,
    ): FloatArray {
        val current = if (sizes) constantSizes else constantAlphas
        if (current.size == count) return current
        val array = FloatArray(count) { value }
        if (sizes) constantSizes = array else constantAlphas = array
        return array
    }

    private fun updateBounds(
        position: FloatAttribute,
        sizes: FloatArray,
        count: Int,
    ) {
        box.setFromPositions(position.array, count)
        var largest = 0f
        for (i in 0 until count) largest = max(largest, sizes[i])
        val pad = largest * HALF
        box.inflate(pad)
        renderable?.setBounds(box)
    }

    private fun build(
        ctx: SyncContext,
        live: SpriteBatchHandle,
        current: MaterialBinding,
    ): RenderableHandle {
        val material = current.material
        val options =
            RenderableOptions(
                culling = points.frustumCulled,
                priority = RenderOrder.priority(points.renderOrder),
                blendOrder = RenderOrder.blendOrder(points.renderOrder),
                fog = material.fog,
                bounds = box,
            )
        val created = ctx.device.createRenderable(live.mesh, listOf(current.instance), null, options)
        renderable = created
        current.users += this
        culling = points.frustumCulled
        renderOrder = points.renderOrder
        fog = material.fog
        shown = true
        transform.invalidate()
        return created
    }

    private fun syncFlags(
        drawable: RenderableHandle,
        current: MaterialBinding,
    ) {
        if (points.frustumCulled != culling) {
            culling = points.frustumCulled
            drawable.setCulling(culling)
        }
        if (points.renderOrder != renderOrder) {
            renderOrder = points.renderOrder
            drawable.setPriority(RenderOrder.priority(renderOrder))
        }
        if (current.material.fog != fog) {
            fog = current.material.fog
            drawable.setFog(fog)
        }
    }

    private fun book(
        ctx: SyncContext,
        current: MaterialBinding,
    ) {
        val bits = ctx.programStateBits(false)
        if (current === booked && bits == bookedBits) return
        ctx.bookProgram(points, current.material)
        booked = current
        bookedBits = bits
    }

    private fun hide() {
        val live = renderable ?: return
        if (!shown) return
        live.setVisible(false)
        shown = false
    }

    private fun dropRenderable() {
        renderable?.destroy()
        renderable = null
        shown = false
        binding?.users?.remove(this)
        transform.invalidate()
    }

    private fun dropBatch(ctx: SyncContext) {
        dropRenderable()
        batchGeometry?.removeDisposeListener(ctx.disposeListener)
        batchGeometry = null
        batch?.destroy()
        batch = null
    }

    private companion object {
        const val NONE = -2
        const val HALF = 0.5f
    }
}
