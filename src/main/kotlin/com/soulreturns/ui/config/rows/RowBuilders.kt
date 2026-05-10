package com.soulreturns.ui.config.rows

import com.mojang.blaze3d.platform.InputConstants
import com.soulreturns.render.DrawContextRenderer
import com.soulreturns.ui.components.SoulSlider
import com.soulreturns.ui.components.SoulToggle
import com.soulreturns.ui.config.components.ConfigRenderers
import com.soulreturns.ui.config.model.ActionRowSpec
import com.soulreturns.ui.config.model.ConfigScreenContext
import com.soulreturns.ui.config.model.LinkTarget
import com.soulreturns.ui.config.registry.ConfigSections
import com.soulreturns.ui.theme.Theme
import io.wispforest.owo.config.ConfigWrapper
import io.wispforest.owo.config.Option
import io.wispforest.owo.config.annotation.RangeConstraint
import io.wispforest.owo.ui.component.ButtonComponent
import io.wispforest.owo.ui.component.TextBoxComponent
import io.wispforest.owo.ui.component.UIComponents
import io.wispforest.owo.ui.container.FlowLayout
import io.wispforest.owo.ui.container.UIContainers
import io.wispforest.owo.ui.core.Insets
import io.wispforest.owo.ui.core.Sizing
import io.wispforest.owo.ui.core.VerticalAlignment
import net.minecraft.client.Minecraft
import net.minecraft.network.chat.Component
import java.util.Locale

/**
 * Builds individual rows — option toggles, sliders, text boxes, keybind pickers, action
 * buttons, link buttons — and the labeled-card sections that group them.
 *
 * Lifecycle: one instance per [ConfigScreenContext] (i.e. per screen lifetime). Call
 * [resetTracking] at the start of each content rebuild to drop stale reset-button references.
 */
internal class RowBuilders(
    private val ctx: ConfigScreenContext,
    private val wrapper: ConfigWrapper<*>,
) {
    /**
     * Per-option reset-button slot containers, keyed by option full path. Buttons get added /
     * removed as the value diverges from / matches the default. Cleared by [resetTracking].
     */
    private val resetSlots = mutableMapOf<String, FlowLayout>()

    /** Drop tracked button references — call once before rebuilding the content body. */
    fun resetTracking() {
        resetSlots.clear()
    }

    // ───────────────────── option-row card sections ─────────────────────

    /** Render a labeled card containing one-or-more option rows. `label = null` → no header. */
    fun addOptionSection(
        parent: FlowLayout,
        label: String?,
        opts: List<Option<*>>
    ) {
        if (label != null) {
            val lbl =
                UIComponents.label(Component.literal(label))
                    .color(Theme.color(Theme.TEXT_DIM))
            lbl.margins(Insets.of(4, 0, 0, 6))
            parent.child(lbl)
        }
        val card = UIContainers.verticalFlow(Sizing.fill(100), Sizing.content())
        card.surface(Theme.panelInsetSurface)
        card.padding(Insets.of(8))
        card.gap(2)
        for (opt in opts) card.child(buildOptionRow(opt))
        parent.child(card)
    }

    /** Render a labeled section of cross-navigation link buttons (no card wrap). */
    fun addLinkSection(
        parent: FlowLayout,
        label: String,
        links: List<LinkTarget>
    ) {
        val lbl =
            UIComponents.label(Component.literal(label))
                .color(Theme.color(Theme.TEXT_DIM))
        lbl.margins(Insets.of(4, 0, 0, 6))
        parent.child(lbl)
        val container = UIContainers.verticalFlow(Sizing.fill(100), Sizing.content())
        container.gap(4)
        for (link in links) container.child(buildLinkRow(link))
        parent.child(container)
    }

    /** Render a labeled card containing one-or-more action rows (label + right-aligned button). */
    fun addActionSection(
        parent: FlowLayout,
        label: String,
        rows: List<ActionRowSpec>
    ) {
        val lbl =
            UIComponents.label(Component.literal(label))
                .color(Theme.color(Theme.TEXT_DIM))
        lbl.margins(Insets.of(4, 0, 0, 6))
        parent.child(lbl)
        val card = UIContainers.verticalFlow(Sizing.fill(100), Sizing.content())
        card.surface(Theme.panelInsetSurface)
        card.padding(Insets.of(8))
        card.gap(2)
        for (r in rows) card.child(buildActionRow(r))
        parent.child(card)
    }

    // ───────────────────── individual rows ─────────────────────

    fun buildOptionRow(opt: Option<*>): FlowLayout {
        val row = UIContainers.horizontalFlow(Sizing.fill(100), Sizing.content())
        row.surface(Theme.rowSurface())
        row.padding(Insets.of(6, 6, 12, 12))
        row.gap(8)
        row.verticalAlignment(VerticalAlignment.CENTER)
        row.margins(Insets.of(1))

        // Visual hint that this option is gated by another option above (e.g. usePestVest under highlightPestEquipment).
        val isDependent = opt.key().path().joinToString(".") in ConfigSections.optionVisibility
        if (isDependent) {
            val arrow =
                UIComponents.label(Component.literal("↳"))
                    .color(Theme.color(Theme.TEXT_DIM))
            arrow.margins(Insets.of(0, 0, 8, 0))
            row.child(arrow)
        }

        val label =
            UIComponents.label(Component.translatable(opt.translationKey()))
                .color(Theme.color(Theme.TEXT))
        label.horizontalSizing(Sizing.expand())
        val tooltipKey = opt.translationKey() + ".tooltip"
        val tooltipText = Component.translatable(tooltipKey)
        if (tooltipText.string != tooltipKey) label.tooltip(tooltipText)
        row.child(label)

        val pathKey = opt.key().path().joinToString(".")
        when {
            opt.value() is Boolean ->
                row.child(buildToggle(@Suppress("UNCHECKED_CAST") (opt as Option<Boolean>)))
            opt.value() is Int || opt.value() is Long || opt.value() is Float || opt.value() is Double ->
                row.child(buildNumericSlider(opt))
            opt.value() is String && pathKey in ConfigSections.keybindOptions ->
                row.child(buildKeybindButton(@Suppress("UNCHECKED_CAST") (opt as Option<String>)))
            opt.value() is String ->
                row.child(buildTextBox(@Suppress("UNCHECKED_CAST") (opt as Option<String>)))
            else -> row.child(UIComponents.label(Component.literal(opt.value().toString())))
        }

        // Fixed-width slot; reset button is added/removed dynamically to avoid phantom hover.
        val slot = UIContainers.horizontalFlow(Sizing.fixed(18), Sizing.fixed(16))
        resetSlots[pathKey] = slot
        if (opt.value() != opt.defaultValue()) slot.child(buildResetIconButton(opt))
        row.child(slot)
        return row
    }

    fun buildActionRow(action: ActionRowSpec): FlowLayout {
        val row = UIContainers.horizontalFlow(Sizing.fill(100), Sizing.content())
        row.surface(Theme.rowSurface())
        row.padding(Insets.of(6, 6, 12, 12))
        row.gap(8)
        row.verticalAlignment(VerticalAlignment.CENTER)
        row.margins(Insets.of(1))

        val label =
            UIComponents.label(Component.literal(action.label))
                .color(Theme.color(Theme.TEXT))
        label.horizontalSizing(Sizing.expand())
        row.child(label)

        val btn = UIComponents.button(Component.literal(action.buttonText)) { action.action(ctx) }
        btn.horizontalSizing(Sizing.fixed(80))
        btn.verticalSizing(Sizing.fixed(20))
        btn.renderer(ConfigRenderers.actionButton())
        row.child(btn)
        return row
    }

    fun buildLinkRow(link: LinkTarget): ButtonComponent {
        val btn =
            UIComponents.button(Component.empty()) {
                ctx.navigateTo(link.targetCat, link.targetSub)
            }
        btn.horizontalSizing(Sizing.fill(100))
        btn.verticalSizing(Sizing.fixed(28))
        btn.margins(Insets.of(2))
        btn.renderer(
            ButtonComponent.Renderer { ctx2, button, _ ->
                val bg = if (button.isHovered) Theme.PANEL_HOVER else Theme.PANEL_INSET
                DrawContextRenderer.roundedFill(
                    ctx2,
                    button.x,
                    button.y,
                    button.x + button.width,
                    button.y + button.height,
                    bg,
                    Theme.ITEM_RADIUS,
                )
                val tr = Minecraft.getInstance().font
                val text = "${link.label}  ›"
                val tx = button.x + 12
                val ty = button.y + (button.height - tr.lineHeight) / 2
                val color = if (button.isHovered) Theme.ACCENT else Theme.TEXT
                ctx2.drawString(tr, Component.literal(text), tx, ty, color, false)
            }
        )
        return btn
    }

    // ───────────────────── individual controls ─────────────────────

    @Suppress("UNCHECKED_CAST")
    private fun buildToggle(opt: Option<Boolean>): SoulToggle {
        val key = opt.key().path().joinToString(".")
        return SoulToggle(opt.value()) { newVal ->
            opt.set(newVal)
            ctx.save()
            refreshResetSlot(opt)
            if (key in ConfigSections.rebuildOnChange) ctx.rebuildContentBody()
        }
    }

    private fun buildNumericSlider(opt: Option<*>): SoulSlider {
        val field = wrapper.fieldForKey(opt.key())
        val rc = field?.getAnnotation(RangeConstraint::class.java)
        val min: Double = rc?.min?.toDouble() ?: 0.0
        val max: Double = rc?.max?.toDouble() ?: 100.0
        val decimals: Int = rc?.decimalPlaces ?: 0

        val slider = SoulSlider(min, max, (opt.value() as Number).toDouble(), decimals)
        slider.onChanged { v ->
            @Suppress("UNCHECKED_CAST")
            when (opt.value()) {
                is Int -> (opt as Option<Int>).set(v.toInt())
                is Long -> (opt as Option<Long>).set(v.toLong())
                is Float -> (opt as Option<Float>).set(v.toFloat())
                is Double -> (opt as Option<Double>).set(v)
                else -> {}
            }
        }
        slider.onSlideEnd {
            ctx.save()
            refreshResetSlot(opt)
        }
        return slider
    }

    private fun buildTextBox(opt: Option<String>): TextBoxComponent {
        val tb = UIComponents.textBox(Sizing.fixed(160), opt.value())
        tb.onChanged().subscribe(
            TextBoxComponent.OnChanged { newVal ->
                opt.set(newVal)
                ctx.save()
            }
        )
        return tb
    }

    private fun buildKeybindButton(opt: Option<String>): ButtonComponent {
        val text = if (ctx.isCapturing(opt)) "> Press a key <" else keybindLabel(opt.value())
        val btn =
            UIComponents.button(Component.literal(text)) {
                // Click the same button while capturing → cancel capture (screen handles the toggle).
                ctx.requestKeybindCapture(opt)
                ctx.rebuildContentBody()
            }
        btn.horizontalSizing(Sizing.fixed(110))
        btn.verticalSizing(Sizing.fixed(20))
        btn.renderer(ConfigRenderers.actionButton())
        return btn
    }

    /** Pretty-print a stored key name like `key.keyboard.f6` → `F6`. Empty string → `Not bound`. */
    fun keybindLabel(stored: String): String {
        if (stored.isBlank()) return "Not bound"
        return try {
            InputConstants.getKey(stored).getDisplayName().string
        } catch (_: Throwable) {
            stored
        }
    }

    // ───────────────────── reset button (per-option) ─────────────────────

    @Suppress("UNCHECKED_CAST")
    private fun buildResetIconButton(opt: Option<*>): ButtonComponent {
        val default = opt.defaultValue()
        val btn =
            UIComponents.button(Component.empty()) {
                (opt as Option<Any>).set(default as Any)
                ctx.save()
                ctx.rebuildContent()
            }
        btn.horizontalSizing(Sizing.fill(100))
        btn.verticalSizing(Sizing.fill(100))
        btn.tooltip(Component.literal("Reset to default: ${formatValue(default)}"))
        btn.renderer(
            ButtonComponent.Renderer { gctx, button, _ ->
                val bg = if (button.isHovered) Theme.PANEL_HOVER else Theme.PANEL_INSET
                gctx.fill(button.x, button.y, button.x + button.width, button.y + button.height, bg)
                val tr = Minecraft.getInstance().font
                val icon = "↺"
                val tx = button.x + (button.width - tr.width(icon)) / 2
                val ty = button.y + (button.height - tr.lineHeight) / 2
                gctx.drawString(tr, Component.literal(icon), tx, ty, if (button.isHovered) Theme.ACCENT else Theme.TEXT_DIM, false)
            }
        )
        return btn
    }

    fun refreshResetSlot(opt: Option<*>) {
        val slot = resetSlots[opt.key().path().joinToString(".")] ?: return
        slot.clearChildren()
        if (opt.value() != opt.defaultValue()) slot.child(buildResetIconButton(opt))
    }

    private fun formatValue(value: Any?): String =
        when (value) {
            is Float -> String.format(Locale.ROOT, "%.2f", value)
            is Double -> String.format(Locale.ROOT, "%.2f", value)
            else -> value?.toString() ?: "null"
        }
}
