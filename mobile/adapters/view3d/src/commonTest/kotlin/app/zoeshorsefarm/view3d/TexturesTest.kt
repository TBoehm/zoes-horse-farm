package app.zoeshorsefarm.view3d

import app.zoeshorsefarm.scene.texture.BlockTextRasterizer
import app.zoeshorsefarm.scene.texture.Raster2D
import app.zoeshorsefarm.scene.texture.Texture
import app.zoeshorsefarm.scene.texture.TextureColorSpace
import app.zoeshorsefarm.scene.texture.Wrap
import app.zoeshorsefarm.scene.texture.createRng
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

// Expected values were computed with the web app (node, textures.js with a faked canvas), so the
// pixel loops are checked against the original. Canvas strokes and gradients are not byte-exact
// between a browser and Raster2D, so those textures are checked by their structure.

/** Sum of the bytes, a position-weighted checksum, the first 8 bytes and 8 bytes at pixel (70, 50). */
private class PixelFingerprint(
    val width: Int,
    pixels: ByteArray,
) {
    var sum = 0L
    var weighted = 0L
    val first = IntArray(8)
    val middle = IntArray(8)

    init {
        for (i in pixels.indices) {
            val v = pixels[i].toInt() and 0xFF
            sum += v
            weighted = (weighted + v.toLong() * ((i % 251) + 1)) % 1_000_000_007L
        }
        for (k in 0 until 8) {
            first[k] = pixels[k].toInt() and 0xFF
            middle[k] = pixels[4 * (50 * width + 70) + k].toInt() and 0xFF
        }
    }

    fun assertMatches(
        expectedSum: Long,
        expectedWeighted: Long,
        expectedFirst: List<Int>,
        expectedMiddle: List<Int>,
    ) {
        assertEquals(expectedSum, sum, "sum")
        assertEquals(expectedWeighted, weighted, "weighted checksum")
        assertContentEquals(expectedFirst.toIntArray(), first, "first pixels")
        assertContentEquals(expectedMiddle.toIntArray(), middle, "pixels at (70, 50)")
    }
}

private fun fingerprint(texture: Texture) = PixelFingerprint(texture.width, texture.image.pixels)

class SandTexturesTest {
    @Test
    fun `sand map and normal map reproduce the web app at size 128`() {
        val sand = createSandTextures(size = 128)
        fingerprint(sand.map).assertMatches(
            12168210,
            532616855,
            listOf(182, 157, 121, 255, 182, 157, 121, 255),
            listOf(192, 168, 131, 255, 192, 169, 132, 255),
        )
        fingerprint(assertNotNull(sand.normalMap)).assertMatches(
            12491045,
            572787940,
            listOf(158, 110, 250, 255, 87, 127, 248, 255),
            listOf(119, 155, 252, 255, 134, 136, 255, 255),
        )
    }

    @Test
    fun `sand map and normal map reproduce the web app at size 256`() {
        val sand = createSandTextures(size = 256)
        fingerprint(sand.map).assertMatches(
            48672590,
            131573647,
            listOf(177, 152, 116, 255, 178, 153, 117, 255),
            listOf(191, 168, 131, 255, 192, 169, 132, 255),
        )
        fingerprint(assertNotNull(sand.normalMap)).assertMatches(
            49971220,
            294734573,
            listOf(126, 127, 255, 255, 61, 123, 236, 255),
            listOf(102, 122, 252, 255, 141, 108, 253, 255),
        )
    }

    @Test
    fun `the normal map is optional`() {
        assertNull(createSandTextures(size = 64, normal = false).normalMap)
    }

    @Test
    fun `sand tiles and is colour data while the normal map is not`() {
        val sand = createSandTextures(size = 64)
        assertEquals(Wrap.REPEAT, sand.map.wrapS)
        assertEquals(Wrap.REPEAT, sand.map.wrapT)
        assertEquals(TextureColorSpace.SRGB, sand.map.colorSpace)
        assertEquals(TextureColorSpace.NONE, assertNotNull(sand.normalMap).colorSpace)
        assertEquals(64, sand.map.width)
    }

    @Test
    fun `is deterministic for a seed and differs between seeds`() {
        val a = createSandTextures(size = 64, seed = 3).map.image.pixels
        val b = createSandTextures(size = 64, seed = 3).map.image.pixels
        val c = createSandTextures(size = 64, seed = 4).map.image.pixels
        assertContentEquals(a, b)
        assertTrue(!a.contentEquals(c))
    }
}

class GrassTextureTest {
    @Test
    fun `the ground of the meadow reproduces the web app at size 128`() {
        val raster = Raster2D(128, 128)
        paintGrassGround(raster, createRng(21), 21)
        PixelFingerprint(128, raster.pixels).assertMatches(
            8637760,
            87424358,
            listOf(127, 137, 74, 255, 126, 137, 74, 255),
            listOf(95, 126, 55, 255, 92, 125, 54, 255),
        )
    }

    @Test
    fun `blades are drawn over an opaque ground and the texture tiles`() {
        val ground = Raster2D(64, 64)
        paintGrassGround(ground, createRng(21), 21)
        val grass = createGrassTexture(size = 64)
        assertEquals(Wrap.REPEAT, grass.wrapS)
        assertEquals(TextureColorSpace.SRGB, grass.colorSpace)
        assertTrue(!grass.image.pixels.contentEquals(ground.pixels))
        for (i in 3 until grass.image.pixels.size step 4) assertEquals(255, grass.image.pixels[i].toInt() and 0xFF)
        assertContentEquals(grass.image.pixels, createGrassTexture(size = 64).image.pixels)
    }
}

class CloudAtlasTest {
    @Test
    fun `has four cloud variants with alpha in the image and no repeat`() {
        val atlas = createCloudAtlas(size = 128)
        assertEquals(128, atlas.width)
        assertEquals(Wrap.CLAMP_TO_EDGE, atlas.wrapS)
        val pixels = atlas.image.pixels
        for (cell in 0 until 4) {
            val ox = (cell % 2) * 64
            val oy = (cell / 2) * 64
            var maxAlpha = 0
            for (y in oy until oy + 64) {
                for (x in ox until ox + 64) maxAlpha = maxOf(maxAlpha, pixels[(y * 128 + x) * 4 + 3].toInt() and 0xFF)
            }
            assertTrue(maxAlpha > 40, "cell $cell has a cloud")
        }
        // the corners of the cells stay clear
        assertEquals(0, pixels[3].toInt() and 0xFF)
    }

    @Test
    fun `is deterministic for a seed`() {
        assertContentEquals(createCloudAtlas(size = 64).image.pixels, createCloudAtlas(size = 64).image.pixels)
    }
}

class SoftRectTextureTest {
    @Test
    fun `reproduces the web app at the default size`() {
        PixelFingerprint(128, createSoftRectTexture().image.pixels).assertMatches(
            14899694,
            878931583,
            listOf(255, 255, 255, 0, 255, 255, 255, 0),
            listOf(255, 255, 255, 206, 255, 255, 255, 206),
        )
    }

    @Test
    fun `reproduces the web app with another size and edge`() {
        PixelFingerprint(64, createSoftRectTexture(size = 64, edge = 0.3).image.pixels).assertMatches(
            3565352,
            446324697,
            listOf(255, 255, 255, 0, 255, 255, 255, 0),
            listOf(255, 255, 255, 49, 255, 255, 255, 64),
        )
    }

    @Test
    fun `is a plain mask without colour space and repeat`() {
        val texture = createSoftRectTexture()
        assertEquals(TextureColorSpace.NONE, texture.colorSpace)
        assertEquals(Wrap.CLAMP_TO_EDGE, texture.wrapS)
    }
}

class LabelAtlasTest {
    private fun items(count: Int) = List(count) { LabelItem("k$it") { _, _, _ -> } }

    @Test
    fun `twelve cells of 128 make one row of a 2048 by 128 atlas`() {
        val atlas = createLabelAtlas(items(12), BlockTextRasterizer)
        assertEquals(2048, atlas.raster.width)
        assertEquals(128, atlas.raster.height)
        val k0 = assertNotNull(atlas.rects["k0"])
        assertEquals(0.00048828125, k0.u0)
        assertEquals(0.06201171875, k0.u1)
        assertEquals(0.0078125, k0.v0)
        assertEquals(0.9921875, k0.v1)
        val k1 = assertNotNull(atlas.rects["k1"])
        assertEquals(0.06298828125, k1.u0)
        assertEquals(0.12451171875, k1.u1)
        val k11 = assertNotNull(atlas.rects["k11"])
        assertEquals(0.68798828125, k11.u0)
        assertEquals(0.74951171875, k11.u1)
    }

    @Test
    fun `many wide cells wrap into rows`() {
        val atlas = createLabelAtlas(items(40), BlockTextRasterizer, cellW = 256, cellH = 64)
        assertEquals(2048, atlas.raster.width)
        assertEquals(512, atlas.raster.height)
        val first = assertNotNull(atlas.rects["k0"])
        assertEquals(0.00048828125, first.u0)
        assertEquals(0.12451171875, first.u1)
        assertEquals(0.876953125, first.v0)
        assertEquals(0.998046875, first.v1)
        val last = assertNotNull(atlas.rects["k39"])
        assertEquals(0.87548828125, last.u0)
        assertEquals(0.99951171875, last.u1)
        assertEquals(0.376953125, last.v0)
        assertEquals(0.498046875, last.v1)
        assertEquals(40, atlas.rects.size)
    }

    @Test
    fun `a single cell gives a 128 by 128 atlas`() {
        val atlas = createLabelAtlas(items(1), BlockTextRasterizer)
        assertEquals(128, atlas.raster.width)
        assertEquals(128, atlas.raster.height)
        val only = assertNotNull(atlas.rects["k0"])
        assertEquals(0.0078125, only.u0)
        assertEquals(0.9921875, only.u1)
    }

    @Test
    fun `every cell is drawn in its own place and clipped to it`() {
        val atlas =
            createLabelAtlas(
                listOf(
                    LabelItem("a") { ctx, w, h ->
                        ctx.fillStyle = "#ff0000"
                        // bigger than the cell: the overshoot must be clipped
                        ctx.fillRect(-10.0, -10.0, w + 20.0, h + 20.0)
                    },
                    LabelItem("b") { ctx, w, h ->
                        ctx.fillStyle = "#00ff00"
                        ctx.fillRect(0.0, 0.0, w / 2.0, h.toDouble())
                    },
                ),
                BlockTextRasterizer,
            )
        val raster = atlas.raster
        assertEquals(256, raster.width)
        assertEquals(255.0, raster.getPixel(127, 5).r)
        assertEquals(0.0, raster.getPixel(127, 5).g)
        // cell b: left half green, right half empty
        assertEquals(255.0, raster.getPixel(128 + 10, 5).g)
        assertEquals(0.0, raster.getPixel(128 + 100, 5).a)
        assertEquals(TextureColorSpace.SRGB, atlas.texture.colorSpace)
        assertEquals(Wrap.CLAMP_TO_EDGE, atlas.texture.wrapS)
    }
}

class FitTextTest {
    @Test
    fun `keeps the size when the text fits`() {
        val ctx = Raster2D(16, 16, BlockTextRasterizer)
        // width = 2 * 0.6 * 40 = 48
        assertEquals(40.0, fitText(ctx, "12", 100.0, 800, 40.0))
        assertEquals("800 40px $SYSTEM_FONT", ctx.font)
    }

    @Test
    fun `shrinks in steps of 2 until the text fits`() {
        val ctx = Raster2D(16, 16, BlockTextRasterizer)
        // 2 * 0.6 * size <= 100 first holds for size 82 (98.4)
        assertEquals(82.0, fitText(ctx, "12", 100.0, 800, 90.0))
        assertEquals("800 82px $SYSTEM_FONT", ctx.font)
    }

    @Test
    fun `never goes below 8 px`() {
        val ctx = Raster2D(16, 16, BlockTextRasterizer)
        assertEquals(8.0, fitText(ctx, "a very long text", 1.0, 800, 30.0))
    }

    @Test
    fun `fontString prints whole sizes without a decimal point`() {
        assertEquals("900 130px $SYSTEM_FONT", fontString(900, 130.0))
        assertEquals("800 93.6px $SYSTEM_FONT", fontString(800, 93.6))
    }
}
