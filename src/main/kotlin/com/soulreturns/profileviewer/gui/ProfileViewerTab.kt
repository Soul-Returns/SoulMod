package com.soulreturns.profileviewer.gui

import net.minecraft.client.gui.DrawContext
import net.minecraft.text.Text

interface ProfileViewerTab {
    val id: String
    val label: Text
    fun render(context: DrawContext, mouseX: Int, mouseY: Int, delta: Float, area: TabArea)
}

data class TabArea(val x: Int, val y: Int, val width: Int, val height: Int)
