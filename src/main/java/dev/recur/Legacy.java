package dev.recur;

import net.minecraft.server.MinecraftServer;
import net.minecraft.world.level.storage.LevelResource;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

/**
 * Recur used to be called Resync. Worlds, loops and settings saved under the old name are picked up as they are:
 * the world's "resync" folder is renamed, and data written with the old names is still read.
 */
public final class Legacy {
	/** The old mod id. */
	public static final String OLD_ID = "resync";

	private Legacy() {}

	/** Renames the world's old data folder (loop, timed resets, clone voices) before anything reads it. */
	public static void migrateWorld(MinecraftServer server) {
		Path root = server.getWorldPath(LevelResource.ROOT);
		Path oldDir = root.resolve(OLD_ID), newDir = root.resolve(Recur.MOD_ID);
		try {
			if (Files.isDirectory(oldDir) && !Files.exists(newDir)) {
				Files.move(oldDir, newDir);
				Recur.LOGGER.info("Moved this world's Resync data to the new Recur folder");
			}
			Path oldMarker = newDir.resolve("created_with_" + OLD_ID), newMarker = newDir.resolve("created_with_" + Recur.MOD_ID);
			if (Files.exists(oldMarker) && !Files.exists(newMarker)) Files.move(oldMarker, newMarker);
		} catch (IOException e) {
			Recur.LOGGER.warn("Couldn't move the old Resync data folder", e);
		}
	}

	/** Copies the old client settings file over if there's no new one yet. */
	public static void migrateFile(Path oldFile, Path newFile) {
		try {
			if (Files.exists(oldFile) && !Files.exists(newFile)) Files.copy(oldFile, newFile);
		} catch (IOException e) {
			Recur.LOGGER.warn("Couldn't copy {}", oldFile, e);
		}
	}

	/** The same key under the old name ("recur_x" -> "resync_x"). */
	public static String old(String key) {
		return key.replaceFirst("^" + Recur.MOD_ID, OLD_ID);
	}
}
