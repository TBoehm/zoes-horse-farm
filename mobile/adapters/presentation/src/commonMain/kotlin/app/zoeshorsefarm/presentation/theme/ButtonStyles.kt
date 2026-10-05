package app.zoeshorsefarm.presentation.theme

// The colour language of the buttons (spec "Farbsprache der Buttons", rule 57): continuing is
// filled, restrained is flat with a border (recognisable by its shape without colour), red only for
// deleting. The brand colour is for headings and never part of a button.

/** What a button means; [id] matches the web class (`.btn`, `.btn-secondary`, ...). */
enum class ButtonRole(
    val id: String,
) {
    /** Go on (default). */
    POSITIVE("positive"),

    /** Restrained: back, cancel, skip, restart. */
    SECONDARY("secondary"),

    /** Deletes something. */
    DANGER("danger"),

    /** One option of a group (not selected; see [ButtonStyles.choiceSelected]). */
    CHOICE("choice"),

    /** Touch control in the ride (the jump button; the others are [ButtonStyles.touchSmall]). */
    TOUCH("touch"),
}

/**
 * How a button looks: [fill] and [ink] (text), the darker [edge] under a 3D button, the outline
 * [border] of a restrained button, and the [pressed] fill. [filled] is false for the outlined kind.
 */
data class ButtonLook(
    val fill: Argb,
    val ink: Argb,
    val filled: Boolean = true,
    val edge: Argb? = null,
    val border: Argb? = null,
    val pressed: Argb? = null,
)

object ButtonStyles {
    private val positive =
        ButtonLook(DesignTokens.positive, DesignTokens.positiveInk, edge = DesignTokens.positiveDark)
    private val secondary =
        ButtonLook(
            fill = DesignTokens.neutralBg,
            ink = DesignTokens.neutralInk,
            filled = false,
            border = DesignTokens.neutralInk,
            pressed = DesignTokens.neutralPress,
        )
    private val danger = ButtonLook(DesignTokens.danger, DesignTokens.dangerInk, edge = DesignTokens.dangerDark)
    private val choice = ButtonLook(DesignTokens.choiceBg, DesignTokens.choiceInk, edge = DesignTokens.choiceEdge)
    private val touch = ButtonLook(DesignTokens.touchJump, DesignTokens.touchInk)

    /** An option button that is selected. */
    val choiceSelected = ButtonLook(DesignTokens.choiceOn, DesignTokens.choiceOnInk, edge = DesignTokens.choiceOnEdge)

    /** The small touch buttons (pause, camera) and the help symbols that copy them. */
    val touchSmall = ButtonLook(DesignTokens.touchSmall, DesignTokens.touchInk)

    /** Light of the gallop button while it is on. */
    val gallopLed: Argb = DesignTokens.touchGallopLed

    fun of(role: ButtonRole): ButtonLook =
        when (role) {
            ButtonRole.POSITIVE -> positive
            ButtonRole.SECONDARY -> secondary
            ButtonRole.DANGER -> danger
            ButtonRole.CHOICE -> choice
            ButtonRole.TOUCH -> touch
        }
}
