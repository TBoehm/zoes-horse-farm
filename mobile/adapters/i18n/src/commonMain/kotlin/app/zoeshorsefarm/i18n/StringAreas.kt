package app.zoeshorsefarm.i18n

// Kept apart from StringArea.kt: the area tables call texts(), so the file that lists them must not
// share a class initializer with it (a cycle would leave the list full of nulls).

/** Every feature area's texts (also the data the tests check). */
val STRING_AREAS: List<StringArea> =
    listOf(
        CORE_STRINGS,
        RIDING_STRINGS,
        PROFILE_STRINGS,
        BADGES_STRINGS,
        COURSES_STRINGS,
        AUDIO_STRINGS,
        HELP_STRINGS,
        DEBUG_STRINGS,
    )
