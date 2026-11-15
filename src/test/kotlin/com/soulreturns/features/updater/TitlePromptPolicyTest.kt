package com.soulreturns.features.updater

import com.soulreturns.platform.updater.UpdateNotice
import com.soulreturns.platform.updater.UpdatePhase
import com.soulreturns.platform.updater.UpdateRelease
import com.soulreturns.platform.updater.UpdateSnapshot
import kotlin.test.Test
import kotlin.test.assertEquals

class TitlePromptPolicyTest {
    private val release = UpdateRelease("0.3.0-beta.3", "beta", emptyList(), null, 1_000)

    private fun snap(phase: UpdatePhase, auto: Boolean = false, skipped: Boolean = false, notices: List<UpdateNotice> = emptyList()) =
        UpdateSnapshot(phase = phase, release = release, autoInstall = auto, skipped = skipped, notices = notices)

    private fun due(
        s: UpdateSnapshot,
        autoInstall: Boolean = false,
        asked: Boolean = true,
        canInstall: Boolean = true,
        shown: Set<String> = emptySet(),
        automatic: Boolean = true,
    ) = TitlePromptPolicy.due(automatic, autoInstall, asked, canInstall, s, shown)

    @Test
    fun finishedAutomaticDownloadIsDueAtTheFirstFrame() {
        // The download completed during client init, before any menu existed.
        assertEquals(TitleDue.UPDATE, due(snap(UpdatePhase.READY_RESTART, auto = true), autoInstall = true))
    }

    @Test
    fun runningAndFailedAutomaticDownloadsAreDue() {
        assertEquals(TitleDue.UPDATE, due(snap(UpdatePhase.DOWNLOADING, auto = true), autoInstall = true))
        assertEquals(TitleDue.UPDATE, due(snap(UpdatePhase.ERROR, auto = true), autoInstall = true))
    }

    @Test
    fun offerIsDueWhenAutoInstallIsOff() {
        assertEquals(TitleDue.UPDATE, due(snap(UpdatePhase.AVAILABLE)))
    }

    @Test
    fun resultArrivingLaterIsStillDue() {
        // Nothing yet at the first frame, then the check answers: same decision on a later frame.
        assertEquals(TitleDue.NONE, due(UpdateSnapshot()))
        assertEquals(TitleDue.UPDATE, due(snap(UpdatePhase.AVAILABLE)))
        assertEquals(TitleDue.UPDATE, due(snap(UpdatePhase.READY_RESTART, auto = true), autoInstall = true))
    }

    @Test
    fun onceSessionAndVersion() {
        val shown = setOf(release.version)
        assertEquals(TitleDue.NONE, due(snap(UpdatePhase.AVAILABLE), shown = shown))
        assertEquals(TitleDue.NONE, due(snap(UpdatePhase.READY_RESTART, auto = true), autoInstall = true, shown = shown))
        // A newer version is a new announcement.
        assertEquals(TitleDue.UPDATE, due(snap(UpdatePhase.AVAILABLE), shown = setOf("0.3.0-beta.2")))
    }

    @Test
    fun skippedVersionIsQuietUnlessUrgent() {
        assertEquals(TitleDue.NONE, due(snap(UpdatePhase.AVAILABLE, skipped = true)))
        assertEquals(TitleDue.NONE, due(snap(UpdatePhase.READY_RESTART, auto = true, skipped = true), autoInstall = true))
        val urgent = listOf(UpdateNotice(UpdateNotice.REVOKED, "withdrawn"))
        assertEquals(TitleDue.UPDATE, due(snap(UpdatePhase.AVAILABLE, skipped = true, notices = urgent)))
    }

    @Test
    fun aDownloadThePlayerStartedIsNotAnnouncedHere() {
        // Ready after an "Update Now" click earlier in the session: chat already said it.
        assertEquals(TitleDue.NONE, due(snap(UpdatePhase.READY_RESTART, auto = false)))
    }

    @Test
    fun questionComesFirstAndOnlyWhenInstallable() {
        val pending = snap(UpdatePhase.READY_RESTART, auto = true)
        assertEquals(TitleDue.ASK_AUTO, due(pending, asked = false))
        assertEquals(TitleDue.ASK_AUTO, due(snap(UpdatePhase.AVAILABLE), asked = false))
        assertEquals(TitleDue.ASK_AUTO, due(UpdateSnapshot(), asked = false))
        // Cannot install here: nothing to ask, the offer still shows.
        assertEquals(TitleDue.UPDATE, due(snap(UpdatePhase.AVAILABLE), asked = false, canInstall = false))
        // Automatic updates already on: not asked again.
        assertEquals(TitleDue.UPDATE, due(pending, autoInstall = true, asked = false))
    }

    @Test
    fun developmentRunsStaySilent() {
        assertEquals(TitleDue.NONE, due(snap(UpdatePhase.AVAILABLE), automatic = false))
        assertEquals(TitleDue.NONE, due(UpdateSnapshot(), asked = false, automatic = false))
    }

    @Test
    fun updatedPhaseIsNotATitleScreenWindow() {
        assertEquals(TitleDue.NONE, due(UpdateSnapshot(phase = UpdatePhase.UPDATED)))
    }
}
