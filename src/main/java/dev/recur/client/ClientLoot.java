package dev.recur.client;

import dev.recur.LoopNetworking;
import net.fabricmc.fabric.api.client.networking.v1.ClientPlayNetworking;
import net.minecraft.client.Minecraft;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.EntityHitResult;
import net.minecraft.world.phys.HitResult;

/**
 * Timed resets: shows what gives you nothing. The crosshair turns red on a block or entity that won't give you an item
 * (asked from the server whenever the crosshair moves to something new), and a container slot turns red when you
 * hover over an item a clone takes out later.
 */
public final class ClientLoot {
	private static int kind;
	private static long value;
	private static int sinceAsked;
	private static boolean red;
	/** Recent answers (target -> [nothing ? 1 : 0, client tick]), so looking back at something is instant. */
	private static final java.util.Map<Long, long[]> CACHE = new java.util.LinkedHashMap<>(64, 0.75f, true) {
		@Override protected boolean removeEldestEntry(java.util.Map.Entry<Long, long[]> e) { return size() > 256; }
	};
	private static long ticks;

	private static int lockedMenu = -1;
	private static java.util.Set<Integer> locked = java.util.Set.of();

	private ClientLoot() {}

	public static boolean crosshairRed() { return red && ClientLoopState.timedTicks >= 0; }

	public static boolean isLocked(int containerId, int slot) {
		return containerId == lockedMenu && locked.contains(slot);
	}

	public static void clear() { kind = 0; value = 0; red = false; lockedMenu = -1; locked = java.util.Set.of(); CACHE.clear(); }

	private static long key(int kind, long value) { return value * 4 + kind; }

	public static void tick(Minecraft mc) {
		ticks++;
		if (ClientLoopState.timedTicks < 0 || mc.level == null || !ClientPlayNetworking.canSend(LoopNetworking.LookPayload.TYPE)) {
			red = false;
			kind = 0;
			return;
		}
		HitResult hit = mc.hitResult;
		int newKind = 0;
		long newValue = 0;
		if (hit instanceof BlockHitResult blockHit && hit.getType() == HitResult.Type.BLOCK) { newKind = 1; newValue = blockHit.getBlockPos().asLong(); }
		else if (hit instanceof EntityHitResult entityHit) { newKind = 2; newValue = entityHit.getEntity().getId(); }
		boolean changed = newKind != kind || newValue != value;
		kind = newKind;
		value = newValue;
		if (newKind == 0) { red = false; return; }
		// For something new, use what we know about it if it's recent. Otherwise keep the colour until the server answers.
		// Moving from one red block to the next keeps it red, so it doesn't blink.
		if (changed) {
			long[] known = CACHE.get(key(newKind, newValue));
			if (known != null && ticks - known[1] < 40) red = known[0] == 1;
		}
		// Ask again now and then too, because a block becomes "a clone mines this" the moment it's mined, for example.
		if (changed || ++sinceAsked >= 10) {
			sinceAsked = 0;
			ClientPlayNetworking.send(new LoopNetworking.LookPayload(newKind, newValue));
		}
	}

	public static void onNoLoot(LoopNetworking.NoLootPayload p) {
		CACHE.put(key(p.kind(), p.value()), new long[] { p.nothing() ? 1 : 0, ticks });
		if (p.kind() == kind && p.value() == value) red = p.nothing();
	}

	public static void onLocked(LoopNetworking.LockedSlotsPayload p) {
		lockedMenu = p.containerId();
		java.util.Set<Integer> set = new java.util.HashSet<>();
		for (int s : p.slots()) set.add(s);
		locked = set;
	}
}
