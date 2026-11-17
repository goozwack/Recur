package dev.recur.client;

import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.Font;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.components.AbstractSliderButton;
import net.minecraft.client.input.MouseButtonEvent;
import net.minecraft.network.chat.Component;

import java.util.function.DoubleConsumer;
import java.util.function.DoubleFunction;

/** A slider in the same style as {@link LoopButton}: dark panel, accent-coloured fill and a bright handle. */
public class LoopSlider extends AbstractSliderButton {
	private final int accent;
	private final DoubleFunction<String> label;
	private DoubleConsumer onChange;
	private final Runnable onRelease;

	public LoopSlider(int x, int y, int w, int h, double value, int accent, DoubleFunction<String> label,
					  DoubleConsumer onChange, Runnable onRelease) {
		super(x, y, w, h, Component.literal(label.apply(value)), value);
		this.accent = accent; this.label = label; this.onChange = onChange; this.onRelease = onRelease;
	}

	/** Replaces what happens when the value changes (for a handler that needs the slider itself). */
	public void setChange(DoubleConsumer onChange) { this.onChange = onChange; }

	public void set(double v) { this.value = Math.max(0, Math.min(1, v)); updateMessage(); }

	@Override protected void updateMessage() { setMessage(Component.literal(label.apply(value))); }

	@Override protected void applyValue() { onChange.accept(value); }

	@Override
	public void onRelease(MouseButtonEvent event) {
		super.onRelease(event);
		onRelease.run();
	}

	@Override
	public void extractWidgetRenderState(GuiGraphicsExtractor g, int mouseX, int mouseY, float a) {
		var pose = g.pose();
		pose.pushMatrix();
		pose.translate(getX(), getY());
		pose.scale(scale, scale);
		drawBody(g, 0, 0, Math.round(getWidth() / scale), Math.round(getHeight() / scale));
		pose.popMatrix();
		handleCursor(g);
	}

	private float scale = 1f;

	public LoopSlider scale(float scale) { this.scale = scale; return this; }

	private void drawBody(GuiGraphicsExtractor g, int x, int y, int w, int h) {
		boolean hot = isHoveredOrFocused();
		g.fill(x + 1, y + 1, x + w - 1, y + h - 1, hot ? Theme.PANEL_LIT : Theme.PANEL); // The corner pixels are left empty.
		int fillW = (int) Math.round(value * (w - 2));
		g.fill(x + 1, y + 1, x + 1 + fillW, y + h - 1, (accent & 0x00FFFFFF) | (hot ? 0x70000000 : 0x50000000));
		int edge = hot ? accent : Theme.mix(0xFF000000, accent, 0.5f);
		g.fill(x + 1, y, x + w - 1, y + 1, edge);
		g.fill(x + 1, y + h - 1, x + w - 1, y + h, edge);
		g.fill(x, y + 1, x + 1, y + h - 1, edge);
		g.fill(x + w - 1, y + 1, x + w, y + h - 1, edge);
		int hx = x + 1 + Math.max(0, Math.min(w - 5, fillW - 2));
		g.fill(hx, y + 2, hx + 3, y + h - 2, Theme.TEXT);
		Font font = Minecraft.getInstance().font;
		String text = getMessage().getString();
		g.text(font, text, x + (w - font.width(text)) / 2, y + (h - 8) / 2, Theme.TEXT);
	}
}
