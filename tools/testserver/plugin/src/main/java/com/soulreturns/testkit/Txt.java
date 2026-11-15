package com.soulreturns.testkit;

import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.TextDecoration;
import net.kyori.adventure.text.serializer.legacy.LegacyComponentSerializer;

/**
 * Hypixel text is legacy-formatted. Its 1.8 backend reaches modern clients as literal strings that still
 * contain the section-sign codes (the client's font renderer applies them), which is why mods strip
 * codes from {@code getString()}. {@link Mode#INLINE} reproduces that; {@link Mode#COMPONENTS} converts
 * the codes into styled components, to check that a parser copes with both.
 */
public final class Txt {
    public enum Mode { INLINE, COMPONENTS }

    public static Mode mode = Mode.INLINE;

    private Txt() {}

    /** Accepts {@code &} as an alias for the section sign, so console commands stay typeable. */
    public static String legacy(String raw) {
        return raw.replaceAll("&(?=[0-9a-fk-orA-FK-OR])", "§");
    }

    public static Component of(String raw) {
        String text = legacy(raw);
        return mode == Mode.INLINE ? Component.text(text) : LegacyComponentSerializer.legacySection().deserialize(text);
    }

    /** Item names and lore: Hypixel's are never italic, vanilla italicises custom names by default. */
    public static Component item(String raw) {
        return Component.empty().decoration(TextDecoration.ITALIC, false).append(of(raw));
    }

    public static String strip(String raw) {
        return legacy(raw).replaceAll("§.", "");
    }
}
