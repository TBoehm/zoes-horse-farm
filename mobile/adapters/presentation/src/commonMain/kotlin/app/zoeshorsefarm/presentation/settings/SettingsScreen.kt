package app.zoeshorsefarm.presentation.settings

import app.zoeshorsefarm.application.AidKind
import app.zoeshorsefarm.application.GRAPHICS_LEVELS
import app.zoeshorsefarm.application.GraphicsLevel
import app.zoeshorsefarm.application.LANGS
import app.zoeshorsefarm.application.Language
import app.zoeshorsefarm.application.SoundChannel
import app.zoeshorsefarm.presentation.AppContext
import app.zoeshorsefarm.presentation.Changes
import app.zoeshorsefarm.presentation.nav.Route
import app.zoeshorsefarm.presentation.nav.ScreenModel
import app.zoeshorsefarm.presentation.profile.ResetSectionModel

// Settings with sections in a fixed order (web: `settings-screen.js`, `settings-sections.js`, the
// volume section of `audio-wiring.js` and the reset section of the profile area).

private const val ORDER_LANGUAGE = 10
private const val ORDER_GRAPHICS = 20
private const val ORDER_AID = 30
private const val ORDER_AUDIO = 40
private const val ORDER_RESET = 90
private const val ORDER_VERSION = 99

/** The id of the "Automatic" graphics option next to the level ids. */
const val GRAPHICS_AUTO_ID = "auto"

/** A section of the settings screen; the sections are shown in [order]. */
sealed interface SettingsBlock {
    val id: String
    val order: Int

    /** Rows of one section. */
    class Rows(
        override val id: String,
        override val order: Int,
        val rows: List<SettingsRow>,
    ) : SettingsBlock

    /** The "Delete progress" section. */
    class Reset(
        override val id: String,
        override val order: Int,
        val model: ResetSectionModel,
    ) : SettingsBlock

    /** The subtle build version line at the bottom. */
    class Version(
        override val id: String,
        override val order: Int,
        val text: String,
    ) : SettingsBlock
}

/**
 * The settings screen. [fromPause]: opened from the pause menu (rules 38, 48, 51): back pops to the
 * pause menu, no melody, no "Delete progress". Every write goes through the settings service.
 */
class SettingsScreenModel(
    private val ctx: AppContext,
    private val fromPause: Boolean = false,
) : ScreenModel {
    /** Changes when a setting changes (also from outside, e.g. the graphics governor). */
    val changes = Changes()
    private val stopListening = ctx.settings.onChange { changes.fire() }

    /** The melody plays only when opened from the main menu (rule 51). */
    override val music: Boolean = !fromPause

    val title: String get() = ctx.t("settings.title")
    val backLabel: String get() = ctx.t("common.back")

    val sections: List<SettingsBlock> =
        buildList {
            add(SettingsBlock.Rows("language", ORDER_LANGUAGE, listOf(languageRow())))
            add(SettingsBlock.Rows("graphics", ORDER_GRAPHICS, listOf(graphicsRow(), fpsRow())))
            add(
                SettingsBlock.Rows(
                    "aid",
                    ORDER_AID,
                    listOf(aidRow("aidFree", AidKind.FREE), aidRow("aidCourse", AidKind.COURSE)),
                ),
            )
            add(
                SettingsBlock.Rows(
                    "audio",
                    ORDER_AUDIO,
                    SoundChannel.entries.map { VolumeRow(it, ctx.settings, ctx::t) },
                ),
            )
            if (!fromPause) add(SettingsBlock.Reset("reset", ORDER_RESET, ResetSectionModel(ctx)))
            add(
                SettingsBlock.Version(
                    "version",
                    ORDER_VERSION,
                    ctx.t("settings.version", mapOf("version" to ctx.version)),
                ),
            )
        }.sortedBy { it.order }

    private fun languageRow() =
        ChoiceRow(
            name = "lang",
            labelText = { ctx.t("settings.language") },
            options = LANGS.map { lang -> ChoiceOption(lang.id) { ctx.t("lang.${lang.id}") } },
            current = {
                ctx.settings
                    .get()
                    .lang.id
            },
            wide = true,
            onSelect = { id ->
                Language.fromId(id)?.let { lang ->
                    ctx.settings.setLang(lang)
                    ctx.i18n.setLang(lang)
                }
            },
        )

    private fun graphicsRow() =
        ChoiceRow(
            name = "graphics",
            labelText = { ctx.t("settings.graphics") },
            options =
                (listOf(GRAPHICS_AUTO_ID) + GRAPHICS_LEVELS.map { it.id }).map { id ->
                    ChoiceOption(id) { ctx.t("graphics.$id") }
                },
            current = {
                val s = ctx.settings.get()
                if (s.graphicsAuto) GRAPHICS_AUTO_ID else s.graphicsLevel.id
            },
            onSelect = { id ->
                // "Automatic" starts at low again and climbs from there (rule 4)
                if (id == GRAPHICS_AUTO_ID) {
                    ctx.settings.setGraphicsAuto()
                } else {
                    GraphicsLevel.fromId(id)?.let(ctx.settings::setGraphicsLevel)
                }
            },
        )

    // frame-rate display in the ride (rules 4, 44), a row of its own so that the two jump-aid switches stay a pair
    private fun fpsRow() =
        ToggleRow(
            name = "showFps",
            labelText = { ctx.t("settings.showFps") },
            state = { ctx.settings.get().showFps },
            wide = true,
            onChange = ctx.settings::setShowFps,
        )

    private fun aidRow(
        key: String,
        kind: AidKind,
    ) = ToggleRow(
        name = key,
        labelText = { ctx.t("settings.$key") },
        state = {
            val s = ctx.settings.get()
            if (kind == AidKind.FREE) s.aidFree else s.aidCourse
        },
        onChange = { ctx.settings.setAid(kind, it) },
    )

    /** Back to the main menu, or to the paused ride. */
    fun back() {
        if (fromPause) ctx.navigator.pop() else ctx.navigator.go(Route.Menu)
    }

    override fun destroy() {
        stopListening()
        changes.clear()
    }
}
