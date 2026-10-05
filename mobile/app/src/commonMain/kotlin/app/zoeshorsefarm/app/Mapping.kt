package app.zoeshorsefarm.app

import app.zoeshorsefarm.application.GraphicsLevel
import app.zoeshorsefarm.application.LevelDecision
import app.zoeshorsefarm.platform.DeviceInfo
import app.zoeshorsefarm.view3d.quality.levelAfterContextLoss
import app.zoeshorsefarm.view3d.quality.DeviceInfo as QualityDeviceInfo

// Small conversions between the platform adapter, the application layer and the view module.

private const val BYTES_PER_GIB = 1024.0 * 1024.0 * 1024.0

/** The platform's device facts in the form the graphics budget reads (memory in GiB, `isTouch`, GPU name). */
internal fun DeviceInfo.toQualityDeviceInfo(): QualityDeviceInfo =
    QualityDeviceInfo(
        totalMemoryGiB = totalMemoryBytes?.let { it / BYTES_PER_GIB },
        cores = cores,
        isTouch = touch,
        rendererName = gpuName,
        screenWidthPx = screenWidthPx,
        screenHeightPx = screenHeightPx,
    )

/**
 * What the crash guard does with an unexpected end while drawing: the rule of a lost graphics device in
 * the foreground (web: `levelAfterContextLoss` given to `createCrashGuard`).
 */
internal fun decideAfterCrash(
    auto: Boolean,
    level: GraphicsLevel,
): LevelDecision {
    val outcome = levelAfterContextLoss(auto, level)
    return LevelDecision(outcome.level, outcome.persist, outcome.hint)
}
