package app.zoeshorsefarm.application

import kotlin.math.min

// The `crashGuard` save section (rule 4): what the unclean-exit guard keeps in the save game.

// Technical value (no game play): the largest time a saved timestamp may have (JS MAX_SAFE_INTEGER).
private const val MAX_TIME = 9_007_199_254_740_991.0

/** The remembered crash shown by the debug display: all fields or none. [extra] = unknown fields. */
data class LastCrash(
    val level: GraphicsLevel,
    val auto: Boolean,
    val seconds: Int,
    val at: String,
    val extra: Map<String, Any?> = emptyMap(),
) {
    fun toTree(): Map<String, Any?> {
        val tree = LinkedHashMap<String, Any?>(extra)
        tree["level"] = level.id
        tree["auto"] = auto
        tree["seconds"] = seconds
        tree["at"] = at
        return tree
    }
}

/**
 * The guard state. [rendering] is the mark "a screen draws the 3D scene"; [level]/[auto] the
 * graphics settings at that time; [since]/[lastSeen] ms timestamps of the mark start and the last
 * heartbeat; [tabId] the tab that set the mark (null: unknown); [hintPending] a manual level above
 * low crashed (tell the player once); [blockedLevels] levels that crashed or lost the 3D picture on
 * this device (low to high); [lastCrash] for the debug display.
 */
data class CrashGuardState(
    val rendering: Boolean = false,
    val level: GraphicsLevel? = null,
    val auto: Boolean = true,
    val since: Long = 0,
    val lastSeen: Long = 0,
    val tabId: String? = null,
    val hintPending: Boolean = false,
    val blockedLevels: List<GraphicsLevel> = emptyList(),
    val lastCrash: LastCrash? = null,
    val unknown: Map<String, Any?> = emptyMap(),
)

private const val TAB_ID_MAX_LENGTH = 64

private fun levelOf(value: Any?): GraphicsLevel? = GraphicsLevel.fromId(value as? String)

private fun finiteNumber(value: Any?): Double? = (value as? Number)?.toDouble()?.takeIf { it.isFinite() }

private fun lastCrashOf(value: Any?): LastCrash? {
    val map = value as? Map<*, *> ?: return null
    val seconds = finiteNumber(map["seconds"])?.takeIf { it >= 0 }
    return levelOf(map["level"])?.let { level ->
        (map["auto"] as? Boolean)?.let { auto ->
            (map["at"] as? String)?.let { at ->
                seconds?.let {
                    val extra = treeEntries(map).filterKeys { key -> key !in LAST_CRASH_KEYS }
                    // whole seconds: a fraction is cut off (JS keeps it), the value is capped at Int.MAX_VALUE
                    LastCrash(level, auto, min(it, Int.MAX_VALUE.toDouble()).toInt(), at, extra)
                }
            }
        }
    }
}

private val LAST_CRASH_KEYS = setOf("level", "auto", "seconds", "at")

private fun isBlockedLevels(value: Any?): Boolean =
    value is List<*> && value.all { levelOf(it) != null } && value.toSet().size == value.size

private val CRASH_GUARD_FIELDS: Map<String, FieldSpec> =
    linkedMapOf(
        "rendering" to Field.bool(false),
        "level" to Field.oneOf(GRAPHICS_LEVELS.map { it.id } + listOf(null), null),
        "auto" to Field.bool(true),
        "since" to Field.number(0.0, MAX_TIME, 0.0),
        "lastSeen" to Field.number(0.0, MAX_TIME, 0.0),
        "tabId" to
            FieldSpec.constant(null) { v -> v == null || (v is String && v.length <= TAB_ID_MAX_LENGTH) },
        "hintPending" to Field.bool(false),
        "blockedLevels" to FieldSpec.constant(emptyList<String>(), ::isBlockedLevels),
        "lastCrash" to FieldSpec.constant(null) { v -> v == null || lastCrashOf(v) != null },
    )

/** The `crashGuard` section. */
object CrashGuardSection : Section<CrashGuardState> {
    override val name = "crashGuard"
    private val schema = ObjectSection(CRASH_GUARD_FIELDS)

    override fun defaults(env: SaveEnv): CrashGuardState = sanitize(null, env)

    override fun sanitize(
        raw: Any?,
        env: SaveEnv,
    ): CrashGuardState {
        val tree = schema.sanitize(raw, env)
        return CrashGuardState(
            rendering = tree["rendering"] as? Boolean ?: false,
            level = levelOf(tree["level"]),
            auto = tree["auto"] as? Boolean ?: true,
            // milliseconds since 1970: a fraction (JS allows one) is cut off by toLong()
            since = (tree["since"] as? Number)?.toLong() ?: 0,
            lastSeen = (tree["lastSeen"] as? Number)?.toLong() ?: 0,
            tabId = tree["tabId"] as? String,
            hintPending = tree["hintPending"] as? Boolean ?: false,
            blockedLevels = (tree["blockedLevels"] as? List<*>)?.mapNotNull { levelOf(it) } ?: emptyList(),
            lastCrash = lastCrashOf(tree["lastCrash"]),
            unknown = tree.filterKeys { it !in CRASH_GUARD_FIELDS },
        )
    }

    override fun toTree(value: CrashGuardState): Map<String, Any?> {
        val tree = LinkedHashMap<String, Any?>(value.unknown)
        tree["rendering"] = value.rendering
        tree["level"] = value.level?.id
        tree["auto"] = value.auto
        tree["since"] = value.since
        tree["lastSeen"] = value.lastSeen
        tree["tabId"] = value.tabId
        tree["hintPending"] = value.hintPending
        tree["blockedLevels"] = value.blockedLevels.map { it.id }
        tree["lastCrash"] = value.lastCrash?.toTree()
        return tree
    }
}

/**
 * Adds a level to a set of blocked levels (levels that crashed or lost the 3D picture on this
 * device): unique, ordered low to high. A null level is ignored. Returns a new list.
 */
fun addBlockedLevel(
    blocked: List<GraphicsLevel>,
    level: GraphicsLevel?,
): List<GraphicsLevel> {
    val set = blocked.toSet() + listOfNotNull(level)
    return GRAPHICS_LEVELS.filter { it in set }
}
