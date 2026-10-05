package app.zoeshorsefarm.view3d

import app.zoeshorsefarm.scene.math.MathUtils
import app.zoeshorsefarm.scene.texture.BlockTextRasterizer
import app.zoeshorsefarm.scene.texture.ImageData
import app.zoeshorsefarm.scene.texture.Raster2D
import app.zoeshorsefarm.scene.texture.TextRasterizer
import app.zoeshorsefarm.scene.texture.Texture
import app.zoeshorsefarm.scene.texture.TileFbm
import app.zoeshorsefarm.scene.texture.canvasTexture
import app.zoeshorsefarm.scene.texture.createRng
import app.zoeshorsefarm.shared.clamp
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.ceil
import kotlin.math.cos
import kotlin.math.floor
import kotlin.math.hypot
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sin
import kotlin.math.sqrt

// Procedural surfaces (rule 2: nothing is loaded). The canvas of the web app is a `Raster2D`
// here; the random numbers and pixel formulas are those of textures.js, so the textures match.
// The geometry helpers of textures.js are in GeometryBuilder.kt, `createRng` and the tileable noise
// in the scene module (`app.zoeshorsefarm.scene.texture`).

private const val TWO_PI = 2 * PI

/** A canvas (web app: `createCanvas`). Text goes through [textRasterizer]. */
fun createCanvas(
    width: Int,
    height: Int,
    textRasterizer: TextRasterizer = BlockTextRasterizer,
): Raster2D = Raster2D(width, height, textRasterizer)

// --- Arena sand ----------------------------------------------------------------------------

/** Arena sand: the colour map and, if wanted, the normal map (tileable, one tile is about 4 m). */
class SandTextures(
    val map: Texture,
    val normalMap: Texture?,
)

/** The height field (and moisture) of the sand, as Float32 like the web app so that the numbers match. */
private class SandField(
    val size: Int,
) {
    val height = FloatArray(size * size)
    val moist = FloatArray(size * size)

    /** Harrow lines, grain and moisture patches. */
    fun fillGround(
        rng: () -> Double,
        seed: Int,
    ) {
        val fbmLow = TileFbm(4, 4, seed)
        val fbmMid = TileFbm(16, 3, seed + 9)
        for (y in 0 until size) {
            for (x in 0 until size) {
                val u = x.toDouble() / size
                val v = y.toDouble() / size
                val i = y * size + x
                // harrow lines: fine parallel grooves, slightly wavy
                val drag = sin((v + fbmLow(u, v) * 0.04) * PI * 2 * 48) * 0.5 + 0.5
                height[i] = (fbmLow(u, v) * 0.6 + fbmMid(u, v) * 0.35 + drag * 0.08 + rng() * 0.12).toFloat()
                moist[i] = fbmLow(u + 0.37, v + 0.11).toFloat()
            }
        }
    }

    /** Hoof prints: oval dents with a rim, often in short trails. */
    fun stampPrints(rng: () -> Double) {
        // pixels per meter
        val px = size / 4.0
        val prints = jsRound(size * 0.035)
        repeat(prints) {
            val cx = rng() * size
            val cy = rng() * size
            val ang = rng() * TWO_PI
            val steps = 1 + floor(rng() * 3).toInt()
            for (s in 0 until steps) {
                val sx = cx + cos(ang) * s * 0.9 * px
                val sy = cy + sin(ang) * s * 0.9 * px
                val r = (0.045 + rng() * 0.015) * px
                stampHoof(sx, sy, ang, r, 0.12 + rng() * 0.18)
            }
        }
    }

    private fun wrap(v: Int): Int = ((v % size) + size) % size

    private fun stampHoof(
        cx: Double,
        cy: Double,
        ang: Double,
        r: Double,
        depth: Double,
    ) {
        val c = cos(ang)
        val s = sin(ang)
        val ext = ceil(r * 1.8).toInt()
        for (dy in -ext..ext) {
            for (dx in -ext..ext) {
                // local coordinates: a along the stride, b across
                val a = (dx * c + dy * s) / (r * 1.1)
                val b = (-dx * s + dy * c) / r
                val d = hypot(a, b)
                if (d <= HOOF_REACH) {
                    val i = wrap(jsRound(cy + dy)) * size + wrap(jsRound(cx + dx))
                    // dent with raised rim, deeper at the toe
                    val bowl = if (d < 1) -(1 - d * d) * (0.8 + 0.4 * max(0.0, a)) else 0.0
                    val rim = if (d >= 0.9 && d < HOOF_REACH) sin((d - 0.9) / 0.7 * PI) * 0.35 else 0.0
                    height[i] = (height[i] + (bowl + rim) * depth).toFloat()
                    if (d < 1) moist[i] = (moist[i] + (1 - d) * 0.25 * depth).toFloat()
                }
            }
        }
    }

    private companion object {
        const val HOOF_REACH = 1.6
    }
}

private val SAND_LIGHT = intArrayOf(208, 186, 148)
private val SAND_DARK = intArrayOf(150, 122, 88)

/** Colours the sand from the height field and sprinkles dark and light grains. */
private fun paintSand(
    raster: Raster2D,
    field: SandField,
    rng: () -> Double,
) {
    val size = field.size
    val img = raster.createImageData(size, size)
    for (i in 0 until size * size) {
        val h = field.height[i].toDouble()
        val m = field.moist[i].toDouble()
        var t = clamp(0.3 + (0.6 - h) * 0.35 + (m - 0.5) * 0.45, 0.0, 1.0)
        val speck = rng()
        if (speck > 0.985) {
            // dark grains
            t = min(1.0, t + 0.35)
        } else if (speck < 0.02) {
            // light grains
            t = max(0.0, t - 0.3)
        }
        val o = i * 4
        for (k in 0 until 3) img[o + k] = SAND_LIGHT[k] + (SAND_DARK[k] - SAND_LIGHT[k]) * t
        img[o + 3] = 255.0
    }
    raster.putImageData(img, 0, 0)
}

/** Normal map from a tileable height field. */
private fun normalMapFromHeight(
    height: FloatArray,
    size: Int,
    strength: Double,
): Raster2D {
    val raster = createCanvas(size, size)
    val img = raster.createImageData(size, size)

    fun at(
        x: Int,
        y: Int,
    ) = height[((y + size) % size) * size + ((x + size) % size)].toDouble()
    for (y in 0 until size) {
        for (x in 0 until size) {
            val dx = (at(x + 1, y) - at(x - 1, y)) * strength
            val dy = (at(x, y + 1) - at(x, y - 1)) * strength
            val len = sqrt(dx * dx + dy * dy + 1)
            val o = (y * size + x) * 4
            img[o] = (-dx / len * 0.5 + 0.5) * 255
            img[o + 1] = (dy / len * 0.5 + 0.5) * 255
            img[o + 2] = (1 / len * 0.5 + 0.5) * 255
            img[o + 3] = 255.0
        }
    }
    raster.putImageData(img, 0, 0)
    return raster
}

/**
 * Arena sand: grainy, slightly wavy, with hoof prints and hints of harrow lines. Returns the
 * colour map and (with [normal]) the normal map, both tileable, one tile is about 4 m.
 */
fun createSandTextures(
    size: Int = 512,
    seed: Int = 7,
    normal: Boolean = true,
): SandTextures {
    val rng = createRng(seed)
    val field = SandField(size)
    field.fillGround(rng, seed)
    field.stampPrints(rng)
    val raster = createCanvas(size, size)
    paintSand(raster, field, rng)
    val map = canvasTexture(raster)
    val normalMap = if (normal) canvasTexture(normalMapFromHeight(field.height, size, 2.2), srgb = false) else null
    return SandTextures(map, normalMap)
}

// --- Meadow --------------------------------------------------------------------------------

private val GRASS_A = intArrayOf(74, 112, 46)
private val GRASS_B = intArrayOf(112, 138, 62)
private val GRASS_DRY = intArrayOf(150, 146, 88)

/** The colour of the meadow ground: green with dry patches and grain (the blades come on top). */
internal fun paintGrassGround(
    raster: Raster2D,
    rng: () -> Double,
    seed: Int,
) {
    val size = raster.width
    val fbm = TileFbm(4, 4, seed)
    val img = raster.createImageData(size, size)
    for (y in 0 until size) {
        for (x in 0 until size) {
            val u = x.toDouble() / size
            val v = y.toDouble() / size
            val n = fbm(u, v)
            val d = MathUtils.smoothstep(fbm(u + 0.5, v + 0.25), 0.62, 0.78) * 0.6
            val g = rng() * 0.25
            val t = clamp(n + g - 0.15, 0.0, 1.0)
            val o = (y * size + x) * 4
            for (k in 0 until 3) {
                val base = GRASS_A[k] + (GRASS_B[k] - GRASS_A[k]) * t
                img[o + k] = base + (GRASS_DRY[k] - base) * d
            }
            img[o + 3] = 255.0
        }
    }
    raster.putImageData(img, 0, 0)
}

/** A blade is drawn up to this far (px) outside the tile, so that its copies wrap around the edges. */
private const val BLADE_MARGIN = 12

private fun inBladeRange(
    v: Double,
    size: Int,
): Boolean = v >= -BLADE_MARGIN && v <= size + BLADE_MARGIN

/** Strokes one blade and its copies one tile away that reach into the tile (tileable). */
private fun strokeBlade(
    ctx: Raster2D,
    x: Double,
    y: Double,
    ang: Double,
    len: Double,
) {
    val size = ctx.width
    for (ox in intArrayOf(-size, 0, size)) {
        for (oy in intArrayOf(-size, 0, size)) {
            val bx = x + ox
            val by = y + oy
            if (inBladeRange(bx, size) && inBladeRange(by, size)) {
                ctx.beginPath()
                ctx.moveTo(bx, by)
                ctx.lineTo(bx + cos(ang) * len, by + sin(ang) * len)
                ctx.stroke()
            }
        }
    }
}

/** Blades as short strokes, wrapped at the edges (tileable). */
private fun paintGrassBlades(
    ctx: Raster2D,
    rng: () -> Double,
) {
    val size = ctx.width
    val scale = size / 512.0
    ctx.lineCap = "round"
    repeat(size * 14) {
        val x = rng() * size
        val y = rng() * size
        val len = (3 + rng() * 7) * scale
        val ang = -PI / 2 + (rng() - 0.5) * 1.6
        val l = 30 + rng() * 30
        val hue = 80 + rng() * 30
        val saturation = 35 + rng() * 25
        ctx.strokeStyle = "hsla($hue, $saturation%, $l%, ${0.35 + rng() * 0.4})"
        ctx.lineWidth = (0.6 + rng() * 1.1) * scale
        strokeBlade(ctx, x, y, ang, len)
    }
}

/** Meadow: green with blades and dry patches (tileable, one tile is about 6 m). */
fun createGrassTexture(
    size: Int = 512,
    seed: Int = 21,
): Texture {
    val rng = createRng(seed)
    val raster = createCanvas(size, size)
    paintGrassGround(raster, rng, seed)
    paintGrassBlades(raster, rng)
    return canvasTexture(raster)
}

// --- Clouds --------------------------------------------------------------------------------

/** One soft puff of a cloud: a radial gradient in a square. */
private fun drawPuff(
    ctx: Raster2D,
    rng: () -> Double,
    ox: Double,
    oy: Double,
    cell: Double,
) {
    val t = rng()
    val x = ox + cell * (0.18 + t * 0.64)
    val hump = sin(t * PI)
    val y = oy + cell * (0.62 - hump * 0.18 * rng() - rng() * 0.08)
    val r = cell * (0.08 + hump * 0.12 + rng() * 0.06)
    val g = ctx.createRadialGradient(x, y - r * 0.3, r * 0.1, x, y, r)
    val shade = 236 + floor(rng() * 19).toInt()
    g.addColorStop(0.0, "rgba($shade,$shade,${min(255, shade + 2)},0.55)")
    g.addColorStop(0.6, "rgba(${shade - 12},${shade - 10},${shade - 6},0.25)")
    g.addColorStop(1.0, "rgba(220,226,235,0)")
    ctx.fillStyle = g
    ctx.fillRect(x - r, y - r, r * 2, r * 2)
}

/** Soft clouds: atlas with 4 variants (2x2), alpha in the image. */
fun createCloudAtlas(
    size: Int = 512,
    seed: Int = 5,
): Texture {
    val rng = createRng(seed)
    val ctx = createCanvas(size, size)
    val cell = size / 2.0
    for (k in 0 until 4) {
        val ox = (k % 2) * cell
        val oy = (k / 2) * cell
        val puffs = 14 + floor(rng() * 10).toInt()
        repeat(puffs) { drawPuff(ctx, rng, ox, oy, cell) }
        // slightly grey underside
        val shadow = ctx.createLinearGradient(0.0, oy + cell * 0.5, 0.0, oy + cell * 0.75)
        shadow.addColorStop(0.0, "rgba(150,160,175,0)")
        shadow.addColorStop(1.0, "rgba(150,160,175,0.18)")
        ctx.globalCompositeOperation = "source-atop"
        ctx.fillStyle = shadow
        ctx.fillRect(ox, oy, cell, cell)
        ctx.globalCompositeOperation = "source-over"
    }
    return canvasTexture(ctx, repeat = false)
}

// --- Ground markings -------------------------------------------------------------------------

/** Soft rectangle mask (alpha) for ground markings. */
fun createSoftRectTexture(
    size: Int = 128,
    edge: Double = 0.18,
): Texture {
    val raster = createCanvas(size, size)
    val img = raster.createImageData(size, size)
    for (y in 0 until size) {
        for (x in 0 until size) {
            val u = min(x, size - 1 - x).toDouble() / size
            val v = min(y, size - 1 - y).toDouble() / size
            val a = MathUtils.smoothstep(min(u, v), 0.0, edge)
            // faint cross stripes so the zone reads as an area
            val stripe = 0.85 + 0.15 * sin(y.toDouble() / size * PI * 10)
            val o = (y * size + x) * 4
            img[o] = 255.0
            img[o + 1] = 255.0
            img[o + 2] = 255.0
            img[o + 3] = a * stripe * 255
        }
    }
    raster.putImageData(img, 0, 0)
    return canvasTexture(raster, repeat = false, srgb = false)
}

// --- Labels ----------------------------------------------------------------------------------

/** A cell of a label atlas: [draw] paints into the cell (origin at its top-left corner, size w by h). */
class LabelItem(
    val key: String,
    val draw: (ctx: Raster2D, w: Int, h: Int) -> Unit,
)

/** The texture coordinates of a cell (v up, like three.js). */
data class UvRect(
    val u0: Double,
    val v0: Double,
    val u1: Double,
    val v1: Double,
)

/** A text atlas: its [texture], the [rects] of the cells by key and the [raster] it was drawn into. */
class LabelAtlas(
    val texture: Texture,
    val rects: Map<String, UvRect>,
    val raster: Raster2D,
)

// widest atlas (px) before the cells wrap into a new row
private const val MAX_ATLAS_WIDTH = 2048

/**
 * Text atlas (system font) for number boards and signs. Text is drawn through [textRasterizer],
 * which the platform provides.
 */
fun createLabelAtlas(
    items: List<LabelItem>,
    textRasterizer: TextRasterizer,
    cellW: Int = 128,
    cellH: Int = 128,
): LabelAtlas {
    val cols = max(1, min(items.size, MAX_ATLAS_WIDTH / cellW))
    val rows = max(1, (items.size + cols - 1) / cols)
    val width = MathUtils.ceilPowerOfTwo(cols * cellW)
    val height = MathUtils.ceilPowerOfTwo(rows * cellH)
    val ctx = createCanvas(width, height, textRasterizer)
    val rects = LinkedHashMap<String, UvRect>()
    items.forEachIndexed { i, item ->
        val x = (i % cols) * cellW
        val y = (i / cols) * cellH
        ctx.save()
        ctx.translate(x.toDouble(), y.toDouble())
        ctx.beginPath()
        ctx.rect(0.0, 0.0, cellW.toDouble(), cellH.toDouble())
        ctx.clip()
        item.draw(ctx, cellW, cellH)
        ctx.restore()
        rects[item.key] =
            UvRect(
                u0 = (x + 1.0) / width,
                u1 = (x + cellW - 1.0) / width,
                v0 = 1 - (y + cellH - 1.0) / height,
                v1 = 1 - (y + 1.0) / height,
            )
    }
    return LabelAtlas(canvasTexture(ctx, repeat = false), rects, ctx)
}

const val SYSTEM_FONT = "system-ui, -apple-system, \"Segoe UI\", Roboto, \"Helvetica Neue\", Arial, sans-serif"

/** A canvas font string such as `800 90px system-ui, ...` (whole sizes without a decimal point). */
fun fontString(
    weight: Int,
    sizePx: Double,
    family: String = SYSTEM_FONT,
): String {
    val size = if (sizePx == floor(sizePx) && abs(sizePx) < 1e9) sizePx.toLong().toString() else sizePx.toString()
    return "$weight ${size}px $family"
}

// the smallest font size fitText goes down to, and its step (px)
private const val MIN_FONT_SIZE = 8.0
private const val FONT_STEP = 2.0

/**
 * Shrinks the font until the text fits [maxWidth]. Sets `ctx.font` to the result and returns its
 * size in px.
 */
fun fitText(
    ctx: Raster2D,
    text: String,
    maxWidth: Double,
    weight: Int,
    sizePx: Double,
): Double {
    var size = sizePx
    ctx.font = fontString(weight, size)
    while (size > MIN_FONT_SIZE && ctx.measureText(text) > maxWidth) {
        size -= FONT_STEP
        ctx.font = fontString(weight, size)
    }
    return size
}
