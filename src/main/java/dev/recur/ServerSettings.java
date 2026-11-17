package dev.recur;

import net.fabricmc.loader.api.FabricLoader;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.level.storage.LevelResource;

import java.io.IOException;
import java.io.Reader;
import java.io.Writer;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Properties;

/**
 * Per-world settings that apply to everyone, kept in recur/settings.properties (the timed reset ones are in timed.dat).
 * They're changed with /loop commands or the config screen. A new world starts from the defaults in
 * config/recur.properties, which the config screen also saves.
 */
public final class ServerSettings {
	/** Everything the config screen shows for a world. */
	public record Values(int loadPercent, boolean smartReset, int timedSeconds, boolean antiDupe, boolean clearBlocks, boolean cloneLoot,
						 boolean loopAntiDupe) {
		public static final Values BUILT_IN = new Values(50, true, TimedReset.DEFAULT_INTERVAL / 20, true, true, false, false);

		public Values clamped() {
			return new Values(Math.max(0, Math.min(100, loadPercent)), smartReset, Math.max(10, timedSeconds), antiDupe, clearBlocks, cloneLoot, loopAntiDupe);
		}
	}

	/** How much of the area around each player has to be loaded before a run starts (0-100%). */
	public static int loadPercent = 50;
	/**
	 * Reset by putting back only the parts of loaded chunks that changed, instead of unloading the whole world and
	 * loading it again. Falls back to the full reset whenever something can't be put back in place.
	 */
	public static boolean smartReset = true;
	/** Killing a clone drops everything it carried (never with the anti-dupe rules on). */
	public static boolean cloneLoot = false;
	/** The anti-dupe rules in normal loops too (timed resets have their own switch, see TimedReset). */
	public static boolean loopAntiDupe = false;

	private ServerSettings() {}

	private static Path file(MinecraftServer server) {
		return server.getWorldPath(LevelResource.ROOT).resolve(Recur.MOD_ID).resolve("settings.properties");
	}

	private static Path defaultsFile() {
		return FabricLoader.getInstance().getConfigDir().resolve("recur.properties");
	}

	public static Values defaults() {
		Values d = Values.BUILT_IN;
		Path f = defaultsFile();
		if (!Files.exists(f)) return d;
		Properties p = new Properties();
		try (Reader r = Files.newBufferedReader(f)) {
			p.load(r);
			return new Values(
				Integer.parseInt(p.getProperty("loadPercent", "" + d.loadPercent()).trim()),
				Boolean.parseBoolean(p.getProperty("smartReset", "" + d.smartReset()).trim()),
				Integer.parseInt(p.getProperty("timedSeconds", "" + d.timedSeconds()).trim()),
				Boolean.parseBoolean(p.getProperty("antiDupe", "" + d.antiDupe()).trim()),
				Boolean.parseBoolean(p.getProperty("clearBlocks", "" + d.clearBlocks()).trim()),
				Boolean.parseBoolean(p.getProperty("cloneLoot", "" + d.cloneLoot()).trim()),
				Boolean.parseBoolean(p.getProperty("loopAntiDupe", "" + d.loopAntiDupe()).trim())).clamped();
		} catch (IOException | NumberFormatException e) {
			Recur.LOGGER.warn("Couldn't read {}", f, e);
			return d;
		}
	}

	public static void saveDefaults(Values v) {
		Path f = defaultsFile();
		Properties p = new Properties();
		p.setProperty("loadPercent", Integer.toString(v.loadPercent()));
		p.setProperty("smartReset", Boolean.toString(v.smartReset()));
		p.setProperty("timedSeconds", Integer.toString(v.timedSeconds()));
		p.setProperty("antiDupe", Boolean.toString(v.antiDupe()));
		p.setProperty("clearBlocks", Boolean.toString(v.clearBlocks()));
		p.setProperty("cloneLoot", Boolean.toString(v.cloneLoot()));
		p.setProperty("loopAntiDupe", Boolean.toString(v.loopAntiDupe()));
		try {
			Files.createDirectories(f.getParent());
			try (Writer w = Files.newBufferedWriter(f)) { p.store(w, "Recur: settings new worlds start with"); }
		} catch (IOException e) {
			Recur.LOGGER.warn("Couldn't save {}", f, e);
		}
	}

	public static void load(MinecraftServer server) {
		Values d = defaults();
		loadPercent = d.loadPercent();
		smartReset = d.smartReset();
		cloneLoot = d.cloneLoot();
		loopAntiDupe = d.loopAntiDupe();
		Path f = file(server);
		if (!Files.exists(f)) return;
		Properties p = new Properties();
		try (Reader r = Files.newBufferedReader(f)) {
			p.load(r);
			loadPercent = Math.max(0, Math.min(100, Integer.parseInt(p.getProperty("loadPercent", "" + d.loadPercent()).trim())));
			smartReset = Boolean.parseBoolean(p.getProperty("smartReset", "" + d.smartReset()).trim());
			cloneLoot = Boolean.parseBoolean(p.getProperty("cloneLoot", "" + d.cloneLoot()).trim());
			loopAntiDupe = Boolean.parseBoolean(p.getProperty("loopAntiDupe", "" + d.loopAntiDupe()).trim());
		} catch (IOException | NumberFormatException e) {
			Recur.LOGGER.warn("Couldn't read {}", f, e);
		}
	}

	public static void save(MinecraftServer server) {
		Path f = file(server);
		Properties p = new Properties();
		p.setProperty("loadPercent", Integer.toString(loadPercent));
		p.setProperty("smartReset", Boolean.toString(smartReset));
		p.setProperty("cloneLoot", Boolean.toString(cloneLoot));
		p.setProperty("loopAntiDupe", Boolean.toString(loopAntiDupe));
		try {
			Files.createDirectories(f.getParent());
			try (Writer w = Files.newBufferedWriter(f)) { p.store(w, "Recur world settings"); }
		} catch (IOException e) {
			Recur.LOGGER.warn("Couldn't save {}", f, e);
		}
		syncAll(server);
	}

	public static Values current() {
		return new Values(loadPercent, smartReset, TimedReset.intervalTicks() / 20, TimedReset.antiDupeSetting(), TimedReset.clearAtPlayers(), cloneLoot, loopAntiDupe);
	}

	/** Someone changed the settings in the config screen. */
	public static void change(ServerPlayer player, Values v) {
		MinecraftServer server = player.level().getServer();
		if (!canEdit(player)) return;
		v = v.clamped();
		loadPercent = v.loadPercent();
		smartReset = v.smartReset();
		cloneLoot = v.cloneLoot();
		loopAntiDupe = v.loopAntiDupe();
		if (v.timedSeconds() * 20 != TimedReset.intervalTicks()) TimedReset.setInterval(server, v.timedSeconds() * 20);
		if (v.antiDupe() != TimedReset.antiDupeSetting()) TimedReset.setAntiDupe(server, v.antiDupe());
		if (v.clearBlocks() != TimedReset.clearAtPlayers()) TimedReset.setClearAtPlayers(server, v.clearBlocks());
		save(server);
	}

	public static boolean canEdit(ServerPlayer player) {
		return player.level().getServer().isSingleplayerOwner(player.nameAndId())
			|| net.minecraft.commands.Commands.hasPermission(net.minecraft.commands.Commands.LEVEL_GAMEMASTERS).test(player.createCommandSourceStack());
	}

	public static void sync(ServerPlayer player) {
		LoopNetworking.sendSettings(player, new LoopNetworking.SettingsPayload(current(), canEdit(player)));
	}

	public static void syncAll(MinecraftServer server) {
		for (ServerPlayer p : server.getPlayerList().getPlayers()) sync(p);
	}
}
