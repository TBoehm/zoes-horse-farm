package app.zoeshorsefarm.presentation.theme

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

private const val MIN_TEXT_CONTRAST = 4.5
private const val MIN_NON_TEXT_CONTRAST = 3.0

// Button colors (rule 57): every text/fill pair of the design tokens must reach the WCAG contrast
// of 4.5:1, and the meaning of the roles must stay distinct.
class PaletteTest {
    private val c = DesignTokens

    // [description, ink, fill]
    private val textPairs =
        listOf(
            Triple("positive button (default)", c.positiveInk, c.positive),
            Triple("restrained button (outlined)", c.neutralInk, c.neutralBg),
            Triple("restrained button while pressed", c.neutralInk, c.neutralPress),
            Triple("delete button", c.dangerInk, c.danger),
            Triple("option button, not selected", c.choiceInk, c.choiceBg),
            Triple("option button, selected", c.choiceOnInk, c.choiceOn),
            Triple("touch jump button label", c.touchInk, c.touchJump),
            Triple("title on the panel cream", c.brand, c.neutralBg),
            Triple("help symbol of a small touch button", c.touchInk, c.touchSmall),
        )

    @Test
    fun hasTheTokensOfTheStyleSheet() {
        assertTrue(DesignTokens.all.size > 10)
    }

    @Test
    fun everyTextPairReaches45To1() {
        for ((name, ink, fill) in textPairs) {
            val ratio = contrastRatio(ink, fill)
            assertTrue(ratio >= MIN_TEXT_CONTRAST, "$name: text contrast $ratio is below $MIN_TEXT_CONTRAST")
        }
    }

    @Test
    fun theBorderOfTheOutlinedButtonIsVisibleOnThePanel() {
        assertTrue(contrastRatio(c.neutralInk, c.neutralBg) >= MIN_NON_TEXT_CONTRAST)
    }

    @Test
    fun filledButtonsStandOutFromThePanelCream() {
        for (fill in listOf(c.positive, c.danger)) {
            assertTrue(contrastRatio(fill, c.neutralBg) >= MIN_NON_TEXT_CONTRAST)
        }
    }

    @Test
    fun keepsTheRolesApartPositiveIsNotTheDeleteColor() {
        assertNotEquals(c.positive, c.danger)
    }

    @Test
    fun everyButtonRoleHasItsOwnIdAndReadableText() {
        val roles = ButtonRole.entries
        assertEquals(roles.size, roles.map { it.id }.toSet().size)
        for (role in roles) {
            val look = ButtonStyles.of(role)
            assertTrue(contrastRatio(look.ink, look.fill) >= MIN_TEXT_CONTRAST, role.id)
        }
        assertTrue(
            contrastRatio(ButtonStyles.choiceSelected.ink, ButtonStyles.choiceSelected.fill) >= MIN_TEXT_CONTRAST,
        )
    }

    @Test
    fun buttonRolesFollowTheColorLanguageOfTheSpec() {
        assertTrue(ButtonStyles.of(ButtonRole.POSITIVE).filled)
        assertTrue(ButtonStyles.of(ButtonRole.DANGER).filled)
        assertTrue(!ButtonStyles.of(ButtonRole.SECONDARY).filled)
        assertNotNull(ButtonStyles.of(ButtonRole.SECONDARY).border)
        assertNull(ButtonStyles.of(ButtonRole.POSITIVE).border)
        // the selected state of an option button differs from the unselected one
        assertNotEquals(ButtonStyles.of(ButtonRole.CHOICE).fill, ButtonStyles.choiceSelected.fill)
        // positive and danger carry a darker 3D edge, the danger one is not the positive one
        assertNotEquals(ButtonStyles.of(ButtonRole.POSITIVE).edge, ButtonStyles.of(ButtonRole.DANGER).edge)
    }

    @Test
    fun theBrandColorIsForHeadingsOnlyNoButtonUsesIt() {
        for (role in ButtonRole.entries) {
            val look = ButtonStyles.of(role)
            assertNotEquals(DesignTokens.brand, look.fill, role.id)
            assertNotEquals(DesignTokens.brand, look.ink, role.id)
        }
    }
}
