package app.zoeshorsefarm.render.filament.backend.sync

import app.zoeshorsefarm.render.filament.backend.device.FakeGpuDevice
import app.zoeshorsefarm.render.filament.backend.device.FakeRenderable
import app.zoeshorsefarm.scene.geometry.BoxGeometry
import app.zoeshorsefarm.scene.geometry.Geometry
import app.zoeshorsefarm.scene.graph.Mesh
import app.zoeshorsefarm.scene.graph.PerspectiveCamera
import app.zoeshorsefarm.scene.graph.Scene
import app.zoeshorsefarm.scene.material.StandardMaterial
import app.zoeshorsefarm.scene.render.FakeRenderBackend

/** Collects what the synchronisation reports. */
class RecordingLog : SyncLog {
    val messages = ArrayList<String>()

    override fun warn(message: String) {
        messages += message
    }
}

/** A scene, a camera, a fake device and a [SceneSync] on it, and `frame()` to run one frame. */
open class SyncHarness {
    val device = FakeGpuDevice()
    val log = RecordingLog()
    val sync = SceneSync(device, log)
    val owner = FakeRenderBackend()
    val scene = Scene()
    val camera = PerspectiveCamera()
    var shadowsEnabled = false

    fun frame() {
        scene.updateMatrixWorld()
        camera.updateMatrixWorld()
        sync.sync(owner, scene, camera, shadowsEnabled)
    }

    fun box(): Geometry = BoxGeometry()

    fun meshAt(
        x: Double = 0.0,
        geometry: Geometry = box(),
        material: StandardMaterial = StandardMaterial(),
    ): Mesh {
        val mesh = Mesh(geometry, material)
        mesh.position.set(x, 0.0, 0.0)
        scene.add(mesh)
        return mesh
    }

    /** The live renderables, in creation order. */
    fun live(): List<FakeRenderable> = device.liveRenderables

    /** A number that changes whenever a frame called into the device for anything but reading. */
    fun deviceActivity(): Int =
        device.meshes.size + device.textures.size + device.instanceData.size + device.spriteBatches.size +
            device.materialInstances.size + device.renderables.size + device.materialInstances.sumOf { it.setCalls } +
            device.renderables.sumOf {
                it.transformCalls + it.boundsCalls + it.boneCalls + it.rangeCalls + it.visibilityCalls
            } + device.meshes.sumOf { it.floatUpdates.size + it.normalUpdates } +
            device.instanceData.sumOf { it.updates.size } + device.spriteBatches.sumOf { it.updates }
}
