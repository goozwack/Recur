package dev.recur.client;

import dev.recur.Recur;
import net.fabricmc.loader.api.FabricLoader;

import java.io.Reader;
import java.io.Writer;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Properties;

/** Per-player Recur preferences, kept in config/recur-client.properties. */
public final class ClientSettings {
	/** Clone voice volume, 0..1. */
	public static volatile float voiceVolume = 1f;
	public static volatile boolean voiceMuted;
	/** Clone chat starts muted. */
	public static boolean chatMuted = true;
	/** How many clones have to talk over each other before the voice curve is used. */
	public static volatile int voiceCrowd = 5;
	/** Clone voice volume by recency within a crowd of talking clones (replaced, never edited, so the voice thread can read it). */
	public static volatile VoiceCurve voiceCurve = VoiceCurve.flat();
	/** Volume of the sounds clones make by what they do (footsteps, blocks, splashing, getting hurt...), 0..1. */
	public static volatile float actionVolume = 1f;
	/** Size of the pause-menu bar (dragged with its grip). */
	public static float barScale = 1f;
	/** How long a loop lasts, in minutes (NO_LIMIT = only when someone dies). */
	public static int loopMinutes = 20;
	/** Clones fade in when a run starts (off: they're just there). */
	public static boolean cloneFadeIn = true;
	/** World settings changed in a world are also what new worlds start with. */
	public static boolean rememberSettings = true;
	public static final int NO_LIMIT = -1;
	/** Accounts (profile UUIDs) on this game that have already been shown the tutorial. */
	private static final java.util.Set<String> TUTORIAL_SEEN = new java.util.LinkedHashSet<>();

	private static final Path FILE = FabricLoader.getInstance().getConfigDir().resolve("recur-client.properties");

	private ClientSettings() {}

	private static String account() {
		return net.minecraft.client.Minecraft.getInstance().getUser().getProfileId().toString();
	}

	/** Has the account playing right now seen the tutorial (in any world, on any server)? */
	public static boolean tutorialSeen() { return TUTORIAL_SEEN.contains(account()); }

	/** The tutorial is being shown to this account: never again. */
	public static void markTutorialSeen() {
		if (TUTORIAL_SEEN.add(account())) save();
	}

	/** What clone voices are actually multiplied by. */
	public static float effectiveVoice() { return voiceMuted ? 0f : voiceVolume; }

	public static void load() {
		dev.recur.Legacy.migrateFile(FILE.resolveSibling(dev.recur.Legacy.OLD_ID + "-client.properties"), FILE); // Older versions were called Resync and used that name for the file.
		if (!Files.exists(FILE)) return;
		Properties p = new Properties();
		try (Reader r = Files.newBufferedReader(FILE)) {
			p.load(r);
			voiceVolume = Math.max(0f, Math.min(1f, Float.parseFloat(p.getProperty("voiceVolume", "1"))));
			voiceMuted = Boolean.parseBoolean(p.getProperty("voiceMuted", "false"));
			barScale = Math.max(0.5f, Math.min(3f, Float.parseFloat(p.getProperty("barScale", "1"))));
			loopMinutes = Integer.parseInt(p.getProperty("loopMinutes", "20").trim());
			if (loopMinutes != NO_LIMIT) loopMinutes = Math.max(2, Math.min(60, loopMinutes));
			chatMuted = Boolean.parseBoolean(p.getProperty("chatMuted", "true"));
			voiceCrowd = Math.max(2, Math.min(16, Integer.parseInt(p.getProperty("voiceCrowd", "5").trim())));
			voiceCurve = VoiceCurve.parse(p.getProperty("voiceCurve", ""));
			actionVolume = Math.max(0f, Math.min(1f, Float.parseFloat(p.getProperty("actionVolume", "1"))));
			cloneFadeIn = Boolean.parseBoolean(p.getProperty("cloneFadeIn", "true"));
			rememberSettings = Boolean.parseBoolean(p.getProperty("rememberSettings", "true"));
			TUTORIAL_SEEN.clear();
			for (String id : p.getProperty("tutorialSeen", "").split(",")) if (!id.isBlank()) TUTORIAL_SEEN.add(id.strip());
			try { ClientLoopState.cloneView = ClientLoopState.CloneView.valueOf(p.getProperty("cloneView", "SHOWN")); }
			catch (IllegalArgumentException ignored) {}
		} catch (Exception e) {
			Recur.LOGGER.warn("Couldn't read {}", FILE, e);
		}
	}

	public static void save() {
		Properties p = new Properties();
		p.setProperty("voiceVolume", Float.toString(voiceVolume));
		p.setProperty("voiceMuted", Boolean.toString(voiceMuted));
		p.setProperty("barScale", Float.toString(barScale));
		p.setProperty("loopMinutes", Integer.toString(loopMinutes));
		p.setProperty("chatMuted", Boolean.toString(chatMuted));
		p.setProperty("voiceCrowd", Integer.toString(voiceCrowd));
		p.setProperty("voiceCurve", voiceCurve.serialise());
		p.setProperty("actionVolume", Float.toString(actionVolume));
		p.setProperty("cloneFadeIn", Boolean.toString(cloneFadeIn));
		p.setProperty("rememberSettings", Boolean.toString(rememberSettings));
		p.setProperty("cloneView", ClientLoopState.cloneView.name());
		p.setProperty("tutorialSeen", String.join(",", TUTORIAL_SEEN));
		try {
			Files.createDirectories(FILE.getParent());
			try (Writer w = Files.newBufferedWriter(FILE)) { p.store(w, "Recur client settings"); }
		} catch (Exception e) {
			Recur.LOGGER.warn("Couldn't save {}", FILE, e);
		}
	}
}
