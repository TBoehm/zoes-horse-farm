package app.zoeshorsefarm.render.filament.material

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNotSame
import kotlin.test.assertSame
import kotlin.test.assertTrue

class MaterialLibraryTest {
    private class FakeMaterial(
        val name: String,
    )

    private class Fixture(
        failing: Set<String> = emptySet(),
    ) {
        val built = mutableListOf<String>()
        val destroyed = mutableListOf<String>()
        val library =
            MaterialLibrary<FakeMaterial>(
                build = { source ->
                    built += source.name
                    if (source.name in failing) null else FakeMaterial(source.name)
                },
                destroy = { destroyed += it.name },
            )
    }

    private val lit = MaterialSpec(Shading.LIT)
    private val lambert = MaterialSpec(Shading.LAMBERT)
    private val unlit = MaterialSpec(Shading.UNLIT)

    @Test
    fun `a material is built when it is first asked for`() {
        val f = Fixture()
        assertEquals(0, f.library.materialCount)
        f.library.get(lit)
        assertEquals(listOf("zhf-lit"), f.built)
        assertEquals(1, f.library.materialCount)
    }

    @Test
    fun `the same spec is built once and cached`() {
        val f = Fixture()
        val a = f.library.get(lit)
        val b = f.library.get(MaterialSpec(Shading.LIT))
        assertSame(a, b)
        assertEquals(1, f.built.size)
    }

    @Test
    fun `different specs are different materials`() {
        val f = Fixture()
        assertNotSame(f.library.get(lit), f.library.get(lambert))
        assertEquals(2, f.library.materialCount)
    }

    @Test
    fun `contains tells what is built without building it`() {
        val f = Fixture()
        assertTrue(!f.library.contains(lit))
        f.library.get(lit)
        assertTrue(f.library.contains(lit))
        assertEquals(1, f.built.size)
    }

    @Test
    fun `keys list the built materials in build order`() {
        val f = Fixture()
        f.library.get(lambert)
        f.library.get(lit)
        assertEquals(listOf("lambert", "lit"), f.library.keys())
    }

    @Test
    fun `a material that fails to compile is reported and not retried`() {
        val f = Fixture(failing = setOf("zhf-lit"))
        assertFailsWith<MaterialBuildException> { f.library.get(lit) }
        assertFailsWith<MaterialBuildException> { f.library.get(lit) }
        assertEquals(1, f.built.size)
        assertEquals(0, f.library.materialCount)
        assertEquals(1, f.library.failedCount)
    }

    @Test
    fun `the failure names the material`() {
        val f = Fixture(failing = setOf("zhf-lit"))
        val error = assertFailsWith<MaterialBuildException> { f.library.get(lit) }
        assertTrue("zhf-lit" in error.message.orEmpty())
    }

    @Test
    fun `clear destroys the materials newest first and forgets them`() {
        val f = Fixture()
        f.library.get(lit)
        f.library.get(lambert)
        f.library.get(unlit)
        f.library.clear()
        assertEquals(listOf("zhf-unlit", "zhf-lambert", "zhf-lit"), f.destroyed)
        assertEquals(0, f.library.materialCount)
        // a cleared library builds again on demand (after a lost context, a level change)
        f.library.get(lit)
        assertEquals(4, f.built.size)
    }

    @Test
    fun `release destroys one material and builds it again when asked for again`() {
        val f = Fixture()
        f.library.get(lit)
        f.library.get(lambert)
        assertTrue(f.library.release(lit))
        assertTrue(!f.library.release(lit))
        assertEquals(listOf("zhf-lit"), f.destroyed)
        assertEquals(1, f.library.materialCount)
        f.library.get(lit)
        assertEquals(3, f.built.size)
    }

    @Test
    fun `the total number of builds counts every compile`() {
        val f = Fixture()
        f.library.get(lit)
        f.library.release(lit)
        f.library.get(lit)
        assertEquals(2, f.library.totalBuilds)
    }

    @Test
    fun `a clear that meets a failing destroy still destroys the rest`() {
        val destroyed = mutableListOf<String>()
        val library =
            MaterialLibrary<FakeMaterial>(
                build = { FakeMaterial(it.name) },
                destroy = {
                    destroyed += it.name
                    if (it.name == "zhf-lambert") error("boom")
                },
            )
        library.get(lit)
        library.get(lambert)
        library.get(unlit)
        val errors = library.clear()
        assertEquals(3, destroyed.size)
        assertEquals(1, errors.size)
    }

    @Test
    fun `the program estimate counts the variants of each material`() {
        val f = Fixture()
        f.library.get(lit)
        val litOnly = f.library.estimatedPrograms
        f.library.get(MaterialSpec(Shading.LIT, skinning = true))
        val withSkinned = f.library.estimatedPrograms
        assertTrue(litOnly > 0)
        assertTrue(withSkinned > litOnly)
    }

    @Test
    fun `an unlit material has fewer programs than a lit one`() {
        val unlitSource = MaterialSources.generate(unlit)
        val litSource = MaterialSources.generate(lit)
        assertTrue(ProgramEstimate.of(unlitSource) < ProgramEstimate.of(litSource))
    }

    @Test
    fun `a skinned material has twice the programs of the same unskinned one`() {
        val plain = ProgramEstimate.of(MaterialSources.generate(lit))
        val skinned = ProgramEstimate.of(MaterialSources.generate(MaterialSpec(Shading.LIT, skinning = true)))
        assertEquals(plain * 2, skinned)
    }

    @Test
    fun `the library passes the marking regions on to the coat shader`() {
        val sources = mutableListOf<MaterialSource>()
        val library =
            MaterialLibrary<FakeMaterial>(
                markings = MarkingRegions.WEB.copy(star = MarkingRegions.Star(0.2f, 0.05f, 0.04f)),
                build = {
                    sources += it
                    FakeMaterial(it.name)
                },
                destroy = {},
            )
        library.get(MaterialSpec(Shading.LIT, coat = CoatKind.STANDARD))
        assertTrue("(s - 0.2000) / 0.0500" in sources.single().fragment)
    }
}
