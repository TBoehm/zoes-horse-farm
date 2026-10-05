package app.zoeshorsefarm.view3d.quality

// Staged quality changes (rule 4): a change of the graphics level during a ride is split into
// small steps that are applied one after the other, a few frames apart, each followed by an async
// shader precompile. Doing everything in one frame (all materials rebuilt, shadow map and drawing
// buffer reallocated, textures uploaded again) can overload the GPU process of a phone or tablet,
// and the system then takes the 3D context away. Pure: the planner and the pacing are tested; the
// world and the engine apply the steps.

/**
 * Frames between two stages. A technical value (no game play): a few frames give the GPU process
 * time to finish the work of the last stage (uploads, shader links) before the next one starts.
 */
const val STAGE_GAP_FRAMES = 6

/** The stages, in the order of a downgrade (cheapest lever for the GPU first). */
enum class QualityStageId(
    val id: String,
) {
    /**
     * Dynamic resolution: the first and biggest lever, no shader involved. The engine applies it
     * in the frame that draws again (resizing clears the drawing buffer).
     */
    PIXEL_RATIO("pixelRatio"),

    /**
     * Shadow pass, shadow map target (2048^2 depth texture on "high"), shadow defines of the
     * shaders. When the materials stage is needed as well, both are applied as one stage
     * ([MERGED_STAGE_ID]).
     */
    SHADOWS("shadows"),

    /**
     * Material type (Lambert / standard), normal maps, fog, environment map and the wind code of
     * the scenery: all of these change the shader programs, so they are one stage with one compile
     * (separate stages would compile intermediate programs that are replaced right away).
     */
    MATERIALS("materials"),

    /** Geometry and material of horse and rider. */
    CHARACTERS("characters"),

    /**
     * Instance counts and geometry detail of the scenery, its details (flowers, bunting, flower
     * boxes, grazing horses, hoof dust, birds, butterflies) and the fog distance (a uniform).
     * Details that appear for the first time bring shader programs with them (flowers, wings,
     * bunting, dust), so the engine compiles after this stage, too.
     */
    DENSITY("density"),
}

/** Ids of all stages, in the order of a downgrade. */
val QUALITY_STAGE_IDS: List<QualityStageId> = QualityStageId.entries

/**
 * One step of a plan. [id] is the stage id ([QualityStageId.id]) or [MERGED_STAGE_ID]; [compile]:
 * the stage changes shaders, so the engine precompiles them before the next stage; [covers]: the
 * stages the step stands for (the applied level is recorded for each of them).
 */
data class QualityStage(
    val id: String,
    val compile: Boolean,
    val covers: List<QualityStageId>,
)

/**
 * The stage that does the shadows and the materials stage in one go. Both of them change the
 * shader programs of everything visible (shadow defines, material type), so two stages would
 * compile a set of programs that the second one throws away at once: a waste of GPU time and, at
 * the peak, of GPU memory (rule 4). Used only when both are needed.
 */
const val MERGED_STAGE_ID = "shadowsAndMaterials"

private class StageSpec(
    val stage: QualityStageId,
    val compile: Boolean,
    /** Reduces a preset to the values the stage is responsible for: the stage is needed when they differ. */
    val key: (QualityPreset) -> Any,
)

private fun fogKey(p: QualityPreset): Any = p.fog ?: "none"

private fun materialsKey(p: QualityPreset): Any = listOf(p.material, p.normalMaps, p.fog != null, p.envMap, p.wind)

private val STAGES: List<StageSpec> =
    listOf(
        StageSpec(QualityStageId.PIXEL_RATIO, compile = false) { it.pixelRatio },
        StageSpec(QualityStageId.SHADOWS, compile = true) { listOf(it.shadows, it.shadowMapSize, it.shadowCasters) },
        StageSpec(QualityStageId.MATERIALS, compile = true, ::materialsKey),
        StageSpec(QualityStageId.CHARACTERS, compile = true) { it.characterDetail },
        StageSpec(QualityStageId.DENSITY, compile = true) {
            listOf(
                it.envDensity,
                it.envDetail,
                it.grassTufts,
                it.flowers,
                it.decor,
                it.grazingHorses,
                it.hoofDust,
                it.birds,
                it.butterflies,
                it.planters,
                fogKey(it),
            )
        },
    )

// A level change never re-uploads textures: there is deliberately no anisotropy stage. Changing the
// anisotropy of a texture means uploading it again with a new mipmap chain (ground, sand and wood
// textures). The world applies the anisotropy of the level when a ride starts and never in the
// middle of one.

/**
 * Do two presets agree on everything the materials stage is responsible for? While they do not
 * (a level change between its materials and its density stage), the shader programs of the visible
 * meshes are about to change, so nothing optional should be shown (see the detail hold of the
 * world).
 */
fun sameMaterialStage(
    a: QualityPreset,
    b: QualityPreset,
): Boolean =
    a.material == b.material &&
        a.normalMaps == b.normalMaps &&
        (a.fog != null) == (b.fog != null) &&
        a.envMap == b.envMap &&
        a.wind == b.wind

private val DESCRIPTORS: Map<QualityStageId, QualityStage> =
    STAGES.associate { s -> s.stage to QualityStage(s.stage.id, s.compile, listOf(s.stage)) }

private val MERGED =
    QualityStage(MERGED_STAGE_ID, compile = true, covers = listOf(QualityStageId.SHADOWS, QualityStageId.MATERIALS))

// A downgrade steps down the resolution first; a climb ends with it (the most expensive step last)
private val UP_ORDER: List<QualityStageId> = QUALITY_STAGE_IDS.reversed()

/**
 * Stages that are still needed to reach [to], when every stage may be at a different preset
 * ([applied]: stage id to the preset it was last applied at; an interrupted switch leaves some
 * stages behind, a missing entry counts as not applied yet). The order is the downgrade order, or
 * its reverse when every stage that has to change goes up. A fitted preset is a copy that keeps its
 * level, so it plans like its level. Returns nothing for a null target.
 */
fun planQualityStagesFromState(
    applied: Map<QualityStageId, QualityPreset?>,
    to: QualityPreset?,
): List<QualityStage> {
    if (to == null) return emptyList()
    val needed =
        STAGES
            .filter { spec -> applied[spec.stage].let { it == null || spec.key(it) != spec.key(to) } }
            .map { it.stage }
            .toSet()
    // up when every stage that has to change is behind the target (a stage never applied counts as not)
    val goingUp = needed.all { id -> applied[id]?.let { it.level.ordinal < to.level.ordinal } == true }
    val merge = QualityStageId.SHADOWS in needed && QualityStageId.MATERIALS in needed
    var mergedPlanned = false
    return (if (goingUp) UP_ORDER else QUALITY_STAGE_IDS)
        .filter { it in needed }
        .mapNotNull { id ->
            if (merge && id in MERGED.covers) {
                // at the place of the first of the two
                if (mergedPlanned) null else MERGED.also { mergedPlanned = true }
            } else {
                DESCRIPTORS.getValue(id)
            }
        }
}

/**
 * Paces the stages: [tick] is called once per drawn frame and returns the next stage when at least
 * `gapFrames` frames have passed since the last one (the first stage of a plan on an idle queue
 * comes on the next frame), otherwise null.
 */
class StageQueue(
    private val gapFrames: Int = STAGE_GAP_FRAMES,
) {
    private var list: List<QualityStage> = emptyList()
    private var head = 0
    private var frames = gapFrames // frames since the last stage (capped): a fresh queue is ready

    /** Number of stages still to come. */
    val pending: Int get() = list.size - head

    /** Replaces the pending stages; the gap since the last stage keeps running. */
    fun plan(stages: List<QualityStage>) {
        list = stages.toList()
        head = 0
    }

    fun tick(): QualityStage? {
        frames = minOf(frames + 1, gapFrames)
        if (head >= list.size || frames < gapFrames) return null
        frames = 0
        val stage = list[head]
        head += 1
        return stage
    }

    fun clear() {
        list = emptyList()
        head = 0
    }
}
