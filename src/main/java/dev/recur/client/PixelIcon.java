package dev.recur.client;

import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.renderer.RenderPipelines;
import net.minecraft.resources.Identifier;

/** The button icons. They're 16x16 sprites in textures/gui/sprites/icon (the checkmark is Minecraft's own). */
public enum PixelIcon {
	HOURGLASS, FLAG, FLAG_PLUS, EYE, EYE_OFF, GHOST, EYE_CLOSED, PENCIL, CLOCK, LEFT, RIGHT, UP, DOWN, GRAPH, CROSS,
	SPEAKER, SPEAKER_OFF, CHAT, CHAT_OFF, STAR, STAR_OFF,
	CHECK(Identifier.withDefaultNamespace("icon/checkmark"), 9, 8);

	/** Size on screen at scale 1, in GUI pixels. */
	public static final int SIZE = 8;
	public static final int ART = 16;

	private final Identifier sprite;
	private final int width, height;
	// our own icons fill the whole 16px; the vanilla checkmark is drawn 1:1 inside a 12px box instead
	private final boolean own;

	PixelIcon() {
		this.sprite = Identifier.fromNamespaceAndPath("recur", "icon/" + name().toLowerCase(java.util.Locale.ROOT));
		this.width = this.height = ART;
		this.own = true;
	}

	PixelIcon(Identifier sprite, int width, int height) {
		this.sprite = sprite;
		this.width = width;
		this.height = height;
		this.own = false;
	}

	// main/accent are unused now, the sprites have their own colours
	public void draw(GuiGraphicsExtractor g, int x, int y, int scale, int main, int accent, float alpha) {
		drawSnapped(g, x, y, scale * (float) SIZE / ART, alpha);
	}

	/**
	 * Draws with each art pixel about `unit` GUI pixels big, rounded to whole screen pixels so the pixels stay square at
	 * any GUI scale. Stays centred where it would have been.
	 */
	public void drawSnapped(GuiGraphicsExtractor g, float x, float y, float unit, float alpha) {
		var pose = g.pose();
		int gui = Minecraft.getInstance().getWindow().getGuiScale();
		float sx = (float) Math.hypot(pose.m00(), pose.m01());
		float half = unit * ART / 2f;
		float cx = (pose.m00() * (x + half) + pose.m10() * (y + half) + pose.m20()) * gui;
		float cy = (pose.m01() * (x + half) + pose.m11() * (y + half) + pose.m21()) * gui;
		int px = Math.max(1, Math.round(own ? unit * sx * gui : unit * ART / 12f * sx * gui));
		int w = width * px, h = height * px;

		pose.pushMatrix();
		pose.identity();
		pose.scale(1f / gui, 1f / gui);
		g.blitSprite(RenderPipelines.GUI_TEXTURED, sprite, Math.round(cx - w / 2f), Math.round(cy - h / 2f), w, h, withAlpha(0xFFFFFFFF, alpha));
		pose.popMatrix();
	}

	static int withAlpha(int argb, float alpha) {
		int a = Math.round(((argb >>> 24) & 0xFF) * alpha);
		return (a << 24) | (argb & 0xFFFFFF);
	}
}
