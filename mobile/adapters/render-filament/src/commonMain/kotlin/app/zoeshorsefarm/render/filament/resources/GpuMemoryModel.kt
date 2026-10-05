package app.zoeshorsefarm.render.filament.resources

import kotlin.math.max

/** The sizes of a frame: the display surface and the (possibly smaller) size it is rendered at. */
class FrameSizes(
    val surfaceWidth: Int,
    val surfaceHeight: Int,
    val renderWidth: Int,
    val renderHeight: Int,
) {
    val scaled: Boolean get() = renderWidth != surfaceWidth || renderHeight != surfaceHeight
}

/**
 * Byte sizes of the GPU objects of the Filament backend, as assumptions that stay on the safe side
 * (the web budget model has the same character: "deliberately on the safe side, tuned on a real
 * device"). The values follow what Filament allocates for a frame with post processing, which is
 * always on here (tone mapping is a post processing step):
 *
 *  - HDR colour buffer: `R11G11B10F`, 4 bytes (`RenderQuality.hdrColorBuffer = MEDIUM`)
 *  - depth: 32 bit float or `D24S8`, 4 bytes
 *  - with MSAA the colour and depth buffers hold `samples` times as much, and a resolve target is
 *    added
 *  - scaled rendering adds the tone mapped LDR target that is then scaled to the surface
 *  - the swap chain: 2 buffers of 4 bytes per surface pixel
 *  - shadow map: a 32 bit depth texture per cascade
 */
object GpuMemoryModel {
    const val HDR_COLOR_BYTES = 4
    const val DEPTH_BYTES = 4
    const val LDR_BYTES = 4
    const val SWAP_BYTES = 4
    const val SWAP_BUFFERS = 2
    const val SHADOW_TEXEL_BYTES = 4

    fun mipLevels(
        width: Int,
        height: Int,
    ): Int {
        var size = max(width, height)
        var levels = 1
        while (size > 1) {
            size /= 2
            levels++
        }
        return levels
    }

    fun textureBytes(
        width: Int,
        height: Int,
        bytesPerTexel: Int,
        mipmapped: Boolean,
    ): Long {
        var w = width
        var h = height
        var total = 0L
        while (true) {
            total += w.toLong() * h * bytesPerTexel
            if (!mipmapped || (w == 1 && h == 1)) break
            w = max(1, w / 2)
            h = max(1, h / 2)
        }
        return total
    }

    fun shadowMapBytes(
        mapSize: Int,
        cascades: Int = 1,
    ): Long = mapSize.toLong() * mapSize * SHADOW_TEXEL_BYTES * cascades

    fun frameBytes(
        sizes: FrameSizes,
        msaaSamples: Int,
    ): Long {
        val renderPixels = sizes.renderWidth.toLong() * sizes.renderHeight
        val surfacePixels = sizes.surfaceWidth.toLong() * sizes.surfaceHeight
        var bytes = renderPixels * (HDR_COLOR_BYTES + DEPTH_BYTES)
        if (msaaSamples > 1) {
            bytes += renderPixels * (HDR_COLOR_BYTES + DEPTH_BYTES) * (msaaSamples - 1)
            bytes += renderPixels * HDR_COLOR_BYTES
        }
        if (sizes.scaled) bytes += renderPixels * LDR_BYTES
        bytes += surfacePixels * SWAP_BYTES * SWAP_BUFFERS
        return bytes
    }
}
