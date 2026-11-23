package com.soulreturns.features.sacks

import com.soulreturns.api.HypixelNetwork
import com.soulreturns.data.sacks.SackCatalogClient
import com.soulreturns.data.skyblock.SkyblockState
import com.soulreturns.platform.concurrent.SoulScope
import com.soulreturns.platform.http.BackendClient
import com.soulreturns.platform.http.BackendResult
import com.soulreturns.util.SkyblockItemUtils
import com.soulreturns.util.SoulLogger
import kotlinx.coroutines.launch
import kotlinx.serialization.Serializable
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents
import net.minecraft.client.Minecraft
import net.minecraft.client.gui.screens.inventory.AbstractContainerScreen
import net.minecraft.core.component.DataComponents
import net.minecraft.world.entity.player.Inventory
import net.minecraft.world.item.ItemStack
import java.util.concurrent.atomic.AtomicInteger

/** Body of `POST /sacks/report`: the complete set of item ids the sack's menu listed (all pages). */
@Serializable
data class SackReportBody(val name: String, val items: List<String>)

/**
 * Keeps the backend's sack table (`GET /sacks`, rule://sacks "Sack catalog") honest: when a sack menu has been
 * read completely and its item ids differ from the catalog's entry for that title, the set is sent to
 * `POST /sacks/report`. The backend applies it once two players agree (or at once for an owner), so a wrong
 * or partial read costs nothing but a pending report.
 *
 * - Ids only, never counts. Once per sack per session: a sack whose set matches the catalog is marked done
 *   without a request; a failed send is retried the next time that sack is read, at most once a minute.
 * - Reading waits until the menu has been unchanged for [STABLE_TICKS] (slots arrive over several ticks) and
 *   follows page arrows ([SackReport.Pages]). Independent of the Sack tracker's toggle: it reads what the
 *   player opens anyway.
 * - **Never on the Hypixel Alpha network** (rule://backend): reads only on a confirmed production
 *   SkyBlock connection, and `AlphaGate` refuses the POST besides.
 */
object SackReporter {
    private val logger = SoulLogger("Soul/Sacks")

    private const val STABLE_TICKS = 10
    private const val RETRY_MS = 60_000L
    private const val INTENT = "sack-report"

    private val pages = SackReport.Pages()

    /** Normalized sack names settled this session (reported, accepted, or equal to the catalog). */
    private val done: MutableSet<String> = java.util.concurrent.ConcurrentHashMap.newKeySet()
    private val lastAttempt = HashMap<String, Long>()

    private var watching: AbstractContainerScreen<*>? = null
    private var watchingTitle = ""
    private val PAGE_PREFIX = Regex("""^\(\d+/\d+\)\s*""")
    private var lastStacks: List<ItemStack> = emptyList()
    private var stableTicks = 0
    private var readThisScreen = false

    private val sentCount = AtomicInteger()

    @Volatile var lastOutcome: String = "nothing read yet"
        private set

    fun register() {
        ClientTickEvents.END_CLIENT_TICK.register(ClientTickEvents.EndTick { tick() })
    }

    private fun allowed(): Boolean = SkyblockState.onSkyblock && HypixelNetwork.dataset == HypixelNetwork.PRODUCTION && !HypixelNetwork.isAlpha

    private fun tick() {
        val screen = Minecraft.getInstance().gui.screen() as? AbstractContainerScreen<*>
        if (screen == null || !SackMenus.isSackMenu(screen) || !allowed()) {
            flushUnread()
            watching = null
            return
        }
        val containerSlots = screen.menu.slots.filter { it.container !is Inventory }
        val stacks = containerSlots.map { it.item }
        if (screen !== watching) {
            flushUnread()
            watching = screen
            // Paged sacks title each page "(1/2) Runes Sack" (seen live 2026-11-23); the sack is the rest.
            watchingTitle = SackParsing.strip(screen.title.string).trim().replace(PAGE_PREFIX, "")
            lastStacks = stacks
            stableTicks = 0
            readThisScreen = false
            return
        }
        // Slot updates replace the ItemStack instance: identical instances for STABLE_TICKS = settled.
        if (stacks.size != lastStacks.size || stacks.indices.any { stacks[it] !== lastStacks[it] }) {
            lastStacks = stacks
            stableTicks = 0
            readThisScreen = false
            return
        }
        if (stableTicks < STABLE_TICKS) {
            stableTicks++
            return
        }
        val now = System.currentTimeMillis()
        if (readThisScreen) {
            pages.touch(now)
            return
        }
        read(watchingTitle, stacks, now)
    }

    /** A sack closed before its slots settled for [STABLE_TICKS] (players flick through quickly): read its last state. */
    private fun flushUnread() {
        if (watching == null || readThisScreen || stableTicks < 2 || lastStacks.all { it.isEmpty }) return
        read(watchingTitle, lastStacks, System.currentTimeMillis())
    }

    private fun read(title: String, stacks: List<ItemStack>, now: Long) {
        readThisScreen = true
        val slots = stacks.map { slot(it) }
        val ids = SackReport.idsOf(slots, SackReport.isGemstone(title))
        val complete = pages.add(title, ids, SackReport.pageNav(slots), now) ?: return
        consider(title, complete, now)
    }

    private fun slot(stack: ItemStack): SackReport.MenuSlot {
        if (stack.isEmpty) return SackReport.MenuSlot(null, "", emptyList())
        val lore = stack.get(DataComponents.LORE)?.lines()?.map { it.string }.orEmpty()
        return SackReport.MenuSlot(SackMenus.itemId(stack), stack.hoverName.string, lore)
    }

    /** Compares a completely read sack with the catalog and sends it when it differs. */
    internal fun consider(title: String, seen: Set<String>, now: Long) {
        val name = SackParsing.normalizeName(title)
        if (name in done) return
        val known = SackCatalogClient.index.byName(title)?.items?.toSet()
        if (!SackReport.differs(known, seen)) {
            done += name
            lastOutcome = "$title: same as the catalog (${seen.size} items)"
            return
        }
        if (now - (lastAttempt[name] ?: 0L) < RETRY_MS) return
        lastAttempt[name] = now
        send(name, SackReportBody(title, seen.sorted()))
    }

    private fun send(name: String, body: SackReportBody) {
        SoulScope.io.launch {
            val result = try {
                BackendClient.post("/sacks/report", body, SackReportBody.serializer(), INTENT)
            } catch (t: Throwable) {
                lastOutcome = "${body.name}: ${t.message ?: t.javaClass.simpleName}"
                return@launch
            }
            if (result is BackendResult.Ok) {
                val status = (result.value["status"] as? kotlinx.serialization.json.JsonPrimitive)?.content
                done += name
                sentCount.incrementAndGet()
                lastOutcome = "${body.name}: reported ${body.items.size} items ($status)"
                logger.info(lastOutcome)
            } else {
                lastOutcome = "${body.name}: ${(result as BackendResult.Failure).message}"
                logger.info(lastOutcome)
            }
        }
    }

    /** `/soul dev sacks`: what the reporter last did. */
    fun summary(): String = "reports sent ${sentCount.get()} · settled ${done.size} · last: $lastOutcome"
}
