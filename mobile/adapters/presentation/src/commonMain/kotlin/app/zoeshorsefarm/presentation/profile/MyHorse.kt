package app.zoeshorsefarm.presentation.profile

import app.zoeshorsefarm.application.HorseSection
import app.zoeshorsefarm.application.appearanceOptions
import app.zoeshorsefarm.application.displayName
import app.zoeshorsefarm.application.isValidName
import app.zoeshorsefarm.application.rename
import app.zoeshorsefarm.application.setAppearance
import app.zoeshorsefarm.domain.horse.Appearance
import app.zoeshorsefarm.domain.horse.Coat
import app.zoeshorsefarm.domain.horse.Marking
import app.zoeshorsefarm.presentation.AppContext
import app.zoeshorsefarm.presentation.Changes
import app.zoeshorsefarm.presentation.nav.Route
import app.zoeshorsefarm.presentation.nav.ScreenModel
import app.zoeshorsefarm.presentation.settings.ChoiceOption
import app.zoeshorsefarm.presentation.settings.ChoiceRow
import kotlin.math.cos
import kotlin.math.sin

/** The 3D horse of the "My horse" screen (implemented by the view adapter). */
fun interface HorsePreview {
    /** Coat or marking changed: dress the preview horse. */
    fun setAppearance(appearance: Appearance)
}

/**
 * "My horse": name, coat colour and head marking with a 3D preview (rule 43). An invalid name is not
 * saved, the last valid name stays. Picking a coat or marking saves it at once.
 */
class MyHorseModel(
    private val ctx: AppContext,
    private val preview: HorsePreview = HorsePreview { },
) : ScreenModel {
    val changes = Changes()

    override val music = true

    /** What is in the name field (may be invalid while typing). */
    var nameText: String = displayName(ctx.store.get(HorseSection), ctx.t("horse.defaultName"))
        private set

    /** False while the typed name is invalid: the hint shows in red. */
    var nameValid: Boolean = true
        private set

    val title: String get() = ctx.t("myHorse.title")
    val nameLabel: String get() = ctx.t("myHorse.name")
    val nameHint: String get() = ctx.t("namePrompt.hint")
    val backLabel: String get() = ctx.t("common.back")

    /** The saved look of the horse (the preview starts with it). */
    val appearance: Appearance
        get() = ctx.store.get(HorseSection).let { Appearance(it.coat, it.marking) }

    private val options = appearanceOptions()
    private val horse get() = ctx.store.get(HorseSection)

    val coat =
        ChoiceRow(
            name = "coat",
            labelText = { ctx.t("myHorse.coat") },
            options = options.coats.map { c -> ChoiceOption(c.id) { ctx.t("coat.${c.id}") } },
            current = { horse.coat.id },
            onSelect = { id -> Coat.fromId(id)?.let { choose(coat = it) } },
        )

    val marking =
        ChoiceRow(
            name = "marking",
            labelText = { ctx.t("myHorse.marking") },
            options = options.markings.map { m -> ChoiceOption(m.id) { ctx.t("marking.${m.id}") } },
            current = { horse.marking.id },
            onSelect = { id -> Marking.fromId(id)?.let { choose(marking = it) } },
        )

    private fun choose(
        coat: Coat? = null,
        marking: Marking? = null,
    ) {
        preview.setAppearance(setAppearance(ctx.store, coat, marking))
        changes.fire()
    }

    /** The name field changed: a valid name is saved at once, an invalid one only shows the hint. */
    fun onNameInput(text: String) {
        nameText = text
        rename(ctx.store, text, defaultName = ctx.t("horse.defaultName"))
        nameValid = isValidName(text)
        changes.fire()
    }

    /** The field lost the focus: it shows the saved name again. */
    fun onNameBlur() {
        nameText = displayName(ctx.store.get(HorseSection), ctx.t("horse.defaultName"))
        nameValid = true
        changes.fire()
    }

    fun back() = ctx.navigator.go(Route.Menu)

    override fun destroy() = changes.clear()
}

private const val PREVIEW_START_ANGLE = 0.9
private const val PREVIEW_SPEED = 0.25
private const val PREVIEW_RADIUS = 5.2
private const val PREVIEW_HEIGHT = 1.9
private const val PREVIEW_TARGET_Y = 1.15
private const val PANEL_GAP = 16.0

/**
 * The camera of the preview: it circles slowly around the horse that stands in the arena (web:
 * the frame function of `my-horse-screen.js`). The view adapter reads the position every frame.
 */
class HorsePreviewCamera {
    var angle: Double = PREVIEW_START_ANGLE
        private set

    val x: Double get() = sin(angle) * PREVIEW_RADIUS
    val y: Double get() = PREVIEW_HEIGHT
    val z: Double get() = cos(angle) * PREVIEW_RADIUS

    // looks at the horse's chest
    val targetX: Double get() = 0.0
    val targetY: Double get() = PREVIEW_TARGET_Y
    val targetZ: Double get() = 0.0

    fun advance(dt: Double) {
        angle += dt * PREVIEW_SPEED
    }

    companion object {
        /**
         * Horizontal shift of the picture in pixels: in landscape the horse stays in the free area
         * right of the panel (web: `camera.setViewOffset(.., -shift, ..)`), in portrait there is none.
         */
        fun viewShift(
            width: Double,
            height: Double,
            panelWidth: Double,
        ): Double = if (width > height) (panelWidth + PANEL_GAP) / 2 else 0.0
    }
}
