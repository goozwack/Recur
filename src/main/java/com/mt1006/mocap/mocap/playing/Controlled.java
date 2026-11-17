package com.mt1006.mocap.mocap.playing;

import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.Mob;
import net.minecraft.world.phys.Vec3;
import org.jetbrains.annotations.Nullable;

import java.util.ArrayList;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Supplier;

/**
 * Entities a clone is moving right now (ones it spawned, or ones that were already there and it took over).
 * While controlled, an entity only goes where the recording says (no gravity, no AI, can't be hurt). Once the clone
 * won't touch it again, it is released and becomes a normal entity that anyone can push, ride or break.
 * An entity is controlled by at most one clone: the one whose recording touched it last.
 */
public final class Controlled
{
	/** Ticks after a clone's last recorded change to an entity before it lets go of it. */
	public static final int RELEASE_AFTER = 60;

	private static final class Info
	{
		PlayingContext ctx;
		final boolean noGravity, invulnerable, noAi, tagged;
		/** Where the recording says the entity is. Movements are deltas and get added to this position. */
		@Nullable Vec3 expected;
		/** The clones it was taken from in turn (the last one on top), its id in each, and when it was taken (see letGo). */
		final java.util.ArrayDeque<Previous> previous = new java.util.ArrayDeque<>();
		/** Its last recorded movement (one tick). */
		Vec3 lastStep = Vec3.ZERO;
		/** How far it's drawn from where the recording has it, shrinking every tick (see glideFrom). */
		Vec3 glide = Vec3.ZERO;

		Info(PlayingContext ctx, Entity e)
		{
			this.ctx = ctx;
			noGravity = e.isNoGravity();
			invulnerable = e.isPermanentlyInvulnerable();
			noAi = e instanceof Mob m && m.isNoAi();
			tagged = e.entityTags().contains(Playing.MOCAP_ENTITY_TAG);
		}
	}

	private record Previous(PlayingContext ctx, int id, long takenAt) {}

	private static final Map<Entity, Info> MAP = new IdentityHashMap<>();

	private Controlled() {}

	public static boolean isControlled(Entity e) { return !MAP.isEmpty() && MAP.containsKey(e); }

	/** Items and experience: they fall, slide and fly to players by themselves (the recording says when they appear and get picked up). */
	public static boolean loose(Entity e)
	{
		return e instanceof net.minecraft.world.entity.item.ItemEntity || e instanceof net.minecraft.world.entity.ExperienceOrb;
	}

	/** Entities clones move that have another id in this run than in their recording, by their recorded id. */
	private static final Map<java.util.UUID, Entity> ALIASES = new java.util.HashMap<>();
	/** Mobs that left their replay to go for a player for real: what their clone did to them doesn't happen any more. */
	private static final java.util.Set<Entity> LEFT = java.util.Collections.newSetFromMap(new java.util.WeakHashMap<>());

	public static void alias(java.util.UUID recorded, Entity e) { ALIASES.put(recorded, e); }

	public static @Nullable Entity alias(java.util.UUID recorded)
	{
		Entity e = ALIASES.isEmpty() ? null : ALIASES.get(recorded);
		return e != null && !e.isRemoved() ? e : null;
	}

	public static void markLeft(Entity e) { LEFT.add(e); }

	public static boolean hasLeft(@Nullable Entity e) { return e != null && !LEFT.isEmpty() && LEFT.contains(e); }

	/** Every entity a clone is moving right now. */
	public static List<Entity> all() { return MAP.isEmpty() ? List.of() : new ArrayList<>(MAP.keySet()); }

	public static @Nullable PlayingContext controller(Entity e)
	{
		Info i = MAP.isEmpty() ? null : MAP.get(e);
		return i != null ? i.ctx : null;
	}

	/** This clone takes the entity over (from the world, a live player, or another clone). */
	public static void take(PlayingContext ctx, Entity e)
	{
		if (!ORPHANS.isEmpty()) { ORPHANS.remove(e); }
		Info info = MAP.get(e);
		if (info != null)
		{
			if (info.ctx != ctx)
			{
				info.previous.push(new Previous(info.ctx, idIn(info.ctx, e), e.level().getGameTime()));
			}
			info.ctx.forget(e); // The new owner adds it back under its own id.
			info.ctx = ctx;
		}
		else
		{
			MAP.put(e, info = new Info(ctx, e));
			dev.recur.EntityOwners.drop(e); // a live player moving it lets go (their recording releases it)
		}
		// Items fall, bounce and slide by themselves (the recording only says when they appear and get picked up),
		// so an item never hangs in the air where the block it landed on in the original run no longer is.
		boolean item = loose(e);
		info.expected = item ? null : e.position();
		if (!item) { e.setDeltaMovement(Vec3.ZERO); }
		applyControl(e);
	}

	private static void applyControl(Entity e)
	{
		if (!loose(e)) { e.setNoGravity(true); }
		// Bosses can still be hurt while a clone plays them back, so a fight against one can be won (see BossReplay).
		// So can a trial spawner's mobs in a clone's fight (see TrialSpawns).
		if (!dev.recur.BossReplay.isBoss(e) && !dev.recur.TrialSpawns.held(e)
			&& !(e instanceof net.minecraft.world.entity.boss.enderdragon.EndCrystal)) { e.setPermanentlyInvulnerable(true); } // crystals can still be shot
		e.addTag(Playing.MOCAP_ENTITY_TAG);
		// The dragon keeps its AI on (only its phases and flying are stopped), or its wings and hitboxes freeze.
		if (e instanceof Mob m && !(e instanceof net.minecraft.world.entity.boss.enderdragon.EnderDragon)) { m.setNoAi(true); }
	}

	/**
	 * A controlled entity went through a portal. The copy on the other side becomes a normal entity again and is
	 * saved with its chunk. Its clone stays behind, so nothing would move it there anyway.
	 */
	public static void changedDimension(Entity original, Entity copy)
	{
		Info info = MAP.remove(original);
		if (info == null) { return; }
		info.ctx.forget(original);
		restore(copy, info);
	}

	/** Forget an entity that never made it into the world. */
	public static void abandon(Entity e) { MAP.remove(e); }

	/** The clone lets go of the entity, which becomes a normal entity again. */
	public static void release(Entity e)
	{
		Info info = MAP.remove(e);
		if (info == null) { return; }
		info.ctx.forget(e);
		restore(e, info);
		RELEASED_AT.put(e, e.level().getGameTime());
		forgetPlayers(e);
	}

	/**
	 * What a clone's mob was angry at in its recording was the recorded player, who is a real player now: once the
	 * clone lets go of it, it doesn't go after them: it goes after the clone instead, or calms down once that's gone.
	 */
	private static void forgetPlayers(Entity e)
	{
		if (!(e instanceof Mob m)) { return; }
		if (m.getLastHurtByMob() instanceof net.minecraft.world.entity.player.Player p && !(p instanceof com.mt1006.mocap.utils.FakePlayer)) { m.setLastHurtByMob(null); }
		dev.recur.CloneAnger.redirect(m); // anger at the recorded player goes to their clone, if it's still around
		if (m.getTarget() instanceof net.minecraft.world.entity.player.Player p && !(p instanceof com.mt1006.mocap.utils.FakePlayer)) { m.setTarget(null); }
	}

	private record Orphan(Vec3 step, long since) {}
	private static final Map<Entity, Orphan> ORPHANS = new java.util.WeakHashMap<>();

	/** Every tick: orphaned bosses glide on; one no clone takes within 3 seconds is let go. */
	public static void tickOrphans()
	{
		if (ORPHANS.isEmpty()) { return; }
		for (var it = ORPHANS.entrySet().iterator(); it.hasNext(); )
		{
			var entry = it.next();
			Entity e = entry.getKey();
			Info info = MAP.get(e);
			if (info == null || e.isRemoved()) { it.remove(); continue; }
			if (e.level().getGameTime() - entry.getValue().since() > 60) { it.remove(); release(e); continue; }
			Vec3 step = entry.getValue().step().scale(0.95);
			entry.setValue(new Orphan(step, entry.getValue().since()));
			info.expected = info.expected.add(step);
			e.snapTo(info.expected.x, info.expected.y, info.expected.z, e.getYRot(), e.getXRot());
		}
	}

	/** Entities let go of in the last few ticks (see justReleased). */
	private static final Map<Entity, Long> RELEASED_AT = new java.util.WeakHashMap<>();

	/**
	 * A clone's recording lets go of it. If this clone took it from another clone a moment ago (a few ticks), it goes
	 * back to that one instead. That happens when a player grabbed it for a tick in the run this was recorded in, right
	 * before an older clone took it back: letting go would set it free in the middle of the older clone's fight.
	 */
	public static void letGo(Entity e) { letGo(e, false); }

	/**
	 * The clone playing it stopped (its recording ended: its player died there, or their run ended). A boss goes back to
	 * the clone that had it before, whose recording still has the rest of that fight, instead of being set free in the
	 * middle of it.
	 */
	public static void letGoAtEnd(Entity e) { letGo(e, dev.recur.BossReplay.isBoss(e)); }

	private static void letGo(Entity e, boolean anyTime)
	{
		Info info = MAP.get(e);
		while (info != null && !info.previous.isEmpty())
		{
			Previous back = info.previous.peek();
			if (!anyTime && e.level().getGameTime() - back.takenAt() > 5) { break; } // it had it for a while
			info.previous.pop();
			if (back.ctx() == info.ctx || !stillPlaying(back.ctx())) { continue; }
			info.ctx.forget(e);
			info.ctx = back.ctx();
			info.ctx.entityMap.put(back.id(), e);
			return;
		}
		// A boss nobody had before: it keeps going the way it was flying until the next clone's recording takes it
		// (see orphans), instead of stopping dead or turning on players.
		if (false)
		{
			info.ctx.forget(e);
			ORPHANS.put(e, new Orphan(info.lastStep, e.level().getGameTime()));
			return;
		}
		release(e);
	}

	/** Was it let go of by a clone in the last few ticks? Then nobody takes it right away (see EntityOwners.claimMob). */
	public static boolean justReleased(Entity e)
	{
		Long at = RELEASED_AT.isEmpty() ? null : RELEASED_AT.get(e);
		return at != null && e.level().getGameTime() - at <= 5;
	}

	private static boolean stillPlaying(PlayingContext ctx)
	{
		return !ctx.entityRemoved && ctx.mainEntity != null && !ctx.mainEntity.isRemoved();
	}

	private static int idIn(PlayingContext ctx, Entity e)
	{
		for (Map.Entry<Integer, Entity> entry : ctx.entityMap.entrySet()) { if (entry.getValue() == e) { return entry.getKey(); } }
		return Integer.MIN_VALUE;
	}

	/**
	 * A boss a clone just took over from another clone jumped to where this recording has it (the two runs went a
	 * little differently). It's put back and glides over there in about half a second instead.
	 */
	public static void glideFrom(Entity e, Vec3 was)
	{
		Info info = MAP.get(e);
		if (info == null || info.expected == null || !dev.recur.BossReplay.isBoss(e)) { return; }
		Vec3 offset = was.subtract(info.expected);
		if (offset.lengthSqr() < 1.0E-4 || offset.lengthSqr() > 64 * 64) { return; } // too far: it really is somewhere else
		info.glide = offset;
		e.snapTo(was.x, was.y, was.z, e.getYRot(), e.getXRot());
	}

	/** Where it's drawn, relative to where the recording has it. */
	public static Vec3 glideOf(Entity e)
	{
		Info info = MAP.isEmpty() ? null : MAP.get(e);
		return info != null ? info.glide : Vec3.ZERO;
	}

	/** Removes it from control without changing it (it's about to be removed). */
	static void drop(Entity e) { MAP.remove(e); }

	private static void restore(Entity e, Info i)
	{
		e.setNoGravity(i.noGravity);
		e.setPermanentlyInvulnerable(i.invulnerable);
		if (!i.tagged) { e.removeTag(Playing.MOCAP_ENTITY_TAG); }
		if (e instanceof Mob m) { m.setNoAi(i.noAi); }
	}

	/** Runs the supplier with the entity in its own uncontrolled state, which is how a checkpoint saves it. */
	public static <T> T natural(Entity e, Supplier<T> s)
	{
		Info i = MAP.isEmpty() ? null : MAP.get(e);
		if (i == null) { return s.get(); }
		restore(e, i);
		try { return s.get(); }
		finally { applyControl(e); }
	}

	/** Where the recording has this entity (null if unknown). */
	public static @Nullable Vec3 expectedOf(Entity e)
	{
		Info i = MAP.isEmpty() ? null : MAP.get(e);
		return i != null ? i.expected : null;
	}

	public static void setExpectedOf(Entity e, Vec3 pos)
	{
		Info i = MAP.get(e);
		if (i != null) { if (i.expected != null) { i.lastStep = pos.subtract(i.expected); } i.expected = pos; }
		if (!ORPHANS.isEmpty()) { ORPHANS.remove(e); }
	}

	/** Every tick, entities left alone by their clone are kept exactly where the recording put them. */
	static void hold(PlayingContext ctx)
	{
		if (MAP.isEmpty()) { return; }
		for (Entity e : ctx.entityMap.values())
		{
			Info i = MAP.get(e);
			if (i == null || i.ctx != ctx || i.expected == null || e.isPassenger() || e.isRemoved()) { continue; }
			if (i.glide != Vec3.ZERO) { i.glide = i.glide.lengthSqr() < 1.0E-3 ? Vec3.ZERO : i.glide.scale(0.8); }
			Vec3 at = i.expected.add(i.glide);
			if (e.position().distanceToSqr(at) > 1.0E-4)
			{
				e.setDeltaMovement(Vec3.ZERO);
				e.snapTo(at.x, at.y, at.z, e.getYRot(), e.getXRot());
			}
		}
	}

	public static void clear()
	{
		ALIASES.clear();
		LEFT.clear();
		for (Map.Entry<Entity, Info> entry : new ArrayList<>(MAP.entrySet()))
		{
			if (!entry.getKey().isRemoved()) { restore(entry.getKey(), entry.getValue()); }
		}
		MAP.clear();
	}
}
