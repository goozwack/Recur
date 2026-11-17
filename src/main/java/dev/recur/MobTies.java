package dev.recur;

import com.mt1006.mocap.mocap.playing.Controlled;
import com.mt1006.mocap.utils.FakePlayer;
import net.minecraft.core.BlockPos;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.Mob;
import net.minecraft.world.entity.OwnableEntity;
import net.minecraft.world.entity.TamableAnimal;
import net.minecraft.world.entity.boss.enderdragon.EnderDragon;
import net.minecraft.world.entity.ai.goal.TemptGoal;
import net.minecraft.world.entity.ai.goal.WrappedGoal;
import net.minecraft.world.entity.ai.memory.MemoryModuleType;
import net.minecraft.world.level.Level;
import net.minecraft.world.phys.AABB;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Iterator;
import java.util.Map;
import java.util.UUID;

/**
 * Mobs are tied to the player they are busy with, and that player's recording moves them. Their clone then plays the
 * mob back exactly as it was: a skeleton keeps fighting the clone, sheep follow it into the pen.
 * <ul>
 * <li>A mob that nobody's recording has yet is tied to a player once it targets them, follows the food they hold,
 * is leashed to them, was just hurt by them, rides with them, or is their pet following them.</li>
 * <li>Doing something to a mob directly (hitting it, using or leashing it, placing a block right next to it) ties
 * it to you straight away, even when it belongs to another player's recording or a clone is playing it back. From
 * that moment the clone lets go of it and your recording takes over. In later runs your clone takes it over from the
 * older clone at the same moment.</li>
 * <li>Once tied, a mob stays in your recording for the rest of the run, along with everything it did earlier in the
 * run (see MobHistory), so your clone plays its whole life.</li>
 * </ul>
 */
public final class MobTies {
	/** How far from a player a mob can be to be tied to them. */
	private static final double REACH = 48;
	/** A mob hurt by a player counts as busy with them for this long. */
	private static final int HURT_TICKS = 100;
	/** A pet further away than this isn't following its owner. */
	private static final double PET_REACH = 24;
	private static final int SCAN_INTERVAL = 5;
	/** The dragon flies far: anyone this close is in the fight. */
	private static final double DRAGON_REACH = 200;


	private MobTies() {}

	public static void clear() { RECORDED_TARGETING.clear(); WAITING_CREAKINGS.clear(); }

	public static void tick(MinecraftServer server) {
		if (!LoopOverlay.active || server.getTickCount() % SCAN_INTERVAL != 0) return;
		handOffs();
		retryCreakings();
		for (ServerPlayer player : server.getPlayerList().getPlayers()) {
			if (player instanceof FakePlayer || !WorldLog.isRecording(player)) continue;
			ServerLevel level = player.level();
			List<Mob> mobs = new ArrayList<>(level.getEntitiesOfClass(Mob.class, player.getBoundingBox().inflate(REACH)));
			for (EnderDragon dragon : level.getDragons()) if (!mobs.contains(dragon)) mobs.add(dragon);
			for (Mob mob : mobs) {
				if (!busyWith(mob, player)) continue;
				UUID owner = EntityOwners.ownerOf(mob);
				if (owner == null && !Controlled.isControlled(mob)) EntityOwners.claimMob(player, mob, false);
			}
		}
	}

	/** How close a mob chasing a player has to be to count as attacking them. */
	private static final double CHASE_REACH = 7;
	/** A mob a clone plays back that comes this close to a player becomes theirs (see handOffs). */
	private static final double HAND_OFF_REACH = 5;

	/** Is this mob going for the player right now: chasing them up close, aiming at them, swelling, firing? */
	private static boolean attacking(Mob mob, ServerPlayer player) {
		if (mob instanceof net.minecraft.world.entity.boss.wither.WitherBoss wither) {
			for (int i = 0; i < 3; i++) if (wither.getAlternativeTarget(i) == player.getId()) return true;
			return false;
		}
		// a guardian charging its laser at them
		if (mob instanceof net.minecraft.world.entity.monster.Guardian guardian && guardian.getActiveAttackTarget() == player) return true;
		if (mob.getTarget() != player) return false;
		// an evoker (or illusioner) casting at them: fangs, vexes, blindness
		if (mob instanceof net.minecraft.world.entity.monster.illager.SpellcasterIllager caster && caster.isCastingSpell()) return true;
		if (mob instanceof net.minecraft.world.entity.monster.warden.Warden) return true; // it only takes a target once it's coming for you
		if (mob instanceof net.minecraft.world.entity.monster.RangedAttackMob || mob instanceof net.minecraft.world.entity.monster.CrossbowAttackMob)
			return mob.isAggressive() || mob.isUsingItem();
		if (mob instanceof net.minecraft.world.entity.monster.Ghast || mob instanceof net.minecraft.world.entity.monster.Blaze)
			return mob.hasLineOfSight(player);
		return mob.distanceToSqr(player) < CHASE_REACH * CHASE_REACH;
	}

	/**
	 * A mob tried to hurt a player (a swing, a shot, a sonic boom...), whether it hit or not: it's theirs, like a mob
	 * they hit, so their clone plays its whole life.
	 */
	public static void onAttempt(Entity attacker, Entity target) {
		if (!(target instanceof ServerPlayer player) || player instanceof FakePlayer) return;
		Mob mob = attacker instanceof Mob m ? m
			: attacker instanceof net.minecraft.world.entity.projectile.Projectile p && p.getOwner() instanceof Mob shooter ? shooter : null;
		if (mob != null && !Controlled.isControlled(mob) && !Controlled.justReleased(mob)) direct(player, mob);
	}

	/** A mob shot something at a player (an arrow, a fireball, a wither skull...). */
	public static void onShot(Entity e) {
		if (!(e instanceof net.minecraft.world.entity.projectile.Projectile p) || !(p.getOwner() instanceof Mob shooter)) return;
		if (shooter.getTarget() instanceof ServerPlayer target) onAttempt(shooter, target);
	}

	/**
	 * Mobs that fight players and that a clone is playing back: one that comes close to a player stops being a
	 * recording. It's simulated again from here on, and the player's recording takes it over.
	 */
	private static void handOffs() {
		for (Entity e : Controlled.all()) {
			if (!(e instanceof Mob mob) || !mob.isAlive() || BossReplay.isBoss(mob) || TrialSpawns.held(mob) || !fightsPlayers(mob)) continue;
			if (mob instanceof net.minecraft.world.entity.monster.Enderman enderman && staredAt(enderman)) continue;
			// a creaking someone's looking at can't move, so it keeps its replay until nobody is
			if (mob instanceof net.minecraft.world.entity.monster.creaking.Creaking && mob.level() instanceof ServerLevel level && watched(level, mob)) continue;
			ServerPlayer near = nearestTarget(mob, HAND_OFF_REACH);
			if (near != null) handOff(mob, near, true);
		}
	}

	/** An enderman a clone plays back that a player looks at gets angry at them, like any enderman. */
	private static boolean staredAt(net.minecraft.world.entity.monster.Enderman enderman) {
		if (!(enderman.level() instanceof ServerLevel level)) return false;
		for (ServerPlayer p : level.players()) {
			if (p instanceof FakePlayer || !p.isAlive() || p.isSpectator() || p.isCreative() || !WorldLog.isRecording(p)) continue;
			if (!((dev.recur.mixin.EndermanAccessor) enderman).recur$isBeingStaredBy(p)) continue;
			enderman.setBeingStaredAt();
			handOff(enderman, p, true);
			return true;
		}
		return false;
	}

	/**
	 * A player hurt a mob a clone plays back, in any way (an arrow, a potion, fire, thorns...): it stops being a replay
	 * before the hit lands, so it takes the damage and turns on them like any mob would. (A melee hit does this already,
	 * see direct.) Bosses keep their replay and add up the damage instead (see BossReplay).
	 */
	public static void onReplayHurt(LivingEntity victim, net.minecraft.world.damagesource.DamageSource source) {
		if (!(victim instanceof Mob mob) || !Controlled.isControlled(mob) || BossReplay.isBoss(mob) || TrialSpawns.held(mob)) return;
		if (source.getEntity() instanceof ServerPlayer player && !(player instanceof FakePlayer)) direct(player, mob);
	}

	/**
	 * Something made a mob a clone plays back go for a player: its group was attacked (zombified piglins, wolves, bees,
	 * zombies calling for help), or a piglin saw them open a chest or mine gold. It stops being a replay and goes for
	 * them, and the player's recording has it from now on.
	 */
	public static void onTargetSet(Mob mob, LivingEntity target) {
		if (!(target instanceof ServerPlayer player) || player instanceof FakePlayer || !Controlled.isControlled(mob) || BossReplay.isBoss(mob) || TrialSpawns.held(mob)) return;
		if (!fightsPlayers(mob) || !player.isAlive() || player.isCreative() || player.isSpectator() || !WorldLog.isRecording(player)) return;
		Controlled.markLeft(mob);
		Controlled.release(mob);
		EntityOwners.claimMob(player, mob, true);
	}

	/** Creakings that gave up on their clone while a player was looking at them. */
	private static final java.util.Set<Mob> WAITING_CREAKINGS = java.util.Collections.newSetFromMap(new java.util.WeakHashMap<>());

	private static void retryCreakings() {
		if (WAITING_CREAKINGS.isEmpty()) return;
		for (Mob mob : new ArrayList<>(WAITING_CREAKINGS)) {
			// gone, let go already, or going for its clone again in the recording
			if (!mob.isAlive() || !Controlled.isControlled(mob) || !Boolean.FALSE.equals(RECORDED_TARGETING.get(mob))) { WAITING_CREAKINGS.remove(mob); continue; }
			if (!(mob.level() instanceof ServerLevel level) || watched(level, mob)) continue;
			var followRange = mob.getAttribute(net.minecraft.world.entity.ai.attributes.Attributes.FOLLOW_RANGE);
			ServerPlayer player = nearestTarget(mob, followRange != null ? followRange.getValue() : 16);
			if (player != null) {
				WAITING_CREAKINGS.remove(mob);
				handOff(mob, player, true);
			}
		}
	}

	/** Is a player in this loop (not a clone) looking at this mob, the way a creaking checks it? */
	private static boolean watched(ServerLevel level, Mob mob) {
		for (ServerPlayer p : level.players()) {
			if (p instanceof FakePlayer || !p.isAlive() || p.isSpectator() || p.isCreative() || !WorldLog.isRecording(p)) continue;
			if (mob.isLookingAtMe(p, 0.5, false, true, mob.getEyeY(), mob.getY() + 0.5 * mob.getScale(), (mob.getEyeY() + mob.getY()) / 2)) return true;
		}
		return false;
	}

	private static boolean fightsPlayers(Mob mob) {
		if (mob instanceof OwnableEntity pet && pet.getOwner() != null) return false; // someone's pet
		// the smallest slimes can't hurt anyone (the smallest magma cubes can)
		if (mob instanceof net.minecraft.world.entity.monster.cubemob.Slime slime && slime.isTiny()) return false;
		return mob instanceof net.minecraft.world.entity.monster.Enemy || mob instanceof net.minecraft.world.entity.NeutralMob;
	}

	/** The nearest player in this loop (not a clone) within reach that this mob would go for, or null. */
	private static ServerPlayer nearestTarget(Mob mob, double reach) {
		if (!(mob.level() instanceof ServerLevel level)) return null;
		ServerPlayer best = null;
		double bestDist = reach * reach;
		for (ServerPlayer p : level.players()) {
			if (p instanceof FakePlayer || !WorldLog.isRecording(p)) continue;
			double d = p.distanceToSqr(mob);
			if (d < bestDist && wouldTarget(level, mob, p, reach)) { bestDist = d; best = p; }
		}
		return best;
	}

	/**
	 * Would this mob go for this player, the way vanilla decides it: not in creative or spectator, not hidden by
	 * invisibility, in sight, not wearing gold around a piglin, and a neutral mob only when it's angry.
	 */
	private static boolean wouldTarget(ServerLevel level, Mob mob, ServerPlayer player, double reach) {
		// creative, spectator, peaceful, invisibility (which shrinks how far mobs notice you) and the like
		// (line of sight is checked here directly: a mob being played back doesn't update what it can see)
		if (!net.minecraft.world.entity.ai.targeting.TargetingConditions.forCombat().range(reach).ignoreLineOfSight().test(level, mob, player)) return false;
		if (!mob.hasLineOfSight(player)) return false;
		if (mob instanceof net.minecraft.world.entity.monster.piglin.Piglin && net.minecraft.world.entity.monster.piglin.PiglinAi.isWearingSafeArmor(player))
			return mob.getBrain().getMemory(MemoryModuleType.ANGRY_AT).map(player.getUUID()::equals).orElse(false);
		if (mob instanceof net.minecraft.world.entity.NeutralMob neutral)
			return neutral.isAngryAt(player, level) || neutral.isAngryAtAllPlayers(level) || Boolean.TRUE.equals(RECORDED_TARGETING.get(mob));
		return true;
	}

	/** The clone lets go of the mob and the player's recording has it from now on (in later runs, their clone takes it over here). */
	private static void handOff(Mob mob, ServerPlayer player, boolean target) {
		Controlled.markLeft(mob);
		Controlled.release(mob);
		EntityOwners.claimMob(player, mob, true);
		if (target) aggro(mob, player);
	}

	/**
	 * Makes a mob go for this player right away, the way each kind keeps its target: wardens by anger, neutral mobs by
	 * a grudge, piglins, hoglins, breezes and other brain-run mobs by their attack memory, the rest by their target.
	 */
	private static void aggro(Mob mob, ServerPlayer player) {
		if (mob instanceof net.minecraft.world.entity.monster.warden.Warden warden) {
			warden.increaseAngerAt(player, 100, false); // 80 and up is angry
			warden.setAttackTarget(player);
			return;
		}
		if (mob instanceof net.minecraft.world.entity.NeutralMob neutral) {
			neutral.setPersistentAngerTarget(net.minecraft.world.entity.EntityReference.of(player));
			neutral.startPersistentAngerTimer();
		}
		if (mob.getBrain().checkMemory(MemoryModuleType.ATTACK_TARGET, net.minecraft.world.entity.ai.memory.MemoryStatus.REGISTERED))
			mob.getBrain().setMemory(MemoryModuleType.ATTACK_TARGET, player);
		mob.setTarget(player);
	}

	/** Whether each mob being played back was going for its player at this point in the recording. */
	private static final Map<Mob, Boolean> RECORDED_TARGETING = new java.util.WeakHashMap<>();

	/**
	 * In the recording, a mob went for (or stopped going for) the player whose clone plays it now. When it gives up on
	 * the clone and a player in this loop is right there for it to go for, it does that instead. Otherwise it carries on
	 * as recorded.
	 */
	public static void onRecordedTargeting(Mob mob, boolean targeting) {
		Boolean was = RECORDED_TARGETING.put(mob, targeting);
		if (targeting || was == null || !was || com.mt1006.mocap.mocap.playing.PlayedScene.fastForwarding) return;
		if (!Controlled.isControlled(mob) || BossReplay.isBoss(mob) || !(mob.level() instanceof ServerLevel level)) return;
		// A creaking can't move while someone's looking at it, so it only switches when nobody is.
		if (mob instanceof net.minecraft.world.entity.monster.creaking.Creaking && watched(level, mob)) {
			WAITING_CREAKINGS.add(mob); // it tries again once nobody's looking (see handOffs)
			return;
		}
		var followRange = mob.getAttribute(net.minecraft.world.entity.ai.attributes.Attributes.FOLLOW_RANGE);
		ServerPlayer player = nearestTarget(mob, followRange != null ? followRange.getValue() : 16);
		if (player != null) handOff(mob, player, true);
	}

	/** Is this mob doing something with (or because of) this player right now? */
	private static boolean busyWith(Mob mob, ServerPlayer player) {
		// The dragon belongs to whoever is fighting it on the island, so it replays the same whole fight.
		if (mob instanceof EnderDragon) return mob.level() == player.level() && mob.distanceToSqr(player) < DRAGON_REACH * DRAGON_REACH;
		if (attacking(mob, player)) return true;
		if (mob.getLeashHolder() == player) return true;
		if (mob.hasPassenger(player)) return true;
		Entity vehicle = mob.getVehicle();
		if (vehicle != null && (vehicle == player || player.getUUID().equals(EntityOwners.ownerOf(vehicle)))) return true;
		LivingEntity hurtBy = mob.getLastHurtByMob();
		if (hurtBy == player && mob.tickCount - mob.getLastHurtByMobTimestamp() < HURT_TICKS) return true;
		// Following the food the player holds: goal-based mobs (sheep, cows, pigs...) and brain-based ones (goats, axolotls...).
		for (WrappedGoal goal : mob.getGoalSelector().getAvailableGoals()) {
			if (goal.isRunning() && goal.getGoal() instanceof TemptGoal tempt && ((dev.recur.mixin.TemptGoalAccessor) tempt).recur$player() == player) return true;
		}
		if (mob.getBrain().hasMemoryValue(MemoryModuleType.TEMPTING_PLAYER)
			&& mob.getBrain().getMemory(MemoryModuleType.TEMPTING_PLAYER).orElse(null) == player) return true;
		// A pet following its owner around.
		if (mob instanceof OwnableEntity pet && pet.getOwner() == player && !(mob instanceof TamableAnimal tame && tame.isOrderedToSit())
			&& mob.distanceToSqr(player) < PET_REACH * PET_REACH) return true;
		return false;
	}

	/** A player did something to this mob directly (hit, used or leashed it): it's tied to them from now on. */
	public static void direct(net.minecraft.world.entity.player.Player player, Entity entity) {
		if (!(player instanceof ServerPlayer sp) || player instanceof FakePlayer || !(entity instanceof Mob mob)) return;
		// Hitting the dragon doesn't take it over: it keeps flying the recorded fight (see BossReplay).
		if (mob instanceof EnderDragon) return;
		// Nor does hitting the wither or a trial spawner's mob a clone is fighting (see TrialSpawns).
		if ((BossReplay.isBoss(mob) || TrialSpawns.held(mob)) && Controlled.isControlled(mob)) return;
		if (!LoopOverlay.active || !WorldLog.isRecording(sp)) return;
		EntityOwners.claimMob(sp, mob, true);
	}

	/**
	 * A clone starts moving a mob that isn't here by its id (it spawned anew this run: a raid wave, a natural spawn). It
	 * takes over the nearest one of the same kind that nobody is moving instead of making a copy, so the mob has been
	 * there all along.
	 */
	public static Entity standIn(com.mt1006.mocap.mocap.playing.PlayingContext ctx, String type, net.minecraft.world.phys.Vec3 pos) {
		ServerLevel level = ctx.level;
		// In the run this was recorded in, the mob was handed over here from an older clone (the player went for it). It
		// was born in that run, so its id is new every run: the one that older clone moves right here now is that mob.
		Entity handed = null;
		double handedDist = HANDED_OVER_REACH * HANDED_OVER_REACH;
		for (Mob m : level.getEntitiesOfClass(Mob.class, new AABB(pos, pos).inflate(HANDED_OVER_REACH), m -> m.isAlive()
			&& Controlled.isControlled(m) && Controlled.controller(m) != ctx && !TrialSpawns.fromSpawner(m)
			&& net.minecraft.world.entity.EntityType.getKey(m.getType()).toString().equals(type))) {
			double d = m.distanceToSqr(pos);
			if (d < handedDist) { handedDist = d; handed = m; }
		}
		if (handed != null) return handed;
		Entity best = null;
		double bestDist = STAND_IN_REACH * STAND_IN_REACH;
		for (Mob m : level.getEntitiesOfClass(Mob.class, new AABB(pos, pos).inflate(STAND_IN_REACH), m -> m.isAlive()
			&& !Controlled.isControlled(m) && EntityOwners.ownerOf(m) == null && !TrialSpawns.fromSpawner(m)
			&& net.minecraft.world.entity.EntityType.getKey(m.getType()).toString().equals(type))) {
			double d = m.distanceToSqr(pos);
			if (d < bestDist) { bestDist = d; best = m; }
		}
		return best;
	}

	private static final double STAND_IN_REACH = 24;
	private static final double HANDED_OVER_REACH = 2;

	/** A block was placed: mobs right next to it are boxed in by the player placing it. */
	public static void afterSetBlock(Level level, BlockPos pos) {
		ServerPlayer actor = WorldLog.actor();
		if (actor == null || ReplayScope.depth > 0 || !(level instanceof ServerLevel sl) || level.getBlockState(pos).isAir()) return;
		for (Mob mob : sl.getEntitiesOfClass(Mob.class, new AABB(pos).inflate(1.0))) direct(actor, mob);
	}
}
