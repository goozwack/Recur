package dev.recur;

import com.mt1006.mocap.utils.FakePlayer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.entity.EntityReference;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.Mob;
import net.minecraft.world.entity.NeutralMob;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.phys.AABB;

import java.util.UUID;

/**
 * A neutral mob (iron golem, enderman, wolf, piglin...) that a clone angered goes after that clone, never the player it
 * was recorded from: in the recording, what it was angry at was that player.
 */
public final class CloneAnger {
	private static final double NEAR = 48;

	private CloneAnger() {}

	/** Its recorded state was just put on it: anger at the recorded player goes to their clone nearby (or nowhere). */
	public static void redirect(Mob mob) {
		if (!(mob instanceof NeutralMob n) || !(mob.level() instanceof ServerLevel level)) return;
		EntityReference<LivingEntity> ref = n.getPersistentAngerTarget();
		UUID at = ref != null ? ref.getUUID() : null;
		if (at == null && mob.getTarget() instanceof Player p && !(p instanceof FakePlayer)) at = p.getUUID();
		if (at == null || level.getPlayerByUUID(at) instanceof FakePlayer) return; // angry at a clone already, or nothing
		FakePlayer clone = cloneOf(level, mob, at);
		if (clone == null) {
			n.stopBeingAngry();
			if (mob.getTarget() instanceof Player p && !(p instanceof FakePlayer)) mob.setTarget(null);
			return;
		}
		n.setPersistentAngerTarget(EntityReference.of(clone));
		mob.setTarget(clone);
	}

	/** The nearest clone of this player around the mob. */
	private static FakePlayer cloneOf(ServerLevel level, Mob mob, UUID player) {
		FakePlayer best = null;
		double bestDist = NEAR * NEAR;
		for (var p : level.getEntitiesOfClass(FakePlayer.class, new AABB(mob.blockPosition()).inflate(NEAR), c -> !c.isRemoved() && c.isAlive())) {
			if (!player.equals(LoopManager.ownerOf(p))) continue;
			double d = p.distanceToSqr(mob);
			if (d < bestDist) { bestDist = d; best = p; }
		}
		return best;
	}

	/** Clones are ignored by mobs, except by a neutral mob one of them angered. */
	public static boolean mayAttack(LivingEntity attacker, LivingEntity target) {
		return target instanceof FakePlayer && attacker instanceof NeutralMob n && n.getPersistentAngerTarget() != null
			&& n.getPersistentAngerTarget().getUUID().equals(target.getUUID());
	}
}
