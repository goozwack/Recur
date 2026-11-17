package dev.recur;

import net.minecraft.network.protocol.game.ClientboundEntityPositionSyncPacket;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.phys.Vec3;

/**
 * A clone moving fast (elytra, an ice boat, a long fall). Normally the game tells players where another player is
 * every other tick, and a clone's replay adds an update of its own for players close by. That's fine at walking
 * speed, but at speed the two don't line up and the clone stutters. So a fast clone's exact spot and speed go to
 * everyone who can see it every tick, and the game's own (older) updates are left out.
 */
public final class CloneMotion {
	/** Blocks per tick: a bit faster than sprinting. */
	private static final double FAST = 0.35;

	private CloneMotion() {}

	public static boolean isFast(double dx, double dy, double dz) {
		return dx * dx + dy * dy + dz * dz > FAST * FAST;
	}

	public static void sendFast(ServerLevel level, Entity clone, Vec3 step, boolean onGround) {
		clone.setDeltaMovement(step); // players' games draw the elytra, a boat and falling from it
		// The kind of update that players' games also measure the game's later (smaller) updates from, so after this
		// both sides agree on where it is, and the game's own update, a step behind, is left out.
		level.getChunkSource().sendToTrackingPlayers(clone, ClientboundEntityPositionSyncPacket.of(clone));
		Object tracked = ((dev.recur.mixin.ChunkMapAccessor) level.getChunkSource().chunkMap).recur$entityMap().get(clone.getId());
		if (tracked != null)
			((dev.recur.mixin.ServerEntityAccessor) ((dev.recur.mixin.TrackedEntityAccessor) tracked).recur$serverEntity())
				.recur$positionCodec().setBase(clone.position());
	}
}
