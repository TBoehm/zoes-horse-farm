package app.zoeshorsefarm.render.filament.material

import app.zoeshorsefarm.render.filament.mesh.VertexSemantic
import app.zoeshorsefarm.render.filament.mesh.toFilament
import app.zoeshorsefarm.render.filament.resources.GpuResourceTracker
import app.zoeshorsefarm.render.filament.resources.ResourceKind
import io.github.erkko68.filament.Engine
import io.github.erkko68.filament.Material
import io.github.erkko68.filament.UserVariantFilterBit
import io.github.erkko68.filament.filamat.MaterialBuilder

/** A compiled Filament material and the memory accounting entry that belongs to it. */
class FilamentMaterial internal constructor(
    val material: Material,
    val source: MaterialSource,
    internal val trackerId: Int,
)

/**
 * Compiles [MaterialSource]s with filamat (the runtime shader compiler, no `.filamat` files) and
 * makes Filament materials of the packages.
 *
 * `MaterialBuilder.init()` starts glslang and the shader translators once per process and
 * [shutdown] frees them at the end of the app. Compiling is slow (tens to hundreds of milliseconds
 * on a phone, per material): build materials ahead of the first frame, and give the compiler a
 * [MaterialPackageCache] that survives restarts so that only the first run pays.
 */
class FilamentMaterialCompiler(
    private val engine: Engine,
    private val tracker: GpuResourceTracker,
    private val cache: MaterialPackageCache? = null,
    private val engineVersion: String = FILAMENT_VERSION,
) {
    init {
        ensureFilamatStarted()
    }

    /** Null if the shader code does not compile. */
    fun compile(source: MaterialSource): FilamentMaterial? {
        val key = MaterialPackageCache.keyOf(source, engineVersion)
        var payload = cache?.get(key)
        if (payload == null) {
            val built = build(source)
            if (!built.isValid || built.data.isEmpty()) return null
            payload = built.data
            cache?.put(key, payload)
        }
        val material = Material.Builder().payload(payload).build(engine) ?: return null
        val id = tracker.register(ResourceKind.MATERIAL, source.name, payload.size.toLong())
        return FilamentMaterial(material, source, id)
    }

    fun destroy(material: FilamentMaterial) {
        engine.destroy(material.material)
        tracker.release(material.trackerId)
    }

    private fun build(source: MaterialSource) =
        MaterialBuilder()
            .name(source.name)
            .platform(MaterialBuilder.Platform.MOBILE)
            .targetApi(MaterialBuilder.TargetApi.ALL)
            .shading(
                if (source.shading ==
                    SourceShading.LIT
                ) {
                    MaterialBuilder.Shading.LIT
                } else {
                    MaterialBuilder.Shading.UNLIT
                },
            ).blending(blendingOf(source))
            .culling(cullingOf(source))
            .doubleSided(source.doubleSided)
            .depthWrite(source.depthWrite)
            .instanced(source.instanced)
            .linearFog(source.linearFog)
            .customSurfaceShading(source.customSurfaceShading)
            .quality(
                if (source.highPrecision) MaterialBuilder.ShaderQuality.HIGH else MaterialBuilder.ShaderQuality.DEFAULT,
            ).variantFilter(variantMask(source.filteredVariants))
            .also { builder -> declare(builder, source) }
            .material(source.fragment)
            .also { builder -> source.vertex?.let { builder.materialVertex(it) } }
            .build()

    private fun declare(
        builder: MaterialBuilder,
        source: MaterialSource,
    ) {
        for (attribute in source.requires) builder.require(attribute.toFilament())
        source.variables.forEachIndexed { index, name ->
            builder.variable(MaterialBuilder.Variable.entries[index], name)
        }
        for (parameter in source.parameters) {
            when (parameter) {
                is UniformParameter -> {
                    builder.parameter(
                        parameter.name,
                        when (parameter.type) {
                            UniformType.FLOAT -> MaterialBuilder.UniformType.FLOAT
                            UniformType.FLOAT3 -> MaterialBuilder.UniformType.FLOAT3
                            UniformType.FLOAT4 -> MaterialBuilder.UniformType.FLOAT4
                        },
                    )
                }

                is SamplerParameter -> {
                    val precision =
                        if (parameter.highPrecision) {
                            MaterialBuilder.ParameterPrecision.HIGH
                        } else {
                            MaterialBuilder.ParameterPrecision.DEFAULT
                        }
                    builder.parameter(
                        parameter.name,
                        MaterialBuilder.SamplerType.SAMPLER_2D,
                        MaterialBuilder.SamplerFormat.FLOAT,
                        precision,
                        parameter.filterable,
                    )
                }
            }
        }
    }

    private fun blendingOf(source: MaterialSource) =
        when (source.blending) {
            SourceBlending.OPAQUE -> MaterialBuilder.BlendingMode.OPAQUE
            SourceBlending.TRANSPARENT -> MaterialBuilder.BlendingMode.TRANSPARENT
        }

    private fun cullingOf(source: MaterialSource) =
        when (source.culling) {
            SourceCulling.BACK -> MaterialBuilder.CullingMode.BACK
            SourceCulling.FRONT -> MaterialBuilder.CullingMode.FRONT
            SourceCulling.NONE -> MaterialBuilder.CullingMode.NONE
        }

    companion object {
        /** The Filament version of filament-kmp 0.7.1; part of the cache key. */
        const val FILAMENT_VERSION = "1.77"

        private var started = false

        private fun ensureFilamatStarted() {
            if (started) return
            MaterialBuilder.init()
            started = true
        }

        /** Frees the shader compiler. Call once when the app ends, after the last compile. */
        fun shutdown() {
            if (!started) return
            MaterialBuilder.shutdown()
            started = false
        }

        /** The `UserVariantFilterBit` mask of the variants a source does not need. */
        fun variantMask(filtered: Set<FilteredVariant>): Int {
            var mask = 0
            for (variant in filtered) {
                mask = mask or
                    when (variant) {
                        FilteredVariant.DYNAMIC_LIGHTING -> UserVariantFilterBit.DYNAMIC_LIGHTING
                        FilteredVariant.SKINNING -> UserVariantFilterBit.SKINNING
                        FilteredVariant.VSM -> UserVariantFilterBit.VSM
                        FilteredVariant.SSR -> UserVariantFilterBit.SSR
                        FilteredVariant.STEREO -> UserVariantFilterBit.STE
                    }
            }
            return mask
        }
    }
}

/** A [MaterialLibrary] that compiles with filamat and makes Filament materials. */
fun filamentMaterialLibrary(
    engine: Engine,
    tracker: GpuResourceTracker,
    cache: MaterialPackageCache? = null,
    markings: MarkingRegions = MarkingRegions.WEB,
): MaterialLibrary<FilamentMaterial> {
    val compiler = FilamentMaterialCompiler(engine, tracker, cache)
    return MaterialLibrary(
        markings = markings,
        build = compiler::compile,
        destroy = compiler::destroy,
    )
}

/** The attributes a mesh lacks for a material, by name; empty if the mesh can be drawn with it. */
fun missingAttributes(
    spec: MaterialSpec,
    provided: Set<VertexSemantic>,
): List<VertexSemantic> = spec.requiredAttributes().filter { it !in provided }
