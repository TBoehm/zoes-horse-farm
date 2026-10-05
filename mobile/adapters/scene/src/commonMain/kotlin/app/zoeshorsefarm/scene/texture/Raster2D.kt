package app.zoeshorsefarm.scene.texture

import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.acos
import kotlin.math.atan2
import kotlin.math.ceil
import kotlin.math.cos
import kotlin.math.floor
import kotlin.math.max
import kotlin.math.min
import kotlin.math.round
import kotlin.math.sin
import kotlin.math.sqrt
import kotlin.math.tan

/** RGBA8 pixels with canvas `ImageData` semantics: stores clamp to 0..255 and round to the nearest integer. */
class ImageData(
    val width: Int,
    val height: Int,
    val data: ByteArray = ByteArray(width * height * 4),
) {
    init {
        require(data.size == width * height * 4) { "data must hold width * height RGBA texels" }
    }

    /** Stores `value` like a `Uint8ClampedArray` (NaN gives 0, halves round to even). */
    operator fun set(
        index: Int,
        value: Double,
    ) {
        data[index] = clampToByte(value)
    }

    /** The channel at `index` as 0..255. */
    operator fun get(index: Int): Int = data[index].toInt() and 0xFF

    companion object {
        fun clampToByte(value: Double): Byte =
            if (value.isNaN()) 0 else round(value.coerceIn(0.0, 255.0)).toInt().toByte()
    }
}

/** Colour gradient for [Raster2D.fillStyle] / `strokeStyle` (canvas `CanvasGradient`). */
class CanvasGradient private constructor(
    private val radial: Boolean,
    private val x0: Double,
    private val y0: Double,
    private val r0: Double,
    private val x1: Double,
    private val y1: Double,
    private val r1: Double,
) {
    private class Stop(
        val offset: Double,
        val color: Rgba,
    )

    private val stops = ArrayList<Stop>()

    /** Adds a colour stop (`offset` 0..1, CSS colour); stops with the same offset keep their order. */
    fun addColorStop(
        offset: Double,
        color: String,
    ) {
        require(offset in 0.0..1.0) { "colour stop offset must be in 0..1" }
        val stop = Stop(offset, CssColor.require(color))
        var i = stops.size
        while (i > 0 && stops[i - 1].offset > offset) i--
        stops.add(i, stop)
    }

    /** Writes the colour at the device point into `out` as r, g, b (0..255) and alpha (0..1). */
    internal fun colorAt(
        px: Double,
        py: Double,
        out: DoubleArray,
    ) {
        val t = if (radial) radialT(px, py) else linearT(px, py)
        if (t.isNaN() || stops.isEmpty()) put(Rgba.TRANSPARENT, out) else interpolate(t, out)
    }

    private fun interpolate(
        t: Double,
        out: DoubleArray,
    ) {
        val first = stops[0]
        val last = stops[stops.size - 1]
        if (t <= first.offset) {
            put(first.color, out)
        } else if (t >= last.offset) {
            put(last.color, out)
        } else {
            val index = stops.indexOfFirst { t <= it.offset }
            val a = stops[index - 1]
            val b = stops[index]
            val span = b.offset - a.offset
            val k = if (span == 0.0) 1.0 else (t - a.offset) / span
            out[0] = a.color.r + (b.color.r - a.color.r) * k
            out[1] = a.color.g + (b.color.g - a.color.g) * k
            out[2] = a.color.b + (b.color.b - a.color.b) * k
            out[3] = a.color.a + (b.color.a - a.color.a) * k
        }
    }

    private fun put(
        c: Rgba,
        out: DoubleArray,
    ) {
        out[0] = c.r
        out[1] = c.g
        out[2] = c.b
        out[3] = c.a
    }

    private fun linearT(
        px: Double,
        py: Double,
    ): Double {
        val dx = x1 - x0
        val dy = y1 - y0
        val len2 = dx * dx + dy * dy
        if (len2 == 0.0) return Double.NaN
        return ((px - x0) * dx + (py - y0) * dy) / len2
    }

    // largest t with r(t) >= 0 such that the point lies on the circle c(t), r(t) (canvas two-circle gradient)
    private fun radialT(
        px: Double,
        py: Double,
    ): Double {
        val cdx = x1 - x0
        val cdy = y1 - y0
        val dr = r1 - r0
        val pdx = px - x0
        val pdy = py - y0
        val a = cdx * cdx + cdy * cdy - dr * dr
        val b = pdx * cdx + pdy * cdy + r0 * dr
        val c = pdx * pdx + pdy * pdy - r0 * r0
        val disc = b * b - a * c
        return when {
            a == 0.0 -> {
                if (b == 0.0) Double.NaN else pickRadius(c / (2 * b), Double.NaN, dr)
            }

            disc < 0 -> {
                Double.NaN
            }

            else -> {
                val root = sqrt(disc)
                val tA = (b + root) / a
                val tB = (b - root) / a
                pickRadius(max(tA, tB), min(tA, tB), dr)
            }
        }
    }

    // the larger root whose circle has a non-negative radius (NaN compares false)
    private fun pickRadius(
        high: Double,
        low: Double,
        dr: Double,
    ): Double =
        if (r0 + high * dr >= 0) {
            high
        } else if (r0 + low * dr >= 0) {
            low
        } else {
            Double.NaN
        }

    companion object {
        internal fun linear(
            x0: Double,
            y0: Double,
            x1: Double,
            y1: Double,
        ) = CanvasGradient(false, x0, y0, 0.0, x1, y1, 0.0)

        internal fun radial(
            x0: Double,
            y0: Double,
            r0: Double,
            x1: Double,
            y1: Double,
            r1: Double,
        ) = CanvasGradient(true, x0, y0, r0, x1, y1, r1)
    }
}

/** Growable double list for path points. */
private class PointList {
    var data = DoubleArray(16)
    var size = 0

    fun add(
        x: Double,
        y: Double,
    ) {
        if (size + 2 > data.size) data = data.copyOf(data.size * 2)
        data[size++] = x
        data[size++] = y
    }

    val lastX: Double get() = data[size - 2]
    val lastY: Double get() = data[size - 1]
}

private class SubPath(
    val points: PointList = PointList(),
    var closed: Boolean = false,
)

private class DrawState(
    val fillStyle: Any,
    val strokeStyle: Any,
    val lineWidth: Double,
    val lineCap: String,
    val globalAlpha: Double,
    val globalCompositeOperation: String,
    val font: String,
    val textAlign: String,
    val textBaseline: String,
    val tx: Double,
    val ty: Double,
    val sx: Double,
    val sy: Double,
    val clipX0: Int,
    val clipY0: Int,
    val clipX1: Int,
    val clipY1: Int,
    val clipMask: ClipMask?,
)

private class ClipMask(
    val x0: Int,
    val y0: Int,
    val width: Int,
    val height: Int,
    val coverage: FloatArray,
) {
    fun at(
        x: Int,
        y: Int,
    ): Float {
        val lx = x - x0
        val ly = y - y0
        return if (lx !in 0 until width || ly !in 0 until height) 0f else coverage[ly * width + lx]
    }
}

/**
 * A small software replacement for a 2D canvas (`createCanvas` + `getContext('2d')`) that covers what
 * the procedural textures and labels of the game draw: rectangles, paths (lines, arcs, quadratic and
 * cubic curves), fills and strokes with colours or gradients, global alpha, `source-over` and
 * `source-atop` compositing, translate/scale/save/restore/clip, text through a [TextRasterizer],
 * `drawImage` of another raster and `ImageData`. It is both the canvas and its context.
 *
 * Edges are anti-aliased with exact area coverage. Pixels are RGBA8 with straight (not premultiplied)
 * alpha, row 0 at the top. Differences from a browser canvas: gradients ignore the current
 * transform, `lineJoin` is always round, overlapping strokes of one `stroke()` call merge by
 * clamping their coverage.
 */
@Suppress("TooManyFunctions", "LargeClass") // the 2D canvas API is wide by nature
class Raster2D(
    override val width: Int,
    override val height: Int,
    private val textRasterizer: TextRasterizer = BlockTextRasterizer,
) : ImageSource {
    override val pixels: ByteArray = ByteArray(width * height * 4)

    /** A colour string or a [CanvasGradient]. */
    var fillStyle: Any = "#000000"

    /** A colour string or a [CanvasGradient]. */
    var strokeStyle: Any = "#000000"
    var lineWidth: Double = 1.0

    /** `"butt"`, `"round"` or `"square"`. */
    var lineCap: String = "butt"
    var globalAlpha: Double = 1.0

    /** `"source-over"`, `"source-atop"`, `"copy"` or `"destination-out"`. */
    var globalCompositeOperation: String = "source-over"
    var font: String = "10px sans-serif"

    /** `"start"`/`"left"`, `"center"`, `"end"`/`"right"`. */
    var textAlign: String = "start"

    /** `"alphabetic"`, `"middle"`, `"top"`, `"hanging"`, `"bottom"`. */
    var textBaseline: String = "alphabetic"

    private var tx = 0.0
    private var ty = 0.0
    private var sx = 1.0
    private var sy = 1.0
    private var clipX0 = 0
    private var clipY0 = 0
    private var clipX1 = width
    private var clipY1 = height
    private var clipMask: ClipMask? = null
    private val stack = ArrayList<DrawState>()
    private val subpaths = ArrayList<SubPath>()
    private var curX = 0.0
    private var curY = 0.0
    private val scratch = DoubleArray(4)

    // ---- state ----------------------------------------------------------------------------------

    fun save() {
        stack.add(
            DrawState(
                fillStyle,
                strokeStyle,
                lineWidth,
                lineCap,
                globalAlpha,
                globalCompositeOperation,
                font,
                textAlign,
                textBaseline,
                tx,
                ty,
                sx,
                sy,
                clipX0,
                clipY0,
                clipX1,
                clipY1,
                clipMask,
            ),
        )
    }

    fun restore() {
        val s = stack.removeLastOrNull() ?: return
        fillStyle = s.fillStyle
        strokeStyle = s.strokeStyle
        lineWidth = s.lineWidth
        lineCap = s.lineCap
        globalAlpha = s.globalAlpha
        globalCompositeOperation = s.globalCompositeOperation
        font = s.font
        textAlign = s.textAlign
        textBaseline = s.textBaseline
        tx = s.tx
        ty = s.ty
        sx = s.sx
        sy = s.sy
        clipX0 = s.clipX0
        clipY0 = s.clipY0
        clipX1 = s.clipX1
        clipY1 = s.clipY1
        clipMask = s.clipMask
    }

    fun translate(
        x: Double,
        y: Double,
    ) {
        tx += x * sx
        ty += y * sy
    }

    fun scale(
        x: Double,
        y: Double,
    ) {
        sx *= x
        sy *= y
    }

    fun createLinearGradient(
        x0: Double,
        y0: Double,
        x1: Double,
        y1: Double,
    ): CanvasGradient = CanvasGradient.linear(x0 * sx + tx, y0 * sy + ty, x1 * sx + tx, y1 * sy + ty)

    fun createRadialGradient(
        x0: Double,
        y0: Double,
        r0: Double,
        x1: Double,
        y1: Double,
        r1: Double,
    ): CanvasGradient = CanvasGradient.radial(x0 * sx + tx, y0 * sy + ty, r0 * sx, x1 * sx + tx, y1 * sy + ty, r1 * sx)

    // ---- path -----------------------------------------------------------------------------------

    fun beginPath() {
        subpaths.clear()
    }

    fun moveTo(
        x: Double,
        y: Double,
    ) {
        val sub = SubPath()
        sub.points.add(x * sx + tx, y * sy + ty)
        subpaths.add(sub)
        curX = x
        curY = y
    }

    fun lineTo(
        x: Double,
        y: Double,
    ) {
        if (subpaths.isEmpty() || subpaths.last().closed) {
            moveTo(x, y)
            return
        }
        subpaths.last().points.add(x * sx + tx, y * sy + ty)
        curX = x
        curY = y
    }

    fun closePath() {
        val sub = subpaths.lastOrNull() ?: return
        sub.closed = true
        // a new subpath starts at the start point of the closed one
        curX = (sub.points.data[0] - tx) / sx
        curY = (sub.points.data[1] - ty) / sy
    }

    fun rect(
        x: Double,
        y: Double,
        w: Double,
        h: Double,
    ) {
        moveTo(x, y)
        lineTo(x + w, y)
        lineTo(x + w, y + h)
        lineTo(x, y + h)
        closePath()
        moveTo(x, y)
    }

    fun quadraticCurveTo(
        cpx: Double,
        cpy: Double,
        x: Double,
        y: Double,
    ) {
        if (subpaths.isEmpty()) moveTo(cpx, cpy)
        val x0 = curX
        val y0 = curY
        val n = curveSteps(abs(cpx - x0) + abs(cpy - y0) + abs(x - cpx) + abs(y - cpy))
        for (i in 1..n) {
            val t = i.toDouble() / n
            val k = 1 - t
            lineTo(k * k * x0 + 2 * k * t * cpx + t * t * x, k * k * y0 + 2 * k * t * cpy + t * t * y)
        }
    }

    fun bezierCurveTo(
        c1x: Double,
        c1y: Double,
        c2x: Double,
        c2y: Double,
        x: Double,
        y: Double,
    ) {
        if (subpaths.isEmpty()) moveTo(c1x, c1y)
        val x0 = curX
        val y0 = curY
        val n =
            curveSteps(abs(c1x - x0) + abs(c1y - y0) + abs(c2x - c1x) + abs(c2y - c1y) + abs(x - c2x) + abs(y - c2y))
        for (i in 1..n) {
            val t = i.toDouble() / n
            val k = 1 - t
            lineTo(
                k * k * k * x0 + 3 * k * k * t * c1x + 3 * k * t * t * c2x + t * t * t * x,
                k * k * k * y0 + 3 * k * k * t * c1y + 3 * k * t * t * c2y + t * t * t * y,
            )
        }
    }

    /** Circular arc; like a canvas it connects to the current point with a line first. */
    fun arc(
        x: Double,
        y: Double,
        radius: Double,
        startAngle: Double,
        endAngle: Double,
        counterclockwise: Boolean = false,
    ) {
        require(radius >= 0) { "negative radius" }
        val twoPi = 2 * PI
        val sweep =
            if (!counterclockwise) {
                if (endAngle - startAngle >= twoPi) twoPi else ((endAngle - startAngle) % twoPi + twoPi) % twoPi
            } else {
                if (startAngle - endAngle >= twoPi) -twoPi else -(((startAngle - endAngle) % twoPi + twoPi) % twoPi)
            }
        val startX = x + radius * cos(startAngle)
        val startY = y + radius * sin(startAngle)
        lineTo(startX, startY)
        val step = if (radius * sx > 0.5) 2 * acos(1 - ARC_TOLERANCE / (radius * sx)) else PI / 4
        val n = max(4, ceil(abs(sweep) / step).toInt())
        for (i in 1..n) {
            val a = startAngle + sweep * i / n
            lineTo(x + radius * cos(a), y + radius * sin(a))
        }
    }

    /** Arc of `radius` tangent to the lines (current point -> x1,y1) and (x1,y1 -> x2,y2). */
    fun arcTo(
        x1: Double,
        y1: Double,
        x2: Double,
        y2: Double,
        radius: Double,
    ) {
        require(radius >= 0) { "negative radius" }
        if (subpaths.isEmpty()) moveTo(x1, y1)
        val x0 = curX
        val y0 = curY
        val v0x = x0 - x1
        val v0y = y0 - y1
        val v2x = x2 - x1
        val v2y = y2 - y1
        val len0 = sqrt(v0x * v0x + v0y * v0y)
        val len2 = sqrt(v2x * v2x + v2y * v2y)
        val cross = v0x * v2y - v0y * v2x
        val zeroLength = len0 == 0.0 || len2 == 0.0
        if (radius == 0.0 || zeroLength || abs(cross) < 1e-12) {
            lineTo(x1, y1)
            return
        }
        val u0x = v0x / len0
        val u0y = v0y / len0
        val u2x = v2x / len2
        val u2y = v2y / len2
        val angle = acos((u0x * u2x + u0y * u2y).coerceIn(-1.0, 1.0))
        val dist = radius / tan(angle / 2)
        val t0x = x1 + u0x * dist
        val t0y = y1 + u0y * dist
        val t1x = x1 + u2x * dist
        val t1y = y1 + u2y * dist
        var bx = u0x + u2x
        var by = u0y + u2y
        val bl = sqrt(bx * bx + by * by)
        bx /= bl
        by /= bl
        val centerDist = radius / sin(angle / 2)
        val cx = x1 + bx * centerDist
        val cy = y1 + by * centerDist
        lineTo(t0x, t0y)
        arc(cx, cy, radius, atan2(t0y - cy, t0x - cx), atan2(t1y - cy, t1x - cx), cross > 0)
    }

    private fun curveSteps(controlPolygonLength: Double): Int =
        (ceil(controlPolygonLength * max(sx, sy) / 2).toInt()).coerceIn(8, 96)

    // ---- painting -------------------------------------------------------------------------------

    fun fill() {
        val polys = subpaths.filter { it.points.size >= 6 }.map { it.points.data.copyOf(it.points.size) }
        rasterize(polys, fillStyle)
    }

    fun stroke() {
        val width = lineWidth * max(sx, sy)
        if (width <= 0) return
        val polys = ArrayList<DoubleArray>()
        for (sub in subpaths) strokePolygons(sub, width, polys)
        rasterize(polys, strokeStyle)
    }

    fun fillRect(
        x: Double,
        y: Double,
        w: Double,
        h: Double,
    ) {
        val dx0 = x * sx + tx
        val dy0 = y * sy + ty
        val dx1 = (x + w) * sx + tx
        val dy1 = (y + h) * sy + ty
        val x0 = min(dx0, dx1)
        val x1 = max(dx0, dx1)
        val y0 = min(dy0, dy1)
        val y1 = max(dy0, dy1)
        if (allWhole(x0, x1, y0, y1)) {
            solidRect(x0.toInt(), y0.toInt(), x1.toInt(), y1.toInt(), fillStyle)
        } else {
            val saved = ArrayList(subpaths)
            subpaths.clear()
            rect(x, y, w, h)
            fill()
            subpaths.clear()
            subpaths.addAll(saved)
        }
    }

    /** Makes the rectangle transparent (inside the clip). */
    fun clearRect(
        x: Double,
        y: Double,
        w: Double,
        h: Double,
    ) {
        val x0 = max(clipX0, floor(min(x * sx + tx, (x + w) * sx + tx)).toInt())
        val x1 = min(clipX1, ceil(max(x * sx + tx, (x + w) * sx + tx)).toInt())
        val y0 = max(clipY0, floor(min(y * sy + ty, (y + h) * sy + ty)).toInt())
        val y1 = min(clipY1, ceil(max(y * sy + ty, (y + h) * sy + ty)).toInt())
        val mask = clipMask
        for (py in y0 until y1) {
            for (px in x0 until x1) {
                val keep = if (mask == null) 0.0 else 1.0 - mask.at(px, py)
                val i = (py * width + px) * 4
                if (keep == 0.0) {
                    pixels[i] = 0
                    pixels[i + 1] = 0
                    pixels[i + 2] = 0
                    pixels[i + 3] = 0
                } else {
                    pixels[i + 3] = ImageData.clampToByte((pixels[i + 3].toInt() and 0xFF) * keep)
                }
            }
        }
    }

    /** Intersects the clip with the current path (an axis-aligned whole-pixel rectangle is kept cheap). */
    fun clip() {
        val polys = subpaths.filter { it.points.size >= 6 }.map { it.points.data.copyOf(it.points.size) }
        val rectangle = polys.size == 1 && isAxisRect(polys[0]) && clipToWholeRect(polys[0])
        if (polys.isEmpty()) {
            clipEverything()
        } else if (!rectangle) {
            clipToCoverage(polys)
        }
    }

    private fun clipEverything() {
        clipX1 = clipX0
        clipY1 = clipY0
    }

    /** Narrows the clip rectangle to a rectangle with whole-pixel edges; false if the edges are fractional. */
    private fun clipToWholeRect(p: DoubleArray): Boolean {
        val x0 = min(p[0], p[4])
        val x1 = max(p[0], p[4])
        val y0 = min(p[1], p[5])
        val y1 = max(p[1], p[5])
        val whole = allWhole(x0, x1, y0, y1)
        if (whole) {
            clipX0 = max(clipX0, x0.toInt())
            clipX1 = max(clipX0, min(clipX1, x1.toInt()))
            clipY0 = max(clipY0, y0.toInt())
            clipY1 = max(clipY0, min(clipY1, y1.toInt()))
        }
        return whole
    }

    private fun clipToCoverage(polys: List<DoubleArray>) {
        val region = coverageOf(polys, clipX0, clipY0, clipX1, clipY1)
        if (region == null) {
            clipEverything()
            return
        }
        val old = clipMask
        if (old != null) {
            for (y in 0 until region.height) {
                for (x in 0 until region.width) {
                    region.coverage[y * region.width + x] *= old.at(region.x0 + x, region.y0 + y)
                }
            }
        }
        clipMask = region
        clipX0 = region.x0
        clipY0 = region.y0
        clipX1 = region.x0 + region.width
        clipY1 = region.y0 + region.height
    }

    /** Width of `text` in the current font, in pixels. */
    fun measureText(text: String): Double = textRasterizer.measure(text, FontSpec.parse(font))

    fun fillText(
        text: String,
        x: Double,
        y: Double,
    ) {
        if (text.isEmpty()) return
        val spec = FontSpec.parse(font)
        val mask = textRasterizer.rasterize(text, spec)
        val metrics = textRasterizer.metrics(spec)
        val penX = alignedPenX(x, textRasterizer.measure(text, spec))
        val baseline = baselineY(y, metrics)
        val originX = round(penX * sx + tx).toInt() + mask.offsetX
        val originY = round(baseline * sy + ty).toInt() + mask.offsetY
        val paint = resolve(fillStyle)
        for (my in 0 until mask.height) {
            val py = originY + my
            if (py < clipY0 || py >= clipY1) continue
            for (mx in 0 until mask.width) {
                val px = originX + mx
                val coverage = (mask.alpha[my * mask.width + mx].toInt() and 0xFF) / 255.0
                if (px in clipX0 until clipX1 && coverage > 0) paintPixel(px, py, paint, coverage)
            }
        }
    }

    private fun alignedPenX(
        x: Double,
        advance: Double,
    ): Double =
        when (textAlign) {
            "center" -> x - advance / 2
            "right", "end" -> x - advance
            else -> x
        }

    private fun baselineY(
        y: Double,
        metrics: FontMetrics,
    ): Double =
        when (textBaseline) {
            "middle" -> y + (metrics.ascent - metrics.descent) / 2
            "top" -> y + metrics.ascent
            "hanging" -> y + metrics.ascent * 0.8
            "bottom" -> y - metrics.descent
            else -> y
        }

    /** Draws `source` with its top-left corner at (dx, dy), scaled to dw x dh (default: its own size). */
    fun drawImage(
        source: Raster2D,
        dx: Double,
        dy: Double,
        dw: Double = source.width.toDouble(),
        dh: Double = source.height.toDouble(),
    ) {
        val x0 = max(clipX0, floor(dx * sx + tx).toInt())
        val x1 = min(clipX1, ceil((dx + dw) * sx + tx).toInt())
        val y0 = max(clipY0, floor(dy * sy + ty).toInt())
        val y1 = min(clipY1, ceil((dy + dh) * sy + ty).toInt())
        val devX = dx * sx + tx
        val devY = dy * sy + ty
        val devW = dw * sx
        val devH = dh * sy
        val mask = clipMask
        for (py in y0 until y1) {
            for (px in x0 until x1) {
                val u = ((px + 0.5 - devX) / devW * source.width - 0.5).coerceIn(0.0, source.width - 1.0)
                val v = ((py + 0.5 - devY) / devH * source.height - 0.5).coerceIn(0.0, source.height - 1.0)
                source.sample(u, v, scratch)
                val cover = if (mask == null) 1.0 else mask.at(px, py).toDouble()
                if (cover > 0) blend(px, py, scratch[0], scratch[1], scratch[2], scratch[3] * globalAlpha * cover)
            }
        }
    }

    fun createImageData(
        width: Int,
        height: Int,
    ): ImageData = ImageData(width, height)

    /** Copies a region; parts outside the raster are transparent. */
    fun getImageData(
        x: Int,
        y: Int,
        w: Int,
        h: Int,
    ): ImageData {
        val out = ImageData(w, h)
        for (row in 0 until h) {
            for (col in 0 until w) {
                val sxp = x + col
                val syp = y + row
                if (sxp !in 0 until width || syp !in 0 until height) continue
                pixels.copyInto(out.data, (row * w + col) * 4, (syp * width + sxp) * 4, (syp * width + sxp) * 4 + 4)
            }
        }
        return out
    }

    /** Writes pixels as they are (no blending, transform or clip). */
    fun putImageData(
        image: ImageData,
        x: Int,
        y: Int,
    ) {
        for (row in 0 until image.height) {
            val py = y + row
            if (py < 0 || py >= height) continue
            for (col in 0 until image.width) {
                val px = x + col
                if (px < 0 || px >= width) continue
                image.data.copyInto(
                    pixels,
                    (py * width + px) * 4,
                    (row * image.width + col) * 4,
                    (row * image.width + col) * 4 + 4,
                )
            }
        }
    }

    /** Colour at pixel (x, y) as 0..255 r, g, b and alpha 0..1. */
    fun getPixel(
        x: Int,
        y: Int,
    ): Rgba {
        val i = (y * width + x) * 4
        return Rgba(
            (pixels[i].toInt() and 0xFF).toDouble(),
            (pixels[i + 1].toInt() and 0xFF).toDouble(),
            (pixels[i + 2].toInt() and 0xFF).toDouble(),
            (pixels[i + 3].toInt() and 0xFF) / 255.0,
        )
    }

    // ---- internals ------------------------------------------------------------------------------

    private fun sample(
        u: Double,
        v: Double,
        out: DoubleArray,
    ) {
        val x0 = floor(u).toInt()
        val y0 = floor(v).toInt()
        val x1 = min(x0 + 1, width - 1)
        val y1 = min(y0 + 1, height - 1)
        val fx = u - x0
        val fy = v - y0
        var r = 0.0
        var g = 0.0
        var b = 0.0
        var a = 0.0
        for (k in 0 until 4) {
            val px = if (k and 1 == 0) x0 else x1
            val py = if (k and 2 == 0) y0 else y1
            val w = (if (k and 1 == 0) 1 - fx else fx) * (if (k and 2 == 0) 1 - fy else fy)
            val i = (py * width + px) * 4
            val pa = (pixels[i + 3].toInt() and 0xFF) / 255.0
            // interpolate premultiplied so transparent texels do not bleed their colour
            r += (pixels[i].toInt() and 0xFF) * pa * w
            g += (pixels[i + 1].toInt() and 0xFF) * pa * w
            b += (pixels[i + 2].toInt() and 0xFF) * pa * w
            a += pa * w
        }
        out[0] = if (a > 0) r / a else 0.0
        out[1] = if (a > 0) g / a else 0.0
        out[2] = if (a > 0) b / a else 0.0
        out[3] = a
    }

    private fun isWhole(v: Double) = abs(v - round(v)) < 1e-9

    private fun allWhole(vararg values: Double): Boolean = values.all { isWhole(it) }

    private fun isAxisRect(p: DoubleArray): Boolean =
        p.size == 8 && p[1] == p[3] && p[2] == p[4] && p[5] == p[7] && p[6] == p[0]

    private sealed interface ResolvedPaint

    private class SolidPaint(
        val rgba: Rgba,
    ) : ResolvedPaint

    private class GradientPaint(
        val gradient: CanvasGradient,
    ) : ResolvedPaint

    private fun resolve(style: Any): ResolvedPaint =
        when (style) {
            is CanvasGradient -> GradientPaint(style)
            is String -> SolidPaint(CssColor.parse(style) ?: Rgba.BLACK)
            else -> error("fill and stroke style must be a CSS colour or a CanvasGradient")
        }

    private fun paintPixel(
        px: Int,
        py: Int,
        paint: ResolvedPaint,
        coverage: Double,
    ) {
        val mask = clipMask
        val cover = if (mask == null) coverage else coverage * mask.at(px, py)
        if (cover <= 0.0) return
        when (paint) {
            is SolidPaint -> {
                blend(px, py, paint.rgba.r, paint.rgba.g, paint.rgba.b, paint.rgba.a * globalAlpha * cover)
            }

            is GradientPaint -> {
                paint.gradient.colorAt(px + 0.5, py + 0.5, scratch)
                blend(px, py, scratch[0], scratch[1], scratch[2], scratch[3] * globalAlpha * cover)
            }
        }
    }

    private fun solidRect(
        x0: Int,
        y0: Int,
        x1: Int,
        y1: Int,
        style: Any,
    ) {
        val paint = resolve(style)
        for (py in max(y0, clipY0) until min(y1, clipY1)) {
            for (px in max(x0, clipX0) until min(x1, clipX1)) paintPixel(px, py, paint, 1.0)
        }
    }

    /** Blends one source colour (straight alpha) into the pixel with the current composite operation. */
    private fun blend(
        px: Int,
        py: Int,
        sr: Double,
        sg: Double,
        sb: Double,
        sa: Double,
    ) {
        val i = (py * width + px) * 4
        val dr = (pixels[i].toInt() and 0xFF).toDouble()
        val dg = (pixels[i + 1].toInt() and 0xFF).toDouble()
        val db = (pixels[i + 2].toInt() and 0xFF).toDouble()
        val da = (pixels[i + 3].toInt() and 0xFF) / 255.0
        val outA: Double
        val outR: Double
        val outG: Double
        val outB: Double
        when (globalCompositeOperation) {
            "source-atop" -> {
                outA = da
                outR = sr * sa + dr * (1 - sa)
                outG = sg * sa + dg * (1 - sa)
                outB = sb * sa + db * (1 - sa)
            }

            "copy" -> {
                outA = sa
                outR = sr
                outG = sg
                outB = sb
            }

            "destination-out" -> {
                outA = da * (1 - sa)
                outR = dr
                outG = dg
                outB = db
            }

            else -> {
                outA = sa + da * (1 - sa)
                if (outA > 0) {
                    outR = (sr * sa + dr * da * (1 - sa)) / outA
                    outG = (sg * sa + dg * da * (1 - sa)) / outA
                    outB = (sb * sa + db * da * (1 - sa)) / outA
                } else {
                    outR = 0.0
                    outG = 0.0
                    outB = 0.0
                }
            }
        }
        pixels[i] = ImageData.clampToByte(outR)
        pixels[i + 1] = ImageData.clampToByte(outG)
        pixels[i + 2] = ImageData.clampToByte(outB)
        pixels[i + 3] = ImageData.clampToByte(outA * 255)
    }

    private fun rasterize(
        polys: List<DoubleArray>,
        style: Any,
    ) {
        if (polys.isEmpty()) return
        val paint = resolve(style)
        val region = coverageOf(polys, clipX0, clipY0, clipX1, clipY1) ?: return
        for (y in 0 until region.height) {
            for (x in 0 until region.width) {
                val c = region.coverage[y * region.width + x]
                if (c > 0f) paintPixel(region.x0 + x, region.y0 + y, paint, c.toDouble())
            }
        }
    }

    /** Anti-aliased coverage of the (implicitly closed, non-zero wound) polygons inside the given bounds. */
    private fun coverageOf(
        polys: List<DoubleArray>,
        bx0: Int,
        by0: Int,
        bx1: Int,
        by1: Int,
    ): ClipMask? {
        var minX = Double.POSITIVE_INFINITY
        var minY = Double.POSITIVE_INFINITY
        var maxX = Double.NEGATIVE_INFINITY
        var maxY = Double.NEGATIVE_INFINITY
        for (p in polys) {
            var i = 0
            while (i < p.size) {
                minX = min(minX, p[i])
                maxX = max(maxX, p[i])
                minY = min(minY, p[i + 1])
                maxY = max(maxY, p[i + 1])
                i += 2
            }
        }
        val x0 = max(bx0, floor(minX).toInt())
        val y0 = max(by0, floor(minY).toInt())
        val x1 = min(bx1, ceil(maxX).toInt())
        val y1 = min(by1, ceil(maxY).toInt())
        if (x1 <= x0 || y1 <= y0) return null
        val w = x1 - x0
        val h = y1 - y0
        val stride = w + 2
        val acc = FloatArray(stride * h)
        for (p in polys) {
            val n = p.size / 2
            for (i in 0 until n) {
                val j = (i + 1) % n
                addEdge(acc, stride, w, h, p[i * 2] - x0, p[i * 2 + 1] - y0, p[j * 2] - x0, p[j * 2 + 1] - y0)
            }
        }
        val coverage = FloatArray(w * h)
        for (y in 0 until h) {
            var sum = 0f
            for (x in 0 until w) {
                sum += acc[y * stride + x]
                coverage[y * w + x] = min(abs(sum), 1f)
            }
        }
        return ClipMask(x0, y0, w, h, coverage)
    }

    /** Adds the edge to the signed-area accumulation buffer, clipping it to the buffer horizontally and vertically. */
    private fun addEdge(
        acc: FloatArray,
        stride: Int,
        w: Int,
        h: Int,
        ax: Double,
        ay: Double,
        bx: Double,
        by: Double,
    ) {
        val up = ay < by
        var x0 = if (up) ax else bx
        var y0 = if (up) ay else by
        var x1 = if (up) bx else ax
        var y1 = if (up) by else ay
        if (ay == by || y1 <= 0 || y0 >= h) return
        val dxdy = (x1 - x0) / (y1 - y0)
        if (y0 < 0) {
            x0 -= y0 * dxdy
            y0 = 0.0
        }
        if (y1 > h) {
            x1 = x0 + (h - y0) * dxdy
            y1 = h.toDouble()
        }
        addClippedEdge(acc, stride, w, if (up) 1.0 else -1.0, x0, y0, x1, y1)
    }

    // splits at x = 0 and x = w; the parts outside become vertical edges on the border
    private fun addClippedEdge(
        acc: FloatArray,
        stride: Int,
        w: Int,
        dir: Double,
        x0: Double,
        y0: Double,
        x1: Double,
        y1: Double,
    ) {
        val ts = ArrayList<Double>(4)
        ts.add(0.0)
        if (x1 != x0) {
            val t0 = (0 - x0) / (x1 - x0)
            val tw = (w - x0) / (x1 - x0)
            val lo = min(t0, tw)
            val hi = max(t0, tw)
            if (lo > 0 && lo < 1) ts.add(lo)
            if (hi > 0 && hi < 1) ts.add(hi)
        }
        ts.add(1.0)
        val right = w.toDouble()
        for (k in 0 until ts.size - 1) {
            val xa = x0 + (x1 - x0) * ts[k]
            val xb = x0 + (x1 - x0) * ts[k + 1]
            val mid = (xa + xb) / 2
            val ya = y0 + (y1 - y0) * ts[k]
            val yb = y0 + (y1 - y0) * ts[k + 1]
            val fromX =
                if (mid < 0) {
                    0.0
                } else if (mid > w) {
                    right
                } else {
                    xa.coerceIn(0.0, right)
                }
            val toX =
                if (mid < 0) {
                    0.0
                } else if (mid > w) {
                    right
                } else {
                    xb.coerceIn(0.0, right)
                }
            accumulateLine(acc, stride, w, dir, fromX, ya, toX, yb)
        }
    }

    // draw_line of font-rs: exact area coverage of a line with y0 < y1, 0 <= x <= w and 0 <= y <= h
    private fun accumulateLine(
        acc: FloatArray,
        stride: Int,
        w: Int,
        dir: Double,
        ax: Double,
        ay: Double,
        bx: Double,
        by: Double,
    ) {
        if (by <= ay) return
        val dxdy = (bx - ax) / (by - ay)
        var x = ax
        val yStart = floor(ay).toInt()
        val yEnd = ceil(by).toInt()
        for (y in yStart until yEnd) {
            val lineStart = y * stride
            val dy = min((y + 1).toDouble(), by) - max(y.toDouble(), ay)
            // rounding must not push the edge outside of the buffer
            val xNext = (x + dxdy * dy).coerceIn(0.0, w.toDouble())
            val d = dy * dir
            val xa = min(x, xNext)
            val xb = max(x, xNext)
            val x0floor = floor(xa)
            val x0i = x0floor.toInt()
            val x1ceil = ceil(xb)
            val x1i = x1ceil.toInt()
            if (x1i <= x0i + 1) {
                val xmf = 0.5 * (x + xNext) - x0floor
                acc[lineStart + x0i] += (d - d * xmf).toFloat()
                acc[lineStart + x0i + 1] += (d * xmf).toFloat()
            } else {
                val s = 1 / (xb - xa)
                val x0f = xa - x0floor
                val a0 = 0.5 * s * (1 - x0f) * (1 - x0f)
                val x1f = xb - x1ceil + 1
                val am = 0.5 * s * x1f * x1f
                acc[lineStart + x0i] += (d * a0).toFloat()
                if (x1i == x0i + 2) {
                    acc[lineStart + x0i + 1] += (d * (1 - a0 - am)).toFloat()
                } else {
                    val a1 = s * (1.5 - x0f)
                    acc[lineStart + x0i + 1] += (d * (a1 - a0)).toFloat()
                    for (xi in x0i + 2 until x1i - 1) acc[lineStart + xi] += (d * s).toFloat()
                    val a2 = a1 + (x1i - x0i - 3) * s
                    acc[lineStart + x1i - 1] += (d * (1 - a2 - am)).toFloat()
                }
                acc[lineStart + x1i] += (d * am).toFloat()
            }
            x = xNext
        }
    }

    /** Polygons (positively wound) covering the stroke of one subpath: a quad per segment plus caps and round joins. */
    private fun strokePolygons(
        sub: SubPath,
        width: Double,
        out: MutableList<DoubleArray>,
    ) {
        val pts = sub.points
        val count = pts.size / 2
        val half = width / 2
        if (count == 1) {
            if (lineCap == "round") out.add(circle(pts.data[0], pts.data[1], half))
        } else if (count > 1) {
            val last = if (sub.closed) count else count - 1
            for (i in 0 until last) segmentQuad(pts, count, i, last, sub.closed, half)?.let { out.add(it) }
            if (lineCap == "round" && !sub.closed) {
                out.add(circle(pts.data[0], pts.data[1], half))
                out.add(circle(pts.data[(count - 1) * 2], pts.data[(count - 1) * 2 + 1], half))
            }
            // round joins at the inner vertices
            val joinFrom = if (sub.closed) 0 else 1
            val joinTo = if (sub.closed) count else count - 1
            for (i in joinFrom until joinTo) out.add(circle(pts.data[i * 2], pts.data[i * 2 + 1], half))
        }
    }

    /** The quad of segment `i` (extended for square caps at the open ends), or null for a zero-length segment. */
    private fun segmentQuad(
        pts: PointList,
        count: Int,
        i: Int,
        last: Int,
        closed: Boolean,
        half: Double,
    ): DoubleArray? {
        var x0 = pts.data[i * 2]
        var y0 = pts.data[i * 2 + 1]
        var x1 = pts.data[((i + 1) % count) * 2]
        var y1 = pts.data[((i + 1) % count) * 2 + 1]
        val len = sqrt((x1 - x0) * (x1 - x0) + (y1 - y0) * (y1 - y0))
        val ux = (x1 - x0) / len
        val uy = (y1 - y0) / len
        if (lineCap == "square" && !closed && len > 0) {
            if (i == 0) {
                x0 -= ux * half
                y0 -= uy * half
            }
            if (i == last - 1) {
                x1 += ux * half
                y1 += uy * half
            }
        }
        val nx = -uy * half
        val ny = ux * half
        val quad = doubleArrayOf(x0 + nx, y0 + ny, x1 + nx, y1 + ny, x1 - nx, y1 - ny, x0 - nx, y0 - ny)
        return if (len == 0.0) null else orient(quad)
    }

    private fun circle(
        cx: Double,
        cy: Double,
        r: Double,
    ): DoubleArray {
        val n = max(8, min(48, ceil(PI / acos(1 - ARC_TOLERANCE / max(r, ARC_TOLERANCE * 1.01))).toInt()))
        val p = DoubleArray(n * 2)
        for (i in 0 until n) {
            val a = 2 * PI * i / n
            p[i * 2] = cx + r * cos(a)
            p[i * 2 + 1] = cy + r * sin(a)
        }
        return orient(p)
    }

    /** Reverses the polygon if needed so that its signed area is positive. */
    private fun orient(p: DoubleArray): DoubleArray {
        var area = 0.0
        val n = p.size / 2
        for (i in 0 until n) {
            val j = (i + 1) % n
            area += p[i * 2] * p[j * 2 + 1] - p[j * 2] * p[i * 2 + 1]
        }
        if (area >= 0) return p
        val r = DoubleArray(p.size)
        for (i in 0 until n) {
            r[i * 2] = p[(n - 1 - i) * 2]
            r[i * 2 + 1] = p[(n - 1 - i) * 2 + 1]
        }
        return r
    }

    private companion object {
        const val ARC_TOLERANCE = 0.01
    }
}
