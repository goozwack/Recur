package dev.recur;

import com.mt1006.mocap.utils.FakePlayer;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.server.level.TicketType;

/**
 * Keeps a 5x5-chunk area around each clone loaded so that what it does, such as breaking blocks or dropping items,
 * still happens normally. A real player would load their whole view distance. This uses the same short ticket a
 * flying ender pearl does, renewed every second.
 */
public final class CloneTickets {
	private static final int RADIUS = 2, EVERY = 20;
	private static int ticks;

	private CloneTickets() {}

	public static void tick(MinecraftServer server) {
		if (++ticks % EVERY != 0) return;
		for (ServerLevel level : server.getAllLevels()) {
			for (ServerPlayer p : level.players()) {
				if (p instanceof FakePlayer && !p.isRemoved())
					level.getChunkSource().addTicketWithRadius(TicketType.ENDER_PEARL, p.chunkPosition(), RADIUS);
			}
		}
	}
}
