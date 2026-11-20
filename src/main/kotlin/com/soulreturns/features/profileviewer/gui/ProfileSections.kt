package com.soulreturns.features.profileviewer.gui

import com.soulreturns.features.profileviewer.gui.kit.Ui
import com.soulreturns.features.profileviewer.gui.sections.AccessoriesSection
import com.soulreturns.features.profileviewer.gui.sections.BestiarySection
import com.soulreturns.features.profileviewer.gui.sections.CollectionsSection
import com.soulreturns.features.profileviewer.gui.sections.CrimsonIsleSection
import com.soulreturns.features.profileviewer.gui.sections.DungeonsSection
import com.soulreturns.features.profileviewer.gui.sections.FarmingSection
import com.soulreturns.features.profileviewer.gui.sections.FishingSection
import com.soulreturns.features.profileviewer.gui.sections.GearSection
import com.soulreturns.features.profileviewer.gui.sections.InventoriesSection
import com.soulreturns.features.profileviewer.gui.sections.MiningSection
import com.soulreturns.features.profileviewer.gui.sections.MiscSection
import com.soulreturns.features.profileviewer.gui.sections.OverviewSection
import com.soulreturns.features.profileviewer.gui.sections.PetsSection
import com.soulreturns.features.profileviewer.gui.sections.RiftSection
import com.soulreturns.features.profileviewer.gui.sections.SkillsSection
import com.soulreturns.features.profileviewer.gui.sections.SlayersSection
import com.soulreturns.features.profileviewer.gui.sections.SoulSection

/**
 * The rail, in order (mock `SECTIONS`). Adding a section = one file under `sections/` and one line here.
 * Keyboard 1–9 jump to the first nine visible entries, 0 to Soul.
 */
object ProfileSections {
    val ALL: List<ProfileSection> = listOf(
        OverviewSection,
        GearSection,
        InventoriesSection,
        AccessoriesSection,
        PetsSection,
        SkillsSection,
        CollectionsSection,
        BestiarySection,
        DungeonsSection,
        SlayersSection,
        MiningSection,
        FishingSection,
        FarmingSection,
        CrimsonIsleSection,
        RiftSection,
        MiscSection,
        SoulSection,
    )

    fun byId(id: String): ProfileSection? = ALL.firstOrNull { it.id == id }

    /** The body of a section whose cards are not built yet: one honest empty state. */
    fun notYet(section: ProfileSection, ui: Ui) {
        ui.space(40)
        ui.empty(section.icon, "${section.title} isn't in this build yet", "The rest of the Profile Viewer is ready; this section follows in a later update.", maxW = 360)
    }
}
