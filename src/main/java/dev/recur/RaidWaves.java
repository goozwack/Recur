package dev.recur;

import dev.recur.mixin.RaidAccessor;
import net.minecraft.core.BlockPos;
import net.minecraft.core.UUIDUtil;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.nbt.Tag;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.util.ProblemReporter;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.EntityProcessor;
import net.minecraft.world.entity.EntitySpawnReason;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.raid.Raid;
import net.minecraft.world.entity.raid.Raider;
import net.minecraft.world.level.storage.TagValueInput;
import net.minecraft.world.level.storage.TagValueOutput;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

/**
 * Raid waves are the same in every run, like the dragon fight. The first time a wave spawns it's recorded (where, when,
 * and every raider with its gear), and from then on that wave spawns exactly like that, at that moment, instead of the
 * raid rolling a new one. What the raiders do after that is up to them (or the clones fighting them). Waves nobody has
 * reached yet spawn by themselves, and get recorded.
 */
public final class RaidWaves {
	/** Kind of the edit holding one wave. */
	public static final int KIND = 16;
	/** How close (blocks) to a raid's center players have to be for its waves to go in their recording. */
	private static final double RANGE = 96;

	/** Recorded waves still to come this run. */
	private static final List<WorldLog.Edit> PENDING = new ArrayList<>();
	/** The level the raid being ticked is in (a raid doesn't keep its level). */
	public static ServerLevel ticking;

	private RaidWaves() {}

	// recording

	/** The raid just rolled and spawned a wave by itself: everyone recording nearby gets it. */
	public static void onWaveSpawned(ServerLevel level, Raid raid, BlockPos spawnPos) {
		if (!LoopOverlay.active || level == null) return;
		int wave = raid.getGroupsSpawned();
		var group = ((RaidAccessor) raid).recur$groupRaiderMap().get(wave);
		if (group == null || group.isEmpty()) return;
		ListTag raiders = new ListTag();
		for (Raider r : group) raiders.add(save(r));
		CompoundTag data = new CompoundTag();
		data.putInt("w", wave);
		BlockPos c = raid.getCenter();
		data.putInt("cx", c.getX()); data.putInt("cy", c.getY()); data.putInt("cz", c.getZ());
		data.putInt("sx", spawnPos.getX()); data.putInt("sy", spawnPos.getY()); data.putInt("sz", spawnPos.getZ());
		data.put("r", raiders);
		record(level, c, data);
	}

	private static void record(ServerLevel level, BlockPos center, CompoundTag data) {
		for (ServerPlayer p : level.players()) {
			if (p instanceof com.mt1006.mocap.utils.FakePlayer || !WorldLog.isRecording(p)) continue;
			if (p.distanceToSqr(center.getX() + 0.5, center.getY(), center.getZ() + 0.5) > RANGE * RANGE) continue;
			WorldLog.addEffect(p, KIND, level, center, data.copy());
		}
	}

	private static CompoundTag save(Raider r) {
		TagValueOutput out = TagValueOutput.createWithContext(ProblemReporter.DISCARDING, r.registryAccess());
		r.saveWithoutId(out);
		CompoundTag t = out.buildResult();
		t.putString("id", EntityType.getKey(r.getType()).toString());
		// it joins the raid of this run, and riders get back on by themselves
		t.remove("Passengers"); t.remove("RaidId"); t.remove("Wave");
		if (r.getVehicle() != null) t.store("recur_ride", UUIDUtil.CODEC, r.getVehicle().getUUID());
		return t;
	}

	// replay

	public static void scheduled(WorldLog.Edit e) { if (e.kind() == KIND) PENDING.add(e); }

	public static void unscheduled(java.util.function.Predicate<WorldLog.Edit> drop) { PENDING.removeIf(drop); }

	public static void clear() { PENDING.clear(); }

	/** Is this wave of this raid recorded and still to come? Then the raid waits for it instead of rolling its own. */
	public static boolean pending(Raid raid, int wave) {
		if (PENDING.isEmpty() || ticking == null) return false;
		String dim = ticking.dimension().identifier().toString();
		for (WorldLog.Edit e : PENDING) if (e.dim().equals(dim) && e.data().getIntOr("w", 0) == wave && near(raid, e.data())) return true;
		return false;
	}

	private static boolean near(Raid raid, CompoundTag d) {
		BlockPos c = raid.getCenter();
		double dx = c.getX() - d.getIntOr("cx", 0), dy = c.getY() - d.getIntOr("cy", 0), dz = c.getZ() - d.getIntOr("cz", 0);
		return dx * dx + dy * dy + dz * dz <= RANGE * RANGE;
	}

	/** The recorded wave spawns now, if its raid is going on in this run and hasn't had it yet. */
	static boolean replay(ServerLevel level, WorldLog.Edit e, long now) {
		PENDING.remove(e);
		if (e.tick() + 40 < now) return true; // long ago (starting from a checkpoint, which has its raiders already)
		CompoundTag d = e.data();
		BlockPos center = new BlockPos(d.getIntOr("cx", 0), d.getIntOr("cy", 0), d.getIntOr("cz", 0));
		Raid raid = level.getRaids().getNearbyRaid(center, (int) (RANGE * RANGE));
		int wave = d.getIntOr("w", 0);
		if (raid == null || raid.isStopped() || raid.isOver() || raid.getGroupsSpawned() >= wave) return true;
		spawn(level, raid, wave, d);
		// whoever is recording now has it too, in case the clone it came from is gone later
		record(level, raid.getCenter(), d);
		return true;
	}

	/** Like the raid spawning a wave itself, but with the recorded raiders (same ids, so clones find the ones they fought). */
	private static void spawn(ServerLevel level, Raid raid, int wave, CompoundTag d) {
		RaidAccessor a = (RaidAccessor) raid;
		a.recur$setTotalHealth(0f);
		List<Raider> riders = new ArrayList<>();
		List<UUID> rides = new ArrayList<>();
		for (Tag t : d.getListOrEmpty("r")) {
			if (!(t instanceof CompoundTag tag)) continue;
			UUID id = tag.read("UUID", UUIDUtil.CODEC).orElse(null);
			Entity existing = id != null ? level.getEntity(id) : null;
			Raider raider;
			boolean add = false;
			if (existing instanceof Raider r && r.isAlive()) raider = r; // a clone already brought it in
			else if (existing != null) continue;
			else {
				Entity made = EntityType.loadEntityRecursive(TagValueInput.create(ProblemReporter.DISCARDING, level.registryAccess(), tag),
					level, EntitySpawnReason.EVENT, EntityProcessor.NOP);
				if (!(made instanceof Raider r)) continue;
				raider = r;
				add = true;
			}
			raid.joinRaid(level, wave, raider, null, false); // no spot: it keeps its recorded place and gear
			if (tag.getBooleanOr("PatrolLeader", false)) raid.setLeader(wave, raider);
			if (add) level.addFreshEntity(raider);
			final Raider rider = raider;
			tag.read("recur_ride", UUIDUtil.CODEC).ifPresent(v -> { riders.add(rider); rides.add(v); });
		}
		for (int i = 0; i < riders.size(); i++) {
			Entity vehicle = level.getEntity(rides.get(i));
			if (vehicle != null && riders.get(i).getVehicle() == null) riders.get(i).startRiding(vehicle);
		}
		a.recur$setWaveSpawnPos(Optional.empty());
		a.recur$setGroupsSpawned(wave);
		a.recur$setStarted(true);
		a.recur$setRaidCooldownTicks(0);
		raid.updateBossbar();
		a.recur$setDirty(level);
		a.recur$playSound(level, new BlockPos(d.getIntOr("sx", 0), d.getIntOr("sy", 0), d.getIntOr("sz", 0)));
	}
}
