package app.zoeshorsefarm.view3d.world

import app.zoeshorsefarm.domain.sim.Element
import app.zoeshorsefarm.domain.sim.ElementKind
import app.zoeshorsefarm.domain.sim.STAND_WIDTH
import app.zoeshorsefarm.scene.geometry.BoxGeometry
import app.zoeshorsefarm.scene.geometry.CylinderGeometry
import app.zoeshorsefarm.scene.geometry.Geometry
import app.zoeshorsefarm.scene.math.Mat4
import app.zoeshorsefarm.scene.math.Quat
import app.zoeshorsefarm.scene.math.Vec3
import app.zoeshorsefarm.scene.texture.Raster2D
import app.zoeshorsefarm.view3d.GeometryBuilder
import app.zoeshorsefarm.view3d.POLE_GEOM_LENGTH
import app.zoeshorsefarm.view3d.POLE_RADIUS
import app.zoeshorsefarm.view3d.PartTransform
import app.zoeshorsefarm.view3d.STAND_X
import app.zoeshorsefarm.view3d.boxOnGround
import app.zoeshorsefarm.view3d.fitText
import app.zoeshorsefarm.view3d.flagSides
import app.zoeshorsefarm.view3d.polesOf
import app.zoeshorsefarm.view3d.standHeight
import app.zoeshorsefarm.view3d.standRows
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.min
import kotlin.math.sign

// Static parts of the obstacles: stands, cups, flags, fillers, number boards and the pole geometry.

/** Stripes of a pole (odd: white ends). */
internal const val STRIPES = 11

private const val WHITE_POST = 0xf5f5f2
private const val CUP_COLOR = 0x3a3a3a
private const val FOOT_COLOR = 0xf0f0ec

/** Pole geometries (along X, centered): white and colored stripes separately. */
internal class PoleGeometries(
    val white: Geometry,
    val colored: Geometry,
)

internal fun buildPoleGeometries(radialSegments: Int = 10): PoleGeometries {
    val white = GeometryBuilder()
    val colored = GeometryBuilder()
    val seg = POLE_GEOM_LENGTH / STRIPES
    for (i in 0 until STRIPES) {
        val end = i == 0 || i == STRIPES - 1
        val g = CylinderGeometry(POLE_RADIUS, POLE_RADIUS, seg, radialSegments, 1, !end)
        g.rotateZ(PI / 2)
        g.translate(-POLE_GEOM_LENGTH / 2 + seg * (i + 0.5), 0.0, 0.0)
        (if (i % 2 == 0) white else colored).add(g, 0xffffff)
    }
    return PoleGeometries(white.build(), colored.build())
}

/** Places something in the element's local frame (+Z = jump direction) into the world. */
internal fun localMatrix(element: Element): Mat4 =
    Mat4().compose(
        Vec3(element.x, 0.0, element.z),
        Quat().setFromAxisAngle(Vec3(0.0, 1.0, 0.0), element.rot),
        Vec3(1.0, 1.0, 1.0),
    )

private val POST_BANDS = doubleArrayOf(0.0, 0.3, 0.55, 0.8, 1.05, 1.3)

/** Writes the static parts of an element into the builder (world space via the element's matrix). */
internal fun addElementStatic(
    builder: GeometryBuilder,
    element: Element,
    color: Int,
    flags: Boolean,
    board: Boolean,
) {
    val matrix = localMatrix(element)

    fun add(
        geometry: Geometry,
        col: Int,
        t: PartTransform = PartTransform(),
    ) {
        builder.add(geometry, col, t).applyMatrix4(matrix)
    }
    val h = standHeight(element)
    val w = STAND_WIDTH
    for (z in standRows(element)) {
        for (sx in listOf(-1.0, 1.0)) {
            val x = sx * STAND_X
            // post: white with colored sections
            val bands = POST_BANDS + h
            for (i in 0 until bands.size - 1) {
                val y0 = bands[i]
                val y1 = if (i == bands.size - 2) h else min(bands[i + 1], h)
                if (y1 <= y0) continue
                add(
                    boxOnGround(w, y1 - y0, w),
                    if (i % 2 ==
                        1
                    ) {
                        color
                    } else {
                        WHITE_POST
                    },
                    PartTransform(x = x, y = y0, z = z),
                )
            }
            add(BoxGeometry(w + 0.03, 0.04, w + 0.03), color, PartTransform(x = x, y = h + 0.02, z = z))
            // feet (T-shape pointing outwards)
            add(boxOnGround(0.1, 0.07, 0.95), FOOT_COLOR, PartTransform(x = x, z = z))
            add(boxOnGround(0.55, 0.07, 0.1), FOOT_COLOR, PartTransform(x = x + sx * 0.25, z = z))
            addCups(::add, element, sx, z)
            if (flags) addFlag(::add, sx, x, z, h)
        }
    }
    // fixed fillers
    if (element.kind == ElementKind.VERTICAL) {
        add(BoxGeometry(POLE_GEOM_LENGTH, 0.24, 0.05), color, PartTransform(y = 0.19))
        add(BoxGeometry(POLE_GEOM_LENGTH - 0.3, 0.06, 0.055), WHITE_POST, PartTransform(y = 0.19))
    }
    // number board post (left of the obstacle, front side)
    if (board) {
        val zFront = standRows(element)[0] - 0.25
        add(boxOnGround(0.05, 1.05, 0.05), 0xe0e0e0, PartTransform(x = STAND_X + 0.75, z = zFront))
        add(BoxGeometry(0.66, 0.56, 0.03), color, PartTransform(x = STAND_X + 0.75, y = 1.0, z = zFront))
    }
}

/** Cups below the pole ends of this row. */
private fun addCups(
    add: (Geometry, Int, PartTransform) -> Unit,
    element: Element,
    sx: Double,
    z: Double,
) {
    for (p in polesOf(element)) {
        for (end in listOf(p.a, p.b)) {
            if (sign(end[0]) != sx || abs(end[2] - z) > 0.2) continue
            add(
                BoxGeometry(0.07, 0.035, 0.12),
                CUP_COLOR,
                PartTransform(
                    x = sx * (STAND_X - STAND_WIDTH / 2 - 0.035),
                    y = end[1] - POLE_RADIUS - 0.018,
                    z = end[2],
                ),
            )
        }
    }
}

/** Direction flag: red on the +t side (local -X), white on -t. */
private fun addFlag(
    add: (Geometry, Int, PartTransform) -> Unit,
    sx: Double,
    x: Double,
    z: Double,
    h: Double,
) {
    val red = sx == flagSides().red.toDouble()
    add(CylinderGeometry(0.012, 0.012, 0.55, 5), 0x9e9e9e, PartTransform(x = x, y = h + 0.27, z = z))
    add(
        BoxGeometry(0.4, 0.27, 0.014),
        if (red) 0xd50000 else 0xfafafa,
        PartTransform(x = x + sx * 0.21, y = h + 0.4, z = z, rz = sx * 0.05),
    )
}

/** Where the number board of an element hangs (it faces the approach). */
internal fun boardMatrix(element: Element): Mat4 {
    val zFront = standRows(element)[0] - 0.25
    val local =
        Mat4().compose(
            Vec3(STAND_X + 0.75, 1.0, zFront),
            Quat().setFromAxisAngle(Vec3(0.0, 1.0, 0.0), PI),
            Vec3(1.0, 1.0, 1.0),
        )
    return localMatrix(element).multiply(local)
}

internal fun drawNumber(text: String): (Raster2D, Int, Int) -> Unit =
    { ctx, w, h ->
        ctx.fillStyle = "#ffffff"
        ctx.fillRect(0.0, 0.0, w.toDouble(), h.toDouble())
        ctx.fillStyle = "#1b1b1b"
        ctx.textAlign = "center"
        ctx.textBaseline = "middle"
        // fitText leaves the font of the size that fits on the context
        fitText(ctx, text, w * 0.86, 800, h * 0.72)
        ctx.fillText(text, w / 2.0, h * 0.54)
    }
