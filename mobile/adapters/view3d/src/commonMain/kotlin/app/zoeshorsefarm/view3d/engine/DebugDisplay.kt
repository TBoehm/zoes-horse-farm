package app.zoeshorsefarm.view3d.engine

import app.zoeshorsefarm.application.GraphicsLevel
import app.zoeshorsefarm.application.LastCrash
import app.zoeshorsefarm.scene.toFixed
import app.zoeshorsefarm.view3d.jsRound

// Debug box of the ride (web: the pure parts of `ui/debug-display.js`): GPU, graphics level, pixel
// ratios, drawing buffer, context losses and the last errors. The text is pure; the box refreshes
// about twice per second like the frame-rate display, so nothing is built per frame. The Compose UI
// only shows [DebugBox.text].

/**
 * The translate function of the UI: key and optional `{name}` parameters to text. Numbers arrive as
 * the text JavaScript would print (whole numbers without a decimal point).
 */
typealias Translate = (key: String, params: Map<String, Any?>?) -> String

private const val ROUND_DIGITS = 2
private const val CRASH_TIME_LENGTH = 16
private const val REFRESH_INTERVAL_S = 0.5
private const val INTERVAL_EPSILON = 1e-9

/** `Number(n.toFixed(2))` printed like JavaScript: "2.63", "1.5", "2". */
private fun roundText(n: Double): String {
    if (!n.isFinite()) return n.toString()
    val text = n.toFixed(ROUND_DIGITS).trimEnd('0').trimEnd('.')
    return if (text == "-0") "0" else text
}

// "2026-10-04T13:05:07.123Z" -> "2026-10-04 13:05" (the zone is part of the translated text)
private fun formatCrashTime(iso: String): String = iso.take(CRASH_TIME_LENGTH).replace('T', ' ')

/** Why the automatic changed the level: the average frame rate, a lost device or a crash. */
private fun changeReason(
    change: LevelChange,
    t: Translate,
): String =
    when (change.kind) {
        ChangeKind.LOSS -> t("debug.reasonLoss", null)
        ChangeKind.CRASH -> t("debug.reasonCrash", null)
        ChangeKind.UP -> t("debug.reasonUp", mapOf("fps" to jsRound(change.fps ?: 0.0)))
        ChangeKind.DOWN -> t("debug.reasonDown", mapOf("fps" to jsRound(change.fps ?: 0.0)))
    }

private fun levelNames(
    levels: List<GraphicsLevel>,
    t: Translate,
): String = levels.joinToString(", ") { t("graphics.${it.id}", null) }

private fun timeAt(
    seconds: Double?,
    t: Translate,
): String = if (seconds == null) t("debug.none", null) else t("debug.atSeconds", mapOf("s" to jsRound(seconds)))

private fun gpuLines(
    info: EngineDiagnostics,
    t: Translate,
): List<String> {
    val gpu = info.gpu.ifEmpty { info.budgetGpu }
    // the memory budget comes from the probe context: show its GPU name when it is another one
    val budgetGpuDiffers = info.gpu.isNotEmpty() && info.budgetGpu.isNotEmpty() && info.budgetGpu != info.gpu
    return buildList {
        add(t("debug.gpu", mapOf("gpu" to gpu.ifEmpty { t("debug.none", null) })))
        if (budgetGpuDiffers) add(t("debug.gpuBudget", mapOf("gpu" to info.budgetGpu)))
    }
}

private fun memoryLine(
    info: EngineDiagnostics,
    t: Translate,
): String {
    val estimate = jsRound(info.gpuEstimateMB)
    val budget = jsRound(info.gpuBudgetMB)
    val cap = info.ratioCap
    // a capped pixel ratio belongs to the same line
    return if (cap != null) {
        t(
            "debug.gpuMemoryCapped",
            mapOf("estimate" to estimate, "budget" to budget, "from" to roundText(cap.from), "to" to roundText(cap.to)),
        )
    } else {
        t("debug.gpuMemory", mapOf("estimate" to estimate, "budget" to budget))
    }
}

private fun deviceLines(
    info: EngineDiagnostics,
    t: Translate,
): List<String> =
    gpuLines(info, t) +
        listOf(
            t(
                if (info.auto) "debug.levelAuto" else "debug.level",
                mapOf("level" to t("graphics.${info.level.id}", null)),
            ),
            t(
                "debug.pixels",
                mapOf("device" to roundText(info.devicePixelRatio), "renderer" to roundText(info.pixelRatio)),
            ),
            t("debug.buffer", mapOf("width" to info.bufferWidth, "height" to info.bufferHeight)),
            t("debug.maxTexture", mapOf("size" to info.maxTextureSize)),
            if (info.antialiasDropped) {
                t("debug.antialiasDropped", null)
            } else {
                t("debug.antialias", mapOf("state" to t(if (info.antialias) "debug.on" else "debug.off", null)))
            },
            memoryLine(info, t),
            t(
                "debug.context",
                mapOf(
                    "lost" to info.contextLost,
                    "restored" to info.contextRestored,
                    "lostAt" to timeAt(info.lostAtS, t),
                    "restoredAt" to timeAt(info.restoredAtS, t),
                ),
            ),
        )

private fun crashLine(
    lastCrash: LastCrash?,
    t: Translate,
): String =
    if (lastCrash != null) {
        t(
            "debug.crash",
            mapOf(
                "level" to t("graphics.${lastCrash.level.id}", null),
                "mode" to t(if (lastCrash.auto) "debug.crashAuto" else "debug.crashManual", null),
                "s" to lastCrash.seconds,
                "at" to formatCrashTime(lastCrash.at),
            ),
        )
    } else {
        t("debug.noCrash", null)
    }

private fun automaticLines(
    info: EngineDiagnostics,
    lastCrash: LastCrash?,
    t: Translate,
): List<String> =
    buildList {
        add(crashLine(lastCrash, t))
        add(
            if (info.blockedLevels.isNotEmpty()) {
                t("debug.blocked", mapOf("levels" to levelNames(info.blockedLevels, t)))
            } else {
                t("debug.noBlocked", null)
            },
        )
        if (info.leftLevels.isNotEmpty()) add(t("debug.left", mapOf("levels" to levelNames(info.leftLevels, t))))
        val change = info.lastChange
        add(
            if (change != null) {
                t("debug.lastChange", mapOf("reason" to changeReason(change, t)))
            } else {
                t("debug.noChange", null)
            },
        )
    }

private fun capLines(
    info: EngineDiagnostics,
    t: Translate,
): List<String> =
    buildList {
        info.shadowCap?.let { add(t("debug.capShadow", mapOf("from" to it.from, "to" to it.to))) }
        if (info.sceneryCapped) add(t("debug.capScenery", null))
        if (info.stagesPending > 0) add(t("debug.stages", mapOf("count" to info.stagesPending)))
    }

private fun errorLines(
    errors: List<DebugError>,
    t: Translate,
): List<String> {
    if (errors.isEmpty()) return listOf(t("debug.noErrors", null))
    return buildList {
        add(t("debug.errors", mapOf("count" to errors.size)))
        // newest first
        for (i in errors.indices.reversed()) {
            add(t("debug.error", mapOf("s" to jsRound(errors[i].atS), "message" to errors[i].message)))
        }
    }
}

/**
 * Text of the box. [info] is [Engine.diagnostics], [errors] the entries of the error log (oldest
 * first). Every word comes from [t] (keys `debug.*`); only numbers and the technical strings (GPU
 * name, error messages) are inserted. [lastCrash] is the unexpected end of an earlier run that the
 * crash guard detected at the start, or null. [version] is the build version (rule 58), shown as
 * the first line when given.
 */
fun formatDebugText(
    info: EngineDiagnostics,
    errors: List<DebugError>,
    t: Translate,
    lastCrash: LastCrash? = null,
    version: String? = null,
): String {
    val lines = ArrayList<String>()
    if (version != null) lines.add(t("debug.version", mapOf("version" to version)))
    lines.addAll(deviceLines(info, t))
    lines.addAll(automaticLines(info, lastCrash, t))
    lines.addAll(capLines(info, t))
    lines.addAll(errorLines(errors, t))
    return lines.joinToString("\n")
}

/**
 * The box: [text] goes into the ride HUD, [frame] is called every frame with the real frame time
 * and re-reads the engine about twice per second, [renderTexts] after a language change.
 * [diagnostics] is `engine::diagnostics`, [errors] the entries of the error log (oldest first).
 */
class DebugBox(
    private val diagnostics: () -> EngineDiagnostics,
    private val errors: () -> List<DebugError>,
    private val t: Translate,
    private val lastCrash: LastCrash? = null,
    private val version: String? = null,
) {
    /** The text to show; never empty (the first one is built by the constructor). */
    var text: String = render()
        private set

    private var elapsed = 0.0

    private fun render(): String = formatDebugText(diagnostics(), errors(), t, lastCrash, version)

    /** Redraws after a language change. */
    fun renderTexts() {
        text = render()
    }

    /** Counts a frame of [rawDt] seconds; returns true when [text] was refreshed. Invalid times are ignored. */
    fun frame(rawDt: Double): Boolean {
        if (!(rawDt > 0) || !rawDt.isFinite()) return false
        elapsed += rawDt
        if (elapsed < REFRESH_INTERVAL_S - INTERVAL_EPSILON) return false
        elapsed = 0.0
        renderTexts()
        return true
    }
}
