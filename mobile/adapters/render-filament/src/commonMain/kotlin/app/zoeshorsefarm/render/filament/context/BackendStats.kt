package app.zoeshorsefarm.render.filament.context

/**
 * What the backend holds right now, for the debug box and the tests that watch the budget (the web
 * `renderer.info`). Filament does not report draw calls or triangles; the scene model counts those
 * (`SceneStats`). Everything here comes from Filament's own object counts and from the byte
 * accounting of [app.zoeshorsefarm.render.filament.resources.GpuResourceTracker].
 */
data class BackendStats(
    val entities: Int,
    val renderables: Int,
    val visibleRenderables: Int,
    val lights: Int,
    val materials: Int,
    val textures: Int,
    val vertexBuffers: Int,
    val indexBuffers: Int,
    val framesRendered: Long,
    /** GPU memory the accounting knows of, in MiB (buffers, textures, frame buffers, shadow map). */
    val trackedMegabytes: Double,
)
