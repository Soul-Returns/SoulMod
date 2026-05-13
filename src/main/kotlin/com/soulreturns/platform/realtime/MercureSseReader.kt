package com.soulreturns.platform.realtime

import java.io.BufferedReader

/**
 * Minimal Server-Sent Events line-protocol parser. Reads from a [BufferedReader] until EOF
 * (caller treats EOF as "connection dropped, reconnect") and invokes [dispatch] for each
 * complete event. SSE comments (lines starting with `:`) are silently dropped — Mercure
 * uses those as keep-alives.
 *
 * Spec: https://datatracker.ietf.org/doc/html/draft-dvj-httpbis-eventsource-eventsource — we
 * implement the subset we need (`data`, `event`, `id`; ignore `retry`).
 */
internal object MercureSseReader {
    data class SseEvent(
        val eventType: String,
        val id: String?,
        val data: String,
    )

    fun readLoop(
        reader: BufferedReader,
        dispatch: (SseEvent) -> Unit,
    ) {
        val dataBuf = StringBuilder()
        var eventType: String? = null
        var id: String? = null

        while (true) {
            val line = reader.readLine() ?: return // EOF
            if (line.isEmpty()) {
                if (dataBuf.isNotEmpty()) {
                    dispatch(
                        SseEvent(
                            eventType = eventType ?: "message",
                            id = id,
                            data = dataBuf.toString(),
                        )
                    )
                }
                dataBuf.setLength(0)
                eventType = null
                id = null
                continue
            }
            if (line.startsWith(":")) continue // SSE comment

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
                // "retry" intentionally ignored — we manage backoff ourselves.
            }
        }
    }
}
