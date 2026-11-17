package dev.recur.client;

import net.minecraft.client.gui.components.Tooltip;
import net.minecraft.network.chat.Component;

/** The loop length slider: a notch per minute from 2 to 60, and one more at the end for no limit. */
public final class LoopLength {
	private static final int MIN = 2, MAX = 60, STEPS = MAX - MIN + 1;

	private LoopLength() {}

	public static int minutes(double v) {
		int step = (int) Math.round(v * STEPS);
		return step >= STEPS ? ClientSettings.NO_LIMIT : MIN + step;
	}

	public static double value(int minutes) {
		if (minutes == ClientSettings.NO_LIMIT) return 1.0;
		return (minutes - MIN) / (double) STEPS;
	}

	public static String label(int minutes) {
		return minutes == ClientSettings.NO_LIMIT ? "Length: no limit" : "Length: " + minutes + " min";
	}

	// speedrun for up to 15 minutes, normal for 15-25, casual for anything longer
	public static Tooltip tooltip(int minutes) {
		String pace = minutes == ClientSettings.NO_LIMIT || minutes >= 25 ? "Casual" : minutes >= 15 ? "Normal" : "Speedrun";
		return Tooltip.create(Component.literal(pace));
	}
}
