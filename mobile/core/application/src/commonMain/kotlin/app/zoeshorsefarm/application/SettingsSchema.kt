package app.zoeshorsefarm.application

import app.zoeshorsefarm.domain.horse.COATS
import app.zoeshorsefarm.domain.horse.Coat
import app.zoeshorsefarm.domain.horse.DEFAULT_APPEARANCE
import app.zoeshorsefarm.domain.horse.MARKINGS
import app.zoeshorsefarm.domain.horse.Marking
import app.zoeshorsefarm.domain.horse.cleanName
import app.zoeshorsefarm.domain.progress.Progress
import app.zoeshorsefarm.domain.progress.sanitizeProgress

// Save-game sections and settings fields for riding, jumping, the horse, progress and sound
// (SRT-002 to SRT-006).

/** Camera modes the player can choose. */
enum class CameraMode(
    val id: String,
) {
    FOLLOW("follow"),
    RIDER("rider"),
    ;

    companion object {
        fun fromId(id: String?): CameraMode? = entries.firstOrNull { it.id == id }
    }
}

val CAMERA_MODES: List<CameraMode> = CameraMode.entries

/** Volume of a sound channel on a new save game. */
const val DEFAULT_VOLUME = 0.5

/**
 * The settings: the only writer is the settings service. [unknown] keeps the fields of a newer
 * save game unchanged.
 */
data class Settings(
    val lang: Language = Language.EN,
    val graphicsAuto: Boolean = true,
    // first start: the automatic begins at the lowest level and works its way up (rule 4); the level
    // it reached is saved and applies at the next start
    val graphicsLevel: GraphicsLevel = AUTO_START_LEVEL,
    val camera: CameraMode = CameraMode.FOLLOW,
    val aidFree: Boolean = true,
    val aidCourse: Boolean = false,
    // fps counter in the ride (rule 4): off by default
    val showFps: Boolean = false,
    // the controls help was closed with "Got it" at least once (rule 56)
    val controlsHelpSeen: Boolean = false,
    val musicVolume: Double = DEFAULT_VOLUME,
    val musicMuted: Boolean = false,
    val sfxVolume: Double = DEFAULT_VOLUME,
    val sfxMuted: Boolean = false,
    val unknown: Map<String, Any?> = emptyMap(),
)

/** The settings fields in save order (the field specs behind [SettingsSection]). */
val SETTINGS_FIELDS: Map<String, FieldSpec> =
    linkedMapOf(
        "lang" to FieldSpec({ env -> (env.defaultLang ?: Language.EN).id }, { v -> LANGS.any { it.id == v } }),
        "graphicsAuto" to Field.bool(true),
        "graphicsLevel" to Field.oneOf(GRAPHICS_LEVELS.map { it.id }, AUTO_START_LEVEL.id),
        "camera" to Field.oneOf(CAMERA_MODES.map { it.id }, CameraMode.FOLLOW.id),
        "aidFree" to Field.bool(true),
        "aidCourse" to Field.bool(false),
        "showFps" to Field.bool(false),
        "controlsHelpSeen" to Field.bool(false),
        "musicVolume" to Field.number(0.0, 1.0, DEFAULT_VOLUME),
        "musicMuted" to Field.bool(false),
        "sfxVolume" to Field.number(0.0, 1.0, DEFAULT_VOLUME),
        "sfxMuted" to Field.bool(false),
    )

private fun Map<String, Any?>.bool(key: String): Boolean = this[key] as? Boolean ?: false

private fun Map<String, Any?>.double(key: String): Double = (this[key] as? Number)?.toDouble() ?: DEFAULT_VOLUME

/** The `settings` section. */
object SettingsSection : Section<Settings> {
    override val name = "settings"
    private val schema = ObjectSection(SETTINGS_FIELDS)

    override fun defaults(env: SaveEnv): Settings = sanitize(null, env)

    override fun sanitize(
        raw: Any?,
        env: SaveEnv,
    ): Settings {
        // every known field is valid after the object section, the elvis branches never apply
        val tree = schema.sanitize(raw, env)
        return Settings(
            lang = Language.fromId(tree["lang"] as? String) ?: Language.EN,
            graphicsAuto = tree.bool("graphicsAuto"),
            graphicsLevel = GraphicsLevel.fromId(tree["graphicsLevel"] as? String) ?: AUTO_START_LEVEL,
            camera = CameraMode.fromId(tree["camera"] as? String) ?: CameraMode.FOLLOW,
            aidFree = tree.bool("aidFree"),
            aidCourse = tree.bool("aidCourse"),
            showFps = tree.bool("showFps"),
            controlsHelpSeen = tree.bool("controlsHelpSeen"),
            musicVolume = tree.double("musicVolume"),
            musicMuted = tree.bool("musicMuted"),
            sfxVolume = tree.double("sfxVolume"),
            sfxMuted = tree.bool("sfxMuted"),
            unknown = tree.filterKeys { it !in SETTINGS_FIELDS },
        )
    }

    override fun toTree(value: Settings): Map<String, Any?> {
        val tree = LinkedHashMap<String, Any?>(value.unknown)
        tree["lang"] = value.lang.id
        tree["graphicsAuto"] = value.graphicsAuto
        tree["graphicsLevel"] = value.graphicsLevel.id
        tree["camera"] = value.camera.id
        tree["aidFree"] = value.aidFree
        tree["aidCourse"] = value.aidCourse
        tree["showFps"] = value.showFps
        tree["controlsHelpSeen"] = value.controlsHelpSeen
        tree["musicVolume"] = value.musicVolume
        tree["musicMuted"] = value.musicMuted
        tree["sfxVolume"] = value.sfxVolume
        tree["sfxMuted"] = value.sfxMuted
        return tree
    }
}

/**
 * The horse of the player: appearance and name. [name] null = no custom name (the language
 * default name applies, rule 43).
 */
data class HorseProfile(
    val coat: Coat = DEFAULT_APPEARANCE.coat,
    val marking: Marking = DEFAULT_APPEARANCE.marking,
    val name: String? = null,
    val nameAnswered: Boolean = false,
    val unknown: Map<String, Any?> = emptyMap(),
)

private val HORSE_FIELDS: Map<String, FieldSpec> =
    linkedMapOf(
        "coat" to Field.oneOf(COATS.map { it.id }, DEFAULT_APPEARANCE.coat.id),
        "marking" to Field.oneOf(MARKINGS.map { it.id }, DEFAULT_APPEARANCE.marking.id),
        "name" to FieldSpec.constant(null) { v -> v == null || (v is String && cleanName(v) == v) },
        "nameAnswered" to Field.bool(false),
    )

/** The `horse` section. */
object HorseSection : Section<HorseProfile> {
    override val name = "horse"
    private val schema = ObjectSection(HORSE_FIELDS)

    override fun defaults(env: SaveEnv): HorseProfile = sanitize(null, env)

    override fun sanitize(
        raw: Any?,
        env: SaveEnv,
    ): HorseProfile {
        val tree = schema.sanitize(raw, env)
        return HorseProfile(
            coat = Coat.fromId(tree["coat"] as? String) ?: DEFAULT_APPEARANCE.coat,
            marking = Marking.fromId(tree["marking"] as? String) ?: DEFAULT_APPEARANCE.marking,
            name = tree["name"] as? String,
            nameAnswered = tree.bool("nameAnswered"),
            unknown = tree.filterKeys { it !in HORSE_FIELDS },
        )
    }

    override fun toTree(value: HorseProfile): Map<String, Any?> {
        val tree = LinkedHashMap<String, Any?>(value.unknown)
        tree["coat"] = value.coat.id
        tree["marking"] = value.marking.id
        tree["name"] = value.name
        tree["nameAnswered"] = value.nameAnswered
        return tree
    }
}

/** The `progress` section: the domain progress with its own sanitizer. */
object ProgressSection : Section<Progress> {
    override val name = "progress"

    override fun defaults(env: SaveEnv): Progress = Progress()

    override fun sanitize(
        raw: Any?,
        env: SaveEnv,
    ): Progress = sanitizeProgress(raw)

    override fun toTree(value: Progress): Map<String, Any?> = value.toTree()
}

/**
 * Every section of the save game, in save order. A storage adapter keeps what it does not know
 * (other sections, other fields) untouched.
 */
val SAVE_SECTIONS: List<Section<*>> = listOf(SettingsSection, HorseSection, ProgressSection, CrashGuardSection)
