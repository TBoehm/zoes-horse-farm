package app.zoeshorsefarm.render.filament.resources

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class GpuMemoryModelTest {
    @Test
    fun `a texture without mipmaps is width times height times bytes per texel`() {
        assertEquals(512L * 512 * 4, GpuMemoryModel.textureBytes(512, 512, bytesPerTexel = 4, mipmapped = false))
    }

    @Test
    fun `a full mip chain of a square power of two texture adds a third`() {
        val base = 512L * 512 * 4
        val chain = GpuMemoryModel.textureBytes(512, 512, 4, mipmapped = true)
        // 10 levels: (4^10 - 1) / 3 texels of 4 bytes, which is the base level times 4/3 minus a sliver
        assertEquals(((1L shl 20) - 1) / 3 * 4, chain)
        assertTrue(chain > base + base / 4)
    }

    @Test
    fun `the mip chain is summed level by level for odd sizes`() {
        // 3x3 + 1x1 texels of one byte
        assertEquals(10L, GpuMemoryModel.textureBytes(3, 3, 1, mipmapped = true))
    }

    @Test
    fun `the number of mip levels follows the larger side`() {
        assertEquals(1, GpuMemoryModel.mipLevels(1, 1))
        assertEquals(10, GpuMemoryModel.mipLevels(512, 512))
        assertEquals(10, GpuMemoryModel.mipLevels(512, 4))
        assertEquals(11, GpuMemoryModel.mipLevels(1025, 1))
    }

    @Test
    fun `the shadow map is one depth texture per cascade`() {
        assertEquals(2048L * 2048 * 4, GpuMemoryModel.shadowMapBytes(2048))
        assertEquals(1024L * 1024 * 4 * 2, GpuMemoryModel.shadowMapBytes(1024, cascades = 2))
    }

    @Test
    fun `a frame without antialiasing holds colour and depth at the render size plus the swap chain`() {
        val plan = FrameSizes(surfaceWidth = 1000, surfaceHeight = 500, renderWidth = 1000, renderHeight = 500)
        val bytes = GpuMemoryModel.frameBytes(plan, msaaSamples = 0)
        val pixels = 1000L * 500
        val expected =
            pixels * (GpuMemoryModel.HDR_COLOR_BYTES + GpuMemoryModel.DEPTH_BYTES) +
                pixels * GpuMemoryModel.SWAP_BYTES * GpuMemoryModel.SWAP_BUFFERS
        assertEquals(expected, bytes)
    }

    @Test
    fun `msaa multiplies the colour and depth buffers and adds a resolve target`() {
        val plan = FrameSizes(1000, 500, 1000, 500)
        val plain = GpuMemoryModel.frameBytes(plan, 0)
        val msaa = GpuMemoryModel.frameBytes(plan, 4)
        val renderPixels = 1000L * 500
        val extra =
            renderPixels * (GpuMemoryModel.HDR_COLOR_BYTES + GpuMemoryModel.DEPTH_BYTES) * 3 +
                renderPixels * GpuMemoryModel.HDR_COLOR_BYTES
        assertEquals(plain + extra, msaa)
    }

    @Test
    fun `a smaller render size shrinks the render targets and keeps the swap chain and adds an upscale target`() {
        val full = GpuMemoryModel.frameBytes(FrameSizes(1000, 1000, 1000, 1000), 0)
        val half = GpuMemoryModel.frameBytes(FrameSizes(1000, 1000, 500, 500), 0)
        assertTrue(half < full)
        val swap = 1000L * 1000 * GpuMemoryModel.SWAP_BYTES * GpuMemoryModel.SWAP_BUFFERS
        val renderPixels = 500L * 500
        val expected =
            renderPixels * (GpuMemoryModel.HDR_COLOR_BYTES + GpuMemoryModel.DEPTH_BYTES + GpuMemoryModel.LDR_BYTES) +
                swap
        assertEquals(expected, half)
    }
}
