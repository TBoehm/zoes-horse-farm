package app.zoeshorsefarm.render.filament.backend.sync

import app.zoeshorsefarm.render.filament.backend.device.MaterialInstanceHandle
import app.zoeshorsefarm.render.filament.backend.device.TextureHandle
import app.zoeshorsefarm.render.filament.backend.mapping.MaterialMapping
import app.zoeshorsefarm.render.filament.backend.mapping.MaterialValues
import app.zoeshorsefarm.render.filament.backend.mapping.ParamSink
import app.zoeshorsefarm.render.filament.material.MaterialSources
import app.zoeshorsefarm.render.filament.material.MaterialSpec
import app.zoeshorsefarm.scene.graph.Node
import app.zoeshorsefarm.scene.material.Material
import app.zoeshorsefarm.scene.material.StandardMaterial
import app.zoeshorsefarm.scene.material.Wind
import app.zoeshorsefarm.scene.texture.Texture

/** A renderable that draws with a binding's material instance: told when the instance is about to be destroyed. */
internal interface MaterialUser {
    /** The instance is about to be destroyed: drop the renderable. */
    fun onMaterialReset()
}

/**
 * One Filament material instance for a scene material drawn with one spec (one shader variant), and
 * the bookkeeping that keeps it in step with the scene material: uniform values are written every
 * frame but only passed on when they changed, textures are bound (and uploaded) when they are first
 * needed or replaced, polygon offset and depth test follow the material. `owner` is set for the
 * instances that belong to one node (instanced meshes have their own instance data texture).
 */
internal class MaterialBinding(
    val material: Material,
    val spec: MaterialSpec,
    val owner: Node?,
    val instance: MaterialInstanceHandle,
    private val textures: TextureRegistry,
    private val placeholder: TextureHandle,
) : ParamSink,
    TextureUser {
    /** The wind that moves this material, if any. */
    val wind: Wind? = MaterialMapping.windOfMaterial(material)

    val users = ArrayList<MaterialUser>(1)

    private class Slot(
        val name: String,
        val kind: Int,
    ) {
        var texture: Texture? = null
        var bound: TextureHandle? = null
    }

    private val slots: Array<Slot> = slotsOf(spec)
    private val cache = HashMap<String, FloatArray>()
    private var lastFrame = -1
    private var offsetFactor = 0f
    private var offsetUnits = 0f
    private var depthTest = true

    /** Writes the values of the material (once per frame, however many meshes share the binding). */
    fun sync(frame: Int) {
        if (lastFrame == frame) return
        lastFrame = frame
        MaterialValues.write(material, spec, this)
        for (i in slots.indices) syncSlot(slots[i])
        syncState()
    }

    /** Forgets the textures: the instance is about to be destroyed. */
    fun release() {
        for (i in slots.indices) {
            val texture = slots[i].texture ?: continue
            textures.unwatch(texture, this)
            slots[i].texture = null
            slots[i].bound = null
        }
    }

    override fun onTextureChanged(
        texture: Texture,
        handle: TextureHandle,
    ) {
        for (i in slots.indices) {
            if (slots[i].texture !== texture) continue
            instance.setTexture(slots[i].name, handle)
            slots[i].bound = handle
        }
    }

    override fun onTextureFreed(texture: Texture) {
        for (i in slots.indices) {
            if (slots[i].texture !== texture) continue
            // never leave the instance pointing at a destroyed texture; sync binds a new one
            instance.setTexture(slots[i].name, placeholder)
            slots[i].texture = null
            slots[i].bound = null
        }
    }

    override fun setFloat(
        name: String,
        x: Float,
    ) {
        val last = cache[name]
        if (last == null) {
            cache[name] = floatArrayOf(x)
        } else if (last[0] == x) {
            return
        } else {
            last[0] = x
        }
        instance.setFloat(name, x)
    }

    override fun setFloat3(
        name: String,
        x: Float,
        y: Float,
        z: Float,
    ) {
        val last = cache[name]
        if (last == null) {
            cache[name] = floatArrayOf(x, y, z)
        } else if (last[0] == x && last[1] == y && last[2] == z) {
            return
        } else {
            last[0] = x
            last[1] = y
            last[2] = z
        }
        instance.setFloat3(name, x, y, z)
    }

    override fun setFloat4(
        name: String,
        x: Float,
        y: Float,
        z: Float,
        w: Float,
    ) {
        val last = cache[name]
        if (last == null) {
            cache[name] = floatArrayOf(x, y, z, w)
        } else if (same(last, x, y, z, w)) {
            return
        } else {
            last[0] = x
            last[1] = y
            last[2] = z
            last[3] = w
        }
        instance.setFloat4(name, x, y, z, w)
    }

    private fun same(
        last: FloatArray,
        x: Float,
        y: Float,
        z: Float,
        w: Float,
    ): Boolean = last[0] == x && last[1] == y && last[2] == z && last[3] == w

    private fun syncSlot(slot: Slot) {
        val texture = textureOf(slot.kind) ?: return
        if (texture !== slot.texture) {
            slot.texture?.let { textures.unwatch(it, this) }
            slot.texture = texture
            slot.bound = null
            textures.watch(texture, this)
        }
        val handle = textures.acquire(texture)
        if (handle !== slot.bound) {
            instance.setTexture(slot.name, handle)
            slot.bound = handle
        }
    }

    private fun textureOf(kind: Int): Texture? =
        when (kind) {
            KIND_MAP -> material.map
            KIND_NORMAL -> (material as? StandardMaterial)?.normalMap
            else -> material.alphaMap
        }

    private fun syncState() {
        val factor = if (material.polygonOffset) material.polygonOffsetFactor.toFloat() else 0f
        val units = if (material.polygonOffset) material.polygonOffsetUnits.toFloat() else 0f
        if (factor != offsetFactor || units != offsetUnits) {
            offsetFactor = factor
            offsetUnits = units
            instance.setPolygonOffset(factor, units)
        }
        if (material.depthTest != depthTest) {
            depthTest = material.depthTest
            instance.setDepthTest(depthTest)
        }
    }

    private companion object {
        const val KIND_MAP = 0
        const val KIND_NORMAL = 1
        const val KIND_ALPHA = 2

        fun slotsOf(spec: MaterialSpec): Array<Slot> {
            val slots = ArrayList<Slot>(3)
            if (spec.baseColorMap) slots += Slot(MaterialSources.BASE_COLOR_MAP, KIND_MAP)
            if (spec.normalMap) slots += Slot(MaterialSources.NORMAL_MAP, KIND_NORMAL)
            if (spec.alphaMap) slots += Slot(MaterialSources.ALPHA_MAP, KIND_ALPHA)
            return slots.toTypedArray()
        }
    }
}
