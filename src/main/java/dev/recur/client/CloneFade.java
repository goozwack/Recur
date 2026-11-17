package dev.recur.client;

/** How see-through the clone being drawn right now is (1 = solid). Rendering happens on one thread. */
public final class CloneFade {
	public static float drawing = 1f;

	private CloneFade() {}

	/** This colour with its opacity scaled by the fade. */
	public static int fade(int argb) {
		if (drawing >= 1f) return argb;
		int a = Math.round(((argb >>> 24) & 0xFF) * drawing);
		return (a << 24) | (argb & 0xFFFFFF);
	}
}
