package app.zoeshorsefarm.scene.material

import app.zoeshorsefarm.scene.math.Color
import app.zoeshorsefarm.scene.math.Vec2
import app.zoeshorsefarm.scene.texture.Texture

// The constructors below are written out in full (named parameters with the three.js defaults), so
// that `new THREE.MeshStandardMaterial({ color, roughness })` ports to
// `StandardMaterial(color = c, roughness = r)`.

/** Physically based surface (three.js `MeshStandardMaterial`): lit by lights and the environment. */
class StandardMaterial(
    color: Int = 0xffffff,
    roughness: Double = 1.0,
    metalness: Double = 0.0,
    normalMap: Texture? = null,
    normalScale: Vec2 = Vec2(1.0, 1.0),
    emissive: Int = 0x000000,
    emissiveIntensity: Double = 1.0,
    envMapIntensity: Double = 1.0,
    map: Texture? = null,
    alphaMap: Texture? = null,
    vertexColors: Boolean = false,
    transparent: Boolean = false,
    opacity: Double = 1.0,
    side: Side = Side.FRONT,
    depthWrite: Boolean = true,
    depthTest: Boolean = true,
    fog: Boolean = true,
    toneMapped: Boolean = true,
    flatShading: Boolean = false,
    alphaTest: Double = 0.0,
    polygonOffset: Boolean = false,
    polygonOffsetFactor: Double = 0.0,
    polygonOffsetUnits: Double = 0.0,
    effect: MaterialEffect? = null,
) : Material("MeshStandardMaterial") {
    var roughness: Double = roughness
    var metalness: Double = metalness
    var normalMap: Texture? = normalMap
    val normalScale: Vec2 = normalScale
    val emissive: Color = Color(emissive)
    var emissiveIntensity: Double = emissiveIntensity
    var envMapIntensity: Double = envMapIntensity

    init {
        configure(
            color = color,
            map = map,
            alphaMap = alphaMap,
            vertexColors = vertexColors,
            transparent = transparent,
            opacity = opacity,
            side = side,
            depthWrite = depthWrite,
            depthTest = depthTest,
            fog = fog,
            toneMapped = toneMapped,
            flatShading = flatShading,
            alphaTest = alphaTest,
            polygonOffset = polygonOffset,
            polygonOffsetFactor = polygonOffsetFactor,
            polygonOffsetUnits = polygonOffsetUnits,
            effect = effect,
        )
    }

    constructor(params: MaterialParams) : this(
        color = params.color,
        roughness = params.roughness ?: 0.8,
        metalness = params.metalness ?: 0.0,
        map = params.map,
        alphaMap = params.alphaMap,
        normalMap = params.normalMap,
        normalScale = params.normalScale.clone(),
        vertexColors = params.vertexColors,
        transparent = params.transparent,
        opacity = params.opacity,
        side = params.side,
        depthWrite = params.depthWrite,
        depthTest = params.depthTest,
        fog = params.fog,
        toneMapped = params.toneMapped,
        flatShading = params.flatShading,
        alphaTest = params.alphaTest,
        polygonOffset = params.polygonOffset,
        polygonOffsetFactor = params.polygonOffsetFactor,
        polygonOffsetUnits = params.polygonOffsetUnits,
        emissive = params.emissive,
    )

    override fun textures(): List<Texture> = listOfNotNull(map, alphaMap, normalMap)
}

/** Cheap diffuse-only lighting (three.js `MeshLambertMaterial`); the "low" quality choice for [StandardMaterial]. */
class LambertMaterial(
    color: Int = 0xffffff,
    emissive: Int = 0x000000,
    emissiveIntensity: Double = 1.0,
    map: Texture? = null,
    alphaMap: Texture? = null,
    vertexColors: Boolean = false,
    transparent: Boolean = false,
    opacity: Double = 1.0,
    side: Side = Side.FRONT,
    depthWrite: Boolean = true,
    depthTest: Boolean = true,
    fog: Boolean = true,
    toneMapped: Boolean = true,
    flatShading: Boolean = false,
    alphaTest: Double = 0.0,
    polygonOffset: Boolean = false,
    polygonOffsetFactor: Double = 0.0,
    polygonOffsetUnits: Double = 0.0,
    effect: MaterialEffect? = null,
) : Material("MeshLambertMaterial") {
    val emissive: Color = Color(emissive)
    var emissiveIntensity: Double = emissiveIntensity

    init {
        configure(
            color = color,
            map = map,
            alphaMap = alphaMap,
            vertexColors = vertexColors,
            transparent = transparent,
            opacity = opacity,
            side = side,
            depthWrite = depthWrite,
            depthTest = depthTest,
            fog = fog,
            toneMapped = toneMapped,
            flatShading = flatShading,
            alphaTest = alphaTest,
            polygonOffset = polygonOffset,
            polygonOffsetFactor = polygonOffsetFactor,
            polygonOffsetUnits = polygonOffsetUnits,
            effect = effect,
        )
    }

    constructor(params: MaterialParams) : this(
        color = params.color,
        map = params.map,
        alphaMap = params.alphaMap,
        vertexColors = params.vertexColors,
        transparent = params.transparent,
        opacity = params.opacity,
        side = params.side,
        depthWrite = params.depthWrite,
        depthTest = params.depthTest,
        fog = params.fog,
        toneMapped = params.toneMapped,
        flatShading = params.flatShading,
        alphaTest = params.alphaTest,
        polygonOffset = params.polygonOffset,
        polygonOffsetFactor = params.polygonOffsetFactor,
        polygonOffsetUnits = params.polygonOffsetUnits,
        emissive = params.emissive,
    )
}

/** Unlit colour or texture (three.js `MeshBasicMaterial`). */
class BasicMaterial(
    color: Int = 0xffffff,
    map: Texture? = null,
    alphaMap: Texture? = null,
    vertexColors: Boolean = false,
    transparent: Boolean = false,
    opacity: Double = 1.0,
    side: Side = Side.FRONT,
    depthWrite: Boolean = true,
    depthTest: Boolean = true,
    fog: Boolean = true,
    toneMapped: Boolean = true,
    flatShading: Boolean = false,
    alphaTest: Double = 0.0,
    polygonOffset: Boolean = false,
    polygonOffsetFactor: Double = 0.0,
    polygonOffsetUnits: Double = 0.0,
    effect: MaterialEffect? = null,
) : Material("MeshBasicMaterial") {
    init {
        configure(
            color = color,
            map = map,
            alphaMap = alphaMap,
            vertexColors = vertexColors,
            transparent = transparent,
            opacity = opacity,
            side = side,
            depthWrite = depthWrite,
            depthTest = depthTest,
            fog = fog,
            toneMapped = toneMapped,
            flatShading = flatShading,
            alphaTest = alphaTest,
            polygonOffset = polygonOffset,
            polygonOffsetFactor = polygonOffsetFactor,
            polygonOffsetUnits = polygonOffsetUnits,
            effect = effect,
        )
    }

    constructor(params: MaterialParams) : this(
        color = params.color,
        map = params.map,
        alphaMap = params.alphaMap,
        vertexColors = params.vertexColors,
        transparent = params.transparent,
        opacity = params.opacity,
        side = params.side,
        depthWrite = params.depthWrite,
        depthTest = params.depthTest,
        fog = params.fog,
        toneMapped = params.toneMapped,
        flatShading = params.flatShading,
        alphaTest = params.alphaTest,
        polygonOffset = params.polygonOffset,
        polygonOffsetFactor = params.polygonOffsetFactor,
        polygonOffsetUnits = params.polygonOffsetUnits,
    )
}

/** Material of a [app.zoeshorsefarm.scene.graph.Sprite] (three.js `SpriteMaterial`, transparent by default). */
class SpriteMaterial(
    color: Int = 0xffffff,
    rotation: Double = 0.0,
    sizeAttenuation: Boolean = true,
    map: Texture? = null,
    alphaMap: Texture? = null,
    vertexColors: Boolean = false,
    transparent: Boolean = true,
    opacity: Double = 1.0,
    side: Side = Side.FRONT,
    depthWrite: Boolean = true,
    depthTest: Boolean = true,
    fog: Boolean = true,
    toneMapped: Boolean = true,
    flatShading: Boolean = false,
    alphaTest: Double = 0.0,
    polygonOffset: Boolean = false,
    polygonOffsetFactor: Double = 0.0,
    polygonOffsetUnits: Double = 0.0,
    effect: MaterialEffect? = null,
) : Material("SpriteMaterial") {
    var rotation: Double = rotation
    var sizeAttenuation: Boolean = sizeAttenuation

    init {
        configure(
            color = color,
            map = map,
            alphaMap = alphaMap,
            vertexColors = vertexColors,
            transparent = transparent,
            opacity = opacity,
            side = side,
            depthWrite = depthWrite,
            depthTest = depthTest,
            fog = fog,
            toneMapped = toneMapped,
            flatShading = flatShading,
            alphaTest = alphaTest,
            polygonOffset = polygonOffset,
            polygonOffsetFactor = polygonOffsetFactor,
            polygonOffsetUnits = polygonOffsetUnits,
            effect = effect,
        )
    }
}

/** Square or textured points (three.js `PointsMaterial`). */
class PointsMaterial(
    color: Int = 0xffffff,
    size: Double = 1.0,
    sizeAttenuation: Boolean = true,
    map: Texture? = null,
    alphaMap: Texture? = null,
    vertexColors: Boolean = false,
    transparent: Boolean = false,
    opacity: Double = 1.0,
    side: Side = Side.FRONT,
    depthWrite: Boolean = true,
    depthTest: Boolean = true,
    fog: Boolean = true,
    toneMapped: Boolean = true,
    flatShading: Boolean = false,
    alphaTest: Double = 0.0,
    polygonOffset: Boolean = false,
    polygonOffsetFactor: Double = 0.0,
    polygonOffsetUnits: Double = 0.0,
    effect: MaterialEffect? = null,
) : Material("PointsMaterial") {
    var size: Double = size
    var sizeAttenuation: Boolean = sizeAttenuation

    init {
        configure(
            color = color,
            map = map,
            alphaMap = alphaMap,
            vertexColors = vertexColors,
            transparent = transparent,
            opacity = opacity,
            side = side,
            depthWrite = depthWrite,
            depthTest = depthTest,
            fog = fog,
            toneMapped = toneMapped,
            flatShading = flatShading,
            alphaTest = alphaTest,
            polygonOffset = polygonOffset,
            polygonOffsetFactor = polygonOffsetFactor,
            polygonOffsetUnits = polygonOffsetUnits,
            effect = effect,
        )
    }
}
