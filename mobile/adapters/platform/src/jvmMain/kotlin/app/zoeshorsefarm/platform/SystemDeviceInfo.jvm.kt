package app.zoeshorsefarm.platform

import java.lang.management.ManagementFactory

/** The development host: cores and physical memory when the JVM exposes them, no screen or GPU. */
actual object SystemDeviceInfo : DeviceInfoSource {
    actual override fun read(): DeviceInfo =
        DeviceInfo(
            totalMemoryBytes = physicalMemory(),
            cores = Runtime.getRuntime().availableProcessors(),
            touch = false,
            screenWidthPx = null,
            screenHeightPx = null,
            gpuName = null,
        )

    private fun physicalMemory(): Long? =
        (ManagementFactory.getOperatingSystemMXBean() as? com.sun.management.OperatingSystemMXBean)
            ?.totalMemorySize
            ?.takeIf { it > 0L }
}
