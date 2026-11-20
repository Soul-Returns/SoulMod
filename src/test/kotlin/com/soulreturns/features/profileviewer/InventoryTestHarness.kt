package com.soulreturns.features.profileviewer

import com.soulreturns.data.hypixel.PlayerIdentity
import com.soulreturns.data.hypixel.SkyblockProfileMember
import com.soulreturns.data.hypixel.SkyblockProfiles
import com.soulreturns.features.profileviewer.gui.ProfileContext
import com.soulreturns.features.profileviewer.gui.ProfileSection
import com.soulreturns.features.profileviewer.gui.kit.KitCanvas
import com.soulreturns.features.profileviewer.gui.kit.KitStyle
import com.soulreturns.features.profileviewer.gui.kit.StackNode
import com.soulreturns.features.profileviewer.gui.kit.Ui
import com.soulreturns.features.profileviewer.gui.kit.ViewState
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.boolean
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import net.minecraft.world.item.ItemStack

/** A recording canvas with deterministic metrics (5 px per character, 6 bold), like the one in `SectionBuildTest`. */
internal class InvRecorder(override val state: ViewState = ViewState()) : KitCanvas {
    val texts = ArrayList<String>()
    override val viewTop = 0
    override val viewBottom = Int.MAX_VALUE
    override val nowMs = 1_800_000_000_000L
    override fun width(text: String, size: Float, bold: Boolean) = text.length * if (bold) 6 else 5
    override fun trackedWidth(text: String, size: Float, tracking: Float, bold: Boolean) = text.length * 6
    override fun rect(x0: Int, y0: Int, x1: Int, y1: Int, color: Int, corner: Int) {}
    override fun text(text: String, x: Int, top: Int, bottom: Int, color: Int, size: Float, bold: Boolean) {
        texts += text
    }
    override fun tracked(text: String, cx: Int, cy: Int, color: Int, size: Float, tracking: Float, bold: Boolean) {
        texts += text
    }
    override fun icon(name: String, x: Int, y: Int, size: Int, color: Int) {}
    override fun item(stack: ItemStack, x: Int, y: Int, size: Int, count: Boolean) {}
    override fun face(uuidUndashed: String, x: Int, y: Int, size: Int) {}
    override fun hovered(x0: Int, y0: Int, x1: Int, y1: Int) = false
    override fun onClick(x0: Int, y0: Int, x1: Int, y1: Int, action: () -> Unit) {}
    override fun tooltip(x0: Int, y0: Int, x1: Int, y1: Int, lines: List<String>, key: String?) {}
    override fun itemTooltip(stack: ItemStack) {}
    override fun field(key: String, x0: Int, y0: Int, x1: Int, y1: Int, placeholder: String) {}
    override fun openSection(id: String) {}

    /** Everything painted as one line (wrapped lines rejoined), `§` codes stripped. */
    fun all(): String = texts.joinToString(" ").replace(Regex("§."), "").replace(Regex("\\s+"), " ")
}

/** Loads a shared fixture with fragment files (`fragments/`) deep-merged into the selected profile's member. */
internal object InvHarness {
    private val json = Json { ignoreUnknownKeys = true }

    private fun resource(path: String): JsonObject =
        json.parseToJsonElement(InvHarness::class.java.getResource(path)!!.readText()).jsonObject

    /** [b] over [a]: objects merge key by key, anything else is replaced. */
    fun merge(a: JsonObject, b: JsonObject): JsonObject {
        val out = LinkedHashMap<String, kotlinx.serialization.json.JsonElement>(a)
        for ((key, value) in b) {
            val old = a[key]
            out[key] = if (old is JsonObject && value is JsonObject) merge(old, value) else value
        }
        return JsonObject(out)
    }

    private fun patched(fixture: JsonObject, fragments: List<JsonObject>): JsonObject {
        val uuid = fixture["uuid"]!!.jsonPrimitive.content
        val profiles = fixture["profiles"]!!.jsonObject
        val list = profiles["profiles"]!!.jsonArray.map { p ->
            val o = p.jsonObject
            if (o["selected"]?.jsonPrimitive?.boolean != true) {
                o
            } else {
                val members = o["members"]!!.jsonObject
                val member = fragments.fold(members[uuid]!!.jsonObject) { acc, fragment -> merge(acc, fragment) }
                JsonObject(o + ("members" to JsonObject(members + (uuid to member))))
            }
        }
        return JsonObject(profiles + ("profiles" to JsonArray(list)))
    }

    fun context(fixture: String, vararg fragments: String): ProfileContext {
        val f = resource("/profileviewer/fixtures/$fixture.json")
        val frags = fragments.map { resource("/profileviewer/fragments/$it.json") }
        val identity = PlayerIdentity(f["uuid"]!!.jsonPrimitive.content, f["name"]!!.jsonPrimitive.content)
        val profiles = SkyblockProfiles.from(patched(f, frags))
        val profile = profiles.pick(null)!!
        return ProfileContext(
            identity, profiles, profile, profile.member(identity.uuidUndashed)!!, isSelf = false,
            caps = null, capsLoaded = true, soulAnswer = null,
            nowMs = 1_800_000_000_000L, opener = {}, looker = {},
        )
    }

    fun member(fixture: String, vararg fragments: String): SkyblockProfileMember = context(fixture, *fragments).member

    /** Builds [section], lays it out at [w] and paints it on [canvas]; returns the height. */
    fun render(ctx: ProfileContext, section: ProfileSection, canvas: InvRecorder = InvRecorder(), w: Int = 969): Pair<InvRecorder, Int> {
        val ui = Ui(section.id, canvas.state)
        section.build(ctx, ui)
        val root = StackNode(ui.nodes, KitStyle.GAP)
        val h = root.height(canvas, w)
        root.paint(canvas, 0, 0, w, h)
        return canvas to h
    }
}
