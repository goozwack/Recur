package dev.recur.client;

import net.minecraft.client.gui.GuiGraphicsExtractor;

/**
 * Every colour Recur's screens use: plain greys, with blue for checkpoints and the clone settings, and brighter colours
 * for the things that should stand out (the agreed checkpoint, pending votes, stars, deleting).
 */
public final class Theme {
	private Theme() {}

	// Button edge colours.
	public static final int GREY = 0xFF9A9A9A;
	public static final int BLUE = 0xFF5B8DD6;
	public static final int GREEN = 0xFF55E58A;
	public static final int YELLOW = 0xFFFFE04A;
	public static final int RED = 0xFFFF5A5A;

	// Panels.
	public static final int PANEL = 0xE0202020;
	public static final int PANEL_LIT = 0xE8343434;
	public static final int PANEL_EDGE = 0xFF505050;
	public static final int DISABLED_EDGE = 0xFF3C3C3C;
	/** The colour of the pause menu bar and the loading screen (without alpha). */
	public static final int SHADE_RGB = 0x141414;

	// Text.
	public static final int TITLE = 0xFFFFFFFF;
	public static final int TEXT = 0xFFE0E0E0;
	public static final int TEXT_DIM = 0xFFA8A8A8;
	public static final int TEXT_OFF = 0xFF707070;
	public static final int WARNING = 0xFFE05555;

	/** A plain framed box for dialogs and lists. */
	public static void panel(GuiGraphicsExtractor g, int x, int y, int w, int h) {
		g.fill(x, y, x + w, y + h, PANEL);
		g.fill(x, y, x + w, y + 1, PANEL_EDGE);
		g.fill(x, y + h - 1, x + w, y + h, PANEL_EDGE);
		g.fill(x, y + 1, x + 1, y + h - 1, PANEL_EDGE);
		g.fill(x + w - 1, y + 1, x + w, y + h - 1, PANEL_EDGE);
	}

	/** Mixes two colours (t = 0 gives a, t = 1 gives b), alpha included. */
	public static int mix(int a, int b, float t) {
		t = Math.max(0f, Math.min(1f, t));
		int aa = a >>> 24, ar = (a >> 16) & 0xFF, ag = (a >> 8) & 0xFF, ab = a & 0xFF;
		int ba = b >>> 24, br = (b >> 16) & 0xFF, bg = (b >> 8) & 0xFF, bb = b & 0xFF;
		return (Math.round(aa + (ba - aa) * t) << 24) | (Math.round(ar + (br - ar) * t) << 16)
			| (Math.round(ag + (bg - ag) * t) << 8) | Math.round(ab + (bb - ab) * t);
	}
}
