package dev.recur;

import net.minecraft.server.MinecraftServer;
import net.minecraft.world.level.storage.LevelResource;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HashSet;
import java.util.Set;
import java.util.UUID;

/**
 * Recur only runs in worlds that were created with it installed. A brand-new world (game time 0 when the server
 * starts) gets a marker file. In any other world (and inside a Flashback replay) Recur simply stays switched off:
 * players can still join and play normally.
 */
public final class WorldGuard {
	private static boolean allowed;

	private WorldGuard() {}

	public static boolean allowed() { return allowed; }

	/** Flashback plays replays back on its own little server: the loop must never run there. */
	public static boolean isReplayServer(MinecraftServer server) {
		for (Class<?> c = server.getClass(); c != null; c = c.getSuperclass()) {
			if (c.getName().startsWith("com.moulberry.flashback.")) return true;
		}
		return false;
	}

	public static void check(MinecraftServer server) {
		if (isReplayServer(server)) {
			allowed = false;
			Recur.LOGGER.info("Flashback replay: Recur is switched off");
			return;
		}
		Path dir = server.getWorldPath(LevelResource.ROOT).resolve("recur");
		Path marker = dir.resolve("created_with_recur");
		long gameTime = server.overworld().getLevelData().getGameTime();
		// Worlds that already used Recur (they have its folder) count as created with it.
		if (Files.exists(marker) || gameTime == 0 || Files.isDirectory(dir)) {
			allowed = true;
			try {
				Files.createDirectories(dir);
				if (!Files.exists(marker)) Files.writeString(marker, "This world was created with Recur.\n");
			} catch (IOException e) {
				Recur.LOGGER.error("Could not write the Recur marker", e);
			}
		} else {
			allowed = false;
			Recur.LOGGER.info("This world was not created with Recur (game time {}): Recur is switched off here", gameTime);
		}
	}

	public static void clear() { allowed = false; }
}
