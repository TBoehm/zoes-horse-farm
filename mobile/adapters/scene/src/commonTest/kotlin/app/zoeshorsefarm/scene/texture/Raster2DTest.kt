package app.zoeshorsefarm.scene.texture

import app.zoeshorsefarm.scene.assertNear
import kotlin.math.PI
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertSame
import kotlin.test.assertTrue

class Raster2DTest {
    private fun alpha(
        r: Raster2D,
        x: Int,
        y: Int,
    ): Int = r.pixels[(y * r.width + x) * 4 + 3].toInt() and 0xFF

    private fun rgba(
        r: Raster2D,
        x: Int,
        y: Int,
    ): List<Int> =
        (0 until 4).map {
            r.pixels[(y * r.width + x) * 4 + it].toInt() and
                0xFF
        }

    private fun coverage(r: Raster2D): Double {
        var sum = 0.0
        for (y in 0 until r.height) for (x in 0 until r.width) sum += alpha(r, x, y) / 255.0
        return sum
    }

    @Test
    fun `a new raster is transparent black`() {
        val r = Raster2D(4, 3)
        assertEquals(4 * 3 * 4, r.pixels.size)
        assertTrue(r.pixels.all { it == 0.toByte() })
    }

    @Test
    fun `fillRect on whole pixels is exact`() {
        val r = Raster2D(8, 8)
        r.fillStyle = "#336699"
        r.fillRect(2.0, 3.0, 4.0, 2.0)
        assertEquals(listOf(0x33, 0x66, 0x99, 255), rgba(r, 2, 3))
        assertEquals(listOf(0x33, 0x66, 0x99, 255), rgba(r, 5, 4))
        assertEquals(0, alpha(r, 6, 4))
        assertEquals(0, alpha(r, 2, 5))
        assertNear(8.0, coverage(r))
    }

    @Test
    fun `a rectangle with fractional edges is anti-aliased`() {
        val r = Raster2D(8, 8)
        r.fillStyle = "#ffffff"
        r.fillRect(1.5, 1.0, 3.0, 2.0)
        assertEquals(128, alpha(r, 1, 1)) // half covered
        assertEquals(255, alpha(r, 2, 1))
        assertEquals(255, alpha(r, 3, 2))
        assertEquals(128, alpha(r, 4, 1))
        assertNear(6.0, coverage(r), 0.01)
    }

    @Test
    fun `a filled circle covers pi r squared`() {
        val r = Raster2D(64, 64)
        r.fillStyle = "#ff0000"
        r.beginPath()
        r.arc(32.3, 31.8, 20.0, 0.0, PI * 2)
        r.fill()
        assertNear(PI * 400, coverage(r), 0.003)
        assertEquals(listOf(255, 0, 0, 255), rgba(r, 32, 32))
        assertEquals(0, alpha(r, 2, 2))
    }

    @Test
    fun `a triangle covers half of its bounding box`() {
        val r = Raster2D(40, 40)
        r.fillStyle = "#000000"
        r.beginPath()
        r.moveTo(0.0, 0.0)
        r.lineTo(30.0, 0.0)
        r.lineTo(0.0, 30.0)
        r.closePath()
        r.fill()
        assertNear(450.0, coverage(r), 0.003)
    }

    @Test
    fun `both winding directions fill the same`() {
        val a = Raster2D(20, 20)
        a.beginPath()
        a.moveTo(3.2, 4.1)
        a.lineTo(15.7, 6.3)
        a.lineTo(9.9, 16.8)
        a.fill()
        val b = Raster2D(20, 20)
        b.beginPath()
        b.moveTo(3.2, 4.1)
        b.lineTo(9.9, 16.8)
        b.lineTo(15.7, 6.3)
        b.fill()
        assertTrue(a.pixels.contentEquals(b.pixels))
    }

    @Test
    fun `shapes are clipped at the borders without shifting the rest`() {
        val r = Raster2D(10, 10)
        r.fillStyle = "#ffffff"
        r.fillRect(-5.0, -5.0, 10.0, 10.0)
        assertEquals(255, alpha(r, 0, 0))
        assertEquals(255, alpha(r, 4, 4))
        assertEquals(0, alpha(r, 5, 5))
        val c = Raster2D(10, 10)
        c.beginPath()
        c.arc(0.0, 0.0, 6.0, 0.0, PI * 2)
        c.fill()
        assertNear(PI * 36 / 4, coverage(c), 0.01)
    }

    @Test
    fun `globalAlpha blends over what is there`() {
        val r = Raster2D(4, 4)
        r.fillStyle = "#000000"
        r.fillRect(0.0, 0.0, 4.0, 4.0)
        r.globalAlpha = 0.5
        r.fillStyle = "#ffffff"
        r.fillRect(0.0, 0.0, 2.0, 2.0)
        assertEquals(listOf(128, 128, 128, 255), rgba(r, 0, 0))
        assertEquals(listOf(0, 0, 0, 255), rgba(r, 3, 3))
    }

    @Test
    fun `source-over of translucent colours keeps straight alpha`() {
        val r = Raster2D(2, 2)
        r.fillStyle = "rgba(255, 0, 0, 0.5)"
        r.fillRect(0.0, 0.0, 2.0, 2.0)
        assertEquals(listOf(255, 0, 0, 128), rgba(r, 0, 0))
        r.fillStyle = "rgba(0, 0, 255, 0.5)"
        r.fillRect(0.0, 0.0, 1.0, 1.0)
        // result alpha 0.5 + 0.502 * 0.5 = 0.751, colour weighted by contribution
        val p = rgba(r, 0, 0)
        assertEquals(192, p[3])
        assertEquals(85, p[0])
        assertEquals(170, p[2])
    }

    @Test
    fun `source-atop only paints where something is already drawn`() {
        val r = Raster2D(4, 2)
        r.fillStyle = "#ff0000"
        r.fillRect(0.0, 0.0, 2.0, 2.0)
        r.globalCompositeOperation = "source-atop"
        r.fillStyle = "rgba(0, 0, 255, 0.5)"
        r.fillRect(0.0, 0.0, 4.0, 2.0)
        assertEquals(listOf(128, 0, 128, 255), rgba(r, 0, 0))
        assertEquals(0, alpha(r, 3, 0))
    }

    @Test
    fun `clearRect makes pixels transparent`() {
        val r = Raster2D(4, 4)
        r.fillStyle = "#ffffff"
        r.fillRect(0.0, 0.0, 4.0, 4.0)
        r.clearRect(1.0, 1.0, 2.0, 2.0)
        assertEquals(0, alpha(r, 1, 1))
        assertEquals(0, alpha(r, 2, 2))
        assertEquals(255, alpha(r, 0, 0))
        assertEquals(255, alpha(r, 3, 3))
    }

    @Test
    fun `save restore translate and clip work together`() {
        val r = Raster2D(20, 10)
        r.fillStyle = "#ff0000"
        r.save()
        r.translate(10.0, 0.0)
        r.beginPath()
        r.rect(0.0, 0.0, 5.0, 5.0)
        r.clip()
        r.fillRect(-10.0, -10.0, 100.0, 100.0)
        r.restore()
        assertEquals(255, alpha(r, 10, 0))
        assertEquals(255, alpha(r, 14, 4))
        assertEquals(0, alpha(r, 15, 4)) // outside the clip
        assertEquals(0, alpha(r, 9, 0))
        assertEquals(0, alpha(r, 10, 5))
        // after restore nothing is clipped or moved any more
        r.fillStyle = "#00ff00"
        r.fillRect(0.0, 9.0, 1.0, 1.0)
        assertEquals(listOf(0, 255, 0, 255), rgba(r, 0, 9))
    }

    @Test
    fun `restore brings back the styles`() {
        val r = Raster2D(2, 2)
        r.fillStyle = "#123456"
        r.globalAlpha = 0.25
        r.save()
        r.fillStyle = "#ffffff"
        r.globalAlpha = 1.0
        r.restore()
        assertEquals("#123456", r.fillStyle)
        assertNear(0.25, r.globalAlpha)
    }

    @Test
    fun `a clip with a non-rectangular path masks softly`() {
        val r = Raster2D(40, 40)
        r.beginPath()
        r.arc(20.0, 20.0, 10.0, 0.0, PI * 2)
        r.clip()
        r.fillStyle = "#ffffff"
        r.fillRect(0.0, 0.0, 40.0, 40.0)
        assertNear(PI * 100, coverage(r), 0.01)
        assertEquals(0, alpha(r, 5, 5))
        assertEquals(255, alpha(r, 20, 20))
    }

    @Test
    fun `linear gradient interpolates the stops`() {
        val r = Raster2D(11, 1)
        val g = r.createLinearGradient(0.0, 0.0, 10.0, 0.0)
        g.addColorStop(0.0, "#000000")
        g.addColorStop(1.0, "#ff0000")
        r.fillStyle = g
        r.fillRect(0.0, 0.0, 11.0, 1.0)
        assertEquals(listOf(13, 0, 0, 255), rgba(r, 0, 0)) // t = 0.05 at the pixel centre
        assertEquals(listOf(140, 0, 0, 255), rgba(r, 5, 0))
        assertEquals(listOf(255, 0, 0, 255), rgba(r, 10, 0)) // t = 1.05 clamps to the last stop
    }

    @Test
    fun `radial gradient fades from centre to rim like a cloud puff`() {
        val r = Raster2D(41, 41)
        val g = r.createRadialGradient(20.0, 17.0, 2.0, 20.0, 20.0, 20.0)
        g.addColorStop(0.0, "rgba(255, 255, 255, 1)")
        g.addColorStop(1.0, "rgba(255, 255, 255, 0)")
        r.fillStyle = g
        r.fillRect(0.0, 0.0, 41.0, 41.0)
        assertEquals(255, alpha(r, 20, 17))
        assertEquals(0, alpha(r, 0, 0))
        val inner = alpha(r, 20, 24)
        val outer = alpha(r, 20, 36)
        assertTrue(inner in 1..254 && outer in 0 until inner, "alpha must fall with the distance: $inner then $outer")
    }

    @Test
    fun `a stroked line has the line width and round caps`() {
        val r = Raster2D(30, 20)
        r.strokeStyle = "#000000"
        r.lineWidth = 4.0
        r.lineCap = "round"
        r.beginPath()
        r.moveTo(10.0, 10.0)
        r.lineTo(20.0, 10.0)
        r.stroke()
        assertEquals(255, alpha(r, 15, 8))
        assertEquals(255, alpha(r, 15, 11))
        assertEquals(0, alpha(r, 15, 7))
        assertEquals(0, alpha(r, 15, 12))
        // the round cap reaches 2 px beyond the end points
        assertTrue(alpha(r, 8, 10) > 200 && alpha(r, 7, 10) == 0)
        assertNear(10.0 * 4 + PI * 4, coverage(r), 0.02)
    }

    @Test
    fun `butt caps end at the end points`() {
        val r = Raster2D(30, 20)
        r.strokeStyle = "#000000"
        r.lineWidth = 2.0
        r.beginPath()
        r.moveTo(10.0, 10.0)
        r.lineTo(20.0, 10.0)
        r.stroke()
        assertEquals(255, alpha(r, 10, 9))
        assertEquals(0, alpha(r, 9, 9))
        assertEquals(0, alpha(r, 20, 9))
        assertNear(20.0, coverage(r), 0.01)
    }

    @Test
    fun `arcTo rounds the corners of a rectangle`() {
        val r = Raster2D(60, 40)
        r.fillStyle = "#1f8f46"
        r.beginPath()
        val x = 8.0
        val y = 8.0
        val w = 44.0
        val h = 24.0
        val rad = 8.0
        r.moveTo(x + rad, y)
        r.arcTo(x + w, y, x + w, y + h, rad)
        r.arcTo(x + w, y + h, x, y + h, rad)
        r.arcTo(x, y + h, x, y, rad)
        r.arcTo(x, y, x + w, y, rad)
        r.closePath()
        r.fill()
        assertEquals(0, alpha(r, 8, 8)) // the corner pixel is cut off
        assertEquals(255, alpha(r, 30, 20))
        assertNear(w * h - (4 - PI) * rad * rad, coverage(r), 0.003)
    }

    @Test
    fun `quadratic and cubic curves enclose the expected area`() {
        val r = Raster2D(40, 40)
        r.beginPath()
        r.moveTo(5.0, 30.0)
        r.quadraticCurveTo(20.0, -10.0, 35.0, 30.0)
        r.closePath()
        r.fill()
        // area under a quadratic Bezier arch: 2/3 of the base times the height (apex at y = 10)
        assertNear(2.0 / 3.0 * 30.0 * 20.0, coverage(r), 0.01)
    }

    @Test
    fun `text is aligned and baselined like a canvas`() {
        val r = Raster2D(100, 40)
        r.font = "800 20px system-ui, sans-serif"
        r.textAlign = "center"
        r.textBaseline = "alphabetic"
        r.fillStyle = "#000000"
        assertNear(36.0, r.measureText("123"))
        r.fillText("123", 50.0, 30.0)
        var minX = 100
        var maxX = -1
        var minY = 40
        var maxY = -1
        for (y in 0 until 40) {
            for (x in 0 until 100) {
                if (alpha(r, x, y) > 0) {
                    minX = minOf(minX, x)
                    maxX = maxOf(maxX, x)
                    minY = minOf(minY, y)
                    maxY = maxOf(maxY, y)
                }
            }
        }
        // block rasteriser: 3 boxes of 10 x 14 px on an advance of 12 px (36 px in all), starting at x = 50 - 18
        assertEquals(32, minX)
        assertEquals(65, maxX)
        assertEquals(16, minY)
        assertEquals(29, maxY)
    }

    @Test
    fun `middle baseline centres the em box vertically`() {
        val r = Raster2D(40, 40)
        r.font = "10px sans-serif"
        r.textAlign = "left"
        r.textBaseline = "middle"
        r.fillText("A", 0.0, 20.0)
        var minY = 40
        var maxY = -1
        for (y in 0 until 40) {
            if (alpha(r, 0, y) > 0) {
                minY = minOf(minY, y)
                maxY = maxOf(maxY, y)
            }
        }
        // ascent 8, descent 2: the baseline sits 3 px below the middle line, the box is 7 px tall
        assertEquals(16, minY)
        assertEquals(22, maxY)
    }

    @Test
    fun `font strings are parsed`() {
        assertEquals(FontSpec(800, 64.0, "system-ui, sans-serif"), FontSpec.parse("800 64px system-ui, sans-serif"))
        assertEquals(FontSpec(400, 10.0, "sans-serif"), FontSpec.parse("nonsense"))
        assertEquals(FontSpec(700, 12.5, "Arial"), FontSpec.parse("bold 12.5px Arial"))
    }

    @Test
    fun `drawImage copies and blends another raster`() {
        val src = Raster2D(4, 4)
        src.fillStyle = "#ff0000"
        src.fillRect(0.0, 0.0, 4.0, 4.0)
        val dst = Raster2D(10, 10)
        dst.globalAlpha = 0.5
        dst.drawImage(src, 3.0, 2.0)
        assertEquals(listOf(255, 0, 0, 128), rgba(dst, 3, 2))
        assertEquals(listOf(255, 0, 0, 128), rgba(dst, 6, 5))
        assertEquals(0, alpha(dst, 7, 5))
        assertEquals(0, alpha(dst, 2, 2))
    }

    @Test
    fun `drawImage scales`() {
        val src = Raster2D(2, 1)
        src.fillStyle = "#ffffff"
        src.fillRect(0.0, 0.0, 1.0, 1.0)
        val dst = Raster2D(8, 4)
        dst.drawImage(src, 0.0, 0.0, 8.0, 4.0)
        assertEquals(255, alpha(dst, 0, 0))
        assertEquals(0, alpha(dst, 7, 3))
    }

    @Test
    fun `image data stores clamp and round like Uint8ClampedArray`() {
        val image = ImageData(2, 1)
        image[0] = 2.5
        image[1] = 3.5
        image[2] = 300.0
        image[3] = -3.0
        image[4] = Double.NaN
        image[5] = 207.51
        assertEquals(listOf(2, 4, 255, 0, 0, 208), (0 until 6).map { image[it] })
    }

    @Test
    fun `putImageData and getImageData move raw pixels`() {
        val r = Raster2D(4, 4)
        val image = r.createImageData(2, 2)
        for (i in 0 until 4) {
            image[i * 4] = 10.0 * (i + 1)
            image[i * 4 + 3] = 255.0
        }
        r.putImageData(image, 1, 1)
        assertEquals(listOf(30, 0, 0, 255), rgba(r, 1, 2))
        val back = r.getImageData(0, 0, 3, 3)
        assertEquals(10, back[(1 * 3 + 1) * 4])
        assertEquals(0, back[0 * 4 + 3])
        // regions outside the raster are transparent
        assertEquals(0, r.getImageData(3, 3, 2, 2)[(1 * 2 + 1) * 4 + 3])
    }

    @Test
    fun `a canvas texture shares the pixels of the raster`() {
        val r = Raster2D(4, 4)
        val texture = canvasTexture(r)
        assertSame(r, texture.image)
        assertEquals(Wrap.REPEAT, texture.wrapS)
        assertEquals(TextureColorSpace.SRGB, texture.colorSpace)
        assertEquals(1, texture.version)
        r.fillStyle = "#ffffff"
        r.fillRect(0.0, 0.0, 4.0, 4.0)
        texture.needsUpdate = true
        assertEquals(2, texture.version)
        assertEquals(255, texture.image.pixels[3].toInt() and 0xFF)
        val data = canvasTexture(r, repeat = false, srgb = false, anisotropy = 4)
        assertEquals(Wrap.CLAMP_TO_EDGE, data.wrapS)
        assertEquals(TextureColorSpace.NONE, data.colorSpace)
        assertEquals(4, data.anisotropy)
    }

    @Test
    fun `css colours`() {
        assertColor(107, 161, 69, 0.5, "hsla(95, 40%, 45%, 0.5)")
        assertColor(0, 128, 255, 1.0, "hsl(210, 100%, 50%)")
        assertColor(128, 128, 128, 1.0, "hsl(0, 0%, 50%)")
        assertColor(82, 20, 82, 0.3, "hsla(300, 60%, 20%, 0.3)")
        assertColor(10, 200, 30, 1.0, "rgb(10, 200, 30)")
        assertColor(255, 170, 0, 1.0, "#fa0")
        assertColor(17, 17, 17, 1.0, "#111")
        assertColor(29, 59, 143, 1.0, "#1d3b8f")
        assertColor(220, 226, 235, 0.0, "rgba(220,226,235,0)")
        assertColor(250, 250, 252, 0.55, "rgba(250,250,252,0.55)")
        assertColor(255, 255, 255, 1.0, "white")
        assertNotNull(CssColor.parse("#11223344"))
        assertEquals(null, CssColor.parse("not a colour"))
    }

    private fun assertColor(
        r: Int,
        g: Int,
        b: Int,
        a: Double,
        css: String,
    ) {
        val c = assertNotNull(CssColor.parse(css), css)
        assertEquals(r, kotlin.math.round(c.r).toInt(), "$css red")
        assertEquals(g, kotlin.math.round(c.g).toInt(), "$css green")
        assertEquals(b, kotlin.math.round(c.b).toInt(), "$css blue")
        assertNear(a, c.a, 1e-9, "$css alpha")
    }

    @Test
    fun `the random numbers and tile noise match the web app`() {
        val rng = createRng(7)
        assertNear(0.011704753153026104, rng())
        assertNear(0.06195825757458806, rng())
        assertNear(0.97690763277933, rng())
        assertNear(0.6990287057124078, rng())
        val zero = createRng(0)
        assertNear(0.26642920868471265, zero())
        assertNear(0.0003297457005828619, zero())
        val noise = TileNoise(4, 3)
        assertNear(0.7202267646789551, noise(0.0, 0.0))
        assertNear(0.5102904018249512, noise(0.3, 0.7))
        assertNear(0.2624168942217827, noise(0.99, 0.5))
        assertNear(0.47672805190086365, noise(1.25, -0.25))
        val fbm = TileFbm(4, 4, 21)
        assertNear(0.5166444073120753, fbm(0.0, 0.0))
        assertNear(0.5008236776116372, fbm(0.3, 0.7))
        assertNear(0.45450242140818997, fbm(0.55, 0.12))
    }

    @Test
    fun `a tile of thousands of round blade strokes with wrapped copies draws`() {
        // the web app's grass texture: short strokes with hsla colours, copied across the tile edges
        val size = 256
        val r = Raster2D(size, size)
        r.fillStyle = "#4a702e"
        r.fillRect(0.0, 0.0, size.toDouble(), size.toDouble())
        r.lineCap = "round"
        val rng = createRng(21)
        for (i in 0 until size * 14) {
            val x = rng() * size
            val y = rng() * size
            val len = 3 + rng() * 7
            val ang = -PI / 2 + (rng() - 0.5) * 1.6
            val hue = 80 + rng() * 30
            val sat = 35 + rng() * 25
            val light = 30 + rng() * 30
            r.strokeStyle = "hsla($hue, $sat%, $light%, ${0.35 + rng() * 0.4})"
            r.lineWidth = 0.6 + rng() * 1.1
            for (ox in intArrayOf(-size, 0, size)) {
                for (oy in intArrayOf(-size, 0, size)) {
                    if (x + ox !in -12.0..size + 12.0 || y + oy !in -12.0..size + 12.0) continue
                    r.beginPath()
                    r.moveTo(x + ox, y + oy)
                    r.lineTo(x + ox + kotlin.math.cos(ang) * len, y + oy + kotlin.math.sin(ang) * len)
                    r.stroke()
                }
            }
        }
        assertEquals(255, alpha(r, 100, 100))
        assertTrue(
            r.pixels.indices.any {
                it % 4 == 1 && (r.pixels[it].toInt() and 0xFF) > 0x80
            },
            "blades lighten the green channel",
        )
    }
}
