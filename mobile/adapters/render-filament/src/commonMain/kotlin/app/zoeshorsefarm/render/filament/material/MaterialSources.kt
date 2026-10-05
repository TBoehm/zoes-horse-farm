package app.zoeshorsefarm.render.filament.material

import app.zoeshorsefarm.render.filament.mesh.InstanceData
import app.zoeshorsefarm.render.filament.mesh.VertexSemantic

/**
 * Generates the Filament material (shader code and settings) for a [MaterialSpec], as strings and
 * plain data. Nothing here calls Filament, so the code is checked in unit tests; the filamat
 * compiler only has to hand it over.
 *
 * Names a material instance sets (all other values are fixed in the code):
 *
 *  - `baseColor` (float4, linear, alpha last), `roughness`, `metallic`, `normalScale`
 *  - samplers `baseColorMap`, `normalMap`, `alphaMap`
 *  - `instanceData`: the instance data texture of an instanced material
 *  - sky: `zenith`, `horizon`, `groundColor`, `sunColor`, `sunDir`
 *  - coat: `uBase`, `uDark`, ... as in the web app, and `roughness`
 */
object MaterialSources {
    const val BASE_COLOR = "baseColor"
    const val ROUGHNESS = "roughness"
    const val METALLIC = "metallic"
    const val NORMAL_SCALE = "normalScale"
    const val BASE_COLOR_MAP = "baseColorMap"
    const val NORMAL_MAP = "normalMap"
    const val ALPHA_MAP = "alphaMap"
    const val INSTANCE_DATA = "instanceData"

    /** The half size of the arena (x width, y length) for the sand. */
    const val ARENA_HALF = "arenaHalf"

    fun generate(
        spec: MaterialSpec,
        markings: MarkingRegions = MarkingRegions.WEB,
    ): MaterialSource {
        val variables = variablesOf(spec)
        return MaterialSource(
            name = "zhf-${spec.key}",
            shading = if (spec.isLit) SourceShading.LIT else SourceShading.UNLIT,
            blending = if (spec.blend == Blend.TRANSPARENT) SourceBlending.TRANSPARENT else SourceBlending.OPAQUE,
            culling = cullingOf(spec),
            doubleSided = spec.doubleSided,
            depthWrite = spec.depthWriteEnabled && spec.shading != Shading.SKY,
            customSurfaceShading = spec.shading == Shading.LAMBERT,
            instanced = spec.usesInstanceData,
            linearFog = true,
            highPrecision = spec.coat != null || spec.sand || spec.shading == Shading.SKY,
            requires = declaredAttributes(spec),
            variables = variables,
            parameters = parametersOf(spec),
            filteredVariants = filteredVariants(spec),
            fragment = fragmentOf(spec, markings),
            vertex = vertexOf(spec),
        )
    }

    private fun cullingOf(spec: MaterialSpec): SourceCulling =
        when {
            spec.shading == Shading.SKY -> SourceCulling.FRONT
            spec.shading == Shading.SPRITE || spec.doubleSided -> SourceCulling.NONE
            else -> SourceCulling.BACK
        }

    private fun filteredVariants(spec: MaterialSpec): Set<FilteredVariant> {
        val filtered =
            mutableSetOf(
                FilteredVariant.DYNAMIC_LIGHTING,
                FilteredVariant.VSM,
                FilteredVariant.SSR,
                FilteredVariant.STEREO,
            )
        if (!spec.skinning) filtered += FilteredVariant.SKINNING
        return filtered
    }

    /** Attributes the material declares: what the shader code reads besides position and tangents. */
    private fun declaredAttributes(spec: MaterialSpec): Set<VertexSemantic> =
        spec.requiredAttributes().filterTo(linkedSetOf()) {
            it == VertexSemantic.COLOR ||
                it == VertexSemantic.UV0 ||
                it.ordinal >= VertexSemantic.CUSTOM0.ordinal
        }

    private fun variablesOf(spec: MaterialSpec): List<String> =
        when {
            spec.coat != null -> CoatGlsl.variables
            spec.sand -> listOf("vGround")
            spec.shading == Shading.SKY -> listOf("vDir")
            spec.shading == Shading.SPRITE -> listOf("sprite")
            needsInstanceColorVariable(spec) -> listOf("instanceColor")
            else -> emptyList()
        }

    private fun needsInstanceColorVariable(spec: MaterialSpec) = spec.usesInstanceColor && !spec.vertexColors

    private fun parametersOf(spec: MaterialSpec): List<ParameterSpec> {
        val parameters = ArrayList<ParameterSpec>()
        when (spec.shading) {
            Shading.SKY -> {
                listOf("zenith", "horizon", "groundColor", "sunColor", "sunDir")
                    .mapTo(parameters) { UniformParameter(it, UniformType.FLOAT3) }
            }

            Shading.SPRITE -> {
                parameters += UniformParameter(BASE_COLOR, UniformType.FLOAT4)
            }

            else -> {
                if (spec.coat != null) {
                    CoatGlsl.uniformColors.mapTo(parameters) { UniformParameter(it, UniformType.FLOAT3) }
                    CoatGlsl.uniformScalars.mapTo(parameters) { UniformParameter(it, UniformType.FLOAT) }
                } else {
                    parameters += UniformParameter(BASE_COLOR, UniformType.FLOAT4)
                }
                if (spec.shading == Shading.LIT) {
                    parameters += UniformParameter(ROUGHNESS, UniformType.FLOAT)
                    if (spec.coat == null) parameters += UniformParameter(METALLIC, UniformType.FLOAT)
                }
                if (spec.normalMap) parameters += UniformParameter(NORMAL_SCALE, UniformType.FLOAT)
                if (spec.baseColorMap) parameters += SamplerParameter(BASE_COLOR_MAP)
                if (spec.sand) parameters += UniformParameter(ARENA_HALF, UniformType.FLOAT3)
                if (spec.normalMap) parameters += SamplerParameter(NORMAL_MAP)
                if (spec.alphaMap) parameters += SamplerParameter(ALPHA_MAP)
            }
        }
        if (spec.usesInstanceData) {
            parameters += SamplerParameter(INSTANCE_DATA, filterable = false, highPrecision = true)
        }
        return parameters
    }

    // ---- fragment ------------------------------------------------------------------------------

    private fun fragmentOf(
        spec: MaterialSpec,
        markings: MarkingRegions,
    ): String =
        when (spec.shading) {
            Shading.SKY -> SKY_FRAGMENT
            Shading.SPRITE -> SPRITE_FRAGMENT
            Shading.UNLIT -> unlitFragment(spec)
            Shading.LIT, Shading.LAMBERT -> litFragment(spec, markings)
        }.trim()

    private fun baseColorLines(spec: MaterialSpec): List<String> {
        val lines = ArrayList<String>()
        lines += "vec4 base = materialParams.$BASE_COLOR;"
        if (spec.vertexColors) lines += "base *= getColor();"
        if (needsInstanceColorVariable(spec)) lines += "base.rgb *= variable_instanceColor.rgb;"
        if (spec.baseColorMap) lines += "base *= texture(materialParams_$BASE_COLOR_MAP, getUV0());"
        if (spec.alphaMap) lines += "base.a *= texture(materialParams_$ALPHA_MAP, getUV0()).g;"
        return lines
    }

    private fun indent(lines: List<String>): String = lines.joinToString("\n") { "    $it" }

    private fun unlitFragment(spec: MaterialSpec): String {
        val body = ArrayList<String>()
        body += "prepareMaterial(material);"
        body += baseColorLines(spec)
        if (!spec.toneMapped) body += "base.rgb = inverseTonemap(base.rgb);"
        if (spec.blend == Blend.TRANSPARENT) body += "base.rgb *= base.a;"
        body += "material.baseColor = base;"
        return "void material(inout MaterialInputs material) {\n${indent(body)}\n}"
    }

    private fun litFragment(
        spec: MaterialSpec,
        markings: MarkingRegions,
    ): String {
        val body = ArrayList<String>()
        if (spec.normalMap) {
            body += "vec3 nm = texture(materialParams_$NORMAL_MAP, getUV0()).xyz * 2.0 - 1.0;"
            body += "nm.xy *= materialParams.$NORMAL_SCALE;"
            body += "material.normal = normalize(nm);"
        }
        body += "prepareMaterial(material);"
        val coat = spec.coat
        if (coat != null) {
            body += "vec4 base = vec4(horseCoat(), 1.0);"
        } else {
            body += baseColorLines(spec)
        }
        if (spec.sand) body += "base.rgb *= sandTint(variable_vGround.xy, materialParams.$ARENA_HALF.xy);"
        if (spec.blend == Blend.TRANSPARENT) body += "base.rgb *= base.a;"
        body += "material.baseColor = base;"
        when {
            spec.shading == Shading.LAMBERT -> {
                body += "material.roughness = 1.0;"
                body += "material.metallic = 0.0;"
                body += "material.reflectance = 0.0;"
            }

            coat != null -> {
                body += "float rough = materialParams.$ROUGHNESS;"
                body += "rough = mix(rough, 0.72, variable_vMat.x);"
                body += "rough = mix(rough, 0.45, variable_vMat.y);"
                body += "rough = mix(rough, 0.12, variable_vMat.z);"
                body += "material.roughness = rough;"
                body += "material.metallic = 0.0;"
            }

            else -> {
                body += "material.roughness = materialParams.$ROUGHNESS;"
                body += "material.metallic = materialParams.$METALLIC;"
            }
        }
        val parts = ArrayList<String>()
        if (coat != null) parts += CoatGlsl.functions(low = coat == CoatKind.LOW, regions = markings)
        if (spec.sand) parts += SAND_FUNCTIONS
        parts += "void material(inout MaterialInputs material) {\n${indent(body)}\n}"
        if (spec.shading == Shading.LAMBERT) parts += LAMBERT_SURFACE_SHADING
        return parts.joinToString("\n")
    }

    // ---- vertex --------------------------------------------------------------------------------

    private fun vertexOf(spec: MaterialSpec): String? =
        when {
            spec.coat != null -> COAT_VERTEX
            spec.sand -> SAND_VERTEX
            spec.shading == Shading.SKY -> SKY_VERTEX
            spec.shading == Shading.SPRITE -> SPRITE_VERTEX
            spec.usesInstanceData || spec.wind != WindEffect.None -> instancedVertex(spec)
            else -> null
        }?.trim()

    private fun instancedVertex(spec: MaterialSpec): String {
        val lines = ArrayList<String>()
        if (spec.wind != WindEffect.None) {
            lines += "float windTime = getMaterialGlobal0().x;"
            lines += "float windStrength = getMaterialGlobal0().y;"
        }
        lines += "vec3 position = getPosition().xyz;"
        lines += "vec3 transformed = position;"
        if (spec.usesInstanceData) {
            lines += "int iid = getInstanceIndex();"
            val perRow = InstanceData.INSTANCES_PER_ROW
            lines += "ivec2 texel = ivec2((iid % $perRow) * ${InstanceData.TEXELS_PER_INSTANCE}, iid / $perRow);"
            lines += "vec4 r0 = texelFetch(materialParams_$INSTANCE_DATA, texel, 0);"
            lines += "vec4 r1 = texelFetch(materialParams_$INSTANCE_DATA, texel + ivec2(1, 0), 0);"
            lines += "vec4 r2 = texelFetch(materialParams_$INSTANCE_DATA, texel + ivec2(2, 0), 0);"
            lines += "mat4 inst = mat4(vec4(r0.x, r1.x, r2.x, 0.0), vec4(r0.y, r1.y, r2.y, 0.0), " +
                "vec4(r0.z, r1.z, r2.z, 0.0), vec4(r0.w, r1.w, r2.w, 1.0));"
        }
        val declarations = WindGlsl.declarations(spec.wind)
        if (declarations.isNotEmpty()) lines += declarations
        lines +=
            WindGlsl
                .snippet(spec.wind)
                .trim()
                .lines()
                .filter { it.isNotBlank() }
        if (spec.usesInstanceData) {
            lines += "vec3 placed = (inst * vec4(transformed, 1.0)).xyz;"
            if (spec.isLit) {
                // three.js: the normal of an instance, also right for non uniform scale. The entity transform
                // of an instanced renderable is the identity, so worldNormal is the object space normal.
                lines += "mat3 im = mat3(inst);"
                lines +=
                    "vec3 n = material.worldNormal / vec3(dot(im[0], im[0]), dot(im[1], im[1]), dot(im[2], im[2]));"
                lines += "material.worldNormal = normalize(im * n);"
            }
            if (spec.usesInstanceColor) {
                lines += "vec3 ic = texelFetch(materialParams_$INSTANCE_DATA, texel + ivec2(3, 0), 0).rgb;"
                lines +=
                    when {
                        spec.wind is WindEffect.Blossom -> {
                            "material.color.rgb = mix(material.color.rgb, material.color.rgb * ic, petal);"
                        }

                        spec.vertexColors -> {
                            "material.color.rgb *= ic;"
                        }

                        else -> {
                            "material.instanceColor = vec4(ic, 1.0);"
                        }
                    }
            }
        } else {
            lines += "vec3 placed = transformed;"
        }
        lines += "material.worldPosition = mulMat4x4Float3(getWorldFromModelMatrix(), placed);"
        return "void materialVertex(inout MaterialVertexInputs material) {\n${indent(lines)}\n}"
    }

    // ---- fixed code ----------------------------------------------------------------------------

    private const val SKY_VERTEX = """
void materialVertex(inout MaterialVertexInputs material) {
    material.vDir = vec4(normalize(getPosition().xyz), 0.0);
}"""

    private const val SKY_FRAGMENT = """
void material(inout MaterialInputs material) {
    prepareMaterial(material);
    vec3 d = normalize(variable_vDir.xyz);
    float h = d.y;
    vec3 col = h > 0.0
      ? mix(materialParams.horizon, materialParams.zenith, pow(clamp(h, 0.0, 1.0), 0.55))
      : mix(materialParams.horizon, materialParams.groundColor, clamp(-h * 6.0, 0.0, 1.0));
    float s = max(dot(d, normalize(materialParams.sunDir)), 0.0);
    col += materialParams.sunColor * (pow(s, 6.0) * 0.18 + pow(s, 64.0) * 0.35);
    col += materialParams.sunColor * smoothstep(0.9993, 0.9997, s) * 6.0;
    material.baseColor = vec4(col, 1.0);
}"""

    /**
     * A quad per particle: custom0.xy is the corner (-1..1), custom1.x the size in metres (the full
     * edge, like a point's diameter), custom1.y the opacity. The quad is moved along the camera axes.
     */
    private const val SPRITE_VERTEX = """
void materialVertex(inout MaterialVertexInputs material) {
    vec4 corner = getCustom0();
    vec4 puff = getCustom1();
    mat4 camera = getWorldFromViewMatrix();
    vec3 right = camera[0].xyz;
    vec3 up = camera[1].xyz;
    material.worldPosition.xyz += (right * corner.x + up * corner.y) * puff.x * 0.5;
    material.sprite = vec4(corner.xy, puff.y, 0.0);
}"""

    private const val SPRITE_FRAGMENT = """
void material(inout MaterialInputs material) {
    prepareMaterial(material);
    float d = length(variable_sprite.xy);
    float a = (1.0 - smoothstep(0.35, 1.0, d)) * variable_sprite.z * materialParams.baseColor.a;
    material.baseColor = vec4(materialParams.baseColor.rgb * a, a);
}"""

    /** The ground position (xz) of the vertex in world space, for the sand pattern. */
    private const val SAND_VERTEX = """
void materialVertex(inout MaterialVertexInputs material) {
    material.vGround = vec4(mulMat4x4Float3(getWorldFromModelMatrix(), getPosition().xyz).xz, 0.0, 0.0);
}"""

    /** `patchSandMaterial` of `arena.js`: the factor the base colour is multiplied by. */
    private const val SAND_FUNCTIONS = """
float gHash(vec2 p) { return fract(sin(dot(p, vec2(127.1, 311.7))) * 43758.5453); }
float gNoise(vec2 p) {
    vec2 i = floor(p);
    vec2 f = fract(p);
    f = f * f * (3.0 - 2.0 * f);
    return mix(mix(gHash(i), gHash(i + vec2(1.0, 0.0)), f.x),
               mix(gHash(i + vec2(0.0, 1.0)), gHash(i + vec2(1.0, 1.0)), f.x), f.y);
}
vec3 sandTint(vec2 gp, vec2 arenaHalf) {
    float n = gNoise(gp * 0.13) * 0.6 + gNoise(gp * 0.55) * 0.4;
    vec3 tint = vec3(0.9 + 0.2 * n);
    // track: band along a rounded rectangle about 1.7 m inside the fence
    vec2 b = arenaHalf - vec2(1.7);
    float R = 5.0;
    vec2 q = abs(gp) - (b - vec2(R));
    float d = length(max(q, 0.0)) + min(max(q.x, q.y), 0.0) - R;
    float wob = gNoise(gp * 0.8) * 0.5;
    float band = 1.0 - smoothstep(0.35, 1.25, abs(d) + wob * 0.4);
    tint *= mix(vec3(1.0), vec3(0.78, 0.72, 0.66), band);
    // lighter sand pushed up against the fence
    float edge = smoothstep(1.0, 0.0, min(arenaHalf.x - abs(gp.x), arenaHalf.y - abs(gp.y)));
    return tint * (1.0 + edge * 0.08);
}"""

    private const val COAT_VERTEX = """
void materialVertex(inout MaterialVertexInputs material) {
    material.vRest = vec4(getCustom0().xyz, 0.0);
    material.vMat = getCustom1();
    material.vFace = vec4(getCustom2().xyz, 0.0);
}"""

    /** The Lambert reflectance of three.js: `albedo / PI` times the light, with shadows. */
    private const val LAMBERT_SURFACE_SHADING = """
vec3 surfaceShading(const MaterialInputs materialInputs, const ShadingData shadingData, const LightData lightData) {
    vec3 light = lightData.colorIntensity.rgb * lightData.colorIntensity.w;
    return shadingData.diffuseColor * light * (lightData.NdotL * lightData.visibility * lightData.attenuation * 0.31830988618);
}"""
}
