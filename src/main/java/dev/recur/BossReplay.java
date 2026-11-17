package dev.recur;

import com.mt1006.mocap.mocap.playing.Controlled;
import com.mt1006.mocap.mocap.playing.PlayingContext;
import net.minecraft.server.MinecraftServer;
import net.minecraft.world.phys.Vec3;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.ai.targeting.TargetingConditions;
import net.minecraft.world.entity.boss.enderdragon.EnderDragon;
import net.minecraft.world.entity.boss.enderdragon.phases.EnderDragonPhase;
import net.minecraft.world.entity.boss.wither.WitherBoss;
import net.minecraft.tags.EntityTypeTags;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

/**
 * Bosses played back by a clone. They fly the same path and do the same attacks every run (the dragon perches, dives
 * and breathes at the same moments), but unlike other mobs they can still be hurt, so the fight can be won.
 * <p>
 * A player's hits on a boss are kept as the damage they did, not as its whole state (WorldLog), so they add up with
 * what everyone else does to it in later runs instead of overwriting it.
 */
public final class BossReplay {
	private static final TargetingConditions HEAD_TARGETS = TargetingConditions.forNonCombat().range(20.0)
		.selector((target, level) -> !target.is(EntityTypeTags.WITHER_FRIENDS) && target.isAlive());

	/** A clone that wants to take over the dragon, which isn't loaded yet. */
	private record Waiting(PlayingContext ctx, int id, UUID uuid, Vec3 pos, long since) {}

	private static final List<Waiting> WAITING = new ArrayList<>();
	/** How long a clone waits for the dragon to load before leaving it to its own AI. */
	private static final int WAIT_TICKS = 200;

	private BossReplay() {}

	/** The clone's recording starts moving the dragon (see EntityUpdate). */
	public static void takeDragon(PlayingContext ctx, int id, UUID uuid, Vec3 pos) {
		if (!tryTake(ctx, id, uuid, pos)) WAITING.add(new Waiting(ctx, id, uuid, pos, ctx.level.getGameTime()));
	}

	private static boolean tryTake(PlayingContext ctx, int id, UUID uuid, Vec3 pos) {
		EnderDragon dragon = null;
		if (uuid != null && ctx.level.getEntity(uuid) instanceof EnderDragon d && usable(d)) dragon = d;
		if (dragon == null) for (EnderDragon d : ctx.level.getDragons()) if (usable(d)) { dragon = d; break; }
		if (dragon == null) return false;
		dragon.snapTo(pos.x, pos.y, pos.z, dragon.getYRot(), dragon.getXRot());
		Controlled.take(ctx, dragon);
		ctx.entityMap.put(id, dragon);
		return true;
	}

	// a dying dragon is left to fly its death by itself
	private static boolean usable(EnderDragon d) {
		return d.isAlive() && d.getPhaseManager().getCurrentPhase().getPhase() != EnderDragonPhase.DYING;
	}

	/** Dead, or a dragon flying its death (it has 1 health left then): nothing hurts it any more. */
	public static boolean dying(Entity e) {
		if (e instanceof LivingEntity l && l.isDeadOrDying()) return true;
		return e instanceof EnderDragon d && d.getPhaseManager().getCurrentPhase().getPhase() == EnderDragonPhase.DYING;
	}

	/** Ticks since this run started (the End's saved dragon and crystals take a few ticks to load back in). */
	private static int runTicks;

	public static boolean justStarted() { return runTicks < 100; }

	public static void tick(MinecraftServer server) {
		runTicks++;
		removeExtraCrystals();
		if (checkEnd) checkEnd(server);
		if (WAITING.isEmpty()) return;
		WAITING.removeIf(w -> w.ctx.entity == null || w.ctx.entity.isRemoved()
			|| w.ctx.level.getGameTime() - w.since > WAIT_TICKS
			|| tryTake(w.ctx, w.id, w.uuid, w.pos));
	}

	public static void clear() { WAITING.clear(); checkEnd = true; runTicks = 0; }

	/** Set when a run starts: once the End island is loaded, its dragon and pillar crystals are made sure of. */
	private static boolean checkEnd;

	/**
	 * A run starts with the End as it was: if the fight isn't won, there's a dragon and a crystal on every pillar (a
	 * reset can lose them, e.g. when the last run ended with a player dying to a crystal).
	 */
	private static void checkEnd(MinecraftServer server) {
		ServerLevel end = server.getLevel(net.minecraft.world.level.Level.END);
		var fight = end != null ? end.getDragonFight() : null;
		if (fight == null) { checkEnd = false; return; }
		if (!end.hasChunk(0, 0) || justStarted() || end.getGameTime() % 20 != 0) return;
		// the dragon and crystals saved at the loop's start are still on their way back
		if (EntityRestore.pending("minecraft:ender_dragon") || EntityRestore.pending("minecraft:end_crystal")) return;
		checkEnd = false;
		var acc = (dev.recur.mixin.EnderDragonFightAccessor) fight;
		// A respawn that was going when the run ended starts over from nothing: clones that respawned it do it again.
		if (acc.recur$respawnStage() != null) { acc.recur$setRespawnStage(null); acc.recur$setRespawnTime(0); }
		if (acc.recur$dragonKilled()) return;
		if (end.getDragons().isEmpty()) ((dev.recur.mixin.EnderDragonFightAccessor) fight).recur$setTicksSinceDragonSeen(1200);
	}

	/**
	 * A crystal a player blew up in an earlier run blows up again: it hurts and knocks back what's around and shows the
	 * explosion. The blocks it broke come back from the recording, so this one leaves blocks alone.
	 */
	public static void explodeCrystal(ServerLevel level, net.minecraft.world.entity.boss.enderdragon.EndCrystal crystal) {
		if (crystal.isRemoved()) return;
		double x = crystal.getX(), y = crystal.getY(), z = crystal.getZ();
		crystal.discard();
		EntityRestore.forget(crystal.getUUID()); // never put back this run
		level.explode(null, x, y, z, 6.0F, false, net.minecraft.world.level.Level.ExplosionInteraction.NONE);
	}

	/** An existing crystal in this spot (one is never put on top of another). */
	/** Crystals that turned up where one already is (see onCrystalLoad), removed at the end of the tick. */
	private static final List<Entity> EXTRA_CRYSTALS = new ArrayList<>();

	/**
	 * A crystal loaded or was made. The End's chunks get generated again after a reset, which makes a new crystal on
	 * every pillar while the one saved before is still there, so they would pile up every loop. A crystal can never be
	 * where another one already is, so the new one goes.
	 */
	public static void onCrystalLoad(Entity e) {
		if (!LoopOverlay.active || !(e instanceof net.minecraft.world.entity.boss.enderdragon.EndCrystal) || !(e.level() instanceof ServerLevel level)) return;
		var others = level.getEntitiesOfClass(net.minecraft.world.entity.boss.enderdragon.EndCrystal.class,
			new net.minecraft.world.phys.AABB(e.position(), e.position()).inflate(0.5), c -> c != e && !c.isRemoved() && !EXTRA_CRYSTALS.contains(c));
		if (!others.isEmpty()) EXTRA_CRYSTALS.add(e);
	}

	private static void removeExtraCrystals() {
		if (EXTRA_CRYSTALS.isEmpty()) return;
		for (Entity e : EXTRA_CRYSTALS) {
			if (e.isRemoved() || Controlled.isControlled(e) || !(e.level() instanceof ServerLevel level)) continue;
			// Only while the one it doubles is still there: the reset can remove that one in the same tick (as the copy
			// from the loop's start), and then this is the pillar's only crystal.
			var kept = level.getEntitiesOfClass(net.minecraft.world.entity.boss.enderdragon.EndCrystal.class,
				new net.minecraft.world.phys.AABB(e.position(), e.position()).inflate(0.5), c -> c != e && !c.isRemoved() && !EXTRA_CRYSTALS.contains(c));
			if (!kept.isEmpty()) e.discard();
		}
		EXTRA_CRYSTALS.clear();
	}

	public static Entity crystalAt(ServerLevel level, Vec3 pos) {
		var found = level.getEntitiesOfClass(net.minecraft.world.entity.boss.enderdragon.EndCrystal.class,
			new net.minecraft.world.phys.AABB(pos, pos).inflate(0.5), e -> !e.isRemoved());
		return found.isEmpty() ? null : found.get(0);
	}

	/** The nearest living entity of this type (by id) within 128 blocks, or null. */
	public static Entity nearestOfType(ServerLevel level, String type, net.minecraft.core.BlockPos pos) {
		Entity best = null;
		double bestDist = 128 * 128;
		var box = new net.minecraft.world.phys.AABB(pos).inflate(128);
		for (Entity e : level.getEntitiesOfClass(LivingEntity.class, box, x -> x.isAlive()
			&& net.minecraft.world.entity.EntityType.getKey(x.getType()).toString().equals(type))) {
			double d = e.distanceToSqr(pos.getX(), pos.getY(), pos.getZ());
			if (d < bestDist) { bestDist = d; best = e; }
		}
		if (best == null && "minecraft:ender_dragon".equals(type)) for (EnderDragon d : level.getDragons()) if (d.isAlive()) return d;
		return best;
	}

	public static boolean isBoss(Entity e) {
		return e instanceof EnderDragon || e instanceof WitherBoss;
	}

	/** Damage a player did to a boss in an earlier run. */
	public static void damage(ServerLevel level, LivingEntity boss, float amount, boolean show) {
		if (dying(boss) || amount <= 0) return;
		// Trial spawner mobs get i-frames, like any hit: for half a second after one, only a harder hit counts. Bosses
		// don't, so every arrow lands (see BossHitMixin).
		if (!isBoss(boss)) {
			var hurt = (dev.recur.mixin.LivingEntityHurtAccessor) boss;
			if (boss.getInvulnerableTime() > 10) {
				float last = hurt.recur$lastHurt();
				if (amount <= last) return;
				hurt.recur$setLastHurt(amount);
				amount -= last;
				show = false;
			} else {
				hurt.recur$setLastHurt(amount);
				boss.setInvulnerableTime(20);
			}
		}
		if (TrialSpawns.held(boss)) TrialSpawns.addDamage(boss, amount);
		if (show) level.broadcastDamageEvent(boss, level.damageSources().generic());
		float health = boss.getHealth() - amount;
		if (health > 0) {
			boss.setHealth(health);
			return;
		}
		if (boss instanceof EnderDragon dragon) {
			// Like the dragon's own killing blow: it flies off to die by itself.
			dragon.setHealth(1.0F);
			dragon.getPhaseManager().setPhase(EnderDragonPhase.DYING);
			Controlled.release(dragon);
		} else {
			MobDrops.killedByReplay(boss); // its loot drops (it's in the recording)
			boss.kill(level);
		}
	}

	private static boolean livePlayer(Entity e) {
		return e instanceof net.minecraft.server.level.ServerPlayer p && !(p instanceof com.mt1006.mocap.utils.FakePlayer)
			&& p.isAlive() && !p.isSpectator() && !p.isCreative();
	}

	/**
	 * A wither skull or dragon fireball the recording has the boss shoot goes for the nearest player it can see, the way
	 * the boss would aim at them (clones aren't who it's fighting now). Out of range, or with nobody in sight, it flies
	 * where it did in the recording.
	 */
	public static void aimAtPlayer(ServerLevel level, net.minecraft.world.entity.Mob boss, Entity shot) {
		double range = boss instanceof WitherBoss ? 20 : 64; // how far each of them shoots at a player
		net.minecraft.server.level.ServerPlayer target = null;
		double best = range * range;
		for (net.minecraft.server.level.ServerPlayer p : level.players()) {
			if (!livePlayer(p)) continue;
			double d = p.distanceToSqr(boss);
			if (d >= best) continue;
			Vec3 to = new Vec3(p.getX(), p.getY(0.5), p.getZ());
			if (level.clip(new net.minecraft.world.level.ClipContext(shot.position(), to, net.minecraft.world.level.ClipContext.Block.COLLIDER,
				net.minecraft.world.level.ClipContext.Fluid.NONE, shot)).getType() != net.minecraft.world.phys.HitResult.Type.MISS) continue;
			best = d;
			target = p;
		}
		if (target == null) return;
		Vec3 dir = new Vec3(target.getX(), target.getY(0.5), target.getZ()).subtract(shot.position());
		double speed = shot.getDeltaMovement().length();
		if (dir.lengthSqr() < 1.0E-6 || speed < 1.0E-4) return;
		shot.setDeltaMovement(dir.normalize().scale(speed));
	}

	/** The recording has the dragon change phase. */
	public static void setPhase(EnderDragon dragon, int id) {
		if (id < 0 || dragon.isDeadOrDying()) return;
		EnderDragonPhase<?> phase = EnderDragonPhase.getById(id);
		if (dragon.getPhaseManager().getCurrentPhase().getPhase() != phase) dragon.getPhaseManager().setPhase(phase);
		// Its death flight runs by itself.
		if (phase == EnderDragonPhase.DYING) Controlled.release(dragon);
	}

	/**
	 * A wither that was just built powers up for 11 seconds: it can't be hurt, its health fills up, and then it blows up.
	 * The game does that in its AI, which is off while a clone plays it back, so it's done here (the same way). The blast
	 * comes from the recording when it's there (with exactly the blocks it broke), and from the wither itself otherwise.
	 */
	public static void powerUp(ServerLevel level, WitherBoss wither) {
		int ticks = wither.getInvulnerableTicks();
		if (ticks <= 0) return;
		int left = ticks - 1;
		((dev.recur.mixin.WitherBossAccessor) wither).recur$bossEvent().setProgress(1.0F - left / 220.0F);
		if (left <= 0) {
			// Its recorded crater, the same every run, comes with the recorded blast (see WorldLog.replayExplosion). Without
			// one, the real blast breaks blocks like vanilla, and the nearest player recording keeps that crater.
			if (WorldLog.craterComing(level, wither)) CRATER_DUE.put(wither, level.getGameTime() + 60);
			else if (blast(wither))
				level.explode(wither, wither.getX(), wither.getEyeY(), wither.getZ(), 7.0F, false, net.minecraft.world.level.Level.ExplosionInteraction.MOB);
			if (!wither.isSilent()) level.globalLevelEvent(1023, wither.blockPosition(), 0);
		}
		wither.setInvulnerableTicks(left);
		if (wither.tickCount % 10 == 0) wither.heal(10.0F);
	}

	/** Withers waiting for their recorded crater, and until when (if it never comes, they blow up by themselves). */
	private static final java.util.Map<WitherBoss, Long> CRATER_DUE = new java.util.WeakHashMap<>();

	/** Every tick, for a wither a clone plays: a recorded crater that didn't come in time (its clone is gone) isn't waited for. */
	public static void checkCrater(ServerLevel level, WitherBoss wither) {
		Long due = CRATER_DUE.isEmpty() ? null : CRATER_DUE.get(wither);
		if (due == null || level.getGameTime() < due) return;
		CRATER_DUE.remove(wither);
		if (blast(wither))
			level.explode(wither, wither.getX(), wither.getEyeY(), wither.getZ(), 7.0F, false, net.minecraft.world.level.Level.ExplosionInteraction.MOB);
	}

	/** Withers that have had their blast this run. */
	private static final java.util.Set<WitherBoss> BLASTED = java.util.Collections.newSetFromMap(new java.util.WeakHashMap<>());

	/** A wither's blast after powering up is happening now: true if it hasn't had it yet (it's done powering up then). */
	public static boolean blast(WitherBoss wither) {
		if (!BLASTED.add(wither)) return false;
		if (wither.getInvulnerableTicks() > 0) wither.setInvulnerableTicks(0);
		return true;
	}

	/**
	 * A wither's AI is off while a clone plays it back, so its side heads would stare ahead. They look at whoever is
	 * near instead, which is what they did before (its skulls are replayed from the recording).
	 */
	public static void aimWitherHeads(ServerLevel level, WitherBoss wither) {
		if (wither.tickCount % 10 != 0) return;
		List<LivingEntity> near = level.getNearbyEntities(LivingEntity.class, HEAD_TARGETS, wither, wither.getBoundingBox().inflate(20.0, 8.0, 20.0));
		// players come first, since that's who its skulls go for (see aimAtPlayer)
		List<LivingEntity> players = new ArrayList<>();
		for (LivingEntity l : near) if (livePlayer(l)) players.add(l);
		List<LivingEntity> pool = players.isEmpty() ? near : players;
		for (int head = 1; head <= 2; head++) {
			Entity current = level.getEntity(wither.getAlternativeTarget(head));
			if (current != null && current.isAlive() && current.distanceToSqr(wither) < 30 * 30 && (players.isEmpty() || players.contains(current))) continue;
			wither.setAlternativeTarget(head, pool.isEmpty() ? 0 : pool.get(level.getRandom().nextInt(pool.size())).getId());
		}
		wither.setAlternativeTarget(0, 0); // the middle head follows the recorded head rotation
		if (wither.getInvulnerableTicks() <= 0) // while powering up, the bar shows that instead
			((dev.recur.mixin.WitherBossAccessor) wither).recur$bossEvent().setProgress(wither.getHealth() / wither.getMaxHealth());
	}
}
