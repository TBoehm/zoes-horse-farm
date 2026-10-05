package app.zoeshorsefarm.view3d.world

import app.zoeshorsefarm.domain.sim.Element
import app.zoeshorsefarm.domain.sim.ElementKind
import app.zoeshorsefarm.domain.sim.STAND_WIDTH
import app.zoeshorsefarm.scene.GpuObject
import app.zoeshorsefarm.scene.geometry.Geometry
import app.zoeshorsefarm.scene.geometry.Path
import app.zoeshorsefarm.scene.geometry.Shape
import app.zoeshorsefarm.scene.geometry.ShapeGeometry
import app.zoeshorsefarm.scene.graph.Camera
import app.zoeshorsefarm.scene.graph.Group
import app.zoeshorsefarm.scene.graph.Mesh
import app.zoeshorsefarm.scene.graph.Sprite
import app.zoeshorsefarm.scene.material.BasicMaterial
import app.zoeshorsefarm.scene.material.SpriteMaterial
import app.zoeshorsefarm.scene.math.Vec3
import app.zoeshorsefarm.scene.texture.BlockTextRasterizer
import app.zoeshorsefarm.scene.texture.TextRasterizer
import app.zoeshorsefarm.scene.texture.canvasTexture
import app.zoeshorsefarm.view3d.STAND_X
import app.zoeshorsefarm.view3d.createCanvas
import app.zoeshorsefarm.view3d.fitText
import app.zoeshorsefarm.view3d.releaseNow
import app.zoeshorsefarm.view3d.standHeight
import kotlin.math.PI
import kotlin.math.max
import kotlin.math.sin

// The highlight of the obstacle that is due next: a pulsing ring on the sand and a number badge
// above it.

private const val RING_CORNER = 0.9 // m, radius of the rounded corners
private const val RING_STROKE = 0.24 // m
private const val BADGE_WIDTH = 256
private const val BADGE_HEIGHT = 320
private const val RING_COLOR = 0xffd21f
private const val POLYGON_OFFSET = -3.0

private fun roundedRectShape(
    hw: Double,
    hd: Double,
    r: Double,
): Shape {
    val s = Shape()
    s.moveTo(-hw + r, -hd)
    s.lineTo(hw - r, -hd)
    s.quadraticCurveTo(hw, -hd, hw, -hd + r)
    s.lineTo(hw, hd - r)
    s.quadraticCurveTo(hw, hd, hw - r, hd)
    s.lineTo(-hw + r, hd)
    s.quadraticCurveTo(-hw, hd, -hw, hd - r)
    s.lineTo(-hw, -hd + r)
    s.quadraticCurveTo(-hw, -hd, -hw + r, -hd)
    return s
}

private fun ringGeometry(
    hw: Double,
    hd: Double,
    stroke: Double,
): Geometry {
    val outer = roundedRectShape(hw, hd, RING_CORNER)
    val inner = roundedRectShape(hw - stroke, hd - stroke, RING_CORNER - stroke * 0.5)
    outer.holes.add(Path(inner.getPoints(6).reversed()))
    val g = ShapeGeometry(outer, 6)
    g.rotateX(-PI / 2)
    return g
}

/** The number badge: a pin with a number, drawn into a canvas and shown as a sprite. */
private class Badge(
    textRasterizer: TextRasterizer,
) {
    private val raster = createCanvas(BADGE_WIDTH, BADGE_HEIGHT, textRasterizer)
    val texture = canvasTexture(raster, repeat = false)
    val material =
        SpriteMaterial(
            map = texture,
            transparent = true,
            depthWrite = false,
            fog = false,
            toneMapped = false,
        )
    val sprite =
        Sprite(material).also {
            it.center.set(0.5, 0.0)
            it.renderOrder = 5
        }
    private var current: String? = null

    fun draw(text: String?) {
        if (text == current) return
        current = text
        // nothing to write: the sprite is hidden then
        if (text == null) return
        val ctx = raster
        ctx.clearRect(0.0, 0.0, BADGE_WIDTH.toDouble(), BADGE_HEIGHT.toDouble())
        // pin: circle with a point at the bottom
        ctx.beginPath()
        ctx.moveTo(128.0, 314.0)
        ctx.lineTo(70.0, 200.0)
        ctx.lineTo(186.0, 200.0)
        ctx.closePath()
        ctx.fillStyle = "#1d3b8f"
        ctx.fill()
        ctx.beginPath()
        ctx.arc(128.0, 124.0, 116.0, 0.0, PI * 2)
        ctx.fillStyle = "#1d3b8f"
        ctx.fill()
        ctx.beginPath()
        ctx.arc(128.0, 124.0, 100.0, 0.0, PI * 2)
        ctx.fillStyle = "#ffd21f"
        ctx.fill()
        ctx.fillStyle = "#152a66"
        ctx.textAlign = "center"
        ctx.textBaseline = "middle"
        // fitText leaves the font of the size that fits on the context
        fitText(ctx, text, 170.0, 900, 130.0)
        ctx.fillText(text, 128.0, 132.0)
        texture.needsUpdate = true
    }
}

private val v1 = Vec3()

internal class Highlight(
    private val release: (GpuObject?) -> Unit = ::releaseNow,
    textRasterizer: TextRasterizer = BlockTextRasterizer,
) {
    val group =
        Group().also {
            it.name = "highlight"
            it.visible = false
        }
    private val ringMaterial =
        BasicMaterial(
            color = RING_COLOR,
            transparent = true,
            opacity = 0.85,
            depthWrite = false,
            toneMapped = false,
            polygonOffset = true,
            polygonOffsetFactor = POLYGON_OFFSET,
            polygonOffsetUnits = POLYGON_OFFSET,
        )
    private val ring =
        Mesh(Geometry(), ringMaterial).also {
            it.position.y = 0.02
            it.renderOrder = 3
            group.add(it)
        }
    private val badge = Badge(textRasterizer).also { group.add(it.sprite) }
    private var time = 0.0
    private var baseY = 2.0

    fun show(
        element: Element,
        text: String?,
    ) {
        val hw = STAND_X + STAND_WIDTH / 2 + 0.7
        val hd = (if (element.kind == ElementKind.OXER) element.spread / 2 else 0.0) + 1.1
        release(ring.geometry)
        ring.geometry = ringGeometry(hw, hd, RING_STROKE)
        group.position.set(element.x, 0.0, element.z)
        group.rotation.y = element.rot
        baseY = standHeight(element) + 0.75
        badge.draw(text)
        badge.sprite.visible = !text.isNullOrEmpty()
        group.visible = true
    }

    fun hide() {
        group.visible = false
    }

    fun update(
        dt: Double,
        camera: Camera?,
    ) {
        if (!group.visible) return
        time += dt
        val pulse = 0.5 + 0.5 * sin(time * 4)
        ringMaterial.opacity = 0.55 + 0.4 * pulse
        ring.scale.setScalar(1 + 0.03 * pulse)
        badge.sprite.position.y = baseY + sin(time * 2.4) * 0.12
        // do not get too small from afar
        var s = 1.1
        if (camera != null) {
            badge.sprite.getWorldPosition(v1)
            s = max(1.1, camera.position.distanceTo(v1) * 0.05)
        }
        badge.sprite.scale.set(s, s * 1.25, 1.0)
    }

    fun dispose() {
        ring.geometry.dispose()
        ringMaterial.dispose()
        badge.texture.dispose()
        badge.material.dispose()
    }
}
