package app.zoeshorsefarm.scene.material

import app.zoeshorsefarm.scene.GpuResource
import app.zoeshorsefarm.scene.math.Color
import app.zoeshorsefarm.scene.math.Vec2
import app.zoeshorsefarm.scene.math.Vec3
import app.zoeshorsefarm.scene.texture.Texture

/** Which faces are drawn (three.js `FrontSide`, `BackSide`, `DoubleSide`). */
enum class Side { FRONT, BACK, DOUBLE }

/**
 * Describes how a surface looks. Materials are data: there is no shader code here. A render backend
 * turns the fields into whatever its renderer needs and keeps one compiled variant ("program") per
 * [programKey] / feature combination. Change a field and set [needsUpdate] when the *variant*
 * changes (a texture added, [effect] switched, `side` changed); plain values (colour, opacity,
 * roughness, uniforms) are read every frame.
 */
abstract class Material(
    /** three.js class name, for example `"MeshStandardMaterial"`: part of the program key. */
    val type: String,
) : GpuResource() {
    var name: String = ""

    /** Base colour in the linear working space (set from sRGB hex by `color.set(0xrrggbb)`). */
    val color: Color = Color(1.0, 1.0, 1.0)
    var map: Texture? = null
    var alphaMap: Texture? = null
    var vertexColors: Boolean = false
    var transparent: Boolean = false
    var opacity: Double = 1.0
    var side: Side = Side.FRONT
    var depthWrite: Boolean = true
    var depthTest: Boolean = true

    /** Affected by the scene's fog. */
    var fog: Boolean = true

    /** Affected by the renderer's tone mapping. */
    var toneMapped: Boolean = true
    var flatShading: Boolean = false
    var alphaTest: Double = 0.0
    var polygonOffset: Boolean = false
    var polygonOffsetFactor: Double = 0.0
    var polygonOffsetUnits: Double = 0.0
    val userData: MutableMap<String, Any?> = HashMap()

    /** Named shader patch (replacement for `onBeforeCompile`), or null for the plain program. */
    var effect: MaterialEffect? = null

    /**
     * Switches [effect] on or off without losing it (the wind patch is off on low quality and on again
     * later). Set [needsUpdate] (and dispose the material to free the old program) after toggling.
     */
    var effectEnabled: Boolean = true

    /** Incremented every time [needsUpdate] is set to true: the backend rebuilds the variant. */
    var version: Int = 0
        private set

    var needsUpdate: Boolean = false
        set(value) {
            field = value
            if (value) version++
        }

    /** The effect that is currently active (null when none or switched off). */
    val activeEffect: MaterialEffect? get() = if (effectEnabled) effect else null

    /** Part of the program key that comes from the effect (three.js `customProgramCacheKey`). */
    open fun customProgramCacheKey(): String = activeEffect?.programKey ?: ""

    /** Textures this material references (they are GPU objects as well). */
    open fun textures(): List<Texture> = listOfNotNull(map, alphaMap)

    protected fun configure(
        color: Int,
        map: Texture?,
        alphaMap: Texture?,
        vertexColors: Boolean,
        transparent: Boolean,
        opacity: Double,
        side: Side,
        depthWrite: Boolean,
        depthTest: Boolean,
        fog: Boolean,
        toneMapped: Boolean,
        flatShading: Boolean,
        alphaTest: Double,
        polygonOffset: Boolean,
        polygonOffsetFactor: Double,
        polygonOffsetUnits: Double,
        effect: MaterialEffect?,
    ) {
        this.color.setHex(color)
        this.map = map
        this.alphaMap = alphaMap
        this.vertexColors = vertexColors
        this.transparent = transparent
        this.opacity = opacity
        this.side = side
        this.depthWrite = depthWrite
        this.depthTest = depthTest
        this.fog = fog
        this.toneMapped = toneMapped
        this.flatShading = flatShading
        this.alphaTest = alphaTest
        this.polygonOffset = polygonOffset
        this.polygonOffsetFactor = polygonOffsetFactor
        this.polygonOffsetUnits = polygonOffsetUnits
        this.effect = effect
    }
}

/**
 * The values two materials of a "standard + Lambert" pair share (the web app builds both from one
 * parameter object). `roughness` / `metalness` only apply to [StandardMaterial].
 */
data class MaterialParams(
    val color: Int = 0xffffff,
    val roughness: Double? = null,
    val metalness: Double? = null,
    val map: Texture? = null,
    val alphaMap: Texture? = null,
    val normalMap: Texture? = null,
    val normalScale: Vec2 = Vec2(1.0, 1.0),
    val vertexColors: Boolean = false,
    val transparent: Boolean = false,
    val opacity: Double = 1.0,
    val side: Side = Side.FRONT,
    val depthWrite: Boolean = true,
    val depthTest: Boolean = true,
    val fog: Boolean = true,
    val toneMapped: Boolean = true,
    val flatShading: Boolean = false,
    val alphaTest: Double = 0.0,
    val polygonOffset: Boolean = false,
    val polygonOffsetFactor: Double = 0.0,
    val polygonOffsetUnits: Double = 0.0,
    val emissive: Int = 0x000000,
)

/** A shader uniform: its value is a `Double`, [Color], [Vec2], [Vec3] or [Boolean]; the view code mutates it in place. */
class Uniform(
    var value: Any,
)

/**
 * A named custom program (three.js `ShaderMaterial`). The shader code lives in the render backend,
 * which knows the [programName] (`"sky"`, `"hoof-dust"`); view code only sets the [uniforms].
 * Materials with the same program name share one program.
 */
open class ShaderMaterial(
    val programName: String,
    uniforms: Map<String, Uniform> = emptyMap(),
    transparent: Boolean = false,
    depthWrite: Boolean = true,
    depthTest: Boolean = true,
    side: Side = Side.FRONT,
    fog: Boolean = false,
    toneMapped: Boolean = true,
) : Material("ShaderMaterial") {
    val uniforms: MutableMap<String, Uniform> = LinkedHashMap(uniforms)

    init {
        this.transparent = transparent
        this.depthWrite = depthWrite
        this.depthTest = depthTest
        this.side = side
        this.fog = fog
        this.toneMapped = toneMapped
    }

    override fun customProgramCacheKey(): String = programName

    fun uniform(name: String): Uniform = uniforms[name] ?: error("material '$programName' has no uniform '$name'")
}

/** Gradient sky dome with a sun disc (the backend's `"sky"` program); draw it on the inside of a sphere. */
class SkyMaterial(
    zenith: Int,
    horizon: Int,
    ground: Int,
    sun: Int,
    sunDirection: Vec3,
) : ShaderMaterial(
        "sky",
        mapOf(
            "zenith" to Uniform(Color(zenith)),
            "horizon" to Uniform(Color(horizon)),
            "groundColor" to Uniform(Color(ground)),
            "sunColor" to Uniform(Color(sun)),
            "sunDir" to Uniform(sunDirection),
        ),
        depthWrite = false,
        side = Side.BACK,
        fog = false,
    ) {
    val zenith: Color get() = uniform("zenith").value as Color
    val horizon: Color get() = uniform("horizon").value as Color
    val groundColor: Color get() = uniform("groundColor").value as Color
    val sunColor: Color get() = uniform("sunColor").value as Color
    val sunDir: Vec3 get() = uniform("sunDir").value as Vec3
}
