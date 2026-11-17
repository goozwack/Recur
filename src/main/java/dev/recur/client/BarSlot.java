package dev.recur.client;

import net.minecraft.client.gui.components.AbstractWidget;

/** A widget in the pause-menu bar's button row: its x offset and width at scale 1. */
public record BarSlot(AbstractWidget widget, int baseX, int baseW) {}
