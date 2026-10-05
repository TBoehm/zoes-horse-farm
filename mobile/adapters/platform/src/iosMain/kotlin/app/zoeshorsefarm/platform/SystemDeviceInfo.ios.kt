@file:OptIn(ExperimentalForeignApi::class)

package app.zoeshorsefarm.platform

import kotlinx.cinterop.ExperimentalForeignApi
import kotlinx.cinterop.useContents
import platform.Foundation.NSProcessInfo
import platform.Metal.MTLCreateSystemDefaultDevice
import platform.UIKit.UIScreen

/**
 * An iPhone or iPad: physical memory and cores from NSProcessInfo, native pixels from UIScreen,
 * GPU name from Metal.
 */
actual object SystemDeviceInfo : DeviceInfoSource {
    actual override fun read(): DeviceInfo {
        val process = NSProcessInfo.processInfo
        val pixels = UIScreen.mainScreen.nativeBounds.useContents { size.width.toInt() to size.height.toInt() }
        return DeviceInfo(
            totalMemoryBytes = process.physicalMemory.toLong(),
            cores = process.activeProcessorCount.toInt(),
            touch = true,
            screenWidthPx = pixels.first,
            screenHeightPx = pixels.second,
            gpuName = MTLCreateSystemDefaultDevice()?.name,
        )
    }
}
