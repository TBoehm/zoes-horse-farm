package app.zoeshorsefarm.application

import app.zoeshorsefarm.application.testing.FakeStore
import app.zoeshorsefarm.domain.horse.Appearance
import app.zoeshorsefarm.domain.horse.Coat
import app.zoeshorsefarm.domain.horse.Marking
import kotlin.test.Test
import kotlin.test.assertContains
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

// Not ported: JS "ignores values that are not part of the offered choices" (the typed Coat and Marking
// enums cannot hold a value that is not offered).
class HorseServiceTest {
    private val named = HorseProfile(name = "Blitz", nameAnswered = true)

    // answerName

    @Test
    fun answerNameSavesTheTrimmedNameAndMarksTheQuestionAsAnswered() {
        val store = FakeStore()
        assertTrue(answerName(store, "  Blitz  "))
        assertEquals("Blitz", store.horse.name)
        assertTrue(store.horse.nameAnswered)
    }

    @Test
    fun answerNameAccepts1To16CharactersOnly() {
        val store = FakeStore()
        assertTrue(answerName(store, "A"))
        assertTrue(answerName(store, "x".repeat(16)))
        assertFalse(answerName(store, "x".repeat(17)))
        assertEquals("x".repeat(16), store.horse.name)
    }

    @Test
    fun answerNameRejectsEmptyBlankAndMissingInputWithoutSaving() {
        val store = FakeStore()
        assertFalse(answerName(store, ""))
        assertFalse(answerName(store, "   "))
        assertFalse(answerName(store, null))
        assertNull(store.horse.name)
        assertFalse(store.horse.nameAnswered)
    }

    // skipName

    @Test
    fun skipNameKeepsTheDefaultNameButCountsTheQuestionAsAnswered() {
        val store = FakeStore(horse = HorseProfile(name = "Old"))
        skipName(store)
        assertNull(store.horse.name)
        assertTrue(store.horse.nameAnswered)
    }

    // needsNamePrompt

    @Test
    fun asksUntilTheQuestionWasAnsweredOrSkipped() {
        // rule 43
        val store = FakeStore()
        assertTrue(needsNamePrompt(store))
        skipName(store)
        assertFalse(needsNamePrompt(store))
    }

    @Test
    fun doesNotAskAgainAfterAnAnswer() {
        val store = FakeStore()
        answerName(store, "Blitz")
        assertFalse(needsNamePrompt(store))
    }

    @Test
    fun countsAnOldSaveWithAValidNameButNoAnsweredFlagAsAnswered() {
        val store = FakeStore(horse = HorseProfile(name = "Blitz", nameAnswered = false))
        assertFalse(needsNamePrompt(store))
    }

    @Test
    fun stillAsksWhenTheSavedNameIsNotValidAndTheFlagIsMissing() {
        assertTrue(needsNamePrompt(FakeStore(horse = HorseProfile(name = "", nameAnswered = false))))
        assertTrue(needsNamePrompt(FakeStore(horse = HorseProfile(name = null, nameAnswered = false))))
    }

    // isValidName

    @Test
    fun isValidNameMirrorsTheNameRule() {
        assertTrue(isValidName("Blitz"))
        assertFalse(isValidName("  "))
        assertFalse(isValidName("x".repeat(17)))
        assertFalse(isValidName(null))
    }

    // rename

    @Test
    fun renameSavesAValidName() {
        val store = FakeStore(horse = named)
        assertTrue(rename(store, " Sturm "))
        assertEquals("Sturm", store.horse.name)
    }

    @Test
    fun renameDoesNotSaveAnInvalidNameAndKeepsTheLastValidOne() {
        val store = FakeStore(horse = named)
        assertFalse(rename(store, ""))
        assertFalse(rename(store, "x".repeat(40)))
        assertEquals("Blitz", store.horse.name)
    }

    @Test
    fun renameDoesNotTurnTheLanguageDefaultNameIntoACustomName() {
        val store = FakeStore()
        assertTrue(rename(store, "Stern", defaultName = "Stern"))
        assertNull(store.horse.name)
        assertTrue(store.horse.nameAnswered)
    }

    @Test
    fun renameWritesNothingWhenTheNameDidNotChange() {
        val store = FakeStore(horse = named)
        var changes = 0
        store.onChange(HorseSection) { changes += 1 }
        rename(store, "Blitz")
        assertEquals(0, changes)
    }

    // typing the language default name: one rule for the name prompt and for renaming

    private val flows: Map<String, (FakeStore, String) -> Boolean> =
        mapOf(
            "name prompt" to { store, input -> answerName(store, input, defaultName = "Stern") },
            "rename" to { store, input -> rename(store, input, defaultName = "Stern") },
        )

    @Test
    fun theDefaultNameStaysNoCustomNameButCountsAsAnswered() {
        for ((flow, save) in flows) {
            val store = FakeStore()
            assertTrue(save(store, " Stern "), flow)
            assertNull(store.horse.name, flow)
            assertTrue(store.horse.nameAnswered, flow)
        }
    }

    @Test
    fun typingTheDefaultNameOverACustomNameRemovesTheCustomName() {
        for ((flow, save) in flows) {
            val store = FakeStore(horse = named)
            assertTrue(save(store, "Stern"), flow)
            assertNull(store.horse.name, flow)
            assertTrue(store.horse.nameAnswered, flow)
        }
    }

    @Test
    fun anyOtherNameIsACustomName() {
        for ((flow, save) in flows) {
            val store = FakeStore()
            assertTrue(save(store, "Blitz"), flow)
            assertEquals("Blitz", store.horse.name, flow)
            assertTrue(store.horse.nameAnswered, flow)
        }
    }

    @Test
    fun withoutAKnownDefaultNameEveryValidNameIsACustomName() {
        val store = FakeStore()
        answerName(store, "Stern")
        assertEquals("Stern", store.horse.name)
    }

    // setAppearance

    @Test
    fun setAppearanceSavesCoatAndMarkingAndReturnsTheSavedAppearance() {
        val store = FakeStore()
        assertEquals(Appearance(Coat.BLACK, Marking.BLAZE), setAppearance(store, Coat.BLACK, Marking.BLAZE))
        assertEquals(Coat.BLACK, store.horse.coat)
        assertEquals(Marking.BLAZE, store.horse.marking)
    }

    @Test
    fun setAppearanceChangesOnlyTheGivenFields() {
        val store = FakeStore(horse = HorseProfile(coat = Coat.GREY, marking = Marking.SNIP))
        assertEquals(Appearance(Coat.PINTO, Marking.SNIP), setAppearance(store, coat = Coat.PINTO))
        assertEquals(Appearance(Coat.PINTO, Marking.NONE), setAppearance(store, marking = Marking.NONE))
    }

    @Test
    fun setAppearanceKeepsTheNameAndUnknownFields() {
        val store = FakeStore(horse = HorseProfile(name = "Blitz", unknown = mapOf("future" to 1)))
        setAppearance(store, coat = Coat.BLACK)
        assertEquals("Blitz", store.horse.name)
        assertEquals(mapOf("future" to 1), store.horse.unknown)
    }

    // appearanceOptions

    @Test
    fun offersTheCoatsAndMarkingsOfTheHorseDomain() {
        val options = appearanceOptions()
        assertContains(options.coats, Coat.BAY)
        assertContains(options.markings, Marking.STAR)
    }

    // displayName

    @Test
    fun showsTheOwnNameElseTheLanguageDefaultName() {
        assertEquals("Blitz", displayName(HorseProfile(name = "Blitz"), "Stern"))
        assertEquals("Stern", displayName(HorseProfile(name = null), "Stern"))
        assertEquals("Stern", displayName(null, "Stern"))
    }
}
