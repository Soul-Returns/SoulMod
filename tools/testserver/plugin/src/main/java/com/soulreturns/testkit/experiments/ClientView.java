package com.soulreturns.testkit.experiments;

import java.util.ArrayList;
import java.util.List;

/** What a client has been sent, applied packet by packet (the self-test reads only this to decide its clicks). */
public final class ClientView {
    public record Heard(long tick, Packet packet) {}

    public int cid = -1;
    public String title = "";
    public int rows;
    public boolean open;
    public Item[] slots = new Item[0];
    public final List<Heard> log = new ArrayList<>();
    /** Off for live sessions: only the self-test reads {@link #log}. */
    public boolean logging = true;

    public void apply(long tick, Packet p) {
        if (logging) log.add(new Heard(tick, p));
        switch (p) {
            case Packet.Open o -> {
                cid = o.cid();
                title = o.title();
                rows = o.rows();
                slots = new Item[o.rows() * 9];
                open = true;
            }
            case Packet.Slot s -> {
                if (s.cid() == cid && s.slot() < slots.length) slots[s.slot()] = s.item();
            }
            case Packet.Content c -> {
                if (c.cid() == cid) slots = c.items().toArray(Item[]::new);
            }
            case Packet.Close c -> {
                if (c.cid() == cid) open = false;
            }
            default -> {
            }
        }
    }

    /** The client closed its screen itself (Esc). */
    public void closedByClient() {
        open = false;
    }

    public Item slot(int i) {
        return i >= 0 && i < slots.length ? slots[i] : null;
    }

    public String name(int i) {
        Item item = slot(i);
        return item == null ? "" : item.plainName();
    }

    public String plainTitle() {
        return Item.strip(title);
    }

    public int size() {
        return log.size();
    }

    public List<Heard> since(int index) {
        return log.subList(Math.min(index, log.size()), log.size());
    }
}
