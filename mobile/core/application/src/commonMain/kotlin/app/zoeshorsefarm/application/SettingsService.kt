package app.zoeshorsefarm.application

import app.zoeshorsefarm.shared.clamp

// Settings use cases on top of the store port: every settings write of the UI goes through here,
// so values are validated in one place (rule 47) and the adapters stay free of rules. The typed
// parameters already rule out unknown languages, levels, cameras, aid kinds and channels.

/** The two jump aids (rule 42): free ride and course ride. */
enum class AidKind(
    val id: String,
) {
    FREE("free"),
    COURSE("course"),
}

/** The two sound channels. */
enum class SoundChannel(
    val id: String,
) {
    MUSIC("music"),
    SFX("sfx"),
}

class SettingsService(
    private val store: Store,
) {
    private val autoSelectedListeners = LinkedHashSet<() -> Unit>()

    private fun patch(change: (Settings) -> Settings) {
        store.update(SettingsSection, change)
    }

    /** Saved settings (immutable). */
    fun get(): Settings = store.get(SettingsSection)

    fun setLang(lang: Language) = patch { it.copy(lang = lang) }

    /** Automatic graphics: on, starting at the lowest level (rule 4); the governor climbs from there. */
    fun setGraphicsAuto() {
        patch { it.copy(graphicsAuto = true, graphicsLevel = AUTO_START_LEVEL) }
        for (listener in autoSelectedListeners.toList()) listener()
    }

    /**
     * Called after "Automatic" was selected. The crash guard uses it to forget the blocked levels,
     * the engine to forget the levels it stepped down from. Returns the function that unsubscribes.
     */
    fun onAutoSelected(listener: () -> Unit): () -> Unit {
        autoSelectedListeners.add(listener)
        return { autoSelectedListeners.remove(listener) }
    }

    /** The player picks a level: automatic graphics are off. */
    fun setGraphicsLevel(level: GraphicsLevel) = patch { it.copy(graphicsAuto = false, graphicsLevel = level) }

    /** The governor changes the level (down or up): automatic graphics stay on. */
    fun setAutoLevel(level: GraphicsLevel) = patch { it.copy(graphicsLevel = level) }

    fun setCamera(mode: CameraMode) = patch { it.copy(camera = mode) }

    fun setAid(
        kind: AidKind,
        on: Boolean,
    ) = patch { if (kind == AidKind.FREE) it.copy(aidFree = on) else it.copy(aidCourse = on) }

    /** Frame-rate display in the ride (rule 4). */
    fun setShowFps(on: Boolean) = patch { it.copy(showFps = on) }

    /** The controls help was closed with "Got it": it no longer shows up by itself (rule 56). */
    fun markControlsHelpSeen() = patch { it.copy(controlsHelpSeen = true) }

    /** Sets the volume (limited to 0..1); a value that is not a finite number is ignored. */
    fun setVolume(
        channel: SoundChannel,
        value: Double,
    ) {
        if (!value.isFinite()) return
        val volume = clamp(value, 0.0, 1.0)
        patch { if (channel == SoundChannel.MUSIC) it.copy(musicVolume = volume) else it.copy(sfxVolume = volume) }
    }

    fun setMuted(
        channel: SoundChannel,
        muted: Boolean,
    ) = patch { if (channel == SoundChannel.MUSIC) it.copy(musicMuted = muted) else it.copy(sfxMuted = muted) }

    /** Calls [listener] with the new settings after every change; returns the function that unsubscribes. */
    fun onChange(listener: (Settings) -> Unit): () -> Unit = store.onChange(SettingsSection, listener)
}
