package com.soulreturns.platform.realtime

import java.io.BufferedReader

/**
 * Minimal Server-Sent Events line-protocol parser. Reads from a [BufferedReader] until EOF
 * (caller treats EOF as "connection dropped, reconnect") and invokes [dispatch] for each complete
 * event. SSE comments (lines starting with `:`) are dropped — Mercure uses them as keep-alives.
 */
internal object MercureSseReader {
    data class SseEvent(val eventType: String, val id: String?, val data: String)

    fun readLoop(reader: BufferedReader, dispatch: (SseEvent) -> Unit) {
        val dataBuf = StringBuilder()
        var eventType: String? = null
        var id: String? = null

        while (true) {
            val line = reader.readLine() ?: return // EOF
            if (line.isEmpty()) {
                if (dataBuf.isNotEmpty()) {
                    dispatch(SseEvent(eventType ?: "message", id, dataBuf.toString()))
                }
                dataBuf.setLength(0)
                eventType = null
                id = null
                continue
            }
            if (line.startsWith(":")) continue // SSE comment / keep-alive

            val colonIdx = line.indexOf(':')
            val field: String
            val value: String
            if (colonIdx < 0) {
                field = line
                value = ""
            } else {
                field = line.substring(0, colonIdx)
                val raw = line.substring(colonIdx + 1)
                value = if (raw.startsWith(" ")) raw.substring(1) else raw
            }
            when (field) {
                "data" -> {
                    if (dataBuf.isNotEmpty()) dataBuf.append('\n')
                    dataBuf.append(value)
                }
                "event" -> eventType = value
                "id" -> id = value
                // "retry" ignored — we manage backoff ourselves.
            }
        }
    }
}
