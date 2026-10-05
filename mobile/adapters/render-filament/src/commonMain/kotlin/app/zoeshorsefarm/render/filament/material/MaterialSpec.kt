package app.zoeshorsefarm.render.filament.material

import app.zoeshorsefarm.render.filament.mesh.VertexSemantic

/** The lighting model of a material. */
enum class Shading(
    val id: String,
) {
    /** Standard PBR (`MeshStandardMaterial`): roughness and metalness. */
    LIT("lit"),

    /** Cheap diffuse lighting (`MeshLambertMaterial`): no specular, custom surface shading. */
    LAMBERT("lambert"),

    /** No lighting (`MeshBasicMaterial`). */
    UNLIT("unlit"),

    /** The sky dome: gradient with sun, drawn from inside, behind everything. */
    SKY("sky"),

    /** Camera facing soft puffs (`THREE.Points` in the web app), as quads. */
    SPRITE("sprite"),
}

enum class Blend(
    val id: String,
) {
    OPAQUE("o"),
    TRANSPARENT("t"),
}

/**
 * How instances get their transform and colour: from the instance data texture (see
 * `InstanceData`), indexed by `getInstanceIndex()`.
 */
enum class InstancingMode(
    val id: String,
) {
    NONE(""),
    TRANSFORMS("i"),
    TRANSFORMS_AND_COLOR("ic"),
}

/** The horse's coat patterns (`horse/material.js`); `LOW` is the cheaper variant of the low level. */
enum class CoatKind(
    val id: String,
) {
    STANDARD("coat"),
    LOW("coatlow"),
}

/**
 * Vertex shader effects of the scenery (`plant-shaders.js`): the same wind moves trees, bushes, grass
 * tufts, flowers, bunting, and the wings of birds and butterflies. All of them read time and
 * strength from Filament's material global 0 (`View.setMaterialGlobal(0, [time, strength, 0, 1])`).
 */
sealed class WindEffect(
    val id: String,
) {
    data object None : WindEffect("")

    /** The crown leans and swings with slow gusts, the leaves shimmer. Instanced. */
    data object Tree : WindEffect("wtree")

    /** The blob sways a little, more at the top. Instanced. */
    data object Bush : WindEffect("wbush")

    /** The tips of grass tufts sway. Instanced. */
    data object Tuft : WindEffect("wtuft")

    /**
     * Flowers: the stem bends, and the `petal` attribute (custom0.x, 1 on petals) picks which
     * vertices take the colour of the instance. `base` is the height of a rigid part (flower box)
     * below the plants. Instanced with instance colours.
     */
    data class Blossom(
        val base: Float,
    ) : WindEffect("wblossom${GlslNumber.keyPart(base)}")

    /** Pennants flutter along `aFlutter` (custom0.xyz): direction times weight. Not instanced. */
    data object Bunting : WindEffect("wbunting")

    /** Wings flap: `rate` in rad/s, `amplitude` per metre from the body, `glide` > 0 pauses the beat. Instanced. */
    data class Wings(
        val rate: Float,
        val amplitude: Float,
        val glide: Float,
    ) : WindEffect("wwings${GlslNumber.keyPart(rate)}_${GlslNumber.keyPart(amplitude)}_${GlslNumber.keyPart(glide)}")
}

/**
 * One material variant: everything that changes the shader code. The Filament material library
 * builds (and caches) one program set per distinct spec.
 *
 * The parameters that only change values (colours, roughness, textures) are not part of the spec:
 * they are set on material instances.
 */
data class MaterialSpec(
    val shading: Shading,
    /** The mesh has a `COLOR` attribute; the colour is multiplied into the base colour. */
    val vertexColors: Boolean = false,
    val baseColorMap: Boolean = false,
    val normalMap: Boolean = false,
    /** Alpha from the green channel of a texture (`alphaMap` of three.js). */
    val alphaMap: Boolean = false,
    /** GPU skinning (the bone attributes must be in the mesh); a variant of the program. */
    val skinning: Boolean = false,
    val instancing: InstancingMode = InstancingMode.NONE,
    val blend: Blend = Blend.OPAQUE,
    val doubleSided: Boolean = false,
    /** Null: opaque materials write depth, transparent ones do not. */
    val depthWrite: Boolean? = null,
    /**
     * False for materials that must not be tone mapped (`toneMapped: false` of three.js, the number
     * signs). Filament tone maps the whole frame, so these invert the curve in the shader.
     */
    val toneMapped: Boolean = true,
    val wind: WindEffect = WindEffect.None,
    val coat: CoatKind? = null,
) {
    init {
        validate()
    }

    val depthWriteEnabled: Boolean get() = depthWrite ?: (blend == Blend.OPAQUE)

    val usesInstanceData: Boolean get() = instancing != InstancingMode.NONE

    val usesInstanceColor: Boolean get() = instancing == InstancingMode.TRANSFORMS_AND_COLOR

    val isLit: Boolean get() = shading == Shading.LIT || shading == Shading.LAMBERT

    /** The vertex attributes a mesh needs to be drawn with this material (the position always). */
    fun requiredAttributes(): Set<VertexSemantic> {
        val attributes = linkedSetOf<VertexSemantic>()
        if (isLit) attributes += VertexSemantic.TANGENTS
        if (vertexColors) attributes += VertexSemantic.COLOR
        if (baseColorMap || normalMap || alphaMap) attributes += VertexSemantic.UV0
        if (skinning) {
            attributes += VertexSemantic.BONE_INDICES
            attributes += VertexSemantic.BONE_WEIGHTS
        }
        if (wind is WindEffect.Blossom || wind is WindEffect.Bunting) attributes += VertexSemantic.CUSTOM0
        if (coat != null) {
            attributes += VertexSemantic.CUSTOM0
            attributes += VertexSemantic.CUSTOM1
            attributes += VertexSemantic.CUSTOM2
        }
        if (shading == Shading.SPRITE) {
            attributes += VertexSemantic.CUSTOM0
            attributes += VertexSemantic.CUSTOM1
        }
        return attributes
    }

    /** Stable text key: equal specs give equal keys and different specs different keys. */
    val key: String
        get() =
            buildList {
                add(shading.id)
                if (vertexColors) add("vc")
                if (baseColorMap) add("map")
                if (normalMap) add("nmap")
                if (alphaMap) add("amap")
                if (skinning) add("skin")
                if (instancing != InstancingMode.NONE) add(instancing.id)
                if (blend == Blend.TRANSPARENT) add("t")
                if (doubleSided) add("ds")
                if (depthWrite != null) add(if (depthWrite) "dw" else "nodw")
                if (!toneMapped) add("notm")
                if (wind != WindEffect.None) add(wind.id)
                if (coat != null) add(coat.id)
            }.joinToString("-")

    private fun validate() {
        if (skinning) {
            require(instancing == InstancingMode.NONE) { "skinned meshes cannot be instanced" }
            require(wind == WindEffect.None) { "skinned meshes cannot have wind" }
        }
        validateWind()
        if (normalMap) {
            require(shading == Shading.LIT) { "normal maps are for the lit shading only" }
            require(instancing == InstancingMode.NONE) { "normal maps cannot be combined with instancing" }
        }
        if (shading == Shading.SKY) {
            require(!vertexColors && !baseColorMap && !alphaMap && !normalMap) { "the sky takes no maps or colours" }
            require(instancing == InstancingMode.NONE && wind == WindEffect.None && !skinning) { "the sky is plain" }
        }
        if (shading == Shading.SPRITE) {
            require(blend == Blend.TRANSPARENT) { "sprites are transparent" }
            require(!vertexColors && !baseColorMap && !alphaMap && !normalMap) { "sprites take no maps or colours" }
            require(instancing == InstancingMode.NONE && wind == WindEffect.None && !skinning) { "sprites are plain" }
        }
        if (coat != null) {
            require(isLit) { "the coat needs a lit material" }
            require(!vertexColors && !baseColorMap && !normalMap && !alphaMap) { "the coat paints the whole surface" }
            require(instancing == InstancingMode.NONE && wind == WindEffect.None) {
                "the coat is for skinned or plain meshes"
            }
        }
    }

    private fun validateWind() {
        if (wind == WindEffect.None) return
        if (wind == WindEffect.Bunting) {
            require(instancing == InstancingMode.NONE) { "bunting wind is for meshes that are not instanced" }
        } else if (wind is WindEffect.Blossom) {
            require(instancing == InstancingMode.TRANSFORMS_AND_COLOR) { "blossoms need instance colours" }
            require(vertexColors) { "blossoms need vertex colours" }
        } else {
            require(instancing != InstancingMode.NONE) { "this wind moves instances, it needs instancing" }
        }
    }
}
