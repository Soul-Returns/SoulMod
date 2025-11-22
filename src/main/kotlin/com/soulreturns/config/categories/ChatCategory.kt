package com.soulreturns.config.categories

import com.google.gson.annotations.Expose
import io.github.notenoughupdates.moulconfig.annotations.ConfigEditorBoolean
import io.github.notenoughupdates.moulconfig.annotations.ConfigOption

class ChatCategory {
    @Expose
    @JvmField
    @ConfigOption(name = "Woot Woot!", desc = "Automatically respond with '/pc Woot Woot!' when 'Double Hook!' is detected in chat")
    @ConfigEditorBoolean
    var wootWoot: Boolean = true
}