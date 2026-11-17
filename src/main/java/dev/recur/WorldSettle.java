package dev.recur;

import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.material.FluidState;

/**
 * Starting at a checkpoint, the world is rebuilt from the loop start by replaying everything players did before it. But
 * what those changes set off over time, like water turning lava into obsidian or sand falling, only
 * happened in scheduled block and fluid ticks afterwards. This runs those ticks right away, while everyone is still on
 * the loading screen, until nothing is flowing or changing any more, so the run begins with the world already settled.
 * Only block and fluid ticks run, so mobs, items, the time of day and clones stay as they are.
 */
public final class WorldSettle {
	/** At most this many ticks' worth (a minute of game time); usually it settles much sooner. */
	private static final int MAX_PASSES = 1200;
	/** Stop once this many passes in a row changed nothing (lava only flows every 30 ticks). */
	private static final int IDLE_PASSES = 40;

	private WorldSettle() {}

	public static void run(MinecraftServer server) {
		ReplayScope.depth++; // what this knocks loose was already in the original run (and its clones' recordings)
		try {
			for (ServerLevel level : server.getAllLevels()) settle(level);
		} catch (Exception e) {
			Recur.LOGGER.warn("Could not settle the world after loading a checkpoint", e);
		} finally {
			ReplayScope.depth--;
		}
	}

	private static void settle(ServerLevel level) {
		long now = level.getGameTime();
		int idle = 0;
		int[] ran = new int[1];
		for (int pass = 1; pass <= MAX_PASSES && idle < IDLE_PASSES; pass++) {
			ran[0] = 0;
			// Ticks scheduled during this are due at "real time + delay", which is always <= now + pass once the pass count
			// passes the delay: so each pass runs the next step of everything that's flowing or updating.
			level.getFluidTicks().tick(now + pass, 65536, (pos, fluid) -> {
				FluidState state = level.getFluidState(pos);
				if (state.is(fluid)) { state.tick(level, pos, level.getBlockState(pos)); ran[0]++; }
			});
			level.getBlockTicks().tick(now + pass, 65536, (pos, block) -> {
				BlockState state = level.getBlockState(pos);
				if (state.is(block)) { state.tick(level, pos, level.getRandom()); ran[0]++; }
			});
			idle = ran[0] == 0 ? idle + 1 : 0;
		}
	}
}
