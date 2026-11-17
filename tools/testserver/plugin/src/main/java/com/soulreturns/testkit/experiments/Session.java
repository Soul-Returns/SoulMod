package com.soulreturns.testkit.experiments;

import java.util.function.Consumer;

/** One player's simulated table: the engine, the wire between it and the client, and what the client was sent. */
public final class Session {
    public final Engine engine;
    public final Wire wire = new Wire();
    public final ClientView view = new ClientView();
    private final Consumer<Packet> sink;

    /** [sink] puts each delivered packet on the real connection (null: only the view sees it). */
    public Session(long seed, Consumer<Packet> sink) {
        this.engine = new Engine(seed);
        this.sink = sink;
    }

    /** One server tick: arrived input, the engine's step, then what reaches the client this tick. */
    public void tick() {
        wire.arrived(engine.tick + 1);
        engine.step();
        flush();
    }

    /** Sends what a command produced outside the tick (an open) as part of the current tick. */
    public void flush() {
        wire.push(engine.tick, engine.drain());
        for (Packet p : wire.deliver(engine.tick)) {
            view.apply(engine.tick, p);
            if (sink != null) sink.accept(p);
        }
    }

    /** A container click the client sent now (on its container [cid]). */
    public void click(int cid, int slot, Click click) {
        wire.send(engine.tick, () -> engine.click(cid, slot, click));
    }

    /** The client closed container [cid] (Esc) now. */
    public void clientClose(int cid) {
        if (cid == view.cid) view.closedByClient();
        wire.send(engine.tick, () -> engine.clientClose(cid));
    }
}
