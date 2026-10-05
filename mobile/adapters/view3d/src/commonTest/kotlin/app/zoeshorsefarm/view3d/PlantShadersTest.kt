package app.zoeshorsefarm.view3d

import app.zoeshorsefarm.scene.material.LambertMaterial
import app.zoeshorsefarm.scene.material.Material
import app.zoeshorsefarm.scene.material.StandardMaterial
import app.zoeshorsefarm.scene.material.Wind
import app.zoeshorsefarm.scene.material.WindEffect
import app.zoeshorsefarm.scene.material.WindKind
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertSame
import kotlin.test.assertTrue

// The GLSL of the patches lives in the Filament backend, so the tests of the web app that look for
// the shader hooks in three.js programs have no counterpart here. What the view code decides is
// which effect, with which parameters, a material gets, and that is tested.

private fun effectOf(material: Material): WindEffect = material.effect as WindEffect

private val PATCHES: Map<WindKind, (Material, Wind) -> Material> =
    mapOf(
        WindKind.TREE to { m, w -> patchTreeWind(m, w) },
        WindKind.BUSH to { m, w -> patchBushWind(m, w) },
        WindKind.TUFT to { m, w -> patchTuftWind(m, w) },
        WindKind.BLOSSOMS to { m, w -> patchBlossoms(m, w) },
        WindKind.BUNTING to { m, w -> patchBunting(m, w) },
        WindKind.WINGS to { m, w -> patchWings(m, w, rate = 9.0, amplitude = 0.5, glide = 1.0) },
    )

class CreateWindTest {
    @Test
    fun `has a running time and a full strength`() {
        val wind = createWind()
        assertEquals(0.0, wind.time)
        assertEquals(1.0, wind.strength)
    }
}

class WindPatchesTest {
    @Test
    fun `give standard and Lambert materials the effect of their kind on the shared wind`() {
        for ((kind, patch) in PATCHES) {
            for (material in listOf<Material>(StandardMaterial(), LambertMaterial())) {
                val wind = createWind()
                val patched = patch(material, wind)
                assertSame(material, patched)
                assertEquals(kind, effectOf(patched).kind, kind.name)
                assertSame(wind, effectOf(patched).wind, kind.name)
                assertTrue(patched.effectEnabled)
            }
        }
    }

    @Test
    fun `give every kind of patch its own program key`() {
        val keys = PATCHES.values.map { patch -> patch(StandardMaterial(), createWind()).customProgramCacheKey() }
        assertEquals(PATCHES.size, keys.toSet().size)
        val a = patchWings(StandardMaterial(), createWind(), rate = 9.0, amplitude = 0.5)
        val b = patchWings(StandardMaterial(), createWind(), rate = 22.0, amplitude = 1.0)
        assertTrue(a.customProgramCacheKey() != b.customProgramCacheKey())
    }

    @Test
    fun `keep the program keys of the web app`() {
        val keys = PATCHES.mapValues { (_, patch) -> patch(StandardMaterial(), createWind()).customProgramCacheKey() }
        assertEquals("wind-tree-v1", keys[WindKind.TREE])
        assertEquals("wind-bush-v1", keys[WindKind.BUSH])
        assertEquals("wind-tuft-v2", keys[WindKind.TUFT])
        assertEquals("wind-blossom-v2-0.000", keys[WindKind.BLOSSOMS])
        assertEquals("wind-bunting-v1", keys[WindKind.BUNTING])
        assertEquals("wind-wings-9.000-0.500-1.000", keys[WindKind.WINGS])
    }

    @Test
    fun `bend a flower only above its rigid base so a flower box does not wobble`() {
        val plain = patchBlossoms(StandardMaterial(), createWind())
        assertEquals(0.0, effectOf(plain).base)
        val boxed = patchBlossoms(StandardMaterial(), createWind(), base = 0.2)
        assertEquals(0.2, effectOf(boxed).base)
        // programs with another base must not share a cache key
        assertTrue(plain.customProgramCacheKey() != boxed.customProgramCacheKey())
        assertEquals("wind-blossom-v2-0.200", boxed.customProgramCacheKey())
    }

    @Test
    fun `wings beat with a phase per instance and glide only when asked to`() {
        val wind = createWind()
        val birds = effectOf(patchWings(StandardMaterial(), wind, rate = 9.0, amplitude = 0.5, glide = 1.0))
        assertEquals(9.0, birds.rate)
        assertEquals(0.5, birds.amplitude)
        assertEquals(1.0, birds.glide)
        val butterflies = effectOf(patchWings(StandardMaterial(), wind, rate = 24.0, amplitude = 1.4))
        assertEquals(0.0, butterflies.glide)
    }

    @Test
    fun `share the one wind of the world`() {
        val wind = createWind()
        val a = effectOf(patchTreeWind(StandardMaterial(), wind))
        val b = effectOf(patchBunting(LambertMaterial(), wind))
        assertSame(a.wind, b.wind)
        wind.time = 12.5
        assertEquals(12.5, b.wind.time)
    }
}

class SetWindPatchTest {
    private fun patched() = patchTreeWind(StandardMaterial(), createWind())

    @Test
    fun `switches the wind code off and on again and tells when the program changes`() {
        val material = patched()
        assertEquals("wind-tree-v1", material.customProgramCacheKey())
        assertTrue(setWindPatch(material, false))
        assertFalse(material.effectEnabled)
        // the plain program key: shared with materials that never had the code
        assertEquals(StandardMaterial().customProgramCacheKey(), material.customProgramCacheKey())
        assertNull(material.activeEffect)
        assertTrue(setWindPatch(material, true))
        assertEquals("wind-tree-v1", material.customProgramCacheKey())
        assertEquals(WindKind.TREE, effectOf(material).kind)
    }

    @Test
    fun `reports no change when the state already is the wanted one or there is no patch`() {
        val material = patched()
        assertFalse(setWindPatch(material, true))
        assertFalse(setWindPatch(StandardMaterial(), false))
        assertNull(StandardMaterial().effect)
    }

    @Test
    fun `patching again switches the wind code back on`() {
        val material = patched()
        setWindPatch(material, false)
        patchTreeWind(material, createWind())
        assertTrue(material.effectEnabled)
    }
}
