@file:OptIn(ExperimentalForeignApi::class, BetaInteropApi::class)

package app.zoeshorsefarm.app

import app.zoeshorsefarm.audio.createPlatformAudio
import app.zoeshorsefarm.platform.DeviceClass
import app.zoeshorsefarm.platform.DeviceInfoSource
import app.zoeshorsefarm.platform.SystemClock
import app.zoeshorsefarm.platform.SystemDeviceInfo
import app.zoeshorsefarm.presentation.LocalTimeZone
import app.zoeshorsefarm.render.filament.material.FilamentMaterialCompiler
import app.zoeshorsefarm.render.filament.material.MaterialPackageCache
import app.zoeshorsefarm.storage.UserDefaultsKeyValueBackend
import io.github.erkko68.filament.NativeSurface
import kotlinx.cinterop.BetaInteropApi
import kotlinx.cinterop.COpaque
import kotlinx.cinterop.ExperimentalForeignApi
import kotlinx.cinterop.addressOf
import kotlinx.cinterop.interpretCPointer
import kotlinx.cinterop.objcPtr
import kotlinx.cinterop.usePinned
import platform.Foundation.NSBundle
import platform.Foundation.NSCachesDirectory
import platform.Foundation.NSData
import platform.Foundation.NSDate
import platform.Foundation.NSFileManager
import platform.Foundation.NSLocale
import platform.Foundation.NSSearchPathForDirectoriesInDomains
import platform.Foundation.NSTimeZone
import platform.Foundation.NSUserDomainMask
import platform.Foundation.create
import platform.Foundation.dataWithContentsOfFile
import platform.Foundation.dateWithTimeIntervalSince1970
import platform.Foundation.localTimeZone
import platform.Foundation.preferredLanguages
import platform.Foundation.writeToFile
import platform.QuartzCore.CAMetalLayer
import platform.posix.memcpy

// The iOS shell's platform: UserDefaults, AVAudioEngine, Metal through Filament, Foundation for the
// language, time zone and version. Compose UI and the Xcode project come later; they create the
// `ZoesHorseFarmApp` with [createIosAppPlatform] and give it the `CAMetalLayer` of their view.

private const val MS_PER_SECOND = 1000.0
private const val SECONDS_PER_MINUTE = 60

/** The drawing surface on iOS: the `CAMetalLayer` of the view that shows the 3D scene. */
class IosSurface(
    val layer: CAMetalLayer,
) : PlatformSurface

private fun IosSurface.toNative(): NativeSurface = NativeSurface(interpretCPointer<COpaque>(layer.objcPtr()))

/** Compiled materials as files in the app's caches directory, so only the first start pays for the compile. */
class IosMaterialCache(
    private val directory: String = defaultDirectory(),
) : MaterialPackageCache {
    init {
        NSFileManager.defaultManager.createDirectoryAtPath(directory, true, null, null)
    }

    private fun pathOf(key: String) = "$directory/$key.material"

    override fun get(key: String): ByteArray? {
        val data = NSData.dataWithContentsOfFile(pathOf(key)) ?: return null
        val bytes = ByteArray(data.length.toInt())
        if (bytes.isNotEmpty()) bytes.usePinned { memcpy(it.addressOf(0), data.bytes, data.length) }
        return bytes
    }

    override fun put(
        key: String,
        data: ByteArray,
    ) {
        val content =
            if (data.isEmpty()) {
                NSData()
            } else {
                data.usePinned { NSData.create(bytes = it.addressOf(0), length = data.size.toULong()) }
            }
        content.writeToFile(pathOf(key), true)
    }

    private companion object {
        fun defaultDirectory(): String {
            val caches = NSSearchPathForDirectoriesInDomains(NSCachesDirectory, NSUserDomainMask, true).firstOrNull()
            return "${caches ?: "."}/zoes-horse-farm-materials"
        }
    }
}

/**
 * The platform of an iPhone or iPad. [debug] switches the debug box of the ride on; [log] gets every
 * error line of the app (default: `println`, which shows in the Xcode console). [inputDevice]: pass
 * `DeviceClass.HYBRID` for an iPad with a keyboard (the shell knows: `GCKeyboard.coalescedKeyboard`); the
 * touch controls then show on the first touch and hide on the first game key. [capTo30Fps] starts the
 * battery lever.
 */
fun createIosAppPlatform(
    debug: Boolean = false,
    log: (String) -> Unit = { println(it) },
    deviceInfo: DeviceInfoSource = SystemDeviceInfo,
    inputDevice: DeviceClass = DeviceClass.TOUCH,
    capTo30Fps: Boolean = false,
): AppPlatform =
    AppPlatform(
        keyValueBackend = UserDefaultsKeyValueBackend(),
        audio = createPlatformAudio(),
        renderBackends =
            FilamentBackendFactory(IosMaterialCache(), log, {
                deviceInfo.read().gpuName
            }) { (it as IosSurface).toNative() },
        deviceInfo = deviceInfo,
        clock = SystemClock,
        timeZone =
            LocalTimeZone { epochMs ->
                val date = NSDate.dateWithTimeIntervalSince1970(epochMs / MS_PER_SECOND)
                (NSTimeZone.localTimeZone.secondsFromGMTForDate(date) / SECONDS_PER_MINUTE).toInt()
            },
        logSink = log,
        preferredLanguages = NSLocale.preferredLanguages.map { it as? String },
        appVersion = NSBundle.mainBundle.objectForInfoDictionaryKey("CFBundleShortVersionString") as? String,
        inputDevice = inputDevice,
        capTo30Fps = capTo30Fps,
        debug = debug,
        shutdown = { FilamentMaterialCompiler.shutdown() },
    )

/** The surface of the 3D view: what the shell passes to `ZoesHorseFarmApp.onSurfaceCreated`. */
fun iosSurfaceOf(layer: CAMetalLayer): PlatformSurface = IosSurface(layer)
