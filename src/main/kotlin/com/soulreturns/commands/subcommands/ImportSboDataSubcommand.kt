package com.soulreturns.commands.subcommands

import com.google.gson.JsonElement
import com.google.gson.JsonObject
import com.google.gson.JsonParser
import com.mojang.brigadier.arguments.StringArgumentType
import com.mojang.brigadier.builder.LiteralArgumentBuilder
import com.soulreturns.features.diana.MythologicalMobTracker
import com.soulreturns.features.diana.MythologicalProfitTracker
import com.soulreturns.util.SoulLogger
import com.soulreturns.util.soulChat
import net.fabricmc.fabric.api.client.command.v2.ClientCommandManager
import net.fabricmc.fabric.api.client.command.v2.FabricClientCommandSource
import net.fabricmc.loader.api.FabricLoader
import net.minecraft.ChatFormatting
import net.minecraft.client.Minecraft
import net.minecraft.network.chat.ClickEvent
import net.minecraft.network.chat.Component
import net.minecraft.network.chat.HoverEvent
import java.io.File
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap

/**
 * `/soul importSboData` — migrate per-mob kill counts + lootshare counts + active time +
 * drop totals + total burrows from Sbo's local data files into Soul's two Diana trackers.
 *
 * **Two-step confirmation:**
 *  1. `/soul importSboData` reads `<minecraft>/config/sbo/dianaTrackerTotal.json`, builds
 *     a summary, stashes the prepared import under a one-shot UUID token, and posts a
 *     chat message with a `[Confirm]` button. The button is a `ClickEvent.RunCommand`
 *     pointing at `/soul importSboDataConfirm <token>`.
 *  2. `/soul importSboDataConfirm <token>` (hidden — registered for the button only)
 *     pulls the token's payload and applies it via [MythologicalMobTracker.importFromSbo]
 *     and [MythologicalProfitTracker.importFromSbo]. Tokens expire after [TOKEN_TTL_MS].
 *
 * **Semantics:** the import OVERWRITES the current Soul totals (mob kills, lootshare
 * counts, cocoons, active time, drop totals, total burrows). Session counters are also
 * cleared so the HUD shows the freshly-imported baseline. Per-mayor event partitions are
 * left alone — Sbo's mayor bucketing doesn't map 1:1 and the current mayor term will
 * accumulate fresh going forward.
 *
 * **Mappings:**
 *  - **Mobs**: Sbo's mob names match Soul's exactly (`"King Minos"`, `"Minos Hunter"`, …).
 *    Per-mob counts in `mobs.<Name>` → `MythologicalMobTracker.totalByMob[<Name>]`. Entries
 *    suffixed `" Ls"` route to `totalLootshareByMob`. The `"TotalMobs"` aggregate is
 *    ignored (we recompute from the per-mob values).
 *  - **Items**: keys already in Skyblock-id form (`ENCHANTED_GOLD`, `ANCIENT_CLAW`, `CRETAN_URN`)
 *    pass through unchanged into the [MythologicalProfitTracker.MOB_LOOT_BUCKET]. `_SHARD`-
 *    suffixed keys are translated to Hypixel's `SHARD_<NAME>` form and routed to the
 *    [MythologicalProfitTracker.ATTRIBUTE_SHARD_BUCKET]. Sbo's display-name keys (`"Griffin
 *    Feather"`, `"Chimera"`, …) are mapped via [SBO_DISPLAY_NAME_MAP]. `" Ls"` / `"Ls"` /
 *    `" LS"` suffixes route the count into [MythologicalProfitTracker.LOOTSHARE_BUCKET]
 *    with the base name re-resolved. Unknown keys are reported in the summary but
 *    skipped on import.
 *  - **Active time**: `items.time` (ms) → `totalActiveMs`.
 *  - **Burrows**: `items.Total Burrows` → profit tracker's total-burrows counter.
 *  - **Coins / scavengerCoins / fishCoins**: not stored in Soul's per-drop schema; shown
 *    in the summary but not imported.
 */
object ImportSboDataSubcommand : SoulSubcommand {
    private val logger = SoulLogger("Soul/Import")

    private const val TOKEN_TTL_MS: Long = 60_000L
    private const val SBO_TOTAL_FILE = "config/sbo/dianaTrackerTotal.json"

    private data class PendingImport(
        val tokenId: String,
        val createdAt: Long,
        val ownKillsByMob: Map<String, Long>,
        val lootshareByMob: Map<String, Long>,
        val cocoonsByMob: Map<String, Long>,
        val totalActiveMs: Long,
        val dropsByBucket: Map<String, Map<String, Long>>,
        val totalBurrows: Long,
        val unmappedKeys: List<String>,
    )

    /** Tokens are one-shot: claimed-once, expire after [TOKEN_TTL_MS] if unconfirmed. */
    private val pending: ConcurrentHashMap<String, PendingImport> = ConcurrentHashMap()

    /**
     * Sbo's display-name item keys → (bucketId, soulItemId). Add new entries here when
     * Sbo extends its key set; missing keys land in the summary's "unmapped" list.
     *
     * Bucket choice: regular Mythological drops go to MOB_LOOT_BUCKET; treasure-burrow
     * items (Griffin Feather, Mythos Fragment, Braided Griffin Feather) go to
     * TREASURE_BURROW_BUCKET. Lootshare-suffixed keys are handled separately at parse
     * time — the base lookup here is always for the non-LS variant.
     */
    private val SBO_DISPLAY_NAME_MAP: Map<String, Pair<String, String>> =
        mapOf(
            "Griffin Feather" to (MythologicalProfitTracker.TREASURE_BURROW_BUCKET to "GRIFFIN_FEATHER"),
            "Mythos Fragment" to (MythologicalProfitTracker.TREASURE_BURROW_BUCKET to "MYTHOLOGICAL_RITUAL_FRAGMENT"),
            "Braided Griffin Feather" to (MythologicalProfitTracker.TREASURE_BURROW_BUCKET to "BRAIDED_GRIFFIN_FEATHER"),
            "Crown of Greed" to (MythologicalProfitTracker.MOB_LOOT_BUCKET to "CROWN_OF_GREED"),
            "Washed-up Souvenir" to (MythologicalProfitTracker.MOB_LOOT_BUCKET to "WASHED_UP_SOUVENIR"),
            "Shimmering Wool" to (MythologicalProfitTracker.MOB_LOOT_BUCKET to "SHIMMERING_WOOL"),
            "Manti-core" to (MythologicalProfitTracker.MOB_LOOT_BUCKET to "MANTI_ARTIFACT"),
            "Chimera" to (MythologicalProfitTracker.MOB_LOOT_BUCKET to "ENCHANTMENT_ULTIMATE_CHIMERA_1"),
            "Brain Food" to (MythologicalProfitTracker.MOB_LOOT_BUCKET to "ENCHANTMENT_BRAIN_FOOD_1"),
            "Fateful Stinger" to (MythologicalProfitTracker.MOB_LOOT_BUCKET to "FATEFUL_STINGER"),
            "Daedalus Stick" to (MythologicalProfitTracker.MOB_LOOT_BUCKET to "DAEDALUS_STICK"),
            "Hilt of Revelations" to (MythologicalProfitTracker.MOB_LOOT_BUCKET to "HILT_OF_REVELATIONS"),
        )

    /** Sbo Ls-suffix patterns (variants of "Ls" Sbo writes inconsistently). */
    private val LS_SUFFIXES: List<String> = listOf(" Ls", "Ls", " LS", " LS")

    override fun register(): LiteralArgumentBuilder<FabricClientCommandSource> {
        return literal("importSboData") {
            runs { _ -> startImport() }
        }
    }

    /** Hidden sibling command registered alongside the main one for the [Confirm] button. */
    fun registerConfirm(): LiteralArgumentBuilder<FabricClientCommandSource> {
        return ClientCommandManager.literal("importSboDataConfirm")
            .then(
                ClientCommandManager.argument("token", StringArgumentType.word())
                    .executes { context ->
                        val token = StringArgumentType.getString(context, "token")
                        confirmImport(token)
                        1
                    },
            )
    }

    private fun startImport() {
        val file = File(FabricLoader.getInstance().gameDir.toFile(), SBO_TOTAL_FILE)
        if (!file.isFile) {
            soulChat("§cImport failed: ${file.absolutePath} not found.")
            return
        }
        val parsed =
            try {
                JsonParser.parseReader(file.reader()).asJsonObject
            } catch (e: Exception) {
                logger.warn("Failed to parse Sbo data: ${e.message}", e)
                soulChat("§cImport failed: couldn't read ${file.name} (${e.message}).")
                return
            }
        pruneExpired()
        val pending = buildImport(parsed)
        this.pending[pending.tokenId] = pending
        emitSummary(pending)
    }

    private fun confirmImport(token: String) {
        pruneExpired()
        val payload = pending.remove(token)
        if (payload == null) {
            soulChat("§cImport token invalid or expired — run §e/soul importSboData§c again.")
            return
        }
        MythologicalMobTracker.importFromSbo(
            ownKillsByMob = payload.ownKillsByMob,
            lootshareByMob = payload.lootshareByMob,
            cocoonsByMob = payload.cocoonsByMob,
            totalActiveMs = payload.totalActiveMs,
        )
        MythologicalProfitTracker.importFromSbo(
            dropsByBucket = payload.dropsByBucket,
            totalBurrowsCount = payload.totalBurrows,
        )
        val mobsImported = payload.ownKillsByMob.values.sum()
        val lsImported = payload.lootshareByMob.values.sum()
        val dropsImported = payload.dropsByBucket.values.sumOf { it.values.sum() }
        soulChat(
            "§aSbo data imported: §f$mobsImported§a kills, §f$lsImported§a lootshare, " +
                "§f$dropsImported§a items, §f${payload.totalBurrows}§a burrows. §7(Soul data overwritten.)",
        )
        logger.info("Sbo import confirmed (token=$token)")
    }

    private fun pruneExpired() {
        val now = System.currentTimeMillis()
        pending.entries.removeAll { now - it.value.createdAt > TOKEN_TTL_MS }
    }

    private fun buildImport(root: JsonObject): PendingImport {
        val items = root.getAsJsonObject("items") ?: JsonObject()
        val mobs = root.getAsJsonObject("mobs") ?: JsonObject()

        val ownKills = HashMap<String, Long>()
        val lootshare = HashMap<String, Long>()
        for ((key, value) in mobs.entrySet()) {
            if (key == "TotalMobs") continue
            val count = readLong(value)
            if (count <= 0L) continue
            val baseName = stripLsSuffix(key)
            if (baseName != null) {
                lootshare[baseName] = (lootshare[baseName] ?: 0L) + count
            } else {
                ownKills[key] = (ownKills[key] ?: 0L) + count
            }
        }

        val dropsByBucket = HashMap<String, HashMap<String, Long>>()
        val unmapped = ArrayList<String>()
        var totalBurrows = 0L
        var activeMs = 0L
        for ((key, value) in items.entrySet()) {
            val count = readLong(value)
            // `when (someString)` is safe (only enum/sealed subjects trigger the
            // $WhenMappings synthetic). Explicit case match here.
            when (key) {
                "Total Burrows" -> totalBurrows = count
                "time" -> activeMs = count
                "coins", "scavengerCoins", "fishCoins" -> Unit // not tracked by Soul
                else -> {
                    if (count > 0L) routeItem(key, count, dropsByBucket, unmapped)
                }
            }
        }

        return PendingImport(
            tokenId = UUID.randomUUID().toString().take(8),
            createdAt = System.currentTimeMillis(),
            ownKillsByMob = ownKills,
            lootshareByMob = lootshare,
            cocoonsByMob = emptyMap(), // Sbo doesn't separately track cocoons
            totalActiveMs = activeMs,
            dropsByBucket = dropsByBucket.mapValues { it.value.toMap() },
            totalBurrows = totalBurrows,
            unmappedKeys = unmapped,
        )
    }

    private fun routeItem(
        sboKey: String,
        count: Long,
        target: HashMap<String, HashMap<String, Long>>,
        unmapped: ArrayList<String>,
    ) {
        // Lootshare suffix peeled FIRST so an LS variant looks up the base mapping.
        val isLootshare = stripLsSuffix(sboKey) != null
        val baseKey = stripLsSuffix(sboKey) ?: sboKey
        val resolved = resolveItem(baseKey)
        if (resolved == null) {
            unmapped.add(sboKey)
            return
        }
        val (baseBucket, itemId) = resolved
        val bucket = if (isLootshare) MythologicalProfitTracker.LOOTSHARE_BUCKET else baseBucket
        target.getOrPut(bucket) { HashMap() }.merge(itemId, count, Long::plus)
    }

    /** Resolve a non-LS Sbo item key to `(bucketId, soulItemId)`, or null when unmapped. */
    private fun resolveItem(baseKey: String): Pair<String, String>? {
        // Skyblock-id-shaped keys pass through to mob_loot, OR attribute_shard for `_SHARD` keys.
        if (baseKey.matches(SKYBLOCK_ID_REGEX)) {
            if (baseKey.endsWith("_SHARD")) {
                val mob = baseKey.removeSuffix("_SHARD")
                return MythologicalProfitTracker.ATTRIBUTE_SHARD_BUCKET to "SHARD_$mob"
            }
            return MythologicalProfitTracker.MOB_LOOT_BUCKET to baseKey
        }
        // Display-name keys go through the explicit map.
        return SBO_DISPLAY_NAME_MAP[baseKey]
    }

    private fun stripLsSuffix(key: String): String? {
        for (suffix in LS_SUFFIXES) {
            if (key.endsWith(suffix)) return key.removeSuffix(suffix).trim()
        }
        return null
    }

    private fun readLong(value: JsonElement?): Long {
        if (value == null || value.isJsonNull) return 0L
        return try {
            value.asLong
        } catch (_: NumberFormatException) {
            0L
        } catch (_: ClassCastException) {
            0L
        } catch (_: IllegalStateException) {
            0L
        }
    }

    private fun emitSummary(payload: PendingImport) {
        val mc = Minecraft.getInstance()
        mc.execute {
            val player = mc.player ?: return@execute
            val ownTotal = payload.ownKillsByMob.values.sum()
            val lsTotal = payload.lootshareByMob.values.sum()
            val dropsTotal = payload.dropsByBucket.values.sumOf { it.values.sum() }
            val activeMinutes = payload.totalActiveMs / 60_000L
            val lines =
                listOf(
                    "§6Sbo import preview §7(§c will overwrite current Soul totals§7)",
                    "  §7Mobs: §f$ownTotal §7own kills, §f$lsTotal §7lootshare",
                    "  §7Drops: §f$dropsTotal §7items across §f${payload.dropsByBucket.size}§7 bucket(s)",
                    "  §7Burrows: §f${payload.totalBurrows}",
                    "  §7Active time: §f${activeMinutes} §7min",
                ) +
                    (if (payload.unmappedKeys.isNotEmpty()) {
                        listOf("  §eUnmapped: §f${payload.unmappedKeys.size} §7key(s) — see /soul logs for details")
                    } else {
                        emptyList()
                    })
            for (line in lines) {
                player.displayClientMessage(Component.literal("§8[§6Soul§8]§r $line"), false)
            }
            if (payload.unmappedKeys.isNotEmpty()) {
                logger.info("Unmapped Sbo keys (skipped): ${payload.unmappedKeys.joinToString(", ")}")
            }
            player.displayClientMessage(buildConfirmRow(payload.tokenId), false)
        }
    }

    private fun buildConfirmRow(tokenId: String): Component {
        val confirmCommand = "/soul importSboDataConfirm $tokenId"
        val confirmButton =
            Component.literal("[Confirm]")
                .withStyle { style ->
                    style.withColor(ChatFormatting.GREEN)
                        .withBold(true)
                        .withClickEvent(ClickEvent.RunCommand(confirmCommand))
                        .withHoverEvent(
                            HoverEvent.ShowText(Component.literal("Run the import — overwrites Soul totals.")),
                        )
                }
        return Component.literal("§8[§6Soul§8]§r §7Click ")
            .append(confirmButton)
            .append(Component.literal(" §7within 60 s to apply. §8(token: §7$tokenId§8)"))
    }

    private val SKYBLOCK_ID_REGEX: Regex = Regex("^[A-Z][A-Z0-9_]*$")
}
