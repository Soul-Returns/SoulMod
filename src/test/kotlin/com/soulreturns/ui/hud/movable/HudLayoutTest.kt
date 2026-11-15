package com.soulreturns.ui.hud.movable

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** The Move GUI's position model: screen anchors, links, keeping on screen, and the edits on top. */
class HudLayoutTest {
    private class Store(var w: Float = 960f, var h: Float = 540f) : HudEditStore {
        val transforms = LinkedHashMap<String, HudTransform>()
        val sizes = HashMap<String, FloatArray>()
        val hidden = HashSet<String>()
        var vanilla: Map<String, VanillaElement> = emptyMap()

        fun add(id: String, w: Float, h: Float, t: HudTransform) {
            transforms[id] = t
            sizes[id] = floatArrayOf(w, h)
        }

        override fun ids(): List<String> = transforms.keys.toList()

        override fun transform(id: String): HudTransform? = transforms[id]

        override fun size(id: String): FloatArray = sizes.getValue(id)

        override fun shown(id: String): Boolean = id !in hidden

        override fun set(id: String, transform: HudTransform) {
            transforms[id] = transform
        }

        override fun layout(): HudLayout = HudLayout(w, h, vanilla, this)

        fun box(id: String): HudBox = layout().box(id)!!
    }

    private fun near(expected: Float, actual: Float, what: String = "") = assertEquals(expected, actual, 0.01f, what)

    private fun assertBox(x: Float, y: Float, b: HudBox) {
        near(x, b.x0, "x0")
        near(y, b.y0, "y0")
    }

    private fun below(target: String, h: Int = HudPlace.START, gap: Float = 5f, follow: Boolean = true) =
        HudLink(HudTargets.hud(target), h = h, v = HudPlace.OUT_END, offsetY = gap, follow = follow, auto = false)

    @Test
    fun soulActionBarHidesTheVanillaOne() {
        fun bar(hidden: Boolean) =
            VanillaHudLayout.build(480, 270, 1f, chat = intArrayOf(0, 0, 10, 10), scoreboard = null, bossShown = false, actionBarHidden = hidden)
                .getValue(VanillaIds.ACTION_BAR)
        assertEquals(false, bar(false).hidden)
        assertEquals(true, bar(true).hidden)
        // A HUD linked to it falls back to its screen anchor, like any hidden target.
        val s = Store()
        s.vanilla = mapOf(VanillaIds.ACTION_BAR to bar(true))
        val link = HudLink(HudTargets.vanilla(VanillaIds.ACTION_BAR), h = HudPlace.CENTER, v = HudPlace.OUT_END, follow = true, auto = false)
        s.add("f", 40f, 10f, HudTransform(hAnchor = HudAnchor.CENTER, vAnchor = HudAnchor.END, offsetY = -32f, link = link))
        assertEquals(HudTargets.vanilla(VanillaIds.ACTION_BAR), s.box("f").info.hiddenTarget)
    }

    // ---------------------------------------------------------------- screen anchor

    @Test
    fun nearestRegionIsPickedPerAxis() {
        assertEquals(HudAnchor.START, HudLayout.pickRegion(10f, 60f, 960f))
        assertEquals(HudAnchor.CENTER, HudLayout.pickRegion(440f, 520f, 960f))
        assertEquals(HudAnchor.END, HudLayout.pickRegion(900f, 950f, 960f))
        val t = HudLayout.screenFromBox(HudTransform(), HudBox(900f, 500f, 50f, 20f), 960f, 540f, reanchor = true)
        assertEquals(HudAnchor.END, t.hAnchor)
        assertEquals(HudAnchor.END, t.vAnchor)
        near(-10f, t.offsetX)
        near(-20f, t.offsetY)
        // A picked anchor stays when auto anchoring is off.
        val fixed = HudLayout.screenFromBox(HudTransform(autoAnchor = false), HudBox(900f, 500f, 50f, 20f), 960f, 540f, reanchor = true)
        assertEquals(HudAnchor.START, fixed.hAnchor)
        near(900f, fixed.offsetX)
    }

    @Test
    fun edgeDistanceHoldsAtAnyWindowSize() {
        val s = Store()
        s.add("a", 100f, 40f, HudTransform(hAnchor = HudAnchor.END, vAnchor = HudAnchor.END, offsetX = -10f, offsetY = -20f))
        assertBox(850f, 480f, s.box("a"))
        s.w = 500f
        s.h = 300f
        assertBox(390f, 240f, s.box("a"))
    }

    @Test
    fun boxIsClampedOnScreenWithoutChangingTheStoredValue() {
        val s = Store(w = 400f, h = 300f)
        val t = HudTransform(offsetX = 380f, offsetY = -45f)
        s.add("a", 100f, 40f, t)
        val b = s.box("a")
        assertBox(300f, 0f, b)
        near(-80f, b.info.clampX)
        near(45f, b.info.clampY)
        assertEquals(t, s.transforms["a"])
    }

    // ---------------------------------------------------------------- links

    @Test
    fun linkSidesGapAndEdges() {
        val s = Store()
        s.add("t", 100f, 50f, HudTransform(offsetX = 200f, offsetY = 100f))
        s.add("below", 40f, 10f, HudTransform(link = below("t")))
        assertBox(200f, 155f, s.box("below"))
        s.add("above", 40f, 10f, HudTransform(link = HudLink(HudTargets.hud("t"), h = HudPlace.END, v = HudPlace.OUT_START, offsetY = -5f, follow = true)))
        assertBox(260f, 85f, s.box("above"))
        s.add("right", 40f, 10f, HudTransform(link = HudLink(HudTargets.hud("t"), h = HudPlace.OUT_END, v = HudPlace.START, offsetX = 5f, follow = true)))
        assertBox(305f, 100f, s.box("right"))
        s.add("left", 40f, 10f, HudTransform(link = HudLink(HudTargets.hud("t"), h = HudPlace.OUT_START, v = HudPlace.END, offsetX = -5f, follow = true)))
        assertBox(155f, 140f, s.box("left"))
        s.add("on", 40f, 10f, HudTransform(link = HudLink(HudTargets.hud("t"), h = HudPlace.CENTER, v = HudPlace.CENTER, follow = true)))
        assertBox(230f, 120f, s.box("on"))
        assertEquals(5f, s.transforms.getValue("left").link!!.gap)
        assertEquals(HudLink(HudTargets.hud("t"), h = HudPlace.OUT_START, offsetX = -5f), HudLink(HudTargets.hud("t"), h = HudPlace.OUT_START).withGap(5f))
        // The side's offset never points into the target.
        assertEquals(0f, HudLink(HudTargets.hud("t"), v = HudPlace.OUT_END, h = HudPlace.START, offsetY = -3f).sanitized().offsetY)
    }

    @Test
    fun centerGlueStaysCenteredWhenEitherGrows() {
        val s = Store()
        s.add("t", 100f, 50f, HudTransform(offsetX = 200f, offsetY = 100f))
        s.add("f", 40f, 10f, HudTransform(link = below("t", h = HudPlace.CENTER)))
        assertBox(230f, 155f, s.box("f"))
        s.sizes["t"] = floatArrayOf(160f, 50f)
        assertBox(260f, 155f, s.box("f"))
        s.sizes["f"] = floatArrayOf(80f, 10f)
        assertBox(240f, 155f, s.box("f"))
    }

    @Test
    fun positionFollowsMovesButGrowthOnlyOnTheAttachedSideAndGluedEdge() {
        val s = Store()
        // Target anchored top-left: it grows right and down.
        s.add("t", 100f, 50f, HudTransform(offsetX = 200f, offsetY = 100f))
        // Below it, not lined up: x keeps its distance to the target's anchor point (its left edge).
        s.add("free", 40f, 10f, HudTransform(link = below("t", h = HudPlace.START, follow = false).copy(offsetX = 30f)))
        // Below it, glued to its right edge.
        s.add("glued", 40f, 10f, HudTransform(link = below("t", h = HudPlace.END)))
        assertBox(230f, 155f, s.box("free"))
        assertBox(260f, 155f, s.box("glued"))
        // Grows: the attached side (below) follows on both; the glued edge follows, the free x does not.
        s.sizes["t"] = floatArrayOf(160f, 80f)
        assertBox(230f, 185f, s.box("free"))
        assertBox(320f, 185f, s.box("glued"))
        // Moves: both follow.
        s.transforms["t"] = s.transforms.getValue("t").copy(offsetX = 300f)
        assertBox(330f, 185f, s.box("free"))
        // A right-anchored target grows to the left; the free follower stays with its right point.
        s.transforms["t"] = HudTransform(hAnchor = HudAnchor.END, offsetX = -100f, offsetY = 100f)
        s.sizes["t"] = floatArrayOf(100f, 50f)
        s.transforms["free"] = HudTransform(link = below("t", follow = false).copy(offsetX = -20f))
        val before = s.box("free").x0
        s.sizes["t"] = floatArrayOf(200f, 50f)
        near(before, s.box("free").x0, "free follower of a right-anchored target")
        // Fixed: that axis uses the follower's own screen anchor.
        s.transforms["free"] = HudTransform(offsetX = 17f, link = below("t", follow = false).copy(fixed = true))
        near(17f, s.box("free").x0)
    }

    @Test
    fun pushedBackOnScreenAtTheEdge() {
        val s = Store()
        s.add("t", 100f, 50f, HudTransform(hAnchor = HudAnchor.END, offsetY = 100f))
        // Glued under its left edge but wider than the room to the right: pushed left, still below.
        s.add("f", 150f, 10f, HudTransform(link = below("t")))
        val b = s.box("f")
        assertBox(810f, 155f, b)
        near(-50f, b.info.clampX)
        assertTrue(!b.info.flipped)
    }

    @Test
    fun flipsToTheOtherSideWhenItsSideHasNoRoom() {
        val s = Store()
        s.add("t", 100f, 50f, HudTransform(hAnchor = HudAnchor.END, offsetY = 100f))
        s.add("f", 40f, 10f, HudTransform(link = HudLink(HudTargets.hud("t"), h = HudPlace.OUT_END, v = HudPlace.START, offsetX = 5f, follow = true)))
        val b = s.box("f")
        assertTrue(b.info.flipped)
        assertBox(815f, 100f, b)
        // Stored link untouched.
        assertEquals(HudPlace.OUT_END, s.transforms.getValue("f").link!!.h)
        // Bottom edge: below has no room, so above.
        s.transforms["t"] = HudTransform(vAnchor = HudAnchor.END, offsetX = 100f)
        s.transforms["f"] = HudTransform(link = below("t"))
        assertBox(100f, 475f, s.box("f"))
    }

    @Test
    fun cycleBreaksOnTheScreenAnchor() {
        val s = Store()
        s.add("a", 50f, 20f, HudTransform(offsetX = 10f, offsetY = 10f, link = below("b")))
        s.add("b", 50f, 20f, HudTransform(offsetX = 300f, offsetY = 300f, link = below("a")))
        val layout = s.layout()
        val a = layout.box("a")!!
        val b = layout.box("b")!!
        // b closes the cycle and sits on its own anchor; a follows it.
        assertNotNull(b.info.cycle)
        assertBox(300f, 300f, b)
        assertBox(300f, 325f, a)
    }

    @Test
    fun hiddenTargetFallsBackOrHandsOverItsPlace() {
        val s = Store()
        s.add("t", 100f, 50f, HudTransform(offsetX = 200f, offsetY = 100f))
        s.add("f", 40f, 10f, HudTransform(offsetX = 5f, offsetY = 6f, link = below("t")))
        s.hidden += "t"
        val b = s.box("f")
        assertBox(5f, 6f, b)
        assertEquals(HudTargets.hud("t"), b.info.hiddenTarget)
        s.transforms["f"] = s.transforms.getValue("f").copy(link = below("t").copy(whenHidden = HudLinkHidden.TAKE_PLACE))
        val taken = s.box("f")
        assertBox(200f, 100f, taken)
        assertEquals(HudTargets.hud("t"), taken.info.tookPlaceOf)
    }

    @Test
    fun vanillaTargetsAnyGuiScale() {
        val s = Store(w = 960f, h = 540f)
        // Hotbar at GUI scale 3 against Soul scale 2: factor 2/3 → GUI px × 1.5 in Soul px.
        val f = 2f / 3f
        fun soul(vararg r: Int) = floatArrayOf(r[0] / f, r[1] / f, r[2] / f, r[3] / f)
        s.vanilla = mapOf(VanillaIds.HOTBAR to VanillaElement(VanillaIds.HOTBAR, "Hotbar", soul(229, 338, 411, 360), false, soul(229, 338, 411, 360)))
        s.add("sprint", 70f, 21f, HudTransform(link = HudLink(HudTargets.vanilla(VanillaIds.HOTBAR), h = HudPlace.OUT_START, v = HudPlace.OUT_START, offsetX = -4f, follow = true)))
        val b = s.box("sprint")
        near(229 / f - 4f, b.x1)
        near(338 / f, b.y1)
    }

    // ---------------------------------------------------------------- edits

    @Test
    fun dropAttachesWithCornerOrCenterGlue() {
        val s = Store()
        val edits = HudEdits(s) { 5f }
        s.add("t", 100f, 50f, HudTransform(offsetX = 200f, offsetY = 100f))
        s.add("f", 40f, 10f, HudTransform())
        // Dropped under it with left edges lined up: glued at the corner.
        edits.attachTo("f", HudTargets.hud("t"), floatArrayOf(200f, 100f, 300f, 150f), HudPlace.START, HudPlace.OUT_END, HudBox(200f, 155f, 40f, 10f))
        var l = s.transforms.getValue("f").link!!
        assertTrue(l.follow)
        assertEquals(HudPlace.START, l.h)
        assertEquals(5f, l.gap)
        assertBox(200f, 155f, s.box("f"))
        // Centered under it: follows its center.
        edits.attachTo("f", HudTargets.hud("t"), floatArrayOf(200f, 100f, 300f, 150f), HudPlace.CENTER, HudPlace.OUT_END, HudBox(230f, 155f, 40f, 10f))
        l = s.transforms.getValue("f").link!!
        assertTrue(l.follow)
        assertEquals(HudPlace.CENTER, l.h)
        // Not lined up: only the side's axis follows.
        edits.attachTo("f", HudTargets.hud("t"), floatArrayOf(200f, 100f, 300f, 150f), HudPlace.START, HudPlace.OUT_END, HudBox(213f, 155f, 40f, 10f))
        l = s.transforms.getValue("f").link!!
        assertTrue(!l.follow)
        assertBox(213f, 155f, s.box("f"))
    }

    @Test
    fun detachAndReanchorKeepTheHudInPlace() {
        val s = Store()
        val edits = HudEdits(s) { 5f }
        s.add("t", 100f, 50f, HudTransform(offsetX = 700f, offsetY = 100f))
        s.add("f", 40f, 10f, HudTransform(link = below("t")))
        val before = s.box("f")
        edits.detach("f")
        assertNull(s.transforms.getValue("f").link)
        assertBox(before.x0, before.y0, s.box("f"))
        edits.setScreenAnchor("f", HudAnchor.CENTER, HudAnchor.END)
        val t = s.transforms.getValue("f")
        assertEquals(HudAnchor.CENTER, t.hAnchor)
        assertTrue(!t.autoAnchor)
        assertBox(before.x0, before.y0, s.box("f"))
    }

    @Test
    fun pickedTargetTakesTheNearestSideOnScreen() {
        val s = Store()
        val edits = HudEdits(s) { 5f }
        s.add("t", 100f, 50f, HudTransform(offsetX = 200f, offsetY = 100f))
        s.add("f", 40f, 10f, HudTransform(offsetX = 320f, offsetY = 110f))
        assertTrue(edits.setLinkTarget("f", HudTargets.hud("t")))
        val l = s.transforms.getValue("f").link!!
        assertEquals(HudPlace.OUT_END, l.h)
        assertBox(305f, 110f, s.box("f"))
        assertTrue(edits.dependsOn("f", "t"))
    }

    @Test
    fun snapAndAttachMath() {
        val guides = ArrayList<Float>()
        near(2f, HudSnap.snapAxis(98f, 20f, listOf(100f), guides)!!)
        assertEquals(listOf(100f), guides)
        assertNull(HudSnap.snapAxis(80f, 10f, listOf(100f), ArrayList()))
        // Equal widths: the edge beats the center on a tie.
        assertEquals(0f, HudSnap.glueSnap(101f, 100f, 100f, 200f)!![1])
        val hit = HudSnap.findLink(HudBox(205f, 157f, 40f, 10f), listOf(HudSnap.Candidate("hud:t", floatArrayOf(200f, 100f, 300f, 150f))), 5f)!!
        assertEquals(HudPlace.OUT_END, hit.v)
        assertNull(HudSnap.findLink(HudBox(205f, 175f, 40f, 10f), listOf(HudSnap.Candidate("hud:t", floatArrayOf(200f, 100f, 300f, 150f))), 5f))
    }
}
