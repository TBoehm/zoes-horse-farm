package app.zoeshorsefarm.presentation.settings

import app.zoeshorsefarm.application.SettingsService
import app.zoeshorsefarm.application.SoundChannel
import kotlin.math.roundToInt

// The rows a settings-like screen is made of (web: `choiceGroup`, `toggleRow` and the volume row).
// A row holds no copy of the value: it reads it again on every access, so a row shows the saved
// state after any change. The UI renders the rows and calls their actions.

/** A row of a settings-like screen. */
sealed interface SettingsRow

/** One option of a [ChoiceRow]; [id] is the stored value, [label] follows the language. */
class ChoiceOption(
    val id: String,
    private val text: () -> String,
) {
    val label: String get() = text()
}

/**
 * A group of large option buttons with radio behaviour (web `choiceGroup`). [wide] rows take the full
 * width: asked for, or more than three options.
 */
class ChoiceRow(
    val name: String,
    private val labelText: () -> String,
    val options: List<ChoiceOption>,
    private val current: () -> String,
    wide: Boolean = false,
    private val onSelect: (String) -> Unit,
) : SettingsRow {
    val wide: Boolean = wide || options.size > MAX_NARROW_OPTIONS
    val label: String get() = labelText()

    /** The id of the selected option. */
    val selected: String get() = current()

    fun select(id: String) = onSelect(id)

    private companion object {
        const val MAX_NARROW_OPTIONS = 3
    }
}

/**
 * An on/off switch (web `toggleRow`). A switch without [label] belongs to a row that brings its own
 * label, such as a volume row.
 */
class ToggleRow(
    val name: String,
    private val labelText: (() -> String)?,
    private val state: () -> Boolean,
    val wide: Boolean = false,
    private val onChange: (Boolean) -> Unit,
) : SettingsRow {
    val label: String? get() = labelText?.invoke()
    val on: Boolean get() = state()

    fun set(value: Boolean) = onChange(value)

    fun toggle() = onChange(!on)
}

/**
 * Volume slider (0..100 in steps of 5) with the sound switch of one channel. The switch shows
 * "sound on": muted means off, the volume is kept (rule 52).
 */
class VolumeRow(
    val channel: SoundChannel,
    private val settings: SettingsService,
    private val texts: (String) -> String,
) : SettingsRow {
    private val id = channel.id

    val name: String get() = "${id}Volume"
    val label: String get() = texts("settings.$id")
    val sliderLabel: String get() = texts("settings.${id}Volume")
    val switchLabel: String get() = texts("settings.${id}On")

    /** The saved volume as the slider position. */
    val percent: Int
        get() {
            val s = settings.get()
            return ((if (channel == SoundChannel.MUSIC) s.musicVolume else s.sfxVolume) * MAX_PERCENT).roundToInt()
        }

    /** The sound of the channel is switched on (not muted). */
    val soundOn: Boolean
        get() {
            val s = settings.get()
            return !(if (channel == SoundChannel.MUSIC) s.musicMuted else s.sfxMuted)
        }

    fun setPercent(value: Int) {
        settings.setVolume(channel, value.coerceIn(MIN_PERCENT, MAX_PERCENT) / MAX_PERCENT.toDouble())
    }

    fun setSoundOn(on: Boolean) = settings.setMuted(channel, !on)

    companion object {
        const val MIN_PERCENT = 0
        const val MAX_PERCENT = 100
        const val STEP_PERCENT = 5
    }
}
