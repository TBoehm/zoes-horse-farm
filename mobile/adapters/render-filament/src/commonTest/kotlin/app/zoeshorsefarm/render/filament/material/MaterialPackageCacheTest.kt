package app.zoeshorsefarm.render.filament.material

import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertNotEquals
import kotlin.test.assertNull

class MaterialPackageCacheTest {
    private fun source(spec: MaterialSpec) = MaterialSources.generate(spec)

    @Test
    fun `the fingerprint is stable for the same source`() {
        val a = source(MaterialSpec(Shading.LIT, vertexColors = true)).fingerprint()
        val b = source(MaterialSpec(Shading.LIT, vertexColors = true)).fingerprint()
        assertEquals(a, b)
    }

    @Test
    fun `different specs have different fingerprints`() {
        val a = source(MaterialSpec(Shading.LIT)).fingerprint()
        val b = source(MaterialSpec(Shading.LAMBERT)).fingerprint()
        val c = source(MaterialSpec(Shading.LIT, doubleSided = true)).fingerprint()
        assertNotEquals(a, b)
        assertNotEquals(a, c)
    }

    @Test
    fun `a change of the marking regions changes the fingerprint of the coat`() {
        val spec = MaterialSpec(Shading.LIT, coat = CoatKind.STANDARD)
        val web = MaterialSources.generate(spec).fingerprint()
        val other =
            MaterialSources.generate(
                spec,
                MarkingRegions.WEB.copy(star = MarkingRegions.Star(0.2f, 0.05f, 0.04f)),
            )
        assertNotEquals(web, other.fingerprint())
    }

    @Test
    fun `the fingerprint is 16 hex digits`() {
        val f = source(MaterialSpec(Shading.UNLIT)).fingerprint()
        assertEquals(16, f.length)
        assertEquals(true, f.all { it in '0'..'9' || it in 'a'..'f' })
    }

    @Test
    fun `the cache key joins the name, the fingerprint and the engine version`() {
        val source = source(MaterialSpec(Shading.LIT))
        val key = MaterialPackageCache.keyOf(source, engineVersion = "1.77")
        assertEquals("zhf-lit-${source.fingerprint()}-1.77", key)
    }

    @Test
    fun `the in memory cache returns what was stored`() {
        val cache = InMemoryPackageCache()
        assertNull(cache.get("a"))
        cache.put("a", byteArrayOf(1, 2, 3))
        assertContentEquals(byteArrayOf(1, 2, 3), cache.get("a"))
        assertEquals(1, cache.size)
    }

    @Test
    fun `storing under a key replaces the earlier package`() {
        val cache = InMemoryPackageCache()
        cache.put("a", byteArrayOf(1))
        cache.put("a", byteArrayOf(2))
        assertContentEquals(byteArrayOf(2), cache.get("a"))
        assertEquals(1, cache.size)
    }
}
