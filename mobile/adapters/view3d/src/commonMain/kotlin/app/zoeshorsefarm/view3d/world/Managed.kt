package app.zoeshorsefarm.view3d.world

import app.zoeshorsefarm.scene.graph.Mesh
import app.zoeshorsefarm.scene.material.LambertMaterial
import app.zoeshorsefarm.scene.material.MaterialParams
import app.zoeshorsefarm.scene.material.StandardMaterial
import app.zoeshorsefarm.scene.texture.Texture

/**
 * The two materials of a surface with the same base values: standard (medium and high) and Lambert
 * (low). The world switches between them with the quality level. [normalMap] is the normal map of
 * the standard one (kept here because a level without normal maps takes it away from the material).
 */
class MaterialPair(
    val standard: StandardMaterial,
    val lambert: LambertMaterial,
    val normalMap: Texture?,
)

/** `materialFactory(kind, params)` of the web app: makes a [MaterialPair] and lets the world manage it. */
typealias MaterialFactory = (kind: String, params: MaterialParams) -> MaterialPair

/** Which shadows a mesh takes part in. */
enum class ShadowRole {
    /** Neither casts nor receives. */
    NONE,

    /** Only receives (ground). */
    RECEIVE,

    /** Casts (when the level lets everything cast) and receives. */
    ALL,

    /** Casts and receives on every level with shadows (horse, obstacles). */
    OBSTACLES,
}

/**
 * A mesh the world manages with its material pair, shadow role and whether it is an optional
 * detail (hidden on levels without it, GPU buffers given back while hidden). [mesh] is null while
 * the mesh does not exist (obstacles before a course is set).
 */
class ManagedMesh(
    var mesh: Mesh?,
    val mats: MaterialPair,
    val shadow: ShadowRole,
    val detail: Boolean = false,
)
