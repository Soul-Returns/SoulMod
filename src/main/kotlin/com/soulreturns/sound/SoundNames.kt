package com.soulreturns.sound

import net.minecraft.client.Minecraft
import net.minecraft.resources.Identifier

/**
 * Display names for sounds, subtitle first (the user's pick): a Minecraft sound reads as its
 * subtitle ("Enderman teleports") with the raw id as the second line, falling back to a readable
 * path from the id ("Enderman › Teleport") when the event has no subtitle. Custom sounds show the
 * name the user gave them. Client thread only.
 */
object SoundNames {
    private val titles = HashMap<String, String>()

    /** Main line. */
    fun title(ref: SoundRef): String = if (ref.isCustom) SoundLibrary.entry(ref.id)?.name ?: ref.id else vanillaTitle(ref.id)

    /** Second line: the full event id, or a custom sound's file name. */
    fun idLine(ref: SoundRef): String = if (ref.isCustom) SoundLibrary.entry(ref.id)?.file ?: "missing file" else ref.id

    fun vanillaTitle(id: String): String = titles.getOrPut(id) { subtitle(id) ?: readablePath(id) }

    /** Subtitles and the language can change on a resource reload; screens drop the cache when they open. */
    fun clearCache() = titles.clear()

    private fun subtitle(id: String): String? {
        val identifier = Identifier.tryParse(id) ?: return null
        val events = Minecraft.getInstance().soundManager.getSoundEvent(identifier) ?: return null
        return events.subtitle?.string?.trim()?.takeIf { it.isNotEmpty() }
    }

    /** `minecraft:entity.enderman.teleport` → `Enderman › Teleport` (the group is dropped; it is the folder). */
    fun readablePath(id: String): String {
        val path = id.substringAfter(':')
        val segments = path.split('.').filter { it.isNotEmpty() }
        val shown = if (segments.size > 1) segments.drop(1) else segments
        return shown.joinToString(" › ") { words(it) }.ifEmpty { id }
    }

    /** `music_disc` → `Music disc`. */
    fun words(segment: String): String = segment.replace('_', ' ').replaceFirstChar { it.uppercaseChar() }

    /** Folder and category names: like [words], but two-letter names are acronyms (`ui` → `UI`). */
    fun groupTitle(group: String): String = if (group.length <= 2) group.uppercase() else words(group)
}
