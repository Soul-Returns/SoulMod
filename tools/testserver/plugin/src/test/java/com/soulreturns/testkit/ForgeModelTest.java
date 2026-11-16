package com.soulreturns.testkit;

import static org.junit.jupiter.api.Assertions.assertEquals;

import org.junit.jupiter.api.Test;

/** Quick Forge, durations and remaining time as captured on Hypixel (docs/research/forge-2026-11-16.md §1, §4a). */
class ForgeModelTest {
    @Test
    void quickForgePercentPerLevel() {
        assertEquals(0, ForgeModel.percent(0));
        assertEquals(10.5, ForgeModel.percent(1));
        assertEquals(11, ForgeModel.percent(2));
        assertEquals(19.5, ForgeModel.percent(19));
        assertEquals(30, ForgeModel.percent(20));
        assertEquals("10.5", ForgeModel.percentText(ForgeModel.percent(1)));
        assertEquals("11", ForgeModel.percentText(ForgeModel.percent(2)));
        assertEquals("30", ForgeModel.percentText(ForgeModel.percent(20)));
    }

    @Test
    void shownDurationsMatchTheCapture() {
        // Blueberry (30 %) against Kiwi's base times.
        assertEquals(21, ForgeModel.shownSeconds(30, 20));
        assertEquals(20_160, ForgeModel.shownSeconds(28_800, 20));
        assertEquals(11_340, ForgeModel.shownSeconds(16_200, 20));
        assertEquals(28_800, ForgeModel.shownSeconds(28_800, 0));
        assertEquals(27, ForgeModel.shownSeconds(30, 1));
    }

    @Test
    void durationFormatDropsZeroUnits() {
        assertEquals("21s", ForgeModel.duration(21));
        assertEquals("42m", ForgeModel.duration(42 * 60));
        assertEquals("5h 36m", ForgeModel.duration(20_160));
        assertEquals("2h 48m", ForgeModel.duration(10_080));
        assertEquals("1d 1h 12m", ForgeModel.duration(90_720));
        assertEquals("4d 21h 36m", ForgeModel.duration(4 * 86_400 + 21 * 3_600 + 36 * 60));
        assertEquals("35m 5s", ForgeModel.duration(2_105));
        assertEquals("3d", ForgeModel.duration(259_200));
        assertEquals("0s", ForgeModel.duration(0));
    }

    @Test
    void remainingCountsDownAndFollowsTheCurrentLevel() {
        long base = 28_800;
        assertEquals(20_160, ForgeModel.remainingSeconds(base, 20, 0));
        assertEquals(20_160, ForgeModel.remainingSeconds(base, 20, 400));
        assertEquals(20_157, ForgeModel.remainingSeconds(base, 20, 3_000));
        // Quick Forge lowered mid-run: the same start, a longer shown duration.
        assertEquals(28_800 - 3, ForgeModel.remainingSeconds(base, 0, 3_000));
        // Before the start (the chamber preset: 35m 5s while 35m is the shown time).
        assertEquals(2_105, ForgeModel.remainingSeconds(3_000, 20, -5_000));
        // Past the end: a finished slot is not simulated, the display stays at 1 s.
        assertEquals(1, ForgeModel.remainingSeconds(30, 20, 99_000));
    }

    @Test
    void recipeBookProgress() {
        assertEquals(100, ForgeModel.bookPercent(123, 123));
        assertEquals(73, ForgeModel.bookPercent(90, 123));
        assertEquals(79, ForgeModel.bookPercent(98, 123));
        assertEquals(25, ForgeModel.barGreen(100));
        assertEquals(18, ForgeModel.barGreen(73));
        assertEquals(19, ForgeModel.barGreen(79));
    }
}
