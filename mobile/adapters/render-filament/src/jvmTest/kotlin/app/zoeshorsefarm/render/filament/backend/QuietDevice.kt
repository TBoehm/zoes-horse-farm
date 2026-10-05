package app.zoeshorsefarm.render.filament.backend

import app.zoeshorsefarm.render.filament.backend.device.GpuDevice
import app.zoeshorsefarm.render.filament.backend.device.InstanceDataHandle
import app.zoeshorsefarm.render.filament.backend.device.MaterialInstanceHandle
import app.zoeshorsefarm.render.filament.backend.device.MeshHandle
import app.zoeshorsefarm.render.filament.backend.device.RenderableHandle
import app.zoeshorsefarm.render.filament.backend.device.SpriteBatchHandle
import app.zoeshorsefarm.render.filament.backend.device.StagePort
import app.zoeshorsefarm.render.filament.backend.device.TextureHandle
import app.zoeshorsefarm.render.filament.context.RenderSettings
import app.zoeshorsefarm.render.filament.material.MaterialSpec
import app.zoeshorsefarm.render.filament.math.LinearRgb
import app.zoeshorsefarm.render.filament.mesh.Aabb
import app.zoeshorsefarm.render.filament.mesh.MeshData
import app.zoeshorsefarm.render.filament.mesh.MeshPacker
import app.zoeshorsefarm.render.filament.mesh.PackOptions
import app.zoeshorsefarm.render.filament.mesh.PrimitiveRange
import app.zoeshorsefarm.render.filament.mesh.RenderableOptions
import app.zoeshorsefarm.render.filament.mesh.TextureData
import app.zoeshorsefarm.render.filament.mesh.VertexSemantic

/**
 * A device that keeps nothing and allocates nothing per call, to measure what the synchronisation itself
 * allocates (the recording fakes copy arrays and make sets on every call).
 */
internal class QuietDevice : GpuDevice {
    override val maxTextureSize = 4096
    override val maxAnisotropy = 4
    override val placeholderTexture: TextureHandle = QuietTexture

    override fun createMesh(
        label: String,
        data: MeshData,
        options: PackOptions,
    ): MeshHandle =
        QuietMesh(
            data.semantics,
            MeshPacker.pack(data, options).bounds,
            data.vertexCount,
            data.indices?.size ?: 0,
        )

    override fun createTexture(
        label: String,
        data: TextureData,
    ): TextureHandle = QuietTexture

    override fun createInstanceData(
        label: String,
        capacity: Int,
    ): InstanceDataHandle = QuietInstanceData(capacity)

    override fun createSpriteBatch(
        label: String,
        capacity: Int,
    ): SpriteBatchHandle = QuietSpriteBatch(capacity)

    override fun createMaterialInstance(
        spec: MaterialSpec,
        label: String,
    ): MaterialInstanceHandle = QuietMaterial

    override fun prepareMaterial(spec: MaterialSpec) = true

    override fun releaseMaterial(spec: MaterialSpec) = Unit

    override fun createRenderable(
        mesh: MeshHandle,
        materials: List<MaterialInstanceHandle>,
        ranges: List<PrimitiveRange>?,
        options: RenderableOptions,
    ): RenderableHandle = QuietRenderable

    override fun flush() = Unit

    override fun dispose() = Unit
}

internal object QuietTexture : TextureHandle {
    override val label = "quiet"
    override val byteSize = 0L

    override fun destroy() = Unit
}

internal class QuietMesh(
    override val semantics: Set<VertexSemantic>,
    override val bounds: Aabb,
    override val vertexCount: Int,
    override val indexCount: Int,
) : MeshHandle {
    override val label = "quiet"
    override val byteSize = 0L
    override val uploadOverflows = 0

    override fun updateFloats(
        semantic: VertexSemantic,
        values: FloatArray,
        count: Int,
    ) = Unit

    override fun updateNormals(normals: FloatArray) = Unit

    override fun destroy() = Unit
}

internal class QuietInstanceData(
    override val capacity: Int,
) : InstanceDataHandle {
    override val uploadOverflows = 0

    override fun update(
        matrices: FloatArray,
        colors: FloatArray?,
        from: Int,
        to: Int,
    ) = Unit

    override fun destroy() = Unit
}

internal class QuietSpriteBatch(
    override val capacity: Int,
) : SpriteBatchHandle {
    override val mesh: MeshHandle = QuietMesh(emptySet(), Aabb(), 0, 0)
    override val uploadOverflows = 0

    override fun update(
        centers: FloatArray,
        sizes: FloatArray,
        opacities: FloatArray,
        count: Int,
    ) = Unit

    override fun destroy() = Unit
}

internal object QuietMaterial : MaterialInstanceHandle {
    override fun setFloat(
        name: String,
        x: Float,
    ) = Unit

    override fun setFloat3(
        name: String,
        x: Float,
        y: Float,
        z: Float,
    ) = Unit

    override fun setFloat4(
        name: String,
        x: Float,
        y: Float,
        z: Float,
        w: Float,
    ) = Unit

    override fun setTexture(
        name: String,
        texture: TextureHandle,
    ) = Unit

    override fun setInstanceData(data: InstanceDataHandle) = Unit

    override fun setPolygonOffset(
        factor: Float,
        units: Float,
    ) = Unit

    override fun setDepthTest(enabled: Boolean) = Unit

    override fun destroy() = Unit
}

internal object QuietRenderable : RenderableHandle {
    override fun setTransform(matrix: FloatArray) = Unit

    override fun setVisible(visible: Boolean) = Unit

    override fun setCastShadows(enabled: Boolean) = Unit

    override fun setReceiveShadows(enabled: Boolean) = Unit

    override fun setCulling(enabled: Boolean) = Unit

    override fun setFog(enabled: Boolean) = Unit

    override fun setDrawOrder(
        priority: Int,
        blendOrder: Int,
    ) = Unit

    override fun setBounds(bounds: Aabb) = Unit

    override fun setBones(palette: FloatArray) = Unit

    override fun setRanges(ranges: List<PrimitiveRange>) = Unit

    override fun setMaterial(
        primitive: Int,
        material: MaterialInstanceHandle,
    ) = Unit

    override fun destroy() = Unit
}

internal class QuietStage : StagePort {
    override fun applySettings(settings: RenderSettings) = Unit

    override fun resize(
        widthPx: Int,
        heightPx: Int,
        devicePixelRatio: Float,
    ) = Unit

    override fun setLens(
        fovDegrees: Float,
        near: Float,
        far: Float,
    ) = Unit

    override fun setCameraPose(matrixWorld: FloatArray) = Unit

    override fun configureSun(
        directionToSun: FloatArray,
        color: LinearRgb,
        intensity: Float,
    ) = Unit

    override fun setSunFocus(
        x: Float,
        y: Float,
        z: Float,
        cameraX: Float,
        cameraY: Float,
        cameraZ: Float,
    ) = Unit

    override fun setAmbient(sh: FloatArray) = Unit

    override fun setWind(
        time: Float,
        strength: Float,
    ) = Unit

    override fun renderFrame() = true
}
