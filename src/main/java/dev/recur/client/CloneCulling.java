package dev.recur.client;

import it.unimi.dsi.fastutil.ints.IntOpenHashSet;
import it.unimi.dsi.fastutil.ints.IntSet;
import net.minecraft.client.Minecraft;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.level.ClipContext;
import net.minecraft.world.phys.HitResult;
import net.minecraft.world.phys.Vec3;

/**
 * Clones that are far away and behind blocks aren't drawn. A player model is costly to draw, and with dozens of clones
 * around that adds up, even for ones you can't see. Nothing else changes: they still move, act and animate, so a clone
 * looks exactly as it should the moment it comes into view. Nearby clones are always drawn, and a clone counts as seen
 * if any of its head, middle or feet is in sight (through glass too).
 */
public final class CloneCulling {
	/** Always drawn this close (blocks). */
	private static final double NEAR = 24;
	/** A drawn clone's line of sight is checked every this many ticks (spread out over the ticks). */
	private static final int EVERY = 4;

	private static final IntSet HIDDEN = new IntOpenHashSet();
	private static int ticks;

	private CloneCulling() {}

	public static boolean hidden(Entity e) { return !HIDDEN.isEmpty() && HIDDEN.contains(e.getId()); }

	public static void tick(Minecraft mc) {
		ticks++;
		var level = mc.level;
		Entity camera = mc.getCameraEntity();
		IntSet clones = ClientLoopState.cloneIds();
		if (level == null || camera == null || clones.isEmpty()) { HIDDEN.clear(); return; }
		HIDDEN.retainAll(clones);
		Vec3 eye = camera.getEyePosition();
		for (int id : clones) {
			// a hidden one is checked every tick, so it never pops in late as it comes into view
			if (!HIDDEN.contains(id) && Math.floorMod(id + ticks, EVERY) != 0) continue;
			Entity e = level.getEntity(id);
			if (e == null || e.distanceToSqr(eye) < NEAR * NEAR || e == camera || seen(level, camera, eye, e)) HIDDEN.remove(id);
			else HIDDEN.add(id);
		}
	}

	private static boolean seen(net.minecraft.world.level.Level level, Entity camera, Vec3 eye, Entity e) {
		Vec3 feet = e.position().add(0, 0.1, 0);
		return clear(level, camera, eye, e.getEyePosition()) || clear(level, camera, eye, feet.add(0, e.getBbHeight() * 0.5, 0)) || clear(level, camera, eye, feet);
	}

	private static boolean clear(net.minecraft.world.level.Level level, Entity camera, Vec3 from, Vec3 to) {
		return level.clip(new ClipContext(from, to, ClipContext.Block.VISUAL, ClipContext.Fluid.NONE, camera)).getType() == HitResult.Type.MISS;
	}
}
