package com.soulreturns.testkit;

import static org.junit.jupiter.api.Assertions.assertEquals;

import org.junit.jupiter.api.Test;

/** Health numbers and nametag shape as they appear in a Hypixel packet log. */
class MobNamesTest {
    @Test
    void amountsFollowHypixel() {
        assertEquals("6,000", MobNames.amount(6_000));
        assertEquals("65,000", MobNames.amount(65_000));
        assertEquals("394.9k", MobNames.amount(394_950));
        assertEquals("750k", MobNames.amount(750_000));
        assertEquals("5.2M", MobNames.amount(5_200_000));
        assertEquals("210M", MobNames.amount(210_000_000));
    }

    @Test
    void parsesSuffixes() {
        assertEquals(6_000, MobNames.parseAmount("6,000"));
        assertEquals(750_000, MobNames.parseAmount("750k"));
        assertEquals(1_500_000, MobNames.parseAmount("1.5M"));
    }

    @Test
    void nametagMatchesHypixel() {
        assertEquals("§r§8[§r§7Lv51§r§8] §r§5\uE078 §r§cEnderman§r §r§a6,000§r§f/§r§a6,000§r§c❤",
            MobNames.nametag("Enderman", 51, 6_000, 6_000));
        assertEquals("[Lv655] \uE078 Voidgloom Seraph IV 68.6M/210M❤",
            MobNames.nametag("Voidgloom Seraph IV", 655, 68_600_000, 210_000_000).replaceAll("§.", ""));
    }
}
