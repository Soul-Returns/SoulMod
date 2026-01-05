package com.soulreturns.config.categories.fishing

import com.soulreturns.config.lib.annotations.ConfigOption
import com.soulreturns.config.lib.annotations.Toggle
import com.soulreturns.config.lib.annotations.TextInput

class ChatSubCategory {
    @JvmField
    @ConfigOption(name = "Double Hook Toggle", description = "Enable double hook message")
    @Toggle
    var doubleHookMessageToggle: Boolean = true

    @JvmField
    @ConfigOption(name = "Double Hook Message", description = "Message to send when 'Double Hook!' is detected in chat")
    @TextInput(placeholder = "Enter message", maxLength = 256)
    var doubleHookMessageText: String = "[Soul] Woot Woot!"
}
