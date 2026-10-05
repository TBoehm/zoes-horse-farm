package app.zoeshorsefarm.scene.graph

import app.zoeshorsefarm.scene.GpuResource
import app.zoeshorsefarm.scene.math.Color
import app.zoeshorsefarm.scene.math.Vec2
import app.zoeshorsefarm.scene.math.Vec3

/**
 * What can be walked like a scene graph: a [Node], or a hand-made list of the objects to compile
 * (the world's compile root yields only the visible objects).
 */
interface Traversable {
    fun traverse(callback: (Node) -> Unit)

    fun traverseVisible(callback: (Node) -> Unit)
}

/** Linear distance fog (three.js `Fog`). */
class Fog(
    color: Int,
    var near: Double = 1.0,
    var far: Double = 1000.0,
) {
    val color: Color = Color(color)
}

/**
 * Image-based light of the sky (replaces the PMREM environment map of the web app): a gradient
 * dome with an optional sun and a coloured floor, which the backend turns into diffuse and specular
 * light. A GPU object: dispose it to free the prefiltered map; the backend builds it again on use.
 */
class EnvironmentLight(
    val zenith: Color,
    val horizon: Color,
    val ground: Color,
    val sunColor: Color,
    val sunDirection: Vec3,
    /** Colour of the flat floor below the horizon (null: the dome's ground colour). */
    val floorColor: Color? = null,
    /** Height of the floor plane relative to the viewer. */
    val floorHeight: Double = -2.0,
) : GpuResource()

/** The root of a scene (three.js `Scene`). */
class Scene : Group() {
    /** Clear colour behind everything (null: the backend's default). */
    var background: Color? = null
    var fog: Fog? = null
    var environment: EnvironmentLight? = null
    var environmentIntensity: Double = 1.0
}

/** Base of the lights (three.js `Light`). [color] is the linear working colour. */
abstract class Light(
    color: Int,
    var intensity: Double,
) : Node() {
    val color: Color = Color(color)
}

/** The shadow map settings of a light (three.js `LightShadow`). */
class LightShadow(
    val camera: OrthographicCamera,
) {
    val mapSize: Vec2 = Vec2(512.0, 512.0)
    var bias: Double = 0.0
    var normalBias: Double = 0.0
    var radius: Double = 1.0

    /** The shadow map once the backend made one (a GPU object the view code may release). */
    var map: GpuResource? = null
}

/**
 * Sunlight (three.js `DirectionalLight`): shines from [position] towards [target] (a node that must
 * be in the scene, or have its matrix updated). Shadows are rendered through [shadow] when the
 * light's `castShadow` is true and the backend has shadows enabled.
 */
class DirectionalLight(
    color: Int = 0xffffff,
    intensity: Double = 1.0,
) : Light(color, intensity) {
    val target: Node = Node()
    val shadow: LightShadow = LightShadow(OrthographicCamera(-5.0, 5.0, 5.0, -5.0, 0.5, 500.0))

    init {
        position.copy(Node.DEFAULT_UP)
    }
}

/** Soft light from a sky colour above and a ground colour below (three.js `HemisphereLight`). */
class HemisphereLight(
    skyColor: Int = 0xffffff,
    groundColor: Int = 0xffffff,
    intensity: Double = 1.0,
) : Light(skyColor, intensity) {
    val groundColor: Color = Color(groundColor)
}
