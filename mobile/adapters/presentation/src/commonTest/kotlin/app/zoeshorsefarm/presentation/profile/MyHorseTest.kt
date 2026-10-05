package app.zoeshorsefarm.presentation.profile

import app.zoeshorsefarm.application.HorseSection
import app.zoeshorsefarm.domain.horse.Appearance
import app.zoeshorsefarm.domain.horse.Coat
import app.zoeshorsefarm.domain.horse.Marking
import app.zoeshorsefarm.presentation.TestApp
import app.zoeshorsefarm.presentation.nav.Route
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.sin
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

private class RecordingPreview : HorsePreview {
    val appearances = mutableListOf<Appearance>()

    override fun setAppearance(appearance: Appearance) {
        appearances += appearance
    }
}

class MyHorseTest {
    private val app = TestApp()
    private val preview = RecordingPreview()

    private fun model() = MyHorseModel(app.ctx, preview)

    @Test
    fun startsWithTheNameOfTheHorseAndNoComplaint() {
        val model = model()
        assertEquals("Flash", model.nameText)
        assertTrue(model.nameValid)
        assertTrue(model.music)
        app.store.update(HorseSection) { it.copy(name = "Zora") }
        assertEquals("Zora", model().nameText)
    }

    @Test
    fun aValidNameIsSavedWhileTyping() {
        val model = model()
        model.onNameInput("Blitzi")
        assertEquals("Blitzi", app.store.horse.name)
        assertTrue(model.nameValid)
        assertEquals("Blitzi", model.nameText)
    }

    @Test
    fun anInvalidInputIsNotSavedAndTheLastValidNameStays() {
        val model = model()
        model.onNameInput("Zora")
        model.onNameInput("")
        assertEquals("Zora", app.store.horse.name)
        assertFalse(model.nameValid)
        assertEquals("", model.nameText)
    }

    @Test
    fun leavingTheFieldRestoresTheSavedName() {
        val model = model()
        model.onNameInput("Zora")
        model.onNameInput("   ")
        model.onNameBlur()
        assertEquals("Zora", model.nameText)
        assertTrue(model.nameValid)
    }

    @Test
    fun theCoatAndMarkingChoicesListTheAppearanceOptions() {
        val model = model()
        assertEquals(listOf("chestnut", "bay", "black", "grey", "pinto"), model.coat.options.map { it.id })
        assertEquals(listOf("Chestnut", "Bay", "Black", "Grey", "Pinto"), model.coat.options.map { it.label })
        assertEquals(listOf("none", "star", "blaze", "snip"), model.marking.options.map { it.id })
        assertEquals("bay", model.coat.selected)
        assertEquals("star", model.marking.selected)
        assertTrue(model.coat.wide)
        assertTrue(model.marking.wide)
    }

    @Test
    fun pickingACoatSavesItAndTellsThePreview() {
        val model = model()
        model.coat.select("black")
        assertEquals(Coat.BLACK, app.store.horse.coat)
        assertEquals("black", model.coat.selected)
        assertEquals(listOf(Appearance(Coat.BLACK, Marking.STAR)), preview.appearances)
        model.marking.select("blaze")
        assertEquals(Marking.BLAZE, app.store.horse.marking)
        assertEquals(Appearance(Coat.BLACK, Marking.BLAZE), preview.appearances.last())
    }

    @Test
    fun anUnknownOptionIsIgnored() {
        val model = model()
        model.coat.select("purple")
        assertEquals(Coat.BAY, app.store.horse.coat)
        assertTrue(preview.appearances.isEmpty())
    }

    @Test
    fun theLabelsAreTranslated() {
        val model = model()
        assertEquals("My Horse", model.title)
        assertEquals("Name", model.nameLabel)
        assertEquals("Coat", model.coat.label)
        assertEquals("Face marking", model.marking.label)
        assertEquals("1 to 16 letters", model.nameHint)
    }

    @Test
    fun thePreviewStartsWithTheSavedAppearance() {
        app.store.update(HorseSection) { it.copy(coat = Coat.GREY, marking = Marking.SNIP) }
        val model = model()
        assertEquals(Appearance(Coat.GREY, Marking.SNIP), model.appearance)
    }

    @Test
    fun backGoesToTheMenu() {
        app.registerStubs("menu")
        model().back()
        assertEquals(Route.Menu, app.navigator.current)
    }
}

class HorsePreviewCameraTest {
    private fun assertClose(
        expected: Double,
        actual: Double,
    ) = assertTrue(abs(expected - actual) < 1e-9, "expected $expected but was $actual")

    @Test
    fun startsAtTheAngleOfTheWebPreview() {
        assertEquals(0.9, HorsePreviewCamera().angle)
    }

    @Test
    fun circlesSlowlyAroundTheHorse() {
        val camera = HorsePreviewCamera()
        camera.advance(2.0)
        assertClose(0.9 + 0.5, camera.angle)
        assertClose(sin(1.4) * 5.2, camera.x)
        assertClose(1.9, camera.y)
        assertClose(cos(1.4) * 5.2, camera.z)
    }

    @Test
    fun looksAtTheChestOfTheHorse() {
        val camera = HorsePreviewCamera()
        assertEquals(0.0, camera.targetX)
        assertEquals(1.15, camera.targetY)
        assertEquals(0.0, camera.targetZ)
    }

    @Test
    fun shiftsThePictureRightOfThePanelInLandscapeOnly() {
        assertClose((300.0 + 16) / 2, HorsePreviewCamera.viewShift(800.0, 400.0, 300.0))
        assertEquals(0.0, HorsePreviewCamera.viewShift(400.0, 800.0, 300.0))
        assertEquals(0.0, HorsePreviewCamera.viewShift(400.0, 400.0, 300.0))
    }
}
