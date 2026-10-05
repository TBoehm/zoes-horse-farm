package app.zoeshorsefarm.render.filament.backend.sync

import app.zoeshorsefarm.render.filament.backend.device.GpuDevice
import app.zoeshorsefarm.render.filament.material.MaterialBuildException
import app.zoeshorsefarm.render.filament.material.MaterialSpec
import app.zoeshorsefarm.scene.DisposeListener
import app.zoeshorsefarm.scene.graph.Node
import app.zoeshorsefarm.scene.material.Material

/**
 * The material instances of the scene's materials. A scene material gets one [MaterialBinding] per
 * spec it is drawn with (and per node for instanced meshes); bindings live until the material is
 * disposed (`free`), like three.js programs. The Filament material of a spec is built on first use
 * and freed with its last instance, so disposing the materials of a quality level frees its shaders.
 */
internal class MaterialRegistry(
    private val device: GpuDevice,
    private val textures: TextureRegistry,
    private val disposeListener: DisposeListener,
    private val log: SyncLog,
    /** Called when the Filament material of a spec was freed with its last instance. */
    private val onSpecReleased: (MaterialSpec) -> Unit = {},
) {
    private class SpecRef(
        val spec: MaterialSpec,
        var count: Int,
    )

    private val records = HashMap<Material, ArrayList<MaterialBinding>>()
    private val specRefs = HashMap<String, SpecRef>()
    private val brokenSpecs = HashSet<String>()

    /** Material instances alive. */
    val bindingCount: Int
        get() {
            var total = 0
            for (list in records.values) total += list.size
            return total
        }

    /** The binding of `material` for `spec`, made on first use; null if the shader does not compile. */
    fun bind(
        material: Material,
        spec: MaterialSpec,
        owner: Node?,
    ): MaterialBinding? {
        val list = records[material]
        if (list != null) {
            for (i in list.indices) {
                val binding = list[i]
                if (binding.owner === owner && binding.spec == spec) return binding
            }
        }
        return create(material, spec, owner)
    }

    /** Frees every instance of a disposed material (and the Filament materials nothing else uses). */
    fun free(material: Material) {
        val list = records.remove(material) ?: return
        material.removeDisposeListener(disposeListener)
        for (binding in list) destroy(binding)
    }

    /** Frees one binding that belongs to a node that is gone. */
    fun release(binding: MaterialBinding) {
        val list = records[binding.material] ?: return
        if (!list.remove(binding)) return
        if (list.isEmpty()) {
            records.remove(binding.material)
            binding.material.removeDisposeListener(disposeListener)
        }
        destroy(binding)
    }

    fun clear() {
        for ((material, list) in records) {
            material.removeDisposeListener(disposeListener)
            for (binding in list) destroy(binding)
        }
        records.clear()
    }

    private fun create(
        material: Material,
        spec: MaterialSpec,
        owner: Node?,
    ): MaterialBinding? {
        val key = spec.key
        if (key in brokenSpecs) return null
        val instance =
            try {
                device.createMaterialInstance(spec, material.name.ifEmpty { key })
            } catch (e: MaterialBuildException) {
                brokenSpecs += key
                log.warn("material $key cannot be drawn: ${e.message}")
                return null
            }
        val binding = MaterialBinding(material, spec, owner, instance, textures, device.placeholderTexture)
        val list =
            records.getOrPut(material) {
                material.addDisposeListener(disposeListener)
                ArrayList(1)
            }
        list += binding
        specRefs.getOrPut(key) { SpecRef(spec, 0) }.count++
        return binding
    }

    private fun destroy(binding: MaterialBinding) {
        // a copy: the users unregister themselves while they drop their renderables
        val users = binding.users.toTypedArray()
        binding.users.clear()
        for (user in users) user.onMaterialReset()
        binding.release()
        binding.instance.destroy()
        val key = binding.spec.key
        val ref = specRefs[key] ?: return
        ref.count--
        if (ref.count <= 0) {
            specRefs.remove(key)
            device.releaseMaterial(ref.spec)
            onSpecReleased(ref.spec)
        }
    }
}
