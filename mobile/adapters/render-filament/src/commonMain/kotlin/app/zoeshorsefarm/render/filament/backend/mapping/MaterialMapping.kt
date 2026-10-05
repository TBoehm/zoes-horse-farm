package app.zoeshorsefarm.render.filament.backend.mapping

import app.zoeshorsefarm.render.filament.material.Blend
import app.zoeshorsefarm.render.filament.material.CoatKind
import app.zoeshorsefarm.render.filament.material.InstancingMode
import app.zoeshorsefarm.render.filament.material.MaterialSpec
import app.zoeshorsefarm.render.filament.material.Shading
import app.zoeshorsefarm.render.filament.mesh.SkinPalette
import app.zoeshorsefarm.scene.geometry.Geometry
import app.zoeshorsefarm.scene.graph.InstancedMesh
import app.zoeshorsefarm.scene.graph.Node
import app.zoeshorsefarm.scene.graph.Points
import app.zoeshorsefarm.scene.graph.SkinnedMesh
import app.zoeshorsefarm.scene.graph.Sprite
import app.zoeshorsefarm.scene.material.BasicMaterial
import app.zoeshorsefarm.scene.material.CoatEffect
import app.zoeshorsefarm.scene.material.LambertMaterial
import app.zoeshorsefarm.scene.material.Material
import app.zoeshorsefarm.scene.material.PointsMaterial
import app.zoeshorsefarm.scene.material.ShaderMaterial
import app.zoeshorsefarm.scene.material.Side
import app.zoeshorsefarm.scene.material.SpriteMaterial
import app.zoeshorsefarm.scene.material.StandardMaterial
import app.zoeshorsefarm.scene.material.Wind
import app.zoeshorsefarm.scene.material.WindKind
import app.zoeshorsefarm.render.filament.material.WindEffect as FilamentWind
import app.zoeshorsefarm.scene.material.WindEffect as SceneWind

/**
 * Turns a scene material, as it is used by one node, into the [MaterialSpec] of the Filament
 * material library (the shader variant). Values that only change colours or numbers are not part of
 * the spec; see [MaterialValues].
 *
 * The scene model describes what it wants; the spec is what the backend can build. Where the two
 * disagree the mapping drops the part the variant cannot do instead of failing, because a frame must
 * never throw: a wind effect on a mesh that is not instanced is ignored, vertex colours without a
 * colour attribute are ignored, and so on. [specFor] returns null for materials the backend has no
 * shader for.
 *
 * Not supported (the web view does not use them): `alphaTest`, `flatShading`, `emissive`, `envMapIntensity`,
 * `Side.BACK` on anything but the sky (drawn double sided), `ShaderMaterial` programs other than
 * `"sky"` and `"hoof-dust"`.
 */
object MaterialMapping {
    const val SKY_PROGRAM = "sky"
    const val HOOF_DUST_PROGRAM = "hoof-dust"

    /** The spec for `material` drawn by `node` (a mesh, points object or sprite), or null if it cannot be drawn. */
    fun specFor(
        node: Node,
        material: Material,
        geometry: Geometry?,
    ): MaterialSpec? {
        val shading = shadingOf(node, material) ?: return null
        return when (shading) {
            Shading.SKY -> MaterialSpec(Shading.SKY, toneMapped = material.toneMapped)
            Shading.SPRITE -> spriteSpec(material)
            else -> surfaceSpec(shading, node, material, geometry)
        }
    }

    private fun shadingOf(
        node: Node,
        material: Material,
    ): Shading? =
        when {
            node is Points -> if (isPointsMaterial(material)) Shading.SPRITE else null
            material is ShaderMaterial -> if (material.programName == SKY_PROGRAM) Shading.SKY else null
            node is Sprite -> Shading.UNLIT
            material is StandardMaterial -> Shading.LIT
            material is LambertMaterial -> Shading.LAMBERT
            material is BasicMaterial || material is SpriteMaterial -> Shading.UNLIT
            else -> null
        }

    private fun isPointsMaterial(material: Material): Boolean =
        material is PointsMaterial || (material is ShaderMaterial && material.programName == HOOF_DUST_PROGRAM)

    private fun spriteSpec(material: Material): MaterialSpec =
        MaterialSpec(
            Shading.SPRITE,
            blend = Blend.TRANSPARENT,
            depthWrite = explicitDepthWrite(material, Blend.TRANSPARENT),
            toneMapped = material.toneMapped,
        )

    private fun surfaceSpec(
        shading: Shading,
        node: Node,
        material: Material,
        geometry: Geometry?,
    ): MaterialSpec {
        val coat = if (hasAll(geometry, GeometryMapping.COAT_ATTRIBUTES)) coatOf(material, shading) else null
        val skinned = node is SkinnedMesh && boneCountOf(node) > 0 && hasAll(geometry, SKIN_ATTRIBUTES)
        val instancing = if (skinned) InstancingMode.NONE else instancingOf(node)
        val blend = if (material.transparent) Blend.TRANSPARENT else Blend.OPAQUE
        // the coat paints the whole surface: no maps, no vertex colours, no wind
        val look = if (coat == null) lookOf(shading, node, material, geometry, instancing) else Look.PLAIN
        val moving = skinned || coat != null
        val wind = if (moving) FilamentWind.None else windOf(material, instancing, look.vertexColors, geometry)
        return MaterialSpec(
            shading,
            vertexColors = look.vertexColors,
            baseColorMap = look.baseColorMap,
            normalMap = look.normalMap,
            alphaMap = look.alphaMap,
            skinning = skinned,
            instancing = instancing,
            blend = blend,
            doubleSided = material.side != Side.FRONT || node is Sprite,
            depthWrite = explicitDepthWrite(material, blend),
            toneMapped = material.toneMapped,
            wind = wind,
            coat = coat,
        )
    }

    /** Which colour sources a surface uses. */
    private class Look(
        val vertexColors: Boolean,
        val baseColorMap: Boolean,
        val normalMap: Boolean,
        val alphaMap: Boolean,
    ) {
        companion object {
            val PLAIN = Look(vertexColors = false, baseColorMap = false, normalMap = false, alphaMap = false)
        }
    }

    private fun lookOf(
        shading: Shading,
        node: Node,
        material: Material,
        geometry: Geometry?,
        instancing: InstancingMode,
    ): Look {
        val hasUv = node is Sprite || geometry?.hasAttribute("uv") == true
        val normalMap = (material as? StandardMaterial)?.normalMap != null
        return Look(
            vertexColors = material.vertexColors && geometry?.hasAttribute("color") == true,
            baseColorMap = material.map != null && hasUv,
            normalMap = normalMap && hasUv && shading == Shading.LIT && instancing == InstancingMode.NONE,
            alphaMap = material.alphaMap != null && hasUv,
        )
    }

    /** Null while the material writes depth like the spec's default (opaque writes, transparent does not). */
    private fun explicitDepthWrite(
        material: Material,
        blend: Blend,
    ): Boolean? = if (material.depthWrite == (blend == Blend.OPAQUE)) null else material.depthWrite

    private fun instancingOf(node: Node): InstancingMode =
        when {
            node !is InstancedMesh -> InstancingMode.NONE
            node.instanceColor != null -> InstancingMode.TRANSFORMS_AND_COLOR
            else -> InstancingMode.TRANSFORMS
        }

    private fun coatOf(
        material: Material,
        shading: Shading,
    ): CoatKind? {
        val effect = material.activeEffect as? CoatEffect ?: return null
        if (shading != Shading.LIT && shading != Shading.LAMBERT) return null
        return if (effect.low) CoatKind.LOW else CoatKind.STANDARD
    }

    private fun windOf(
        material: Material,
        instancing: InstancingMode,
        vertexColors: Boolean,
        geometry: Geometry?,
    ): FilamentWind {
        val effect = material.activeEffect as? SceneWind ?: return FilamentWind.None
        val instanced = instancing != InstancingMode.NONE
        return when (effect.kind) {
            WindKind.BUNTING -> {
                if (!instanced && hasAll(geometry, listOf("aFlutter"))) FilamentWind.Bunting else FilamentWind.None
            }

            WindKind.BLOSSOMS -> {
                val ok =
                    instancing == InstancingMode.TRANSFORMS_AND_COLOR && vertexColors &&
                        hasAll(geometry, listOf("petal"))
                if (ok) FilamentWind.Blossom(effect.base.toFloat()) else FilamentWind.None
            }

            else -> {
                if (instanced) instancedWind(effect) else FilamentWind.None
            }
        }
    }

    private fun instancedWind(effect: SceneWind): FilamentWind =
        when (effect.kind) {
            WindKind.TREE -> {
                FilamentWind.Tree
            }

            WindKind.BUSH -> {
                FilamentWind.Bush
            }

            WindKind.TUFT -> {
                FilamentWind.Tuft
            }

            WindKind.WINGS -> {
                FilamentWind.Wings(effect.rate.toFloat(), effect.amplitude.toFloat(), effect.glide.toFloat())
            }

            else -> {
                FilamentWind.None
            }
        }

    /** The wind a material is moved by, or null (the backend feeds the first one it finds to the shaders). */
    fun windOfMaterial(material: Material): Wind? = (material.activeEffect as? SceneWind)?.wind

    private val SKIN_ATTRIBUTES = listOf("skinIndex", "skinWeight")

    /** The bones Filament skins with, 0 if the mesh has no usable skeleton (none, or more than Filament takes). */
    fun boneCountOf(node: SkinnedMesh): Int {
        val count = node.skeleton?.bones?.size ?: 0
        return if (count in 1..SkinPalette.MAX_BONES) count else 0
    }

    private fun hasAll(
        geometry: Geometry?,
        names: List<String>,
    ): Boolean = geometry != null && names.all { geometry.hasAttribute(it) }
}
