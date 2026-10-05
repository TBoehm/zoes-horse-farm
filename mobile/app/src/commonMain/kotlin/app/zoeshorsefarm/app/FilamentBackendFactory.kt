package app.zoeshorsefarm.app

import app.zoeshorsefarm.render.filament.backend.FilamentRenderBackend
import app.zoeshorsefarm.render.filament.material.MaterialPackageCache
import app.zoeshorsefarm.scene.render.RenderBackend
import io.github.erkko68.filament.NativeSurface

// The Filament backend as the app's render backend: the one place that knows both the shell's surface
// and `FilamentRenderBackend`.

// Samples of the multisampled buffers when the memory budget carries antialiasing (0, 2 or 4 are valid)
private const val MSAA_SAMPLES = 4

/**
 * Creates `FilamentRenderBackend`s. [toNative] turns the platform's [PlatformSurface] into Filament's
 * `NativeSurface` (iOS: the `CAMetalLayer` pointer; Android: the `Surface`). [cache] keeps the compiled
 * materials between runs (a file in the app's cache directory); without it every start compiles them.
 */
class FilamentBackendFactory(
    private val cache: MaterialPackageCache? = null,
    private val toNative: (PlatformSurface) -> NativeSurface,
) : RenderBackendFactory {
    override fun create(antialias: Boolean): SurfaceBackend? {
        val backend = FilamentRenderBackend.create(cache = cache) ?: return null
        backend.msaaSamples = if (antialias) MSAA_SAMPLES else 0
        return FilamentSurfaceBackend(backend, toNative)
    }
}

private class FilamentSurfaceBackend(
    private val filament: FilamentRenderBackend,
    private val toNative: (PlatformSurface) -> NativeSurface,
) : SurfaceBackend {
    override val backend: RenderBackend get() = filament

    // Filament reports "context restored" itself when a surface is attached after a detach
    override fun attach(
        surface: PlatformSurface,
        size: SurfaceSize,
    ) = filament.attachSurface(toNative(surface), size.widthPx, size.heightPx, size.density.toFloat())

    override fun resize(size: SurfaceSize) =
        filament.onSurfaceResized(size.widthPx, size.heightPx, size.density.toFloat())

    override fun detach() = filament.detachSurface()
}
