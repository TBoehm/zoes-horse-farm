package app.zoeshorsefarm.platform

/**
 * What the graphics budget and the debug display want to know about the device. Unknown values are
 * null. [screenWidthPx] and [screenHeightPx] are real pixels (not points), in the screen's natural
 * orientation.
 */
data class DeviceInfo(
    val totalMemoryBytes: Long?,
    val cores: Int,
    val touch: Boolean,
    val screenWidthPx: Int?,
    val screenHeightPx: Int?,
    val gpuName: String?,
)

/** The port for reading [DeviceInfo]; tests hand in fixed values. */
fun interface DeviceInfoSource {
    fun read(): DeviceInfo
}

/** Reads the device the app runs on (iOS: NSProcessInfo, UIScreen, Metal; JVM: the host, for development). */
expect object SystemDeviceInfo : DeviceInfoSource {
    override fun read(): DeviceInfo
}
