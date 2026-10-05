package app.zoeshorsefarm.scene.render

import app.zoeshorsefarm.scene.DisposeListener
import app.zoeshorsefarm.scene.GpuObject
import app.zoeshorsefarm.scene.geometry.Geometry
import app.zoeshorsefarm.scene.graph.InstancedMesh
import app.zoeshorsefarm.scene.graph.Mesh
import app.zoeshorsefarm.scene.graph.Node
import app.zoeshorsefarm.scene.graph.Points
import app.zoeshorsefarm.scene.graph.Scene
import app.zoeshorsefarm.scene.graph.SkinnedMesh
import app.zoeshorsefarm.scene.graph.Traversable
import app.zoeshorsefarm.scene.material.Material
import app.zoeshorsefarm.scene.material.Side
import app.zoeshorsefarm.scene.material.StandardMaterial
import kotlin.math.max

/** The scene state that changes which program a material is drawn with. */
data class ProgramState(
    val fog: Boolean = false,
    val environment: Boolean = false,
    val shadows: Boolean = false,
)

/** Number of live GPU objects by kind. */
data class GpuCounts(
    val programs: Int,
    val materials: Int,
    val geometries: Int,
    val instanced: Int,
)

/**
 * Approximation of the program a material is drawn with: the values that differ between the
 * materials of this game (type, effect key, instancing, vertex colours, textures, side, skinning,
 * fog, environment map, shadows). Exact enough to compare quality levels and to see leaks; it
 * is the same key in the real backend's accounting.
 */
fun programKey(
    node: Node,
    material: Material,
    state: ProgramState,
): String {
    val instanced = node as? InstancedMesh
    val flags =
        listOf(
            material.type,
            material.customProgramCacheKey(),
            flag(instanced != null, "inst"),
            flag(instanced?.instanceColor != null, "instColor"),
            flag(node is SkinnedMesh, "skin"),
            flag(node is Points, "points"),
            flag(material.vertexColors, "vc"),
            flag(material.map != null, "map"),
            flag(material is StandardMaterial && material.normalMap != null, "nmap"),
            flag(material.alphaMap != null, "amap"),
            flag(material.side == Side.DOUBLE, "double"),
            flag(material.transparent, "transp"),
            flag(state.fog && material.fog, "fog"),
            flag(state.environment && material is StandardMaterial, "env"),
            flag(state.shadows && node.receiveShadow, "shadow"),
        )
    return flags.joinToString("|")
}

private fun flag(
    on: Boolean,
    name: String,
): String = if (on) name else ""

/**
 * A stand-in for what the GPU keeps, for JVM tests (rule 4: the peak of live shader programs and
 * objects during a level change). No GPU: it follows the bookkeeping rules of three.js r186:
 *
 * - a material gets one program per distinct [programKey] it is drawn with, and keeps ALL of them
 *   until it is disposed, so a material that switches variant without a dispose holds the old
 *   programs as well;
 * - programs are shared between materials with the same key, a program is freed when the last
 *   material releases it;
 * - geometries and instanced meshes (their instance buffers) are uploaded when they are drawn and
 *   freed by dispose().
 *
 * [compile] is a draw of everything the root yields through `traverse` (the world's compile root
 * yields the visible objects only); it uploads what is new.
 */
class GpuTracker(
    private val scene: Scene,
    private val shadowsEnabled: () -> Boolean = { false },
) {
    private val materials = LinkedHashMap<Material, MutableSet<String>>()
    private val materialListeners = HashMap<Material, DisposeListener>()
    private val programRefs = LinkedHashMap<String, Int>()
    private val geometries = LinkedHashSet<Geometry>()
    private val instanced = LinkedHashSet<InstancedMesh>()
    private val objectListeners = HashMap<GpuObject, DisposeListener>()
    private var peakCounts = GpuCounts(0, 0, 0, 0)

    fun snapshot(): GpuCounts = GpuCounts(programRefs.size, materials.size, geometries.size, instanced.size)

    /** Highest number of live objects seen since the last [resetPeak], measured after every upload and dispose. */
    val peak: GpuCounts get() = peakCounts

    fun resetPeak() {
        peakCounts = snapshot()
    }

    /** Is this geometry, instanced mesh or material uploaded now? */
    fun holds(item: Any): Boolean =
        when (item) {
            is Geometry -> item in geometries
            is InstancedMesh -> item in instanced
            is Material -> item in materials
            else -> false
        }

    /** Keys of the live programs (for messages). */
    fun programKeys(): List<String> = programRefs.keys.sorted()

    fun compile(root: Traversable) {
        root.traverse { node ->
            if (node is Mesh) {
                node.forEachMaterial { uploadMaterial(node, it) }
                uploadGeometry(node.geometry)
                if (node is InstancedMesh) uploadInstanced(node)
            } else if (node is Points) {
                uploadMaterial(node, node.material)
                uploadGeometry(node.geometry)
            }
        }
        bumpPeak()
    }

    private fun bumpPeak() {
        val now = snapshot()
        peakCounts =
            GpuCounts(
                max(peakCounts.programs, now.programs),
                max(peakCounts.materials, now.materials),
                max(peakCounts.geometries, now.geometries),
                max(peakCounts.instanced, now.instanced),
            )
    }

    private fun releaseMaterial(material: Material) {
        val keys = materials.remove(material) ?: return
        for (key in keys) {
            val n = (programRefs[key] ?: 1) - 1
            if (n <= 0) programRefs.remove(key) else programRefs[key] = n
        }
    }

    private fun uploadMaterial(
        node: Node,
        material: Material,
    ) {
        val state = ProgramState(scene.fog != null, scene.environment != null, shadowsEnabled())
        val key = programKey(node, material, state)
        var keys = materials[material]
        if (keys == null) {
            keys = LinkedHashSet()
            materials[material] = keys
            val listener =
                DisposeListener {
                    materialListeners.remove(material)?.let { material.removeDisposeListener(it) }
                    releaseMaterial(material)
                    bumpPeak()
                }
            materialListeners[material] = listener
            material.addDisposeListener(listener)
        }
        if (keys.add(key)) programRefs[key] = (programRefs[key] ?: 0) + 1
    }

    private fun uploadGeometry(geometry: Geometry) {
        if (!geometries.add(geometry)) return
        val listener =
            DisposeListener {
                geometries.remove(geometry)
                objectListeners.remove(geometry)?.let { geometry.removeDisposeListener(it) }
            }
        objectListeners[geometry] = listener
        geometry.addDisposeListener(listener)
    }

    private fun uploadInstanced(mesh: InstancedMesh) {
        if (!instanced.add(mesh)) return
        val listener =
            DisposeListener {
                instanced.remove(mesh)
                objectListeners.remove(mesh)?.let { mesh.removeDisposeListener(it) }
            }
        objectListeners[mesh] = listener
        mesh.addDisposeListener(listener)
    }
}
