package dev.recur;

import dev.recur.mixin.ServerLevelAccessor;
import net.minecraft.core.BlockPos;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.ServerScoreboard;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.entity.raid.Raids;
import net.minecraft.world.level.dimension.end.EnderDragonFight;
import net.minecraft.world.level.saveddata.WeatherData;
import net.minecraft.world.level.storage.SavedDataStorage;
import net.minecraft.world.scores.Objective;
import net.minecraft.world.scores.PlayerTeam;
import net.minecraft.world.scores.ScoreboardSaveData;

import java.util.ArrayList;

/**
 * Some state, such as the scoreboard, raids, the dragon fight and the weather, lives in objects the server never re-reads from disk.
 * After a reset the saved-data cache is cleared, so the pristine copies are re-read from the untouched files and
 * pushed back into the live objects.
 */
public final class StateRebind {
	private StateRebind() {}

	public static void rebindAll(MinecraftServer server) {
		try { rebindScoreboard(server); } catch (Exception e) { Recur.LOGGER.error("Scoreboard reset failed", e); }
		for (ServerLevel level : server.getAllLevels()) {
			try { rebindRaids(level); } catch (Exception e) { Recur.LOGGER.error("Raid reset failed in {}", level.dimension().identifier(), e); }
			try { rebindDragonFight(level); } catch (Exception e) { Recur.LOGGER.error("Dragon fight reset failed", e); }
		}
	}

	private static void rebindScoreboard(MinecraftServer server) {
		SavedDataStorage storage = server.overworld().getDataStorage();
		ScoreboardSaveData pristine = storage.computeIfAbsent(ScoreboardSaveData.TYPE);
		ServerScoreboard board = server.getScoreboard();
		for (Objective o : new ArrayList<>(board.getObjectives())) board.removeObjective(o);
		for (PlayerTeam t : new ArrayList<>(board.getPlayerTeams())) board.removePlayerTeam(t);
		board.load(pristine.getData());
	}

	private static void rebindRaids(ServerLevel level) {
		Raids old = level.getRaids();
		Raids pristine = level.getDataStorage().computeIfAbsent(Raids.TYPE);
		// the old raids stop, which also takes their bars off everyone's screen
		if (old != pristine) for (var raid : ((dev.recur.mixin.RaidsAccessor) old).recur$raidMap().values()) raid.stop();
		((ServerLevelAccessor) level).recur$setRaids(pristine);
	}

	/** The raids going on in every dimension, which a checkpoint keeps (only dimensions with one are in it). */
	public static CompoundTag captureRaids(MinecraftServer server) {
		CompoundTag out = new CompoundTag();
		for (ServerLevel level : server.getAllLevels()) {
			Raids raids = level.getRaids();
			if (((dev.recur.mixin.RaidsAccessor) raids).recur$raidMap().isEmpty()) continue;
			Raids.CODEC.encodeStart(level.registryAccess().createSerializationContext(net.minecraft.nbt.NbtOps.INSTANCE), raids).result()
				.ifPresent(tag -> out.put(level.dimension().identifier().toString(), tag));
		}
		return out;
	}

	/** Puts a checkpoint's raids back: their bars come back, they count their raiders and spawn their next waves. */
	public static void restoreRaids(MinecraftServer server, CompoundTag saved) {
		if (saved == null || saved.isEmpty()) return;
		for (ServerLevel level : server.getAllLevels()) {
			var tag = saved.get(level.dimension().identifier().toString());
			if (tag == null) continue;
			try {
				Raids.CODEC.parse(level.registryAccess().createSerializationContext(net.minecraft.nbt.NbtOps.INSTANCE), tag).result().ifPresent(raids -> {
					Raids old = level.getRaids();
					for (var raid : ((dev.recur.mixin.RaidsAccessor) old).recur$raidMap().values()) raid.stop();
					((ServerLevelAccessor) level).recur$setRaids(raids);
				});
			} catch (Exception e) { Recur.LOGGER.error("Couldn't put the raids back in {}", level.dimension().identifier(), e); }
		}
	}

	private static void rebindDragonFight(ServerLevel level) {
		EnderDragonFight old = level.getDragonFight();
		if (old == null) return;
		EnderDragonFight fight = level.getDataStorage().computeIfAbsent(EnderDragonFight.TYPE);
		// the old fight's boss bar would otherwise stay on everyone's screen
		if (old != fight) ((dev.recur.mixin.EnderDragonFightAccessor) old).recur$dragonEvent().removeAllPlayers();
		fight.init(level, level.getSeed(), BlockPos.ZERO);
		level.setDragonFight(fight);
	}

	// ---- weather: snapshot at loop start, restored exactly on every reset

	public static CompoundTag captureWeather(MinecraftServer server) {
		WeatherData w = server.getWeatherData();
		CompoundTag t = new CompoundTag();
		t.putInt("clear", w.getClearWeatherTime());
		t.putInt("rainTime", w.getRainTime());
		t.putInt("thunderTime", w.getThunderTime());
		t.putBoolean("raining", w.isRaining());
		t.putBoolean("thundering", w.isThundering());
		return t;
	}

	public static void restoreWeather(MinecraftServer server, CompoundTag t) {
		if (t == null || t.isEmpty()) return;
		WeatherData w = server.getWeatherData();
		w.setClearWeatherTime(t.getIntOr("clear", 0));
		w.setRainTime(t.getIntOr("rainTime", 0));
		w.setThunderTime(t.getIntOr("thunderTime", 0));
		w.setRaining(t.getBooleanOr("raining", false));
		w.setThundering(t.getBooleanOr("thundering", false));
	}
}
