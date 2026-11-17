package dev.recur.client;

import dev.recur.LoopNetworking;

import java.util.List;

/** Latest loop state received from the server, plus the reset-animation timer. */
public final class ClientLoopState {
	/** False in worlds made without Recur (and before the server said anything): Recur's menus stay hidden. */
	public static boolean available;
	public static boolean active;
	public static int myClones;
	public static int totalClones;
	public static int runNumber = 1;
	public static int remainingTicks;
	/** Timed resets: ticks until the world resets, or -1 when that mode is off. */
	public static int timedTicks = -1;
	public static int myVote = -1;
	public static int effectiveId = -1;
	public static int players = 1;
	public static int startVotes;
	public static List<LoopNetworking.CheckpointInfo> checkpoints = List.of();

	/** Ticks since the reset animation began (-1 = not playing); fades out after the server ends it. */
	public static int resetTicks = -1;
	public static boolean resetEnding;
	public static int endingTicks;

	/** How clones are drawn (pause-menu button cycles through these). */
	public enum CloneView {
		SHOWN("Shown", 1f, 1f),
		TRANSPARENT("Transparent", 0.35f, 1f),
		HIDDEN("Hidden", 0f, 1f),
		HIDDEN_NO_TAGS("Hidden, no names", 0f, 0f);

		public final String label;
		public final float body, tag;

		CloneView(String label, float body, float tag) { this.label = label; this.body = body; this.tag = tag; }

		public CloneView next() { return values()[(ordinal() + 1) % values().length]; }
	}

	public static CloneView cloneView = CloneView.SHOWN;
	/** Opacity of the name tag currently being drawn (set per entity by the renderer mixins). */
	public static float drawingTagAlpha = 1f;
	/** Clone entity id -> client tick it appeared at (clones are hidden for 2 seconds, then fade in). */
	private static final it.unimi.dsi.fastutil.ints.Int2IntOpenHashMap CLONES = new it.unimi.dsi.fastutil.ints.Int2IntOpenHashMap();
	private static final int NONE = Integer.MAX_VALUE;
	static { CLONES.defaultReturnValue(NONE); }
	private static int clientTicks;
	private static final int HIDDEN_TICKS = 40, FADE_TICKS = 30;

	private ClientLoopState() {}

	/** Clone entity id -> the order its clone was made in (higher is newer). */
	private static final it.unimi.dsi.fastutil.ints.Int2IntOpenHashMap AGES = new it.unimi.dsi.fastutil.ints.Int2IntOpenHashMap();
	static { AGES.defaultReturnValue(-1); }

	public static void onClones(java.util.List<Integer> ids, java.util.List<Integer> ages, boolean fadeIn) {
		for (int i = 0; i < ids.size(); i++) {
			CLONES.put((int) ids.get(i), fadeIn && ClientSettings.cloneFadeIn ? clientTicks : Integer.MIN_VALUE / 2);
			AGES.put((int) ids.get(i), (int) ages.get(i));
		}
	}

	/** The order this clone was made in (higher is newer), or -1 if unknown. */
	public static int cloneAge(int entityId) { return AGES.get(entityId); }

	public static void clearClones() { CLONES.clear(); AGES.clear(); }

	/** Left the world/server: forget everything, so nothing (timer, loading screen) carries over to the next one. */
	public static void clearAll() {
		CLONES.clear();
		AGES.clear();
		available = false; active = false; timedTicks = -1; myClones = 0; totalClones = 0; runNumber = 1; remainingTicks = 0;
		myVote = -1; effectiveId = -1; players = 1; startVotes = 0; checkpoints = List.of();
		resetTicks = -1; resetEnding = false; endingTicks = 0;
	}

	/** Called for every entity, every frame and tick: kept to a single primitive hash lookup. */
	public static boolean isClone(int entityId) { return !CLONES.isEmpty() && CLONES.containsKey(entityId); }

	public static it.unimi.dsi.fastutil.ints.IntSet cloneIds() { return CLONES.keySet(); }

	/** -1 = not a clone, otherwise how far its spawn fade-in is (0 for the first 2 seconds, then up to 1). */
	public static float cloneFade(int entityId, float partialTicks) {
		if (CLONES.isEmpty()) return -1f;
		int start = CLONES.get(entityId);
		if (start == NONE) return -1f;
		float t = clientTicks - start + partialTicks - HIDDEN_TICKS;
		return Math.max(0f, Math.min(1f, t / FADE_TICKS));
	}

	public static void apply(LoopNetworking.StatePayload p) {
		available = p.available();
		timedTicks = p.timedTicks();
		active = p.active();
		myClones = p.myClones();
		totalClones = p.totalClones();
		runNumber = p.runNumber();
		remainingTicks = p.remainingTicks();
		myVote = p.myVote();
		effectiveId = p.effectiveId();
		players = p.players();
		startVotes = p.startVotes();
		checkpoints = p.checkpoints();
	}

	public static void onReset(boolean start) {
		// nothing that was flying around before the reset is left over after it
		net.minecraft.client.Minecraft.getInstance().particleEngine.clearParticles();
		if (start) { CLONES.clear(); resetTicks = 0; resetEnding = false; endingTicks = 0; }
		else resetEnding = true;
	}

	public static void tick() {
		if (resetTicks >= 0) {
			resetTicks++;
			if (resetEnding && ++endingTicks > 10) resetTicks = -1;
		}
		// The integrated server is frozen while the singleplayer game is paused, so the timer must be too.
		if (net.minecraft.client.Minecraft.getInstance().isPaused()) return;
		clientTicks++;
		// /tick freeze stops the loop's clock on the server, so the countdown shown here stops with it.
		var level = net.minecraft.client.Minecraft.getInstance().level;
		if (level != null && !level.tickRateManager().runsNormally()) return;
		if (active && remainingTicks > 0 && resetTicks < 0) remainingTicks--;
		if (timedTicks > 0) timedTicks--;
	}

	private static int cachedSeconds = -1;
	private static String cachedTime = "";

	/** The time left as "m:ss". It is drawn every frame, so the text is only rebuilt when the second changes. */
	public static String timeLeft() { return remainingTicks < 0 ? "No time limit" : format(remainingTicks); }

	/** Time until the next timed reset, "m:ss". */
	public static String timedLeft() { return format(timedTicks); }

	private static String format(int ticks) {
		int s = (ticks + 19) / 20;
		if (s != cachedSeconds) {
			cachedSeconds = s;
			int sec = s % 60;
			cachedTime = (s / 60) + (sec < 10 ? ":0" : ":") + sec;
		}
		return cachedTime;
	}
}
