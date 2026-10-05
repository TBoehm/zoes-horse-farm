package app.zoeshorsefarm.application

import app.zoeshorsefarm.domain.horse.Appearance
import app.zoeshorsefarm.domain.horse.COATS
import app.zoeshorsefarm.domain.horse.Coat
import app.zoeshorsefarm.domain.horse.MARKINGS
import app.zoeshorsefarm.domain.horse.Marking
import app.zoeshorsefarm.domain.horse.cleanName

// Horse use cases: name question (rule 43), renaming, appearance, display name.
// Name rules live in domain/horse; this service validates through them and persists via the store.

fun isValidName(input: String?): Boolean = cleanName(input) != null

/**
 * True while the first-start name question was neither answered nor skipped (rule 43).
 * An older save that has a valid name but no answered flag counts as answered.
 */
fun needsNamePrompt(store: Store): Boolean {
    val horse = store.get(HorseSection)
    return !horse.nameAnswered && cleanName(horse.name) == null
}

/**
 * One rule for the name prompt ([answerName]) and for renaming ([rename]): typing the language
 * default name does not make a custom name (name stays null); any other valid name does. Either
 * way the question is answered. An invalid name is not saved, the last valid name stays (rule 43).
 * @param defaultName the language default name
 * @return false if the input is invalid (nothing is saved)
 */
private fun saveName(
    store: Store,
    input: String?,
    defaultName: String?,
): Boolean {
    val typed = cleanName(input) ?: return false
    val name = if (defaultName != null && typed == defaultName) null else typed
    val current = store.get(HorseSection)
    if (name == current.name && current.nameAnswered) return true
    store.update(HorseSection) { it.copy(name = name, nameAnswered = true) }
    return true
}

/** Saves the answer to the name question (first start). See [saveName]. */
fun answerName(
    store: Store,
    input: String?,
    defaultName: String? = null,
): Boolean = saveName(store, input, defaultName)

/** Renames the horse. See [saveName]. */
fun rename(
    store: Store,
    input: String?,
    defaultName: String? = null,
): Boolean = saveName(store, input, defaultName)

/** "Skip": the language default name stays, the question counts as answered. */
fun skipName(store: Store) {
    store.update(HorseSection) { it.copy(name = null, nameAnswered = true) }
}

/** The selectable coats and markings. */
data class AppearanceOptions(
    val coats: List<Coat>,
    val markings: List<Marking>,
)

fun appearanceOptions() = AppearanceOptions(COATS, MARKINGS)

/** Saves coat and/or marking (a null stays as it is) and returns the saved appearance. */
fun setAppearance(
    store: Store,
    coat: Coat? = null,
    marking: Marking? = null,
): Appearance {
    val next = store.update(HorseSection) { it.copy(coat = coat ?: it.coat, marking = marking ?: it.marking) }
    return Appearance(next.coat, next.marking)
}

/** Own name, else the language default name. */
fun displayName(
    horse: HorseProfile?,
    defaultName: String,
): String = horse?.name ?: defaultName
