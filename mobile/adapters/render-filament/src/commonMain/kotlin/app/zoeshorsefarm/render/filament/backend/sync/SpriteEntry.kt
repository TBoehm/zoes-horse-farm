package app.zoeshorsefarm.render.filament.backend.sync

import app.zoeshorsefarm.render.filament.backend.device.RenderableHandle
import app.zoeshorsefarm.render.filament.backend.mapping.MaterialMapping
import app.zoeshorsefarm.render.filament.backend.mapping.RenderOrder
import app.zoeshorsefarm.render.filament.backend.mapping.SpriteBillboard
import app.zoeshorsefarm.render.filament.mesh.RenderableOptions
import app.zoeshorsefarm.scene.graph.Sprite

/**
 * Draws a `Sprite` (the pin above the next obstacle) as the unit quad turned to the camera: every
 * frame the model matrix is composed from the node's world matrix and the camera (see
 * [SpriteBillboard]). Sprites are not counted in the program books, like in the scene model's
 * `GpuTracker`.
 */
internal class SpriteEntry(
    private val sprite: Sprite,
) : DrawEntry(sprite),
    MaterialUser {
    private var renderable: RenderableHandle? = null
    private var binding: MaterialBinding? = null
    private var bindingVersion = -1
    private var shown = false
    private val transform = TransformCache()
    private val nodeWorld = FloatArray(MATRIX_SIZE)
    private var culling = true
    private var renderOrder = 0
    private var fog = true

    override fun update(
        ctx: SyncContext,
        visible: Boolean,
    ) {
        if (!visible) {
            hide()
            return
        }
        val current = bindingFor(ctx)
        if (current == null) {
            hide()
            return
        }
        val drawable = renderable ?: build(ctx, current)
        current.sync(ctx.frame)
        syncFlags(drawable, current)
        sprite.matrixWorld.toFloatArray(nodeWorld)
        SpriteBillboard.compose(
            nodeWorld,
            ctx.cameraWorld,
            sprite.center.x.toFloat(),
            sprite.center.y.toFloat(),
            sprite.material.rotation.toFloat(),
            transform.current,
        )
        if (transform.commit()) drawable.setTransform(transform.current)
        if (!shown) {
            drawable.setVisible(true)
            shown = true
        }
        ctx.countQuad()
    }

    override fun onMaterialReset() {
        dropRenderable()
        binding = null
        bindingVersion = -1
    }

    override fun destroy(ctx: SyncContext) {
        dropRenderable()
        binding?.users?.remove(this)
    }

    private fun bindingFor(ctx: SyncContext): MaterialBinding? {
        val material = sprite.material
        val existing = binding
        if (existing != null && existing.material === material && bindingVersion == material.version) return existing
        val spec = MaterialMapping.specFor(sprite, material, null)
        if (spec == null) {
            ctx.log.warn("sprite material '${material.name}' has no Filament shader and is not drawn")
            return null
        }
        dropRenderable()
        existing?.users?.remove(this)
        bindingVersion = material.version
        val fresh = ctx.materials.bind(material, spec, null)
        // told when the material is disposed, also before the first draw
        fresh?.users?.add(this)
        binding = fresh
        return fresh
    }

    private fun build(
        ctx: SyncContext,
        current: MaterialBinding,
    ): RenderableHandle {
        val options =
            RenderableOptions(
                culling = sprite.frustumCulled,
                priority = RenderOrder.priority(sprite.renderOrder),
                blendOrder = RenderOrder.blendOrder(sprite.renderOrder),
                fog = current.material.fog,
            )
        val created = ctx.device.createRenderable(ctx.spriteQuad, listOf(current.instance), null, options)
        renderable = created
        culling = sprite.frustumCulled
        renderOrder = sprite.renderOrder
        fog = current.material.fog
        shown = true
        transform.invalidate()
        return created
    }

    private fun syncFlags(
        drawable: RenderableHandle,
        current: MaterialBinding,
    ) {
        if (sprite.frustumCulled != culling) {
            culling = sprite.frustumCulled
            drawable.setCulling(culling)
        }
        if (sprite.renderOrder != renderOrder) {
            renderOrder = sprite.renderOrder
            drawable.setDrawOrder(RenderOrder.priority(renderOrder), RenderOrder.blendOrder(renderOrder))
        }
        if (current.material.fog != fog) {
            fog = current.material.fog
            drawable.setFog(fog)
        }
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
        transform.invalidate()
    }

    private companion object {
        const val MATRIX_SIZE = 16
    }
}
