package com.soulreturns.features.dev

import com.soulreturns.util.MessageDetector

/**
 * Where every `/soul dev …` command writes, instead of chat.
 *
 * ### Why not chat
 * Diagnostics are long — `/soul dev backend test` alone runs to dozens of lines — and chat is the
 * worst possible surface for them: it scrolls away, it wraps at whatever width the player happens to
 * have, it is interleaved with Hypixel's own spam, and getting it back out to paste into an issue
 * means screenshotting it. A dump that cannot be copied is a dump that gets retyped wrong.
 *
 * So a dev command [begin]s a capture, writes [line]s into it, and [DevOutputScreen] renders the
 * whole thing scrollable with a copy button. The lines are kept after the screen closes, so
 * `/soul dev output` can reopen the last one.
 *
 * ### Threading
 * Commands are dispatched on the client thread but most of them finish on [com.soulreturns.platform.concurrent.SoulScope],
 * appending as results arrive. So [line] is safe from any thread, and the screen re-reads
 * [snapshot] every frame rather than being pushed to.
 */
object DevOutput {
    /** Guards [buffer]; contended only between a command's coroutine and the render thread. */
    private val lock = Any()
    private val buffer = ArrayList<String>()

    @Volatile
    var title: String = "Dev output"
        private set

    /**
     * Start a new capture and show it. Replaces whatever the last command left behind — one command,
     * one dump, rather than an ever-growing scrollback nobody trimmed.
     */
    fun begin(title: String) {
        synchronized(lock) { buffer.clear() }
        this.title = title
        DevOutputScreen.open()
    }

    /** Append one line. `§` color codes are kept — the screen renders them. Any thread. */
    fun line(text: String) {
        synchronized(lock) { buffer.add(text) }
    }

    /** The lines as they stand. Copied, so the render thread never iterates a list being appended to. */
    fun snapshot(): List<String> = synchronized(lock) { ArrayList(buffer) }

    fun isEmpty(): Boolean = synchronized(lock) { buffer.isEmpty() }

    /**
     * The capture as plain text for the clipboard — **color codes stripped**, because the point of
     * copying is to paste it somewhere that would show `§8` as literal noise.
     */
    fun plainText(): String =
        buildString {
            append(MessageDetector.stripColorCodes(title))
            append('\n')
            for (line in snapshot()) {
                append(MessageDetector.stripColorCodes(line))
                append('\n')
            }
        }
}
