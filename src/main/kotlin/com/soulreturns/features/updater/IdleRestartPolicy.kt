package com.soulreturns.features.updater

internal class IdleRestartPolicy {
    private var quietSince: Long? = null
    private val shown = HashSet<String>()

    fun activity() { quietSince = null }

    fun markShown(version: String) { shown.add(version) }

    fun due(now: Long, eligible: Boolean, version: String?): Boolean {
        if (!eligible || version == null) {
            activity()
            return false
        }
        val since = quietSince ?: now.also { quietSince = it }
        return now - since >= 60_000L && shown.add(version)
    }
}
