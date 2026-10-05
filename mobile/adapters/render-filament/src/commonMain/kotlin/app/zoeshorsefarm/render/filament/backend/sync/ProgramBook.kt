package app.zoeshorsefarm.render.filament.backend.sync

import app.zoeshorsefarm.scene.material.Material

/**
 * The shader programs the scene would have, by the rules of the scene model's `GpuTracker` (which
 * mirrors three.js): a material holds one program per distinct `programKey` it was drawn with and
 * keeps all of them until it is disposed; materials with the same key share a program, which goes
 * away with the last material that held it. The count is what `RenderBackend.info.programs` reports,
 * so the view's program budget rules (`world-budget`, `world-stages`) hold on the device as well.
 *
 * Filament compiles its own variants of every material (see `ProgramEstimate`); that is a separate
 * number and not what the view code reasons about.
 */
class ProgramBook {
    private val keysOfMaterial = HashMap<Material, MutableSet<String>>()
    private val references = LinkedHashMap<String, Int>()

    /** Programs alive. */
    val count: Int get() = references.size

    /** Keys of the live programs, sorted (for messages). */
    fun keys(): List<String> = references.keys.sorted()

    fun materialCount(): Int = keysOfMaterial.size

    /** Records that `material` is drawn with the program `key`. Returns true if that was new. */
    fun add(
        material: Material,
        key: String,
    ): Boolean {
        val keys = keysOfMaterial.getOrPut(material) { LinkedHashSet() }
        if (!keys.add(key)) return false
        references[key] = (references[key] ?: 0) + 1
        return true
    }

    /** Frees the programs of a disposed material. */
    fun release(material: Material) {
        val keys = keysOfMaterial.remove(material) ?: return
        for (key in keys) {
            val remaining = (references[key] ?: 1) - 1
            if (remaining <= 0) references.remove(key) else references[key] = remaining
        }
    }

    fun clear() {
        keysOfMaterial.clear()
        references.clear()
    }
}
