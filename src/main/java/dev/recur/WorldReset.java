package dev.recur;

import net.minecraft.core.registries.Registries;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.server.permissions.LevelBasedPermissionSet;
import net.minecraft.world.level.GameType;
import net.minecraft.world.level.gamerules.GameRules;

import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Resets the whole world: every looping player waits in a far-away holding spot while every other chunk unloads
 * (their changes go to the overlay), then the overlay is dropped so everything reloads from the untouched files.
 */
public final class WorldReset {
	private static final int MIN_TICKS = 40;
	private static final int STABLE_TICKS = 60;
	private static final int MAX_TICKS = 30 * 20;

	private static boolean running;
	private static int lastCount = -1;
	private static int stableTicks;
	private static int lastBaseline;
	private static int waited;
	private static int savedRespawnRadius = 10;
	private static boolean savedSpectatorsGenerate = true;
	private static Runnable onComplete;
	private static Map<String, Long> clockSnapshot = Map.of();
	private static net.minecraft.nbt.CompoundTag weatherSnapshot = new net.minecraft.nbt.CompoundTag();

	private WorldReset() {}

	public static boolean isRunning() { return running; }

	/** The in-game clocks (time of day, etc.) to put back on every reset. */
	public static void setClockSnapshot(Map<String, Long> snapshot) { clockSnapshot = snapshot; }

	public static Map<String, Long> clockSnapshot() { return clockSnapshot; }

	public static void setWeatherSnapshot(net.minecraft.nbt.CompoundTag tag) { weatherSnapshot = tag; }
	public static net.minecraft.nbt.CompoundTag weatherSnapshot() { return weatherSnapshot; }

	public static Map<String, Long> captureClocks(MinecraftServer server) {
		Map<String, Long> out = new java.util.HashMap<>();
		var clocks = server.registryAccess().lookupOrThrow(Registries.WORLD_CLOCK);
		clocks.listElements().forEach(holder ->
			out.put(holder.key().identifier().toString(), server.clockManager().getInstance(holder).totalTicks()));
		return out;
	}

	/** Whether the last reset was done in place (the world's entities then come back through EntityRestore). */
	private static boolean lastInPlace;

	public static boolean lastWasInPlace() { return lastInPlace; }

	public static void begin(MinecraftServer server, List<ServerPlayer> players, Runnable done) {
		onComplete = done;
		waited = 0;

		// The smart reset puts back only the parts of chunks that changed, and nothing is unloaded or loaded again.
		if (ServerSettings.smartReset) {
			discardClones(server);
			for (ServerPlayer player : players) hold(player);
			if (TimedReset.restoreInPlace(server)) {
				lastInPlace = true;
				StateRebind.restoreWeather(server, weatherSnapshot);
				applyClocks(server, clockSnapshot);
				Runnable r = onComplete;
				onComplete = null;
				if (r != null) r.run();
				return;
			}
			Recur.LOGGER.info("Some changed chunks couldn't be put back in place: resetting the whole world instead");
		}
		lastInPlace = false;

		// Spawn chunks never unload, so they are switched off while a reset is in progress.
		savedRespawnRadius = server.overworld().getGameRules().get(GameRules.RESPAWN_RADIUS);
		command(server, "gamerule respawn_radius 0");

		// Spectators don't keep chunks loaded while spectators_generate_chunks is off, so everyone stays where they are as a
		// spectator and the whole world can unload around them.
		savedSpectatorsGenerate = server.overworld().getGameRules().get(GameRules.SPECTATORS_GENERATE_CHUNKS);
		command(server, "gamerule spectators_generate_chunks false");
		// Any clone left behind (e.g. one that finished playing) would keep its chunks loaded and stop them resetting.
		discardClones(server);
		for (ServerPlayer player : players) hold(player);
		lastCount = -1;
		stableTicks = 0;
		running = true;
	}

	private static void discardClones(MinecraftServer server) {
		for (ServerLevel l : server.getAllLevels()) {
			for (ServerPlayer p : new java.util.ArrayList<>(l.players())) {
				if (p instanceof com.mt1006.mocap.utils.FakePlayer) p.discard();
			}
		}
	}

	/** Puts a player into the reset's waiting state: loading screen, spectator, holding no chunks. */
	public static void hold(ServerPlayer player) {
		if (player.containerMenu != player.inventoryMenu) player.closeContainer(); // a chest left open across the reset
		LoopNetworking.sendReset(player, true);
		player.setGameMode(GameType.SPECTATOR);
		// A teleport in place makes the chunk map notice the player no longer holds chunks.
		player.teleportTo(player.level(), player.getX(), player.getY(), player.getZ(), Set.of(), player.getYRot(), player.getXRot(), true);
	}

	/** The server is stopping in the middle of a reset: put the gamerules back so they aren't saved changed. */
	public static void onServerStopping(MinecraftServer server) {
		if (!running) return;
		command(server, "gamerule respawn_radius " + savedRespawnRadius);
		command(server, "gamerule spectators_generate_chunks " + savedSpectatorsGenerate);
		running = false;
		onComplete = null;
	}

	public static void abort() {
		running = false;
		onComplete = null;
		lastCount = -1;
		stableTicks = 0;
		waited = 0;
		clockSnapshot = Map.of();
		weatherSnapshot = new net.minecraft.nbt.CompoundTag();
	}

	public static void tick(MinecraftServer server) {
		if (!running) return;
		waited++;

		// The chunks around the holding spot stay loaded, so instead of waiting for zero we wait until the number of
		// loaded chunks stops changing (everything else has unloaded).
		int count = 0, baseline = 0;
		for (ServerLevel l : server.getAllLevels()) {
			count += l.getChunkSource().getLoadedChunksCount();
			baseline += l.getForceLoadedChunks().size();
		}
		stableTicks = count == lastCount ? stableTicks + 1 : 0;
		lastCount = count;
		lastBaseline = baseline;
		// Done as soon as only force-loaded chunks remain; otherwise wait for the count to stop changing for 3 seconds.
		if (count <= baseline && waited >= MIN_TICKS) { finish(server); return; }
		// Always at least 2 seconds; give up waiting after 30.
		if ((stableTicks >= STABLE_TICKS && waited >= MIN_TICKS) || waited >= MAX_TICKS) finish(server);
	}

	private static void finish(MinecraftServer server) {
		running = false;
		if (waited >= MAX_TICKS) Recur.LOGGER.warn("Chunk unloading did not settle within {} ticks; some chunks may keep loop changes", MAX_TICKS);
		// Drain pending async chunk writes into the overlay first, otherwise a late write could survive the clear.
		server.saveEverything(true, true, true);
		LoopOverlay.clearAll();
		StateRebind.rebindAll(server);

		command(server, "gamerule respawn_radius " + savedRespawnRadius);
		command(server, "gamerule spectators_generate_chunks " + savedSpectatorsGenerate);
		StateRebind.restoreWeather(server, weatherSnapshot);
		applyClocks(server, clockSnapshot);

		Runnable r = onComplete;
		onComplete = null;
		if (r != null) r.run();
	}

	public static void applyClocks(MinecraftServer server, Map<String, Long> snapshot) {
		if (snapshot == null || snapshot.isEmpty()) return;
		var clocks = server.registryAccess().lookupOrThrow(Registries.WORLD_CLOCK);
		clocks.listElements().forEach(holder -> {
			Long ticks = snapshot.get(holder.key().identifier().toString());
			if (ticks != null) server.clockManager().setTotalTicks(holder, ticks);
		});
	}

	private static void command(MinecraftServer server, String cmd) {
		var source = server.createCommandSourceStack().withPermission(LevelBasedPermissionSet.OWNER).withSuppressedOutput();
		server.getCommands().performPrefixedCommand(source, cmd);
	}
}
