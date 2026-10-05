package app.zoeshorsefarm.render.filament.material

import app.zoeshorsefarm.render.filament.mesh.VertexSemantic

enum class UniformType { FLOAT, FLOAT3, FLOAT4 }

/** A value of a material that material instances set: a uniform or a texture sampler. */
sealed interface ParameterSpec {
    val name: String
}

class UniformParameter(
    override val name: String,
    val type: UniformType,
) : ParameterSpec

/** A 2D texture. `highPrecision` and not `filterable` for data textures read with `texelFetch`. */
class SamplerParameter(
    override val name: String,
    val filterable: Boolean = true,
    val highPrecision: Boolean = false,
) : ParameterSpec

enum class SourceShading { LIT, UNLIT }

enum class SourceBlending { OPAQUE, TRANSPARENT }

enum class SourceCulling { BACK, FRONT, NONE }

/** Shader variants of a Filament material that this app never needs: not compiled, less memory. */
enum class FilteredVariant { DYNAMIC_LIGHTING, SKINNING, VSM, SSR, STEREO }

/**
 * Everything the filamat `MaterialBuilder` is told to make a material, as plain data and strings: the
 * shader code is generated without touching Filament, so it can be checked in unit tests.
 *
 * `requires` lists the attributes the material declares besides the position (tangents are implied by
 * lit shading), `variables` the interpolants between vertex and fragment shader (`variable_NAME` in
 * the fragment, `material.NAME` in the vertex shader).
 */
class MaterialSource(
    val name: String,
    val shading: SourceShading,
    val blending: SourceBlending,
    val culling: SourceCulling,
    val doubleSided: Boolean,
    val depthWrite: Boolean,
    val customSurfaceShading: Boolean,
    val instanced: Boolean,
    val linearFog: Boolean,
    val highPrecision: Boolean,
    val requires: Set<VertexSemantic>,
    val variables: List<String>,
    val parameters: List<ParameterSpec>,
    val filteredVariants: Set<FilteredVariant>,
    val fragment: String,
    val vertex: String?,
)
