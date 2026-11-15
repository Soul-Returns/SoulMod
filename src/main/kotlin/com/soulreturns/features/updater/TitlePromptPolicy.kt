package com.soulreturns.features.updater

import com.soulreturns.platform.updater.UpdatePhase
import com.soulreturns.platform.updater.UpdateSnapshot

/** What [UpdateTitlePrompt] owes the player right now. */
enum class TitleDue { NONE, ASK_AUTO, UPDATE }

/**
 * The title-screen decision as a pure function of the updater's state, so it holds whether the
 * result was final before the first menu frame or arrives later. The question comes first; the
 * update window is due once per version ([shown]) for an offer the player has not skipped (urgent
 * ones show regardless) or for an automatic download in any state (progress, "restart required",
 * failure).
 */
object TitlePromptPolicy {
    fun due(
        automatic: Boolean,
        autoInstall: Boolean,
        autoInstallAsked: Boolean,
        canInstallUpdates: Boolean,
        s: UpdateSnapshot,
        shown: Set<String>,
    ): TitleDue {
        if (!automatic) return TitleDue.NONE
        // Automatic updates already on: nothing left to ask.
        if (!autoInstall && !autoInstallAsked && canInstallUpdates) return TitleDue.ASK_AUTO
        val release = s.release ?: return TitleDue.NONE
        if (release.version in shown) return TitleDue.NONE
        return if (offerDue(s)) TitleDue.UPDATE else TitleDue.NONE
    }

    private fun offerDue(s: UpdateSnapshot): Boolean {
        if (s.phase == UpdatePhase.AVAILABLE) return !s.skipped || s.urgent != null
        val auto = s.autoInstall && !s.skipped
        return auto && (s.phase == UpdatePhase.DOWNLOADING || s.phase == UpdatePhase.READY_RESTART || s.phase == UpdatePhase.ERROR)
    }
}
