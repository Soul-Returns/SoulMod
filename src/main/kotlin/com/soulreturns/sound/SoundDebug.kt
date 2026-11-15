package com.soulreturns.sound

import com.soulreturns.config.cfg
import com.soulreturns.features.dev.DevOutput
import com.soulreturns.util.soulChat
import net.fabricmc.loader.api.FabricLoader
import java.nio.file.Path
import java.util.Locale

/**
 * `/soul dev sounds …`: the sound system's debug surface, and the only way to manage the library and
 * replacer rules until their screens exist. Dumps go to [DevOutput]; play/preview/stop confirm in
 * chat instead, so no window covers the subtitles they produce.
 */
object SoundDebug {
    fun overview() {
        DevOutput.begin("/soul dev sounds")
        help()
        DevOutput.line("")
        library()
        DevOutput.line("")
        rules()
        DevOutput.line("")
        val recent = RecentSounds.snapshot()
        DevOutput.line("§bRecently heard§r: ${recent.size} distinct (see /soul dev sounds recent)")
    }

    private fun help() {
        DevOutput.line("§7Refs: §fcustom:<id>§7 for a library entry, otherwise a sound event id (§fentity.enderman.teleport§7).")
        DevOutput.line("  /soul dev sounds import <path> — copy an .ogg/.wav/.mp3 into config/soul/sounds/")
        DevOutput.line("  /soul dev sounds play|preview <ref> [volume] [pitch] — play once / as the single preview")
        DevOutput.line("  /soul dev sounds stop — stop the preview")
        DevOutput.line("  /soul dev sounds rename <id> <name> | delete <id> | fav <ref>")
        DevOutput.line("  /soul dev sounds recent [clear] — sounds the client played lately")
        DevOutput.line("  /soul dev sounds catalog [filter] — sound events the client knows")
        DevOutput.line("  /soul dev sounds replacer on|off")
        DevOutput.line("  /soul dev sounds rule mute <id> [category]")
        DevOutput.line("  /soul dev sounds rule replace <id> <ref> [volume] [pitch] [category]")
        DevOutput.line("  /soul dev sounds rule remove|toggle <index>")
    }

    private fun library() {
        val entries = SoundLibrary.entries()
        val stats = CustomClips.stats()
        DevOutput.line("§bLibrary§r ${SoundLibrary.dir}: ${entries.size} entries, clips cached=${stats.clips - stats.decoding - stats.failed} decoding=${stats.decoding} failed=${stats.failed} ${mib(stats.bytes)}")
        for (e in entries) {
            val (opened, finished, mono) = CustomClips.streamCounts(e.id)
            val streams = if (opened == 0) "" else " §8streams opened=$opened read=$finished last=${if (mono == true) "mono (positional)" else "native"}"
            DevOutput.line("  §fcustom:${e.id}§r \"${e.name}\" ${e.format} ${e.durationMs} ms §8${e.file} — ${CustomClips.state(e.id)}$streams")
        }
        DevOutput.line("§bFavorites§r: " + SoundLibrary.favorites().joinToString { it.key }.ifEmpty { "-" })
        DevOutput.line("§bRecently used§r: " + SoundLibrary.recents().joinToString { it.key }.ifEmpty { "-" })
    }

    private fun rules() {
        DevOutput.line("§bReplacer§r: " + if (cfg.sounds.replacerEnabled) "§aon" else "§coff")
        val rules = SoundReplacer.rules()
        if (rules.isEmpty()) DevOutput.line("  §8no rules")
        rules.forEachIndexed { i, r ->
            val target = if (r.action == ReplaceRule.REPLACE) " → ${r.replacement?.let(::describe) ?: "§cnothing"}" else ""
            val state = if (r.enabled) "" else " §8(disabled)"
            DevOutput.line("  §f#$i§r ${r.action} ${r.match}${r.sourceFilter?.let { " [$it]" } ?: ""}$target$state")
        }
    }

    fun import(pathText: String) {
        DevOutput.begin("/soul dev sounds import")
        val path = resolvePath(pathText)
        DevOutput.line("§7Importing $path …")
        SoundLibrary.importAsync(path).thenAccept { result ->
            result
                .onSuccess { DevOutput.line("§aImported§r custom:${it.id} \"${it.name}\" ${it.format} ${it.durationMs} ms") }
                .onFailure { DevOutput.line("§cImport failed:§r ${it.message}") }
        }
    }

    fun play(
        args: String,
        preview: Boolean,
    ) {
        val ref = parseRef(args) ?: return
        val instance = if (preview) SoulSounds.preview(ref) else SoulSounds.play(ref)
        if (instance == null) soulChat("§cCannot play ${ref.key} (unknown sound; see the log)") else soulChat("§7Playing ${describe(ref)}")
    }

    fun stop() {
        SoulSounds.stopPreview()
        soulChat("§7Preview stopped.")
    }

    fun rename(args: String) {
        val id = args.substringBefore(' ').trim()
        val name = args.substringAfter(' ', "").trim()
        DevOutput.begin("/soul dev sounds rename")
        DevOutput.line(if (SoundLibrary.rename(id, name)) "§aRenamed§r custom:$id to \"$name\"" else "§cNo entry '$id' or empty name")
    }

    fun delete(id: String) {
        DevOutput.begin("/soul dev sounds delete")
        DevOutput.line(if (SoundLibrary.delete(id.trim())) "§aDeleted§r custom:${id.trim()}" else "§cNo entry '${id.trim()}'")
    }

    fun favorite(args: String) {
        val ref = parseRef(args) ?: return
        val now = SoundLibrary.toggleFavorite(ref)
        DevOutput.begin("/soul dev sounds fav")
        DevOutput.line("${ref.key}: " + if (now) "§afavorite" else "§7no longer a favorite")
    }

    fun recent(clear: Boolean) {
        DevOutput.begin("/soul dev sounds recent")
        if (clear) {
            RecentSounds.clear()
            DevOutput.line("§7Cleared.")
            return
        }
        val now = System.currentTimeMillis()
        val list = RecentSounds.snapshot()
        DevOutput.line("§7Newest first; repeats of one id + category merge (count). Position = last play.")
        if (list.isEmpty()) DevOutput.line("  §8nothing yet")
        for (s in list) {
            val pos = s.position?.let { String.format(Locale.ROOT, "%.1f %.1f %.1f", it.x, it.y, it.z) } ?: "relative"
            val outcome =
                when (s.outcome) {
                    RecentSound.MUTED -> " §cmuted"
                    RecentSound.REPLACED -> " §areplaced"
                    else -> ""
                }
            DevOutput.line(
                String.format(
                    Locale.ROOT, "  §f%s§r §8[%s]§r v%.2f p%.2f ×%d §8%s, %.1fs ago%s",
                    s.id, s.category, s.volume, s.pitch, s.count, pos, (now - s.time) / 1000.0, outcome,
                ),
            )
        }
    }

    fun catalog(filter: String) {
        DevOutput.begin("/soul dev sounds catalog")
        val all = SoundLibrary.vanillaCatalog()
        val shown = if (filter.isBlank()) all else all.filter { it.id.contains(filter.trim(), ignoreCase = true) }
        DevOutput.line("§7${shown.size} of ${all.size} sound events" + if (filter.isBlank()) "" else " matching \"${filter.trim()}\"")
        val groups = shown.groupBy { it.group }
        for ((group, sounds) in groups) {
            DevOutput.line("§b$group§r (${sounds.size})")
            for (s in sounds) DevOutput.line("  ${s.id}§8 ${s.subgroup ?: ""}")
        }
    }

    fun replacer(on: Boolean) {
        SoundReplacer.enabled = on
        DevOutput.begin("/soul dev sounds replacer")
        rules()
    }

    /** `mute <id> [category]`, `replace <id> <ref> [volume] [pitch] [category]`, `remove <i>`, `toggle <i>`. */
    fun rule(args: String) {
        DevOutput.begin("/soul dev sounds rule")
        val parts = args.trim().split(Regex("\\s+"))
        val error: String? =
            when (parts.firstOrNull()) {
                "mute" -> {
                    val match = parts.getOrNull(1)
                    if (match == null) {
                        "Usage: rule mute <id> [category]"
                    } else {
                        SoundReplacer.addRule(ReplaceRule(match = match, sourceFilter = parts.getOrNull(2), action = ReplaceRule.MUTE))
                        null
                    }
                }
                "replace" -> {
                    val match = parts.getOrNull(1)
                    val numbers = parts.drop(3).mapNotNull { it.toFloatOrNull() }
                    val category = parts.drop(3).firstOrNull { it.toFloatOrNull() == null }
                    val ref = parts.getOrNull(2)?.let { SoundRef.parse(it, numbers.getOrElse(0) { 1f }, numbers.getOrElse(1) { 1f }) }
                    if (match == null || ref == null) {
                        "Usage: rule replace <id> <ref> [volume] [pitch] [category]"
                    } else {
                        SoundReplacer.addRule(ReplaceRule(match = match, sourceFilter = category, action = ReplaceRule.REPLACE, replacement = ref))
                        if (!SoulSounds.canPlay(ref)) DevOutput.line("§eWarning: ${ref.key} cannot play right now (unknown); the original will play instead.")
                        null
                    }
                }
                "remove" -> {
                    val i = parts.getOrNull(1)?.toIntOrNull()
                    if (i == null || !SoundReplacer.removeRule(i)) "No rule #${parts.getOrNull(1)}" else null
                }
                "toggle" -> {
                    val i = parts.getOrNull(1)?.toIntOrNull()
                    val rule = i?.let { SoundReplacer.rules().getOrNull(it) }
                    if (i == null || rule == null) "No rule #${parts.getOrNull(1)}" else { SoundReplacer.updateRule(i, rule.copy(enabled = !rule.enabled)); null }
                }
                else -> "Usage: rule mute|replace|remove|toggle …"
            }
        if (error != null) DevOutput.line("§c$error")
        rules()
        if (!cfg.sounds.replacerEnabled) DevOutput.line("§eThe replacer is off: /soul dev sounds replacer on")
    }

    private fun parseRef(args: String): SoundRef? {
        val parts = args.trim().split(Regex("\\s+"))
        val volume = parts.getOrNull(1)?.toFloatOrNull() ?: 1f
        val pitch = parts.getOrNull(2)?.toFloatOrNull() ?: 1f
        val ref = SoundRef.parse(parts.firstOrNull().orEmpty(), volume, pitch)
        if (ref == null) soulChat("§cNot a sound ref: '${parts.firstOrNull().orEmpty()}'")
        return ref
    }

    private fun describe(ref: SoundRef): String {
        val name = if (ref.isCustom) SoundLibrary.entry(ref.id)?.name?.let { " \"$it\"" } ?: " §c(missing)§r" else ""
        return String.format(Locale.ROOT, "%s%s v%.2f p%.2f", ref.key, name, ref.volume, ref.pitch)
    }

    private fun resolvePath(text: String): Path {
        val t = text.trim().removeSurrounding("\"")
        val expanded = if (t.startsWith("~/")) System.getProperty("user.home") + t.substring(1) else t
        val p = Path.of(expanded)
        return if (p.isAbsolute) p else FabricLoader.getInstance().gameDir.resolve(p).normalize()
    }

    private fun mib(bytes: Long): String = String.format(Locale.ROOT, "%.1f MiB", bytes / 1024.0 / 1024.0)
}
