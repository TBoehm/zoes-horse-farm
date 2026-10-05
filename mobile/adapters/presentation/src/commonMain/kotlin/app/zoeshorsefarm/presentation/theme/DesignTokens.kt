package app.zoeshorsefarm.presentation.theme

// Design tokens: the colours of the first `:root` block of `src/adapters/ui/styles/main.css`. Screens
// use only these tokens, never colours of their own for buttons (spec "Farbsprache der Buttons",
// rule 57). Each fill/ink pair is checked by PaletteTest (WCAG contrast of at least 4.5:1).

object DesignTokens {
    // sky and meadow behind the screens
    val bgTop = Argb.rgb(0x8fd3ff)
    val bgMid = Argb.rgb(0xd9f1ff)
    val grass = Argb.rgb(0x6cbf4f)
    val grassDark = Argb.rgb(0x3f7d3a)

    /** Panel background: rgba(255, 251, 243, 0.94). */
    val panel = Argb.rgba(0xfffbf3, 0.94)
    val ink = Argb.rgb(0x2b1d12)
    val inkSoft = Argb.rgb(0x6b5442)

    /** Heading / brand colour: titles only, never a button or anything inside one (rule 57). */
    val brand = Argb.rgb(0x9c3a1f)
    val accent = Argb.rgb(0xf2b632)

    // Button role "positive": go on (menu entries, "Go", "Next", "Again", "Got it")
    val positive = Argb.rgb(0x2e7d32)
    val positiveInk = Argb.rgb(0xffffff)

    /** 3D edge under the button. */
    val positiveDark = Argb.rgb(0x1b5e20)

    // Button role "secondary": restrained, outlined ("Back", "Cancel", "Skip", "Restart")
    val neutralBg = Argb.rgb(0xfff4e0)

    /** Text and border of the restrained button. */
    val neutralInk = Argb.rgb(0x6b4423)
    val neutralPress = Argb.rgb(0xf3dfba)

    // Button role "danger": only buttons that delete something
    val danger = Argb.rgb(0xb3261e)
    val dangerInk = Argb.rgb(0xffffff)
    val dangerDark = Argb.rgb(0x7f1710)

    // Button role "choice": option buttons, not selected and selected
    val choiceBg = Argb.rgb(0xefe4d3)
    val choiceInk = Argb.rgb(0x2b1d12)
    val choiceEdge = Argb.rgb(0xc9b79d)
    val choiceOn = Argb.rgb(0xf2a900)
    val choiceOnInk = Argb.rgb(0x2b1d12)
    val choiceOnEdge = Argb.rgb(0xa87400)

    // Touch buttons in the ride (the label is white on a darkened glass look)
    val touchJump = Argb.rgb(0x1e6fb5)
    val touchInk = Argb.rgb(0xffffff)

    /** Light of the gallop button while it is on. */
    val touchGallopLed = Argb.rgb(0xb6ff6b)

    /** Small touch buttons (pause, ...) as a solid colour, for the help symbols that copy them. */
    val touchSmall = Argb.rgb(0x4b3a2b)
    val focus = Argb.rgb(0x1f6feb)

    /** Corner radius of panels and buttons, in dp. */
    const val RADIUS_DP = 18

    /** Smallest touch target, in dp. */
    const val TOUCH_MIN_DP = 44

    // panel shadow: 0 10px 30px rgba(40, 25, 10, 0.25)
    val shadowColor = Argb.rgba(0x28190a, 0.25)
    const val SHADOW_OFFSET_Y_DP = 10
    const val SHADOW_BLUR_DP = 30

    /** Every colour token by name (what the palette test walks through). */
    val all: Map<String, Argb> =
        linkedMapOf(
            "bgTop" to bgTop,
            "bgMid" to bgMid,
            "grass" to grass,
            "grassDark" to grassDark,
            "panel" to panel,
            "ink" to ink,
            "inkSoft" to inkSoft,
            "brand" to brand,
            "accent" to accent,
            "positive" to positive,
            "positiveInk" to positiveInk,
            "positiveDark" to positiveDark,
            "neutralBg" to neutralBg,
            "neutralInk" to neutralInk,
            "neutralPress" to neutralPress,
            "danger" to danger,
            "dangerInk" to dangerInk,
            "dangerDark" to dangerDark,
            "choiceBg" to choiceBg,
            "choiceInk" to choiceInk,
            "choiceEdge" to choiceEdge,
            "choiceOn" to choiceOn,
            "choiceOnInk" to choiceOnInk,
            "choiceOnEdge" to choiceOnEdge,
            "touchJump" to touchJump,
            "touchInk" to touchInk,
            "touchGallopLed" to touchGallopLed,
            "touchSmall" to touchSmall,
            "focus" to focus,
        )
}
