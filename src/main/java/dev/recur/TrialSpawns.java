package dev.recur;

import com.mt1006.mocap.utils.FakePlayer;
import dev.recur.mixin.TrialSpawnerDataAccessor;
import net.minecraft.core.BlockPos;
import net.minecraft.core.UUIDUtil;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.util.ProblemReporter;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.EntityProcessor;
import net.minecraft.world.entity.EntitySpawnReason;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.level.block.entity.TrialSpawnerBlockEntity;
import net.minecraft.world.level.block.entity.trialspawner.TrialSpawner;
import net.minecraft.world.level.block.entity.trialspawner.TrialSpawnerState;
import net.minecraft.world.level.gameevent.GameEvent;
import net.minecraft.world.level.storage.TagValueInput;
import net.minecraft.world.level.storage.TagValueOutput;

import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.WeakHashMap;

/**
 * Trial spawner fights. Clones don't set spawners off: the first fight at a spawner on the timeline is recorded (when it
 * started, and every mob it spawned, where and with what), and a clone's fight plays out the same way every run. Its
 * mobs keep their replay even when a player hits them, so the clones keep fighting them (a player's hits still count).
 * A player who joins gets a whole fight's worth of mobs of their own (up to 4 players), spawning alongside the clones'
 * ones, and those fight the player for real. A player who gets to a spawner before a clone would have has the fight to themselves, and the clone
 * finds nothing there. With unlimited loops a spawner's 30 minute rest can end, and the next fight is decided the same way.
 */
public final class TrialSpawns {
	/** Kind of the edit holding a fight starting (t = 0) or one of its mobs spawning (t = 1). */
	public static final int KIND = 17;
	/** Key in a recorded mob's data: the fight it's from. */
	public static final String TAG = "recur_trial";
	/** Most players a spawner adds mobs for. */
	private static final int MAX_PLAYERS = 4;

	private static final class Session {
		final long start;
		/** A live player's fight, which the spawner runs by itself. Otherwise a clone's, played from the recording. */
		final boolean live;
		/** Mobs the spawner added by itself during a clone's fight, for the players who joined. */
		int extra;
		/** Recorded mobs spawned so far. */
		int recorded;
		/** Rewards that were the clones' (theirs drop from the recording). */
		int clonesRewards;
		/** Players recording this fight. */
		final Set<UUID> recorders = new HashSet<>();

		Session(long start, boolean live) { this.start = start; this.live = live; }
	}

	private static final Map<String, Session> SESSIONS = new HashMap<>();
	/** Clones' fights that happened this run, so what they gave drops. */
	private static final Set<String> REPLAYED = new HashSet<>();
	/** Recorded mobs still to spawn this run. */
	private static final List<WorldLog.Edit> PENDING = new ArrayList<>();
	/** Recorded mobs: they keep their replay. */
	private static final Set<Entity> HELD = Collections.newSetFromMap(new WeakHashMap<>());
	/** Damage players did to recorded mobs, which they keep. */
	private static final Map<Entity, Float> DAMAGE = new WeakHashMap<>();
	/** Every mob a spawner spawned, and the fight it's from. */
	private static final Map<Entity, String> FIGHT_OF = new WeakHashMap<>();
	/** Recorded mobs spawned this run (a fight is in several recordings once players joined it: each spawns once). */
	private static final Set<UUID> SPAWNED = new HashSet<>();

	/** The spawner being ticked. */
	private static ServerLevel level;
	private static BlockPos pos;
	private static boolean allowing, blocking, tagging;

	private TrialSpawns() {}

	private static String key(ServerLevel l, BlockPos p) { return l.dimension().identifier() + "|" + p.asLong(); }

	public static void enter(ServerLevel l, BlockPos p) { level = l; pos = p.immutable(); }

	public static void exit() { level = null; pos = null; }

	private static Session current() { return level == null ? null : SESSIONS.get(key(level, pos)); }

	private static TrialSpawnerDataAccessor data(TrialSpawner spawner) { return (TrialSpawnerDataAccessor) spawner.getStateData(); }

	public static boolean held(Entity e) { return !HELD.isEmpty() && HELD.contains(e); }

	public static boolean fromSpawner(Entity e) { return !FIGHT_OF.isEmpty() && FIGHT_OF.containsKey(e); }

	public static String fightOf(Entity e) { return FIGHT_OF.isEmpty() ? null : FIGHT_OF.get(e); }

	/** A clone's mob spawns only if its fight happened in this run (otherwise someone got to the spawner first). */
	public static boolean addsHere(CompoundTag recorded) {
		String fight = recorded.getStringOr(TAG, "");
		return fight.isEmpty() || REPLAYED.contains(fight);
	}

	public static boolean replayed(String fight) { return REPLAYED.contains(fight); }

	/** A clone takes over a mob from its fight (one this run spawned, or one a checkpoint brought back): it keeps its replay. */
	public static void onTaken(CompoundTag recorded, Entity e) {
		String fight = recorded.getStringOr(TAG, "");
		if (fight.isEmpty() || !REPLAYED.contains(fight)) return;
		HELD.add(e);
		FIGHT_OF.put(e, fight);
	}

	// who a spawner sees

	public static List<UUID> withoutClones(ServerLevel l, List<UUID> found) {
		if (found.isEmpty()) return found;
		List<UUID> out = new ArrayList<>(found.size());
		for (UUID id : found) if (!(l.getEntity(id) instanceof FakePlayer)) out.add(id);
		return out;
	}

	// a fight starting and ending

	public static void onState(TrialSpawner spawner, TrialSpawnerState next) {
		if (level == null) return;
		String k = key(level, pos);
		if (next == TrialSpawnerState.ACTIVE && spawner.getState() != TrialSpawnerState.ACTIVE) {
			if (SESSIONS.containsKey(k) || !LoopOverlay.active) return; // a clone's fight starting
			Session s = new Session(WorldLog.now(), true);
			SESSIONS.put(k, s);
			record(spawner, s, null);
		} else if (next == TrialSpawnerState.COOLDOWN || next == TrialSpawnerState.WAITING_FOR_PLAYERS || next == TrialSpawnerState.INACTIVE) {
			SESSIONS.remove(k);
		}
	}

	// mobs

	/** The spawner is about to spawn a mob by itself. During a clone's fight it only adds the mobs for players who joined. */
	public static boolean maySpawn(TrialSpawner spawner) {
		allowing = false;
		Session s = current();
		if (s == null || s.live) return true;
		if (s.extra >= extraMobs(spawner)) return false;
		allowing = true;
		return true;
	}

	public static void spawned(TrialSpawner spawner, Optional<UUID> made) {
		boolean extra = allowing;
		allowing = false;
		Session s = current();
		if (s == null || made.isEmpty()) return;
		if (extra) s.extra++;
		Entity e = level.getEntity(made.get());
		if (e == null) return;
		String fight = key(level, pos) + "|" + s.start;
		e.getSelfAndPassengers().forEach(x -> FIGHT_OF.put(x, fight));
		CompoundTag d = new CompoundTag();
		d.putInt("t", 1);
		d.putLong("s", s.start);
		d.put("e", save(e));
		record(spawner, s, d);
	}

	/** How many mobs the spawner adds for the players (not clones) who joined a clone's fight: a whole fight's worth each. */
	private static int extraMobs(TrialSpawner spawner) {
		int players = 0;
		for (UUID id : data(spawner).recur$detectedPlayers()) if (level.getEntity(id) instanceof ServerPlayer p && !(p instanceof FakePlayer)) players++;
		players = Math.min(players, MAX_PLAYERS - 1);
		// as many as the spawner gives one player on their own, so joining a clone is as big a fight as the first time
		return (int) Math.ceil(spawner.activeConfig().totalMobs() * players);
	}

	/** During a clone's fight, it's over once every recorded mob and every added one has spawned (and they all died). */
	public static Boolean finished() {
		Session s = current();
		if (s == null || s.live || !(level.getBlockEntity(pos) instanceof TrialSpawnerBlockEntity be)) return null;
		if (pending(s) > 0) return false;
		return s.extra >= extraMobs(be.getTrialSpawner());
	}

	/** Recorded mobs of this fight still to spawn. */
	private static int pending(Session s) {
		String dim = level.dimension().identifier().toString();
		int n = 0;
		for (WorldLog.Edit e : PENDING) if (e.dim().equals(dim) && e.pos().equals(pos) && e.data().getLongOr("s", 0) == s.start) n++;
		return n;
	}

	private static CompoundTag save(Entity e) {
		TagValueOutput out = TagValueOutput.createWithContext(ProblemReporter.DISCARDING, e.registryAccess());
		e.saveWithoutId(out);
		CompoundTag t = out.buildResult();
		t.putString("id", EntityType.getKey(e.getType()).toString());
		return t;
	}

	/** Puts this in the recording of every player the spawner sees (with the start of the fight, if they don't have it). */
	private static void record(TrialSpawner spawner, Session s, CompoundTag d) {
		if (!LoopOverlay.active) return;
		for (UUID id : data(spawner).recur$detectedPlayers()) {
			if (!(level.getEntity(id) instanceof ServerPlayer p) || p instanceof FakePlayer || !WorldLog.isRecording(p)) continue;
			if (s.recorders.add(id)) {
				CompoundTag start = new CompoundTag();
				start.putInt("t", 0);
				start.putLong("s", s.start);
				start.putBoolean("o", spawner.isOminous());
				WorldLog.addEffect(p, KIND, level, pos, start);
			}
			if (d != null) WorldLog.addEffect(p, KIND, level, pos, d.copy());
		}
	}

	// rewards

	public static void startReward() {
		Session s = current();
		blocking = s != null && !s.live && s.clonesRewards > 0;
		if (blocking) { s.clonesRewards--; return; }
		tagging = s != null;
		if (tagging) MobDrops.startReward("t|" + key(level, pos) + "|" + s.start);
	}

	/** The clones' rewards come from the recording, exactly as they were. */
	public static boolean blockingReward() { return blocking; }

	public static void endReward() {
		if (tagging) MobDrops.endReward();
		blocking = tagging = false;
	}

	// damage to recorded mobs

	public static void addDamage(Entity mob, float amount) { if (amount > 0) DAMAGE.merge(mob, amount, Float::sum); }

	public static float damage(Entity mob) { return DAMAGE.isEmpty() ? 0f : DAMAGE.getOrDefault(mob, 0f); }

	// replay

	public static void scheduled(WorldLog.Edit e) { if (e.kind() == KIND && e.data().getIntOr("t", 0) == 1) PENDING.add(e); }

	public static void unscheduled(java.util.function.Predicate<WorldLog.Edit> drop) { PENDING.removeIf(drop); }

	public static void clear() {
		SESSIONS.clear(); REPLAYED.clear(); PENDING.clear(); HELD.clear(); DAMAGE.clear(); FIGHT_OF.clear(); SPAWNED.clear();
	}

	static boolean replay(ServerLevel l, WorldLog.Edit e, long now) {
		PENDING.remove(e);
		String k = key(l, e.pos());
		long start = e.data().getLongOr("s", e.tick());
		if (e.tick() + 40 < now) {
			// Long ago (starting from a checkpoint): the checkpoint has that fight's mobs, which their clones take over.
			if (e.data().getIntOr("t", 0) == 0) REPLAYED.add(k + "|" + start);
			return true;
		}
		if (!(l.getBlockEntity(e.pos()) instanceof TrialSpawnerBlockEntity be)) return true;
		TrialSpawner spawner = be.getTrialSpawner();
		Session s = SESSIONS.get(k);
		ServerLevel wasLevel = level;
		BlockPos wasPos = pos;
		enter(l, e.pos());
		try {
			if (e.data().getIntOr("t", 0) == 0) {
				// someone got here first, it's resting, or another clone's copy of this fight started it already
				if (s != null || spawner.getState() != TrialSpawnerState.WAITING_FOR_PLAYERS) return true;
				s = new Session(start, false);
				s.clonesRewards = 1;
				SESSIONS.put(k, s);
				REPLAYED.add(k + "|" + start);
				if (e.data().getBooleanOr("o", false) && !spawner.isOminous()) spawner.applyOminous(l, e.pos());
				data(spawner).recur$detectedPlayers().add(nearestClone(l, e.pos()));
				spawner.setState(l, TrialSpawnerState.ACTIVE);
				return true;
			}
			if (s == null || s.live || s.start != start) return true; // its fight isn't the one going on
			spawnRecorded(l, spawner, s, e.data());
			return true;
		} finally {
			level = wasLevel;
			pos = wasPos;
		}
	}

	private static UUID nearestClone(ServerLevel l, BlockPos at) {
		ServerPlayer best = null;
		double bestDist = 48 * 48;
		for (ServerPlayer p : l.players()) {
			if (!(p instanceof FakePlayer) || p.isRemoved()) continue;
			double d = p.distanceToSqr(at.getX() + 0.5, at.getY(), at.getZ() + 0.5);
			if (d < bestDist) { bestDist = d; best = p; }
		}
		return best != null ? best.getUUID() : UUID.randomUUID();
	}

	/** A recorded mob spawns where and as it did, with its id, so the clone that fought it finds it. */
	private static void spawnRecorded(ServerLevel l, TrialSpawner spawner, Session s, CompoundTag d) {
		CompoundTag tag = d.getCompoundOrEmpty("e");
		UUID id = tag.read("UUID", UUIDUtil.CODEC).orElse(null);
		Entity mob = id != null ? l.getEntity(id) : null;
		if (mob != null && !mob.isAlive()) return;
		if (id != null && !SPAWNED.add(id) && mob == null) return; // spawned already, and gone since
		String fight = key(l, pos) + "|" + s.start;
		if (mob == null) {
			mob = EntityType.loadEntityRecursive(TagValueInput.create(ProblemReporter.DISCARDING, l.registryAccess(), tag),
				l, EntitySpawnReason.TRIAL_SPAWNER, EntityProcessor.NOP);
			if (mob == null) return;
			mob.getSelfAndPassengers().forEach(x -> { HELD.add(x); FIGHT_OF.put(x, fight); });
			if (!l.tryAddFreshEntityWithPassengers(mob)) return;
		} else {
			mob.getSelfAndPassengers().forEach(x -> { HELD.add(x); FIGHT_OF.put(x, fight); });
		}
		TrialSpawnerDataAccessor data = data(spawner);
		if (data.recur$currentMobs().add(mob.getUUID())) data.recur$setTotalMobsSpawned(data.recur$totalMobsSpawned() + 1);
		int flame = (spawner.isOminous() ? TrialSpawner.FlameParticle.OMINOUS : TrialSpawner.FlameParticle.NORMAL).encode();
		l.levelEvent(3011, pos, flame);
		l.levelEvent(3012, mob.blockPosition(), flame);
		l.gameEvent(mob, GameEvent.ENTITY_PLACE, mob.blockPosition());
		record(spawner, s, d); // whoever is recording now has it too
		s.recorded++;
		// The mobs for players who joined come along with the recorded ones, spread over the fight, so everyone fights
		// at once instead of the clones clearing theirs first. (Any still owed come the usual way at the end.)
		int owed = (int) Math.ceil(extraMobs(spawner) * (double) s.recorded / (s.recorded + pending(s))) - s.extra;
		for (int i = 0; i < owed; i++) if (spawner.spawnMob(l, pos).isEmpty()) break;
	}
}
