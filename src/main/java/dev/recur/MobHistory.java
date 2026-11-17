package dev.recur;

import com.mt1006.mocap.mocap.actions.EntityUpdate;
import com.mt1006.mocap.mocap.files.RecordingFiles;
import com.mt1006.mocap.mocap.playing.Controlled;
import com.mt1006.mocap.mocap.playing.Playing;
import com.mt1006.mocap.mocap.recording.EntityState;
import com.mt1006.mocap.mocap.recording.Recording;
import com.mt1006.mocap.utils.FakePlayer;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.Mob;
import net.minecraft.world.entity.boss.enderdragon.EnderDragon;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.Iterator;
import java.util.List;
import java.util.Map;

/**
 * What the mobs around recording players have been doing since the run started (or since they spawned), kept on the
 * side. Once a player does something with a mob (see MobTies), all of it goes into their recording, so in later runs
 * their clone plays that mob's whole life and not just the part after.
 */
public final class MobHistory {
	private static final double REACH = 96;
	private static final int MAX_MOBS = 1000;
	private static final int SCAN_INTERVAL = 20;

	private static final Map<Mob, Shadow> SHADOWS = new HashMap<>();
	/** Counts recording ticks, so recordings and the histories here line up even when the server skips some. */
	private static int clock;
	/** Ids far above the ones a recording hands out itself, so they never clash. */
	private static int nextId = 1 << 24;

	private MobHistory() {}

	public static int clock() { return clock; }

	public static void step() { clock++; }

	public static void clear() { SHADOWS.clear(); NEW.clear(); }

	/**
	 * Mobs that just came close, waiting for their history to start. Starting one saves the whole mob once, which adds
	 * up when a crowd shows up at the same time, so they're started a few per tick over the next second instead.
	 */
	private static final java.util.LinkedHashSet<Mob> NEW = new java.util.LinkedHashSet<>();
	private static int startsPerTick;

	/** After the recordings had their tick: carry on every mob's history, and start one for mobs that came close. */
	public static void tick(MinecraftServer server) {
		if (!LoopOverlay.active || !Recording.anyRecording()) {
			if (!SHADOWS.isEmpty()) SHADOWS.clear();
			NEW.clear();
			return;
		}
		if (clock % SCAN_INTERVAL == 0) {
			for (ServerPlayer player : server.getPlayerList().getPlayers()) {
				if (player instanceof FakePlayer || Recording.active(player) == null) continue;
				for (Mob mob : player.level().getEntitiesOfClass(Mob.class, player.getBoundingBox().inflate(REACH), MobHistory::worthKeeping)) {
					if (SHADOWS.size() + NEW.size() >= MAX_MOBS) break;
					if (!SHADOWS.containsKey(mob)) NEW.add(mob);
				}
			}
			startsPerTick = (NEW.size() + SCAN_INTERVAL - 1) / SCAN_INTERVAL; // all of them by the next scan
		}
		for (int i = 0; i < startsPerTick && !NEW.isEmpty(); i++) {
			var it = NEW.iterator();
			Mob mob = it.next();
			it.remove();
			if (worthKeeping(mob) && !mob.isRemoved()) SHADOWS.putIfAbsent(mob, new Shadow(clock));
		}
		for (Iterator<Map.Entry<Mob, Shadow>> it = SHADOWS.entrySet().iterator(); it.hasNext(); ) {
			var entry = it.next();
			Mob mob = entry.getKey();
			// Nobody did anything with it before it died or left, or a recording has it now: nothing more to keep.
			if (mob.isRemoved() || mob.isDeadOrDying() || Controlled.isControlled(mob) || EntityOwners.ownerOf(mob) != null) {
				it.remove();
				continue;
			}
			entry.getValue().capture(mob);
		}
	}

	/** A sound or particles from a mob nobody's recording has yet: kept with its history, in case it ends up in one. */
	static void note(Mob mob, WorldLog.Edit effect) {
		Shadow shadow = SHADOWS.get(mob);
		if (shadow != null && shadow.effects.size() < MAX_EFFECTS) shadow.effects.add(effect);
	}

	private static final int MAX_EFFECTS = 4000;

	private static boolean worthKeeping(Mob mob) {
		return mob.isAlive() && !(mob instanceof EnderDragon) && !Controlled.isControlled(mob) && EntityOwners.ownerOf(mob) == null
			&& !mob.entityTags().contains(Playing.MOCAP_ENTITY_TAG);
	}

	/**
	 * A recording takes this mob over: its history, if it started after the recording did. Either way it isn't kept here
	 * any more.
	 */
	public static Shadow take(Mob mob, int recordingStart) {
		Shadow shadow = SHADOWS.remove(mob);
		return shadow != null && shadow.start >= recordingStart ? shadow : null;
	}

	/** Packs older history down while it waits (movement data shrinks to a fraction). */
	private static final java.util.zip.Deflater DEFLATER = new java.util.zip.Deflater(java.util.zip.Deflater.BEST_SPEED);
	/** How much history is kept unpacked before it's packed. */
	private static final int BLOCK = 8192;

	/**
	 * One mob's history: the bytes its recording would have had, in parts tagged with the tick they belong to. Older
	 * parts are kept packed and only unpacked if a recording takes the mob over.
	 */
	public static final class Shadow {
		public final int id = nextId++;
		public final int start;
		public EntityState last;
		private int lastTickCount = Integer.MIN_VALUE;
		private RecordingFiles.Writer current = new RecordingFiles.Writer();
		private final List<byte[]> packed = new ArrayList<>();
		private int packedSize; // how many bytes the packed blocks hold once unpacked
		private int[] ticks = new int[16], ends = new int[16];
		private int parts;
		private byte[] all; // everything unpacked, while being put into a recording
		/** Its sounds and particles so far (footsteps, a warden's sonic boom...). */
		private final List<WorldLog.Edit> effects = new ArrayList<>();

		public List<WorldLog.Edit> effects() { return effects; }

		private Shadow(int start) { this.start = start; }

		private void capture(Mob mob) {
			// It wasn't ticked (its chunk isn't running): nothing about it changed by itself, so there's nothing new to write.
			if (last != null && mob.tickCount == lastTickCount) return;
			lastTickCount = mob.tickCount;
			int before = current.size();
			if (last == null) new EntityUpdate(EntityUpdate.ADD, id, mob).write(current);
			EntityState state = new EntityState(mob);
			state.saveTrackedEntityDifference(current, id, last);
			last = state;
			if (current.size() == before) return;
			if (parts == ticks.length) {
				ticks = Arrays.copyOf(ticks, parts * 2);
				ends = Arrays.copyOf(ends, parts * 2);
			}
			ticks[parts] = clock;
			ends[parts] = packedSize + current.size();
			parts++;
			if (current.size() >= BLOCK) pack();
		}

		private void pack() {
			DEFLATER.reset();
			DEFLATER.setInput(current.raw(), 0, current.size());
			DEFLATER.finish();
			byte[] out = new byte[current.size() + 64];
			int n = 0;
			while (!DEFLATER.finished()) {
				if (n == out.length) out = Arrays.copyOf(out, out.length * 2);
				n += DEFLATER.deflate(out, n, out.length - n);
			}
			packed.add(Arrays.copyOf(out, n));
			packedSize += current.size();
			current.clear();
		}

		/** Unpacks everything so the parts can be copied out (see Recording.spliceHistories). */
		public void unpack() {
			if (all != null) return;
			all = new byte[packedSize + current.size()];
			int at = 0;
			java.util.zip.Inflater inflater = new java.util.zip.Inflater();
			try {
				for (byte[] block : packed) {
					inflater.reset();
					inflater.setInput(block);
					while (!inflater.finished()) {
						int n = inflater.inflate(all, at, all.length - at);
						if (n == 0 && (inflater.needsInput() || inflater.needsDictionary())) break;
						at += n;
					}
				}
			} catch (java.util.zip.DataFormatException e) {
				throw new IllegalStateException(e);
			} finally {
				inflater.end();
			}
			System.arraycopy(current.raw(), 0, all, packedSize, current.size());
			packed.clear();
		}

		public int parts() { return parts; }

		public int tickOf(int part) { return ticks[part]; }

		public int length(int part) { return ends[part] - (part == 0 ? 0 : ends[part - 1]); }

		/** Copies one part into the array at this position; returns how many bytes that was. */
		public int copyPart(int part, byte[] into, int at) {
			int from = part == 0 ? 0 : ends[part - 1];
			System.arraycopy(all, from, into, at, ends[part] - from);
			return ends[part] - from;
		}
	}
}
