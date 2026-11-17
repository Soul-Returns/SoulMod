package com.soulreturns.testkit.experiments;

import java.util.List;

/** What the simulated Hypixel sends, in order; the plugin turns each into the vanilla packet (strings in bridge form). */
public sealed interface Packet {
    /** open_screen of a {@code generic_9x<rows>} chest menu. */
    record Open(int cid, int rows, String title) implements Packet {}

    /** One container_set_slot of a container slot (null = empty). */
    record Slot(int cid, int slot, Item item) implements Packet {}

    /** The open burst's player-inventory part: every inventory slot of the menu as single set_slot packets. */
    record PlayerSlots(int cid, int containerSize) implements Packet {}

    /** One full container_set_content (the container's slots; the plugin appends the player inventory). */
    record Content(int cid, List<Item> items) implements Packet {}

    /** A sound at the player; pitch already quantized to n/63 like Hypixel's. */
    record Sound(String id, String source, float volume, float pitch) implements Packet {}

    record Chat(String text) implements Packet {}

    /** container_close from the server. */
    record Close(int cid) implements Packet {}
}
