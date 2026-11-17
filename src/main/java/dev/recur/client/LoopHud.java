package dev.recur.client;

import net.minecraft.client.DeltaTracker;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.Font;
import net.minecraft.client.gui.GuiGraphicsExtractor;

/** The small time-left counter (top right) and the loading screen shown while the world resets. */
public final class LoopHud {
	private LoopHud() {}

	private static final int SPINNER_DOTS = 12;
	private static final int PIXEL = 3;

	public static void render(GuiGraphicsExtractor g, DeltaTracker delta) {
		Minecraft mc = Minecraft.getInstance();
		int w = mc.getWindow().getGuiScaledWidth(), h = mc.getWindow().getGuiScaledHeight();
		Font font = mc.font;

		if (ClientLoopState.resetTicks >= 0) {
			renderLoading(g, font, w, h);
			return;
		}
		if (ClientLoopState.timedTicks >= 0) {
			String time = "Reset in " + ClientLoopState.timedLeft();
			int color = ClientLoopState.timedTicks < 20 * 10 ? Theme.WARNING : Theme.TEXT;
			g.text(font, time, w - font.width(time) - 4, 4, color);
			return;
		}
		if (!ClientLoopState.active) return;

		if (ClientLoopState.remainingTicks < 0) return; // no time limit, so no timer
		String time = ClientLoopState.timeLeft();
		int color = ClientLoopState.remainingTicks < 20 * 30 ? Theme.WARNING : Theme.TEXT;
		g.text(font, time, w - font.width(time) - 4, 4, color);
	}

	private static void renderLoading(GuiGraphicsExtractor g, Font font, int w, int h) {
		float alpha = ClientLoopState.resetEnding ? Math.max(0f, 1f - ClientLoopState.endingTicks / 10f) : 1f;
		int a = (int) (alpha * 255) << 24;
		g.fill(0, 0, w, h, a | Theme.SHADE_RGB);

		int cx = w / 2, cy = h / 2;
		String clone = "Clone #" + ClientLoopState.myClones;
		g.centeredText(font, clone, cx, cy - 46, a | (Theme.TEXT & 0xFFFFFF));

		// The spinner is a ring of square dots on a coarse grid. Each dot fades after the head passes it, and the ring
		// tightens a little towards the tail.
		double t = System.nanoTime() / 1_000_000_000.0;
		int head = (int) (t * 14) % SPINNER_DOTS;
		for (int i = 0; i < SPINNER_DOTS; i++) {
			int age = (head - i + SPINNER_DOTS) % SPINNER_DOTS;
			double angle = i * (Math.PI * 2 / SPINNER_DOTS) - Math.PI / 2;
			double radius = 14 - age * 0.5;
			int x = snap(cx + Math.cos(angle) * radius), y = snap(cy + Math.sin(angle) * radius);
			float bright = 1f - age / (float) SPINNER_DOTS;
			int dotAlpha = (int) (alpha * bright * 255);
			if (dotAlpha <= 4) continue;
			g.fill(x, y, x + PIXEL, y + PIXEL, (dotAlpha << 24) | (Theme.TEXT_DIM & 0xFFFFFF));
		}

		int dots = (int) (t * 3) % 4;
		g.centeredText(font, "Loading" + ".".repeat(dots), cx, cy + 28, a | (Theme.TEXT_DIM & 0xFFFFFF));
	}

	private static int snap(double v) {
		return (int) Math.floor(v / PIXEL) * PIXEL;
	}
}
