package app.zoeshorsefarm.render.filament.material

import app.zoeshorsefarm.render.filament.mesh.VertexSemantic
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class MaterialSourcesTest {
    private fun generate(spec: MaterialSpec) = MaterialSources.generate(spec)

    private fun assertSane(spec: MaterialSpec) {
        val problems = GlslSanity.check(generate(spec))
        assertTrue(problems.isEmpty(), "${spec.key}: $problems")
    }

    private fun uniformNames(source: MaterialSource) =
        source.parameters.filterIsInstance<UniformParameter>().map { it.name }

    private val instanced = InstancingMode.TRANSFORMS
    private val instancedColor = InstancingMode.TRANSFORMS_AND_COLOR

    // ---- every variant is consistent ----------------------------------------------------------

    @Test
    fun `every valid combination of the spec options generates consistent code`() {
        var checked = 0
        val winds =
            listOf(
                WindEffect.None,
                WindEffect.Tree,
                WindEffect.Bush,
                WindEffect.Tuft,
                WindEffect.Blossom(0.35f),
                WindEffect.Bunting,
                WindEffect.Wings(9f, 0.5f, 1f),
            )
        val problems = ArrayList<String>()
        for (shading in Shading.entries) {
            for (bits in 0 until 256) {
                for (instancing in InstancingMode.entries) {
                    for (wind in winds) {
                        for (coat in listOf(null, CoatKind.STANDARD, CoatKind.LOW)) {
                            val spec =
                                try {
                                    MaterialSpec(
                                        shading = shading,
                                        vertexColors = bits and 1 != 0,
                                        baseColorMap = bits and 2 != 0,
                                        normalMap = bits and 4 != 0,
                                        alphaMap = bits and 8 != 0,
                                        skinning = bits and 16 != 0,
                                        blend = if (bits and 32 != 0) Blend.TRANSPARENT else Blend.OPAQUE,
                                        doubleSided = bits and 64 != 0,
                                        toneMapped = bits and 128 == 0,
                                        instancing = instancing,
                                        wind = wind,
                                        coat = coat,
                                    )
                                } catch (_: IllegalArgumentException) {
                                    continue
                                }
                            checked++
                            val found = GlslSanity.check(generate(spec))
                            if (found.isNotEmpty()) problems += "${spec.key}: $found"
                        }
                    }
                }
            }
        }
        assertTrue(checked > 500, "only $checked combinations were valid")
        assertTrue(problems.isEmpty(), problems.take(5).joinToString("\n"))
    }

    @Test
    fun `the web materials are all expressible`() {
        val webMaterials =
            listOf(
                // world pairs (standard, then lambert)
                MaterialSpec(Shading.LIT, vertexColors = true, baseColorMap = true, normalMap = true),
                MaterialSpec(Shading.LAMBERT, vertexColors = true, baseColorMap = true),
                MaterialSpec(Shading.LIT),
                MaterialSpec(Shading.LIT, vertexColors = true, instancing = instanced, wind = WindEffect.Tree),
                MaterialSpec(
                    Shading.LAMBERT,
                    vertexColors = true,
                    instancing = instanced,
                    doubleSided = true,
                    wind = WindEffect.Tuft,
                ),
                MaterialSpec(
                    Shading.LIT,
                    vertexColors = true,
                    instancing = instancedColor,
                    doubleSided = true,
                    wind = WindEffect.Blossom(0.4f),
                ),
                MaterialSpec(Shading.LAMBERT, vertexColors = true, doubleSided = true, wind = WindEffect.Bunting),
                // the horse, the rider
                MaterialSpec(Shading.LIT, skinning = true, coat = CoatKind.STANDARD),
                MaterialSpec(Shading.LAMBERT, skinning = true, coat = CoatKind.LOW),
                MaterialSpec(Shading.LIT, skinning = true, vertexColors = true),
                MaterialSpec(Shading.LAMBERT, skinning = true, vertexColors = true),
                // basic materials: glow ring, aid marker, number signs, clouds
                MaterialSpec(Shading.UNLIT, blend = Blend.TRANSPARENT),
                MaterialSpec(Shading.UNLIT, blend = Blend.TRANSPARENT, alphaMap = true, doubleSided = true),
                MaterialSpec(Shading.UNLIT, baseColorMap = true, toneMapped = false),
                MaterialSpec(Shading.UNLIT, baseColorMap = true, blend = Blend.TRANSPARENT, doubleSided = true),
                // sky and dust
                MaterialSpec(Shading.SKY),
                MaterialSpec(Shading.SPRITE, blend = Blend.TRANSPARENT),
                // birds and butterflies
                MaterialSpec(Shading.LIT, instancing = instanced, wind = WindEffect.Wings(9f, 0.5f, 1f)),
                MaterialSpec(Shading.LIT, instancing = instanced, wind = WindEffect.Wings(24f, 1.4f, 0f)),
            )
        webMaterials.forEach(::assertSane)
    }

    // ---- shared properties --------------------------------------------------------------------

    @Test
    fun `the material name is the spec key`() {
        val spec = MaterialSpec(Shading.LIT, vertexColors = true)
        assertEquals("zhf-${spec.key}", generate(spec).name)
    }

    @Test
    fun `generation is deterministic`() {
        val spec = MaterialSpec(Shading.LIT, skinning = true, coat = CoatKind.STANDARD)
        val a = generate(spec)
        val b = generate(spec)
        assertEquals(a.fragment, b.fragment)
        assertEquals(a.vertex, b.vertex)
    }

    @Test
    fun `skinning is only compiled for skinned materials`() {
        assertTrue(FilteredVariant.SKINNING in generate(MaterialSpec(Shading.LIT)).filteredVariants)
        assertFalse(FilteredVariant.SKINNING in generate(MaterialSpec(Shading.LIT, skinning = true)).filteredVariants)
    }

    @Test
    fun `variants the app never uses are always filtered`() {
        val filtered = generate(MaterialSpec(Shading.LIT)).filteredVariants
        assertTrue(
            filtered.containsAll(
                listOf(
                    FilteredVariant.DYNAMIC_LIGHTING,
                    FilteredVariant.VSM,
                    FilteredVariant.SSR,
                    FilteredVariant.STEREO,
                ),
            ),
        )
    }

    @Test
    fun `culling is back, none for double sided, front for the sky and none for sprites`() {
        assertEquals(SourceCulling.BACK, generate(MaterialSpec(Shading.LIT)).culling)
        assertEquals(SourceCulling.NONE, generate(MaterialSpec(Shading.LIT, doubleSided = true)).culling)
        assertEquals(SourceCulling.FRONT, generate(MaterialSpec(Shading.SKY)).culling)
        assertEquals(SourceCulling.NONE, generate(MaterialSpec(Shading.SPRITE, blend = Blend.TRANSPARENT)).culling)
    }

    @Test
    fun `depth write follows the spec and is never on for the sky`() {
        assertTrue(generate(MaterialSpec(Shading.LIT)).depthWrite)
        assertFalse(generate(MaterialSpec(Shading.UNLIT, blend = Blend.TRANSPARENT)).depthWrite)
        assertFalse(generate(MaterialSpec(Shading.SKY)).depthWrite)
    }

    @Test
    fun `the blend mode of the spec reaches the source`() {
        assertEquals(SourceBlending.OPAQUE, generate(MaterialSpec(Shading.LIT)).blending)
        assertEquals(
            SourceBlending.TRANSPARENT,
            generate(MaterialSpec(Shading.UNLIT, blend = Blend.TRANSPARENT)).blending,
        )
    }

    // ---- lit, lambert, unlit ------------------------------------------------------------------

    @Test
    fun `lit sets roughness and metallic from the parameters`() {
        val source = generate(MaterialSpec(Shading.LIT))
        assertEquals(SourceShading.LIT, source.shading)
        assertTrue("material.roughness = materialParams.roughness;" in source.fragment)
        assertTrue("material.metallic = materialParams.metallic;" in source.fragment)
        assertEquals(listOf("baseColor", "roughness", "metallic"), uniformNames(source))
        assertFalse(source.customSurfaceShading)
    }

    @Test
    fun `lambert uses custom shading with the one over pi diffuse of three js`() {
        val source = generate(MaterialSpec(Shading.LAMBERT))
        assertEquals(SourceShading.LIT, source.shading)
        assertTrue(source.customSurfaceShading)
        assertTrue("0.31830988618" in source.fragment)
        assertTrue("lightData.visibility" in source.fragment)
        assertTrue("material.reflectance = 0.0;" in source.fragment)
        assertEquals(listOf("baseColor"), uniformNames(source))
    }

    @Test
    fun `unlit only sets the base colour`() {
        val source = generate(MaterialSpec(Shading.UNLIT))
        assertEquals(SourceShading.UNLIT, source.shading)
        assertTrue("material.baseColor = base;" in source.fragment)
        assertNull(source.vertex)
        assertTrue(source.requires.isEmpty())
    }

    @Test
    fun `vertex colours are multiplied into the base colour`() {
        val source = generate(MaterialSpec(Shading.LIT, vertexColors = true))
        assertTrue("base *= getColor();" in source.fragment)
        assertTrue(VertexSemantic.COLOR in source.requires)
    }

    @Test
    fun `the base colour map is sampled with the first uv set`() {
        val source = generate(MaterialSpec(Shading.LIT, baseColorMap = true))
        assertTrue("texture(materialParams_baseColorMap, getUV0())" in source.fragment)
        assertTrue(source.parameters.any { it is SamplerParameter && it.name == "baseColorMap" })
        assertTrue(VertexSemantic.UV0 in source.requires)
    }

    @Test
    fun `the normal map is scaled and set before prepareMaterial`() {
        val source = generate(MaterialSpec(Shading.LIT, normalMap = true))
        val normal = source.fragment.indexOf("material.normal = ")
        val prepare = source.fragment.indexOf("prepareMaterial(material);")
        assertTrue(normal in 0 until prepare, "normal at $normal, prepare at $prepare")
        assertTrue("nm.xy *= materialParams.normalScale;" in source.fragment)
        assertTrue("normalScale" in uniformNames(source))
    }

    @Test
    fun `the alpha map is read from the green channel`() {
        val source = generate(MaterialSpec(Shading.UNLIT, blend = Blend.TRANSPARENT, alphaMap = true))
        assertTrue("base.a *= texture(materialParams_alphaMap, getUV0()).g;" in source.fragment)
    }

    @Test
    fun `transparent colours are premultiplied because Filament blends premultiplied alpha`() {
        val unlit = generate(MaterialSpec(Shading.UNLIT, blend = Blend.TRANSPARENT))
        assertTrue("base.rgb *= base.a;" in unlit.fragment)
        val lit = generate(MaterialSpec(Shading.LIT, blend = Blend.TRANSPARENT))
        assertTrue("base.rgb *= base.a;" in lit.fragment)
        val opaque = generate(MaterialSpec(Shading.LIT))
        assertFalse("base.rgb *= base.a;" in opaque.fragment)
    }

    @Test
    fun `a material that must not be tone mapped inverts the curve`() {
        assertTrue("inverseTonemap(base.rgb)" in generate(MaterialSpec(Shading.UNLIT, toneMapped = false)).fragment)
        assertFalse("inverseTonemap" in generate(MaterialSpec(Shading.UNLIT)).fragment)
    }

    @Test
    fun `fog is linear in distance only`() {
        assertTrue(generate(MaterialSpec(Shading.LIT)).linearFog)
    }

    // ---- instancing ----------------------------------------------------------------------------

    @Test
    fun `an instanced material reads its matrix from the data texture`() {
        val source = generate(MaterialSpec(Shading.LIT, instancing = instanced))
        assertTrue(source.instanced)
        val vertex = source.vertex!!
        assertTrue("getInstanceIndex()" in vertex)
        assertTrue("texelFetch(materialParams_instanceData, texel, 0)" in vertex)
        assertTrue("(iid % 256) * 4" in vertex)
        assertTrue("iid / 256" in vertex)
        assertTrue("material.worldPosition = mulMat4x4Float3(getWorldFromModelMatrix(), placed);" in vertex)
        val sampler = source.parameters.filterIsInstance<SamplerParameter>().single { it.name == "instanceData" }
        assertFalse(sampler.filterable)
        assertTrue(sampler.highPrecision)
    }

    @Test
    fun `an instanced lit material rotates its normal like three js`() {
        val vertex = generate(MaterialSpec(Shading.LIT, instancing = instanced)).vertex!!
        assertTrue("material.worldNormal = normalize(im * n);" in vertex)
        assertTrue("dot(im[0], im[0])" in vertex)
        assertFalse("worldNormal" in generate(MaterialSpec(Shading.UNLIT, instancing = instanced)).vertex!!)
    }

    @Test
    fun `instance colours go through a variable when the mesh has no vertex colours`() {
        val source = generate(MaterialSpec(Shading.LIT, instancing = instancedColor))
        assertEquals(listOf("instanceColor"), source.variables)
        assertTrue("material.instanceColor = vec4(ic, 1.0);" in source.vertex!!)
        assertTrue("base.rgb *= variable_instanceColor.rgb;" in source.fragment)
    }

    @Test
    fun `instance colours multiply the vertex colours when the mesh has them`() {
        val source = generate(MaterialSpec(Shading.LIT, vertexColors = true, instancing = instancedColor))
        assertTrue(source.variables.isEmpty())
        assertTrue("material.color.rgb *= ic;" in source.vertex!!)
    }

    @Test
    fun `instances without colours do not read the colour texel`() {
        val vertex = generate(MaterialSpec(Shading.LIT, instancing = instanced)).vertex!!
        assertFalse("ivec2(3, 0)" in vertex)
    }

    // ---- wind ----------------------------------------------------------------------------------

    @Test
    fun `wind reads time and strength from material global 0`() {
        val vertex = generate(MaterialSpec(Shading.LIT, instancing = instanced, wind = WindEffect.Tree)).vertex!!
        assertTrue("float windTime = getMaterialGlobal0().x;" in vertex)
        assertTrue("float windStrength = getMaterialGlobal0().y;" in vertex)
    }

    @Test
    fun `no wind code without wind`() {
        val vertex = generate(MaterialSpec(Shading.LIT, instancing = instanced)).vertex!!
        assertFalse("windTime" in vertex)
    }

    @Test
    fun `the tree wind keeps the crown maths of the web shader`() {
        val vertex = generate(MaterialSpec(Shading.LIT, instancing = instanced, wind = WindEffect.Tree)).vertex!!
        assertTrue("clamp((position.y - 1.6) / 5.6, 0.0, 1.0)" in vertex)
        assertTrue("0.65 + 0.35 * sin(windTime * 0.31 + wp.x * 0.021 + wp.y * 0.017)" in vertex)
        assertTrue("vec2 wp = vec2(inst[3].x, inst[3].z);" in vertex)
    }

    @Test
    fun `the bush and tuft winds keep their constants`() {
        val bush = generate(MaterialSpec(Shading.LIT, instancing = instanced, wind = WindEffect.Bush)).vertex!!
        assertTrue("clamp(position.y / 1.2, 0.0, 1.0)" in bush)
        val tuft = generate(MaterialSpec(Shading.LIT, instancing = instanced, wind = WindEffect.Tuft)).vertex!!
        assertTrue("transformed.x += tuftSway * position.y * 0.18 * windStrength;" in tuft)
    }

    @Test
    fun `blossoms bend above their base and take the instance colour on petals only`() {
        val spec =
            MaterialSpec(
                Shading.LIT,
                vertexColors = true,
                instancing = instancedColor,
                wind = WindEffect.Blossom(0.35f),
            )
        val vertex = generate(spec).vertex!!
        assertTrue("float petal = getCustom0().x;" in vertex)
        assertTrue("max(position.y - 0.350, 0.0)" in vertex)
        assertTrue("mix(material.color.rgb, material.color.rgb * ic, petal)" in vertex)
    }

    @Test
    fun `bunting flutters along the flutter attribute without instancing`() {
        val spec = MaterialSpec(Shading.LAMBERT, vertexColors = true, doubleSided = true, wind = WindEffect.Bunting)
        val source = generate(spec)
        assertFalse(source.instanced)
        assertTrue("vec3 aFlutter = getCustom0().xyz;" in source.vertex!!)
        assertTrue("transformed += aFlutter * pennantFlap * windStrength * 0.12;" in source.vertex)
        assertTrue("vec3 placed = transformed;" in source.vertex)
    }

    @Test
    fun `wings flap with the instance index as phase`() {
        val spec =
            MaterialSpec(
                Shading.LIT,
                instancing = instanced,
                wind = WindEffect.Wings(rate = 9f, amplitude = 0.5f, glide = 1f),
            )
        val vertex = generate(spec).vertex!!
        assertTrue("float wingPhase = float(iid) * 1.37;" in vertex)
        assertTrue("sin(windTime * 9.000 + wingPhase)" in vertex)
        assertTrue("abs(position.x) * 0.500 * wingPause" in vertex)
        assertTrue("1.000 > 0.0" in vertex)
    }

    // ---- sky and sprite -----------------------------------------------------------------------

    @Test
    fun `the sky is an unlit gradient with a sun disc`() {
        val source = generate(MaterialSpec(Shading.SKY))
        assertEquals(SourceShading.UNLIT, source.shading)
        assertEquals(listOf("vDir"), source.variables)
        assertEquals(listOf("zenith", "horizon", "groundColor", "sunColor", "sunDir"), uniformNames(source))
        assertTrue("pow(clamp(h, 0.0, 1.0), 0.55)" in source.fragment)
        assertTrue("smoothstep(0.9993, 0.9997, s) * 6.0" in source.fragment)
        assertTrue("clamp(-h * 6.0, 0.0, 1.0)" in source.fragment)
        assertTrue(source.highPrecision)
    }

    @Test
    fun `a sprite expands to a quad along the camera axes`() {
        val source = generate(MaterialSpec(Shading.SPRITE, blend = Blend.TRANSPARENT))
        val vertex = source.vertex!!
        assertTrue("getWorldFromViewMatrix()" in vertex)
        assertTrue("(right * corner.x + up * corner.y) * puff.x * 0.5" in vertex)
        assertTrue(VertexSemantic.CUSTOM0 in source.requires && VertexSemantic.CUSTOM1 in source.requires)
        assertTrue("smoothstep(0.35, 1.0, d)" in source.fragment)
        assertEquals(listOf("sprite"), source.variables)
    }

    // ---- coat ----------------------------------------------------------------------------------

    @Test
    fun `the coat declares the uniforms of the web material and three variables`() {
        val source = generate(MaterialSpec(Shading.LIT, skinning = true, coat = CoatKind.STANDARD))
        val names = uniformNames(source)
        for (name in listOf("uBase", "uDark", "uBelly", "uHair", "uPointColor", "uMuzzle", "uHoof", "uWhite")) {
            assertTrue(name in names, name)
        }
        for (name in listOf("uPoints", "uDapple", "uPinto", "uMarking", "uFlare", "uBlink", "roughness")) {
            assertTrue(name in names, name)
        }
        assertEquals(listOf("vRest", "vMat", "vFace"), source.variables)
        assertTrue(source.highPrecision)
    }

    @Test
    fun `coat uniforms are read from the material parameters`() {
        val fragment = generate(MaterialSpec(Shading.LIT, coat = CoatKind.STANDARD)).fragment
        assertTrue("materialParams.uBase" in fragment)
        assertFalse(Regex("""(?<![.\w])uBase\b""").containsMatchIn(fragment), "bare uBase left")
        assertTrue("variable_vMat" in fragment)
    }

    @Test
    fun `the standard coat has fine noise and spots, the low coat paints wraps and blinks`() {
        val standard = generate(MaterialSpec(Shading.LIT, coat = CoatKind.STANDARD)).fragment
        val low = generate(MaterialSpec(Shading.LAMBERT, coat = CoatKind.LOW)).fragment
        assertTrue("hzNoise(p * 3.0) * 0.6" in standard)
        assertFalse("hzNoise(p * 3.0) * 0.6" in low)
        assertTrue("float sp = smoothstep(0.72, 0.85, hzNoise(p * 55.0));" in standard)
        assertFalse("hzNoise(p * 55.0)" in low)
        assertTrue("wrapCol" in low)
        assertFalse("wrapCol" in standard)
        assertTrue("vMat.z * uBlink".replace("uBlink", "materialParams.uBlink") in low)
        assertFalse("uBlink" in standard)
        assertFalse("#ifdef" in standard || "#ifdef" in low)
    }

    @Test
    fun `the coat markings use the regions of the head`() {
        val fragment = generate(MaterialSpec(Shading.LIT, coat = CoatKind.STANDARD)).fragment
        assertTrue("(s - 0.1550) / 0.0480" in fragment)
        assertTrue("mix(0.0260, 0.0420" in fragment)
        assertTrue("(s - 0.5750) / 0.0260" in fragment)
        val other =
            MaterialSources
                .generate(
                    MaterialSpec(Shading.LIT, coat = CoatKind.STANDARD),
                    MarkingRegions.WEB.copy(star = MarkingRegions.Star(0.2f, 0.05f, 0.04f)),
                ).fragment
        assertTrue("(s - 0.2000) / 0.0500" in other)
    }

    @Test
    fun `the standard coat mixes the roughness with the material weights`() {
        val fragment = generate(MaterialSpec(Shading.LIT, coat = CoatKind.STANDARD)).fragment
        assertTrue("rough = mix(rough, 0.72, variable_vMat.x);" in fragment)
        assertTrue("rough = mix(rough, 0.12, variable_vMat.z);" in fragment)
    }

    @Test
    fun `the coat vertex shader forwards the custom attributes`() {
        val source = generate(MaterialSpec(Shading.LIT, skinning = true, coat = CoatKind.STANDARD))
        assertTrue("material.vRest = vec4(getCustom0().xyz, 0.0);" in source.vertex!!)
        assertTrue(
            setOf(VertexSemantic.CUSTOM0, VertexSemantic.CUSTOM1, VertexSemantic.CUSTOM2).all { it in source.requires },
        )
    }
}
