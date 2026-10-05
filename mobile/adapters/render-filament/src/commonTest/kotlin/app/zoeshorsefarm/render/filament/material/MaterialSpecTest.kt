package app.zoeshorsefarm.render.filament.material

import app.zoeshorsefarm.render.filament.mesh.VertexSemantic
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNotEquals
import kotlin.test.assertTrue

class MaterialSpecTest {
    @Test
    fun `a plain lit material needs only tangents`() {
        assertEquals(setOf(VertexSemantic.TANGENTS), MaterialSpec(Shading.LIT).requiredAttributes())
    }

    @Test
    fun `an unlit material needs no attribute besides the position`() {
        assertEquals(emptySet(), MaterialSpec(Shading.UNLIT).requiredAttributes())
    }

    @Test
    fun `vertex colours and maps add their attributes`() {
        val spec = MaterialSpec(Shading.LIT, vertexColors = true, baseColorMap = true)
        assertEquals(
            setOf(VertexSemantic.TANGENTS, VertexSemantic.COLOR, VertexSemantic.UV0),
            spec.requiredAttributes(),
        )
    }

    @Test
    fun `an alpha map needs uvs on an unlit material`() {
        val spec = MaterialSpec(Shading.UNLIT, blend = Blend.TRANSPARENT, alphaMap = true)
        assertEquals(setOf(VertexSemantic.UV0), spec.requiredAttributes())
    }

    @Test
    fun `skinning needs bone indices and weights`() {
        val attributes = MaterialSpec(Shading.LIT, skinning = true).requiredAttributes()
        assertTrue(VertexSemantic.BONE_INDICES in attributes)
        assertTrue(VertexSemantic.BONE_WEIGHTS in attributes)
    }

    @Test
    fun `blossom wind needs the petal attribute and bunting needs the flutter attribute`() {
        val blossom =
            MaterialSpec(
                Shading.LAMBERT,
                vertexColors = true,
                instancing = InstancingMode.TRANSFORMS_AND_COLOR,
                wind = WindEffect.Blossom(base = 0.5f),
            )
        assertTrue(VertexSemantic.CUSTOM0 in blossom.requiredAttributes())
        val bunting = MaterialSpec(Shading.LAMBERT, vertexColors = true, doubleSided = true, wind = WindEffect.Bunting)
        assertTrue(VertexSemantic.CUSTOM0 in bunting.requiredAttributes())
    }

    @Test
    fun `the coat needs three custom attributes`() {
        val spec = MaterialSpec(Shading.LIT, skinning = true, coat = CoatKind.STANDARD)
        val attributes = spec.requiredAttributes()
        assertTrue(
            setOf(VertexSemantic.CUSTOM0, VertexSemantic.CUSTOM1, VertexSemantic.CUSTOM2).all { it in attributes },
        )
    }

    @Test
    fun `a sprite needs the corner and size attributes`() {
        val attributes = MaterialSpec(Shading.SPRITE, blend = Blend.TRANSPARENT).requiredAttributes()
        assertTrue(VertexSemantic.CUSTOM0 in attributes)
        assertTrue(VertexSemantic.CUSTOM1 in attributes)
    }

    @Test
    fun `different specs have different keys`() {
        val specs =
            listOf(
                MaterialSpec(Shading.LIT),
                MaterialSpec(Shading.LAMBERT),
                MaterialSpec(Shading.UNLIT),
                MaterialSpec(Shading.LIT, vertexColors = true),
                MaterialSpec(Shading.LIT, baseColorMap = true),
                MaterialSpec(Shading.LIT, normalMap = true),
                MaterialSpec(Shading.LIT, skinning = true),
                MaterialSpec(Shading.LIT, doubleSided = true),
                MaterialSpec(Shading.UNLIT, blend = Blend.TRANSPARENT),
                MaterialSpec(Shading.UNLIT, blend = Blend.TRANSPARENT, depthWrite = false),
                MaterialSpec(Shading.UNLIT, blend = Blend.TRANSPARENT, alphaMap = true),
                MaterialSpec(Shading.UNLIT, toneMapped = false),
                MaterialSpec(Shading.LIT, instancing = InstancingMode.TRANSFORMS),
                MaterialSpec(Shading.LIT, instancing = InstancingMode.TRANSFORMS_AND_COLOR),
                MaterialSpec(Shading.LIT, instancing = InstancingMode.TRANSFORMS, wind = WindEffect.Tree),
                MaterialSpec(Shading.LIT, instancing = InstancingMode.TRANSFORMS, wind = WindEffect.Bush),
                MaterialSpec(Shading.LIT, instancing = InstancingMode.TRANSFORMS, wind = WindEffect.Tuft),
                MaterialSpec(
                    Shading.LIT,
                    instancing = InstancingMode.TRANSFORMS,
                    wind = WindEffect.Wings(9f, 0.5f, 1f),
                ),
                MaterialSpec(
                    Shading.LIT,
                    instancing = InstancingMode.TRANSFORMS,
                    wind = WindEffect.Wings(24f, 1.4f, 0f),
                ),
                MaterialSpec(Shading.LIT, skinning = true, coat = CoatKind.STANDARD),
                MaterialSpec(Shading.LAMBERT, skinning = true, coat = CoatKind.LOW),
                MaterialSpec(Shading.SKY),
                MaterialSpec(Shading.SPRITE, blend = Blend.TRANSPARENT),
            )
        assertEquals(specs.size, specs.map { it.key }.toSet().size)
    }

    @Test
    fun `equal specs have equal keys and are equal`() {
        val a = MaterialSpec(Shading.LIT, vertexColors = true, wind = WindEffect.Bunting)
        val b = MaterialSpec(Shading.LIT, vertexColors = true, wind = WindEffect.Bunting)
        assertEquals(a, b)
        assertEquals(a.key, b.key)
    }

    @Test
    fun `wind parameters are part of the key`() {
        val a = MaterialSpec(Shading.LIT, instancing = InstancingMode.TRANSFORMS, wind = WindEffect.Wings(9f, 0.5f, 1f))
        val b = MaterialSpec(Shading.LIT, instancing = InstancingMode.TRANSFORMS, wind = WindEffect.Wings(9f, 0.5f, 0f))
        assertNotEquals(a.key, b.key)
        val c =
            MaterialSpec(
                Shading.LIT,
                vertexColors = true,
                instancing = InstancingMode.TRANSFORMS_AND_COLOR,
                wind = WindEffect.Blossom(0f),
            )
        val d = c.copy(wind = WindEffect.Blossom(0.9f))
        assertNotEquals(c.key, d.key)
    }

    @Test
    fun `keys are safe as Filament material names`() {
        val spec =
            MaterialSpec(Shading.LIT, instancing = InstancingMode.TRANSFORMS, wind = WindEffect.Wings(9.5f, 0.25f, 1f))
        assertTrue(spec.key.all { it.isLetterOrDigit() || it == '_' || it == '-' }, spec.key)
    }

    @Test
    fun `wind that moves instances needs instancing`() {
        assertFailsWith<IllegalArgumentException> { MaterialSpec(Shading.LIT, wind = WindEffect.Tree) }
        assertFailsWith<IllegalArgumentException> { MaterialSpec(Shading.LIT, wind = WindEffect.Bush) }
        assertFailsWith<IllegalArgumentException> { MaterialSpec(Shading.LIT, wind = WindEffect.Tuft) }
        assertFailsWith<IllegalArgumentException> { MaterialSpec(Shading.LIT, wind = WindEffect.Wings(1f, 1f, 0f)) }
    }

    @Test
    fun `bunting wind does not use instancing`() {
        assertFailsWith<IllegalArgumentException> {
            MaterialSpec(Shading.LIT, instancing = InstancingMode.TRANSFORMS, wind = WindEffect.Bunting)
        }
    }

    @Test
    fun `blossoms need instance colours and vertex colours`() {
        assertFailsWith<IllegalArgumentException> {
            MaterialSpec(Shading.LIT, instancing = InstancingMode.TRANSFORMS, wind = WindEffect.Blossom(0f))
        }
        assertFailsWith<IllegalArgumentException> {
            MaterialSpec(Shading.LIT, instancing = InstancingMode.TRANSFORMS_AND_COLOR, wind = WindEffect.Blossom(0f))
        }
    }

    @Test
    fun `skinned meshes cannot be instanced or windy`() {
        assertFailsWith<IllegalArgumentException> {
            MaterialSpec(Shading.LIT, skinning = true, instancing = InstancingMode.TRANSFORMS)
        }
        assertFailsWith<IllegalArgumentException> {
            MaterialSpec(
                Shading.LIT,
                skinning = true,
                wind = WindEffect.Bunting,
            )
        }
    }

    @Test
    fun `normal maps need a lit material without instancing`() {
        assertFailsWith<IllegalArgumentException> { MaterialSpec(Shading.LAMBERT, normalMap = true) }
        assertFailsWith<IllegalArgumentException> { MaterialSpec(Shading.UNLIT, normalMap = true) }
        assertFailsWith<IllegalArgumentException> {
            MaterialSpec(Shading.LIT, normalMap = true, instancing = InstancingMode.TRANSFORMS)
        }
    }

    @Test
    fun `the sky takes nothing but its own parameters`() {
        assertFailsWith<IllegalArgumentException> { MaterialSpec(Shading.SKY, vertexColors = true) }
        assertFailsWith<IllegalArgumentException> { MaterialSpec(Shading.SKY, baseColorMap = true) }
        assertFailsWith<IllegalArgumentException> { MaterialSpec(Shading.SKY, instancing = InstancingMode.TRANSFORMS) }
    }

    @Test
    fun `a sprite is always transparent and plain`() {
        assertFailsWith<IllegalArgumentException> { MaterialSpec(Shading.SPRITE, blend = Blend.OPAQUE) }
        assertFailsWith<IllegalArgumentException> {
            MaterialSpec(Shading.SPRITE, blend = Blend.TRANSPARENT, vertexColors = true)
        }
    }

    @Test
    fun `the coat needs a lit material and nothing else that paints the surface`() {
        assertFailsWith<IllegalArgumentException> { MaterialSpec(Shading.UNLIT, coat = CoatKind.STANDARD) }
        assertFailsWith<IllegalArgumentException> {
            MaterialSpec(
                Shading.LIT,
                coat = CoatKind.STANDARD,
                vertexColors = true,
            )
        }
        assertFailsWith<IllegalArgumentException> {
            MaterialSpec(
                Shading.LIT,
                coat = CoatKind.STANDARD,
                baseColorMap = true,
            )
        }
    }

    @Test
    fun `the unlit variants that filter lighting are named by their shading`() {
        assertTrue(MaterialSpec(Shading.UNLIT).key.startsWith("unlit"))
        assertTrue(MaterialSpec(Shading.LAMBERT).key.startsWith("lambert"))
        assertTrue(MaterialSpec(Shading.LIT).key.startsWith("lit"))
    }

    @Test
    fun `transparency and depth write defaults follow the blend mode`() {
        assertTrue(MaterialSpec(Shading.LIT).depthWriteEnabled)
        assertTrue(!MaterialSpec(Shading.UNLIT, blend = Blend.TRANSPARENT).depthWriteEnabled)
        assertTrue(MaterialSpec(Shading.UNLIT, blend = Blend.TRANSPARENT, depthWrite = true).depthWriteEnabled)
        assertTrue(!MaterialSpec(Shading.UNLIT, depthWrite = false).depthWriteEnabled)
    }
}
