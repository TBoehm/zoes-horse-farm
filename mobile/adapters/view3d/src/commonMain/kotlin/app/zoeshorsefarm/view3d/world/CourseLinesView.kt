package app.zoeshorsefarm.view3d.world

import app.zoeshorsefarm.scene.GpuObject
import app.zoeshorsefarm.scene.geometry.BoxGeometry
import app.zoeshorsefarm.scene.geometry.Geometry
import app.zoeshorsefarm.scene.geometry.PlaneGeometry
import app.zoeshorsefarm.scene.graph.Group
import app.zoeshorsefarm.scene.graph.Mesh
import app.zoeshorsefarm.scene.material.BasicMaterial
import app.zoeshorsefarm.scene.material.MaterialParams
import app.zoeshorsefarm.scene.math.Euler
import app.zoeshorsefarm.scene.math.Mat4
import app.zoeshorsefarm.scene.math.Quat
import app.zoeshorsefarm.scene.math.Vec3
import app.zoeshorsefarm.scene.texture.BlockTextRasterizer
import app.zoeshorsefarm.scene.texture.Raster2D
import app.zoeshorsefarm.scene.texture.TextRasterizer
import app.zoeshorsefarm.view3d.CourseLines
import app.zoeshorsefarm.view3d.GeometryBuilder
import app.zoeshorsefarm.view3d.LabelAtlas
import app.zoeshorsefarm.view3d.LabelItem
import app.zoeshorsefarm.view3d.LineKind
import app.zoeshorsefarm.view3d.LineSegment
import app.zoeshorsefarm.view3d.PartTransform
import app.zoeshorsefarm.view3d.UvRect
import app.zoeshorsefarm.view3d.boxOnGround
import app.zoeshorsefarm.view3d.createLabelAtlas
import app.zoeshorsefarm.view3d.fitText
import app.zoeshorsefarm.view3d.linePosts
import app.zoeshorsefarm.view3d.planLines
import app.zoeshorsefarm.view3d.releaseNow
import kotlin.math.PI
import kotlin.math.sin

// Start and finish lines: ground line, posts with sign.

private const val START_COLOR = 0x1f8f46
private const val FINISH_COLOR = 0xc62828

private fun kindKey(kind: LineKind): String = if (kind == LineKind.START) "start" else "finish"

private fun drawSign(
    kind: LineKind,
    text: String,
): (Raster2D, Int, Int) -> Unit =
    { ctx, w, h ->
        val start = kind == LineKind.START
        ctx.fillStyle = "#ffffff"
        ctx.fillRect(0.0, 0.0, w.toDouble(), h.toDouble())
        ctx.fillStyle = if (start) "#1f8f46" else "#c62828"
        roundRect(ctx, 8.0, 8.0, w - 16.0, h - 16.0, 18.0)
        ctx.fill()
        if (!start) {
            // finish flag: checkered stripe on top
            val sq = (h - 16) / 6.0
            var i = 0
            while (i * sq < w - 16) {
                ctx.fillStyle = if (i % 2 != 0) "#111" else "#fff"
                ctx.fillRect(8 + i * sq, 8.0, sq, sq)
                ctx.fillStyle = if (i % 2 != 0) "#fff" else "#111"
                ctx.fillRect(8 + i * sq, 8 + sq, sq, sq)
                i += 1
            }
        }
        ctx.fillStyle = "#ffffff"
        ctx.textAlign = "center"
        ctx.textBaseline = "middle"
        // fitText leaves the font of the size that fits on the context
        fitText(ctx, text, w - 40.0, 800, h * 0.5)
        ctx.fillText(text, w / 2.0, if (start) h / 2.0 else h * 0.62)
    }

/** Path of a rectangle with rounded corners (call `fill()` or `stroke()` afterwards). */
fun roundRect(
    ctx: Raster2D,
    x: Double,
    y: Double,
    w: Double,
    h: Double,
    r: Double,
) {
    ctx.beginPath()
    ctx.moveTo(x + r, y)
    ctx.arcTo(x + w, y, x + w, y + h, r)
    ctx.arcTo(x + w, y + h, x, y + h, r)
    ctx.arcTo(x, y + h, x, y, r)
    ctx.arcTo(x, y, x + w, y, r)
    ctx.closePath()
}

/** Two-sided sign quad (front and back both read correctly), atlas rect [r]. */
fun makeSignQuad(
    w: Double,
    h: Double,
    r: UvRect,
    thickness: Double = 0.02,
): Geometry {
    val front = PlaneGeometry(w, h)
    val back = PlaneGeometry(w, h)
    for (g in listOf(front, back)) {
        val uv = g.uv
        for (i in 0 until uv.count) {
            uv.setXY(i, r.u0 + uv.getX(i) * (r.u1 - r.u0), r.v0 + uv.getY(i) * (r.v1 - r.v0))
        }
    }
    front.translate(0.0, 0.0, thickness)
    back.rotateY(PI)
    back.translate(0.0, 0.0, -thickness)
    val merged = GeometryBuilder()
    merged.add(front, 0xffffff)
    merged.add(back, 0xffffff)
    return merged.build()
}

private const val GLOW_COLOR = 0xffd21f
private const val GLOW_OPACITY = 0.6
private const val GLOW_OFFSET = -2.0

/**
 * Start/finish lines: ground line, posts with sign. `set(null)` clears them; `setFinishMarked(true)`
 * lets the finish line glow. [textRasterizer] draws the texts of the signs.
 */
class CourseLinesView(
    materialFactory: MaterialFactory,
    private val release: (GpuObject?) -> Unit = ::releaseNow,
    private val textRasterizer: TextRasterizer = BlockTextRasterizer,
) {
    val group = Group().also { it.name = "course-lines" }
    private val staticMats = materialFactory("lines", MaterialParams(vertexColors = true, roughness = 0.8))
    private var staticMesh: Mesh? = null
    private var signMesh: Mesh? = null
    private var signMaterial: BasicMaterial? = null
    private var atlas: LabelAtlas? = null
    private val glowMaterial =
        BasicMaterial(
            color = GLOW_COLOR,
            transparent = true,
            opacity = GLOW_OPACITY,
            depthWrite = false,
            toneMapped = false,
            polygonOffset = true,
            polygonOffsetFactor = GLOW_OFFSET,
            polygonOffsetUnits = GLOW_OFFSET,
        )
    private val glow =
        Mesh(PlaneGeometry(1.0, 1.0), glowMaterial).also {
            it.rotation.x = -PI / 2
            it.visible = false
            it.renderOrder = 2
            group.add(it)
        }
    private var finishMarked = false
    private var time = 0.0
    private var finishLine: LineSegment? = null

    /** The managed mesh of the static parts (its mesh is set while lines exist). */
    val meshes = listOf(ManagedMesh(null, staticMats, ShadowRole.OBSTACLES))

    private fun clear() {
        staticMesh?.let {
            group.remove(it)
            release(it.geometry)
        }
        staticMesh = null
        signMesh?.let {
            group.remove(it)
            release(it.geometry)
            release(signMaterial)
            release(atlas?.texture)
        }
        signMesh = null
        meshes[0].mesh = null
        finishLine = null
    }

    fun set(lines: CourseLines?) {
        clear()
        glow.visible = false
        if (lines == null) return
        val entries = planLines(lines)
        finishLine = entries.firstOrNull { it.finish }?.seg
        val labelAtlas =
            createLabelAtlas(
                entries.map { LabelItem(kindKey(it.kind), drawSign(it.kind, it.text)) },
                textRasterizer,
                cellW = 256,
                cellH = 128,
            )
        atlas = labelAtlas
        val builder = GeometryBuilder()
        val signs = GeometryBuilder()
        for (e in entries) addEntry(builder, signs, labelAtlas, e.kind, e.seg)

        val mesh = Mesh(builder.build(), staticMats.standard)
        mesh.castShadow = true
        mesh.receiveShadow = true
        group.add(mesh)
        staticMesh = mesh
        val material = BasicMaterial(map = labelAtlas.texture, toneMapped = false)
        signMaterial = material
        val sign = Mesh(signs.build(), material)
        signMesh = sign
        group.add(sign)
        meshes[0].mesh = mesh

        finishLine?.let {
            glow.position.set(it.cx, 0.016, it.cz)
            glow.rotation.set(-PI / 2, 0.0, it.angle)
            glow.scale.set(1.4, it.length + 0.6, 1.0)
            glow.visible = finishMarked
        }
    }

    /** The chalk line, the posts with their flags and the sign of one line. */
    private fun addEntry(
        builder: GeometryBuilder,
        signs: GeometryBuilder,
        labelAtlas: LabelAtlas,
        kind: LineKind,
        seg: LineSegment,
    ) {
        val ang = seg.angle
        // chalk line on the ground
        val line = PlaneGeometry(0.14, seg.length).rotateX(-PI / 2)
        builder.add(line, 0xffffff, PartTransform(x = seg.cx, y = 0.012, z = seg.cz, ry = ang))
        // posts at both ends with flags: red on the right, white on the left in riding direction
        for (p in linePosts(seg)) {
            builder.add(boxOnGround(0.07, 1.7, 0.07), 0xf2f2f2, PartTransform(x = p.x, z = p.z))
            builder.add(
                BoxGeometry(0.02, 0.28, 0.38),
                if (p.red) 0xd32f2f else 0xffffff,
                PartTransform(x = p.x, y = 1.52, z = p.z, ry = ang + PI / 2),
            )
        }
        // sign on the first post, readable from both sides
        val rect = checkNotNull(labelAtlas.rects[kindKey(kind)]) { "no cell for the sign of the line" }
        val mid = seg.a
        val sign = makeSignQuad(1.1, 0.55, rect)
        val m =
            Mat4().compose(
                Vec3(mid.x, 2.0, mid.z),
                Quat().setFromEuler(Euler(0.0, ang + PI / 2, 0.0)),
                Vec3(1.0, 1.0, 1.0),
            )
        signs.addPainted(sign, m)
        val color = if (kind == LineKind.START) START_COLOR else FINISH_COLOR
        builder.add(
            BoxGeometry(1.16, 0.61, 0.03),
            color,
            PartTransform(x = mid.x, y = 2.0, z = mid.z, ry = ang + PI / 2),
        )
    }

    fun setFinishMarked(on: Boolean) {
        finishMarked = on
        glow.visible = finishMarked && finishLine != null
    }

    fun update(dt: Double) {
        time += dt
        if (glow.visible) glowMaterial.opacity = 0.45 + 0.3 * (0.5 + 0.5 * sin(time * 5))
    }

    fun dispose() {
        clear()
        glow.geometry.dispose()
        glowMaterial.dispose()
    }
}
