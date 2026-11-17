package com.soulreturns.testkit.experiments;

import java.util.ArrayList;
import java.util.List;
import java.util.TreeMap;

/**
 * The connection between the simulated Hypixel and the client: {@code lag} (half before the server judges a click,
 * half on every answer, like {@code hx terminal lag}) and {@code split} (one tick's slot packets spread over two
 * ticks). Freezes are the Netty tap's job.
 */
public final class Wire {
    public int lagMs = 120;
    public boolean split;
    private final TreeMap<Long, List<Packet>> down = new TreeMap<>();
    private final TreeMap<Long, List<Runnable>> up = new TreeMap<>();
    private final List<Packet> carry = new ArrayList<>();

    public long lagTicks() {
        return lagMs <= 0 ? 0 : Math.max(1, (lagMs + 49) / 50);
    }

    public long upTicks() {
        return lagTicks() / 2;
    }

    public long downTicks() {
        return lagTicks() - upTicks();
    }

    /** A click or close leaving the client at [tick]; [arrive] runs when it reaches the server. */
    public void send(long tick, Runnable arrive) {
        long at = tick + upTicks();
        if (at <= tick) arrive.run();
        else up.computeIfAbsent(at, k -> new ArrayList<>()).add(arrive);
    }

    /** Client input that has arrived by [tick]; run them before the server's step of that tick. */
    public void arrived(long tick) {
        while (!up.isEmpty() && up.firstKey() <= tick) up.pollFirstEntry().getValue().forEach(Runnable::run);
    }

    /** The server sent [packets] in [tick]; they reach the client after the down half of the lag. */
    public void push(long tick, List<Packet> packets) {
        if (packets.isEmpty()) return;
        down.computeIfAbsent(tick + downTicks(), k -> new ArrayList<>()).addAll(packets);
    }

    /** What reaches the client in [tick], in order. With split, the second half of a tick's slot packets comes one tick later. */
    public List<Packet> deliver(long tick) {
        List<Packet> fresh = new ArrayList<>();
        while (!down.isEmpty() && down.firstKey() <= tick) fresh.addAll(down.pollFirstEntry().getValue());
        List<Packet> out = new ArrayList<>(carry);
        carry.clear();
        long slots = fresh.stream().filter(p -> p instanceof Packet.Slot).count();
        if (!split || slots < 2) {
            out.addAll(fresh);
            return out;
        }
        long keep = slots / 2, seen = 0;
        int cut = 0;
        for (; cut < fresh.size(); cut++) {
            if (fresh.get(cut) instanceof Packet.Slot && ++seen > keep) break;
        }
        out.addAll(fresh.subList(0, cut));
        carry.addAll(fresh.subList(cut, fresh.size()));
        return out;
    }

    public void clear() {
        down.clear();
        up.clear();
        carry.clear();
    }
}
