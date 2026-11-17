package com.soulreturns.features.experiments

/**
 * Clicks this client sent that the server has not echoed yet, oldest first. A pending click ages only
 * while the server talks ([SILENT_MS]), so a lag spike never drops it; one older than [PENDING_MS] of
 * server traffic is dropped (the terminal solver's rule): Hypixel did not count it.
 */
class Pending<T> {
    private class Entry<T>(val value: T, var age: Long)

    private val list = ArrayList<Entry<T>>()

    val size: Int get() = list.size

    fun add(value: T) {
        list += Entry(value, 0)
    }

    /** The oldest pending click is answered. */
    fun shift(): T? = if (list.isEmpty()) null else list.removeAt(0).value

    fun clear() = list.clear()

    /** [dt] ms passed; [talking] = a packet arrived within [SILENT_MS]. Returns how many were dropped. */
    fun age(dt: Long, talking: Boolean): Int {
        if (!talking || dt <= 0) return 0
        for (e in list) e.age += dt
        var dropped = 0
        while (list.isNotEmpty() && list[0].age > PENDING_MS) {
            list.removeAt(0)
            dropped++
        }
        return dropped
    }

    companion object {
        const val PENDING_MS = 1000L
        const val SILENT_MS = 250L
    }
}

/** A judged press: send it now, or not (with the reason the screen shows). */
sealed class Verdict {
    data object Send : Verdict()

    /** [kind]: `reveal` (pattern still playing), `wrong`, `done` (all answers sent), `mismatch` (Superpairs). */
    data class Block(val kind: String, val text: String) : Verdict()
}
