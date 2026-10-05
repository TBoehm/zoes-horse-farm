package app.zoeshorsefarm.view3d

import app.zoeshorsefarm.scene.texture.createRng
import kotlin.math.hypot
import kotlin.math.roundToInt
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotEquals
import kotlin.test.assertTrue

private fun plan(
    seed: Int = 5,
    options: MeadowOptions = MeadowOptions(),
) = planMeadow(createRng(seed), options)

class PlanMeadowTest {
    @Test
    fun `is deterministic for a seed`() {
        assertEquals(plan(7), plan(7))
        assertNotEquals(plan(7).flowers, plan(8).flowers)
    }

    @Test
    fun `plants a dense meadow in patches of several colours`() {
        val (patches, flowers) = plan()
        assertTrue(patches.size >= 30)
        assertTrue(flowers.size >= 3000)
        val used = flowers.map { it.color }.toSet()
        assertEquals(FLOWER_COLORS.size, used.size)
        for (name in listOf("white", "yellow", "pink", "violet", "blue")) {
            assertTrue(FLOWER_COLORS.any { it.name == name })
        }
    }

    @Test
    fun `keeps flowers off the sand the paths the buildings and the paddock`() {
        for (f in plan(3).flowers) {
            assertFalse(isBlocked(f.x, f.z, 0.3))
            assertFalse(paddockContains(f.x, f.z, -1.2))
        }
    }

    @Test
    fun `stays on the flat meadow around the facility`() {
        for (f in plan(4).flowers) assertTrue(hypot(f.x, f.z) < 90)
    }

    @Test
    fun `puts patches near the judges hut and the stable`() {
        val patches = plan().patches

        fun near(
            x: Double,
            z: Double,
            radius: Double,
        ) = patches.count { hypot(it.x - x, it.z - z) < radius }
        assertTrue(near(SITE.hut.x, SITE.hut.z, 12.0) >= 2)
        assertTrue(near(SITE.stable.x, SITE.stable.z, 22.0) >= 2)
    }

    @Test
    fun `keeps flowers close to their patch with a sensible size`() {
        val (patches, flowers) = plan(9)
        for (f in flowers) {
            val patch = patches[f.patch]
            assertTrue(hypot(f.x - patch.x, f.z - patch.z) <= patch.radius + 1e-9)
            assertTrue(f.scale > 0.6)
            assertTrue(f.scale < 1.5)
        }
    }

    @Test
    fun `orders the flowers so that every prefix is a thinner copy of the whole meadow`() {
        val (patches, flowers) = plan(2)
        for (share in listOf(0.25, 0.5)) {
            val seen = flowers.take((flowers.size * share).roundToInt()).map { it.patch }.toSet()
            assertTrue(seen.size >= patches.size * 0.9)
        }
    }

    @Test
    fun `gives most patches a main colour and some a second one`() {
        val (patches, flowers) = plan(6)
        val mixed = patches.count { it.accent != null }
        assertTrue(mixed > 0)
        assertTrue(mixed < patches.size)
        val main = flowers.count { it.color == patches[it.patch].color }
        assertTrue(main.toDouble() / flowers.size > 0.6)
    }

    @Test
    fun `accepts a smaller meadow for tests and low budgets`() {
        val flowers = plan(1, MeadowOptions(patchCount = 6, perPatch = 10.0..12.0)).flowers
        assertTrue(flowers.size <= 6 * 12)
        assertTrue(flowers.size >= 6 * 10)
    }

    @Test
    fun `reproduces the plan of the web app for seed 5`() {
        // expected values computed with the web app (node, createRng(5), default options)
        val (patches, flowers) = plan()
        assertEquals(44, patches.size)
        assertEquals(3543, flowers.size)
        assertEquals(Patch(33.0, -19.5, 3.0, 4, null), patches[0])
        val p40 = patches[40]
        assertEquals(24.629778097826684, p40.x, 1e-9)
        assertEquals(32.71208345285139, p40.z, 1e-9)
        assertEquals(2.961766903940588, p40.radius, 1e-9)
        assertEquals(2, p40.color)
        val first = flowers.first()
        assertEquals(35.2062981964409, first.x, 1e-9)
        assertEquals(-18.954214557805106, first.z, 1e-9)
        assertEquals(1.0211411198279037, first.scale, 1e-9)
        assertEquals(4.5768744563008195, first.yaw, 1e-9)
        assertEquals(4, first.color)
        assertEquals(0, first.patch)
        val last = flowers.last()
        assertEquals(25.530906031313275, last.x, 1e-9)
        assertEquals(30.08390258555201, last.z, 1e-9)
        assertEquals(40, last.patch)
        assertEquals(15401.823129529403, flowers.sumOf { it.x }, 1e-6)
        assertEquals(11266.046559078322, flowers.sumOf { it.z }, 1e-6)
        assertEquals(3627.4395692286303, flowers.sumOf { it.scale }, 1e-6)
        assertEquals(7818, flowers.sumOf { it.color })
    }

    @Test
    fun `reproduces a small meadow of the web app for seed 1`() {
        val (patches, flowers) = plan(1, MeadowOptions(patchCount = 6, perPatch = 10.0..12.0))
        assertEquals(6, patches.size)
        assertEquals(66, flowers.size)
        assertEquals(Patch(-26.5, 30.0, 2.4, 2, 5), patches[5])
        assertEquals(-32.93638668915301, flowers[3].x, 1e-9)
        assertEquals(8.682345258468853, flowers[3].z, 1e-9)
        assertEquals(1.1107553129849657, flowers[3].scale, 1e-9)
        assertEquals(4, flowers[3].color)
        assertEquals(3, flowers[3].patch)
    }
}

class ButterflyAnchorsTest {
    @Test
    fun `picks patches within sight of the arena`() {
        val anchors = butterflyAnchors(plan().patches, 8)
        assertEquals(8, anchors.size)
        for (a in anchors) {
            assertTrue(hypot(a.x, a.z) < 60)
            assertTrue(a.radius > 0)
        }
    }

    @Test
    fun `returns fewer anchors when there are not enough patches`() {
        val patches = plan(1, MeadowOptions(patchCount = 3, perPatch = 5.0..5.0)).patches
        assertTrue(butterflyAnchors(patches, 10).size <= 3)
    }

    @Test
    fun `reproduces the anchors of the web app for seed 5`() {
        val anchors = butterflyAnchors(plan().patches, 8)
        assertEquals(PatchAnchor(25.95606675140419, -2.5423658902625084, 2.595011233724654), anchors[0])
        assertEquals(PatchAnchor(-31.0, 9.0, 3.0), anchors[3])
        assertEquals(PatchAnchor(33.0, -19.5, 3.0), anchors[7])
    }
}
