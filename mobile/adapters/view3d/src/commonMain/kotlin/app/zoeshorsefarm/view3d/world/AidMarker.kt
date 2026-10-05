package app.zoeshorsefarm.view3d.world

import app.zoeshorsefarm.domain.sim.Element
import app.zoeshorsefarm.domain.sim.Zone
import app.zoeshorsefarm.scene.geometry.PlaneGeometry
import app.zoeshorsefarm.scene.graph.Mesh
import app.zoeshorsefarm.scene.material.BasicMaterial
import app.zoeshorsefarm.view3d.aidPlacement
import app.zoeshorsefarm.view3d.createSoftRectTexture
import kotlin.math.PI
import kotlin.math.sin

// Take-off aid (rule 42): translucent band on the sand in front of the front rail.

private const val AID_HEIGHT = 0.018 // above the sand, below the lines
private const val AID_COLOR = 0x2fe6a6
private const val AID_OPACITY = 0.6
private const val POLYGON_OFFSET = -2.0

class AidMarker(
    color: Int = AID_COLOR,
) {
    private val geometry = PlaneGeometry(1.0, 1.0).rotateX(-PI / 2)
    private val alphaMap = createSoftRectTexture()
    private val material =
        BasicMaterial(
            color = color,
            alphaMap = alphaMap,
            transparent = true,
            opacity = AID_OPACITY,
            depthWrite = false,
            toneMapped = false,
            polygonOffset = true,
            polygonOffsetFactor = POLYGON_OFFSET,
            polygonOffsetUnits = POLYGON_OFFSET,
        )
    val mesh =
        Mesh(geometry, material).also {
            it.name = "aid-marker"
            it.position.y = AID_HEIGHT
            it.renderOrder = 2
            it.visible = false
        }
    private var time = 0.0

    /** Only changes the transform, no new geometry. */
    fun set(
        element: Element,
        dir: Int,
        zone: Zone?,
    ) {
        val t = aidPlacement(element, dir, zone)
        if (t == null) {
            mesh.visible = false
            return
        }
        mesh.position.set(t.x, AID_HEIGHT, t.z)
        mesh.rotation.set(0.0, t.rotY, 0.0)
        mesh.scale.set(t.width, 1.0, t.depth)
        mesh.visible = true
    }

    fun hide() {
        mesh.visible = false
    }

    fun update(dt: Double) {
        if (!mesh.visible) return
        time += dt
        material.opacity = 0.5 + 0.15 * sin(time * 3)
    }

    fun dispose() {
        geometry.dispose()
        alphaMap.dispose()
        material.dispose()
    }
}
