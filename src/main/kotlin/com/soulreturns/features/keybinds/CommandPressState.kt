package com.soulreturns.features.keybinds

/** Samples suppressed presses too; resuming or installing a binding cannot manufacture a press. */
class CommandPressState {
    private var held = false
    private var wasEligible = false

    fun poll(down: Boolean, eligible: Boolean): Boolean {
        // GLFW synthesizes releases on focus loss, so the first resumed sample must only prime state.
        val fire = eligible && wasEligible && down && !held
        held = down
        wasEligible = eligible
        return fire
    }
}
