package dev.recur.client;

import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.Font;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.input.InputWithModifiers;
import net.minecraft.client.renderer.RenderPipelines;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.Identifier;

/**
 * Recur's button: a charcoal panel with a coloured edge and a pixel-art icon (or a picture) on the left. The corner
 * pixels are left empty so the edge looks slightly rounded. Hovering brightens the edge and the panel. A primary
 * button is filled with its colour so it stands out as the main choice on its screen. Buttons with no text show only
 * the icon, centred. A pending button has a dashed yellow outline.
 */
public class LoopButton extends Button {
	/** Icons take about 12 GUI pixels (snapped to whole screen pixels per art pixel, see PixelIcon.drawSnapped). */
	private static final int ICON_PX = 12;
	private static final long DOUBLE_PRESS_MS = 200;
	/** Every 6 pixels of a pending button's outline, this many are drawn. */
	private static final int DASH = 3;

	private PixelIcon icon;
	private final int accent;
	private boolean selected;
	private boolean primary;
	private boolean pending;
	private String rightText;
	private Identifier picture;
	private int pictureWidth, pictureHeight;
	private long lastPress;
	private float scale = 1f;

	public LoopButton(int x, int y, int width, int height, Component message, PixelIcon icon, int accent, OnPress onPress) {
		super(x, y, width, height, message, onPress, DEFAULT_NARRATION);
		this.icon = icon;
		this.accent = accent;
	}

	public LoopButton selected(boolean selected) { this.selected = selected; return this; }

	/** Marks the main choice on its screen, which is drawn filled with its colour. */
	public LoopButton primary() { this.primary = true; return this; }

	/** A dashed yellow outline, plus a short text on the right (e.g. "1/2"). */
	public LoopButton pending(boolean pending, String rightText) { this.pending = pending; this.rightText = rightText; return this; }

	/** Shows a picture instead of the icon (a texture of the given size, drawn to fit the button's height). */
	public LoopButton picture(Identifier texture, int textureWidth, int textureHeight) {
		this.picture = texture; this.pictureWidth = textureWidth; this.pictureHeight = textureHeight; return this;
	}

	public void setIcon(PixelIcon icon) { this.icon = icon; }

	/** Draws everything this many times bigger (the widget's own size should already be scaled to match). */
	public LoopButton scale(float scale) { this.scale = scale; return this; }

	/** Some setups deliver a click twice, so a second press within 200 ms is ignored. */
	@Override
	public void onPress(InputWithModifiers input) {
		long now = System.currentTimeMillis();
		if (now - lastPress < DOUBLE_PRESS_MS) return;
		lastPress = now;
		super.onPress(input);
	}

	@Override
	protected void extractContents(GuiGraphicsExtractor g, int mouseX, int mouseY, float partial) {
		if (scale == 1f) { drawBody(g, getX(), getY(), getWidth(), getHeight()); return; }
		var pose = g.pose();
		pose.pushMatrix();
		pose.translate(getX(), getY());
		pose.scale(scale, scale);
		drawBody(g, 0, 0, Math.round(getWidth() / scale), Math.round(getHeight() / scale));
		pose.popMatrix();
	}

	private void drawBody(GuiGraphicsExtractor g, int x, int y, int w, int h) {
		float alpha = this.alpha;
		boolean hovered = this.active && this.isHoveredOrFocused();
		boolean lit = hovered || selected;

		int edge;
		int body;
		if (!this.active) {
			edge = Theme.DISABLED_EDGE;
			body = Theme.PANEL;
		} else if (primary) {
			edge = lit ? Theme.mix(accent, 0xFFFFFFFF, 0.25f) : accent;
			body = Theme.mix(Theme.PANEL, accent, lit ? 0.7f : 0.55f);
		} else {
			edge = lit ? accent : Theme.mix(0xFF000000, accent, 0.5f);
			body = lit ? Theme.mix(Theme.PANEL_LIT, accent & 0x00FFFFFF | 0xF2000000, 0.12f) : Theme.PANEL;
		}

		// The panel fills only the inside of the edge, which leaves the four corner pixels empty.
		g.fill(x + 1, y + 1, x + w - 1, y + h - 1, PixelIcon.withAlpha(body, alpha));
		int edgeColor = PixelIcon.withAlpha(edge, alpha);
		g.fill(x + 1, y, x + w - 1, y + 1, edgeColor);
		g.fill(x + 1, y + h - 1, x + w - 1, y + h, edgeColor);
		g.fill(x, y + 1, x + 1, y + h - 1, edgeColor);
		g.fill(x + w - 1, y + 1, x + w, y + h - 1, edgeColor);
		// A one-pixel bevel: lighter along the top, darker along the bottom.
		g.fill(x + 1, y + 1, x + w - 1, y + 2, PixelIcon.withAlpha(0x1EFFFFFF, alpha));
		g.fill(x + 1, y + h - 2, x + w - 1, y + h - 1, PixelIcon.withAlpha(0x40000000, alpha));
		if (pending) drawDashedOutline(g, x, y, w, h, alpha);

		Font font = Minecraft.getInstance().font;
		String text = getMessage().getString();

		// The left side shows either a picture (a checkpoint's thumbnail) or the pixel icon.
		int leftWidth;
		int leftX = text.isEmpty() ? x + (w - ICON_PX) / 2 : x + 5;
		if (picture != null && !text.isEmpty()) {
			int pictureH = h - 4, pictureW = Math.round(pictureH * pictureWidth / (float) pictureHeight);
			int pictureY = y + 2;
			g.fill(leftX - 1, pictureY - 1, leftX + pictureW + 1, pictureY + pictureH + 1, PixelIcon.withAlpha(edge, alpha));
			g.blit(RenderPipelines.GUI_TEXTURED, picture, leftX, pictureY, 0f, 0f, pictureW, pictureH,
				pictureWidth, pictureHeight, pictureWidth, pictureHeight);
			leftWidth = pictureW;
		} else {
			icon.drawSnapped(g, leftX, y + (h - ICON_PX) / 2f, ICON_PX / (float) PixelIcon.ART, this.active ? alpha : alpha * 0.45f);
			leftWidth = ICON_PX;
		}
		if (text.isEmpty()) return;

		int textX = leftX + leftWidth + 6;
		int rightWidth = 0;
		if (rightText != null && !rightText.isEmpty()) {
			rightWidth = font.width(rightText) + 6;
			g.text(font, rightText, x + w - rightWidth, y + (h - 8) / 2, PixelIcon.withAlpha(pending ? Theme.YELLOW : Theme.TEXT_DIM, alpha));
		}
		int maxWidth = x + w - 4 - rightWidth - textX;
		if (font.width(text) > maxWidth) {
			while (text.length() > 1 && font.width(text + "...") > maxWidth) text = text.substring(0, text.length() - 1);
			text += "...";
		}
		int textColor = !this.active ? Theme.TEXT_OFF : hovered || primary ? 0xFFFFFFFF : Theme.TEXT;
		g.text(font, text, textX, y + (h - 8) / 2, PixelIcon.withAlpha(textColor, alpha));
	}

	/** A yellow dashed border (3 pixels on, 3 off) around the button. */
	private static void drawDashedOutline(GuiGraphicsExtractor g, int x, int y, int w, int h, float alpha) {
		int color = PixelIcon.withAlpha(Theme.YELLOW, alpha);
		// Walk the border clockwise (top, right, bottom, left).
		int i = 0;
		i = dashes(g, x, y, 1, 0, w, i, color);
		i = dashes(g, x + w - 1, y + 1, 0, 1, h - 1, i, color);
		i = dashes(g, x + w - 2, y + h - 1, -1, 0, w - 1, i, color);
		dashes(g, x, y + h - 2, 0, -1, h - 2, i, color);
	}

	/** The drawn parts of one straight edge, each as a single rectangle. Returns the perimeter index after it. */
	private static int dashes(GuiGraphicsExtractor g, int x, int y, int dx, int dy, int length, int start, int color) {
		int k = 0;
		while (k < length) {
			boolean on = ((start + k) % (DASH * 2)) < DASH;
			int run = 1;
			while (k + run < length && (((start + k + run) % (DASH * 2)) < DASH) == on) run++;
			if (on) {
				int x0 = x + dx * k, y0 = y + dy * k, x1 = x + dx * (k + run - 1), y1 = y + dy * (k + run - 1);
				g.fill(Math.min(x0, x1), Math.min(y0, y1), Math.max(x0, x1) + 1, Math.max(y0, y1) + 1, color);
			}
			k += run;
		}
		return start + length;
	}
}
