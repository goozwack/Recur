package dev.recur;

import net.minecraft.server.MinecraftServer;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.level.storage.LevelResource;

import java.io.BufferedInputStream;
import java.io.BufferedOutputStream;
import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * Simple Voice Chat support: what a player says during a run is recorded (the already-encoded voice packets, stamped
 * with their moment on the shared timeline) and their clone says it again, from where the clone is, at the same
 * moment in later runs. Without Simple Voice Chat installed, it does nothing.
 * This class never touches the voice chat API itself; {@link #backend} is set by the voice chat plugin if it loads.
 */
public final class VoiceBridge {
	/** One voice packet: when it was said (timeline ticks, with fractions), whether it was whispered, the audio. */
	public record Frame(double time, boolean whisper, byte[] opus) {}

	/** Implemented by the voice chat plugin (only loaded when Simple Voice Chat is installed). */
	public interface Backend {
		void play(List<Frame> frames, Entity clone);
		void stopAll();
	}

	public static volatile Backend backend;

	/** Timeline position of the current server tick, and when that tick started (for sub-tick precision). */
	private static volatile long timeline;
	private static volatile long tickNanos;
	private static volatile boolean running;

	private static final Map<UUID, List<Frame>> RECORDING = new HashMap<>();

	private VoiceBridge() {}

	public static boolean available() { return backend != null; }

	public static void onTick(long timelineTick, boolean isRunning) {
		timeline = timelineTick;
		tickNanos = System.nanoTime();
		running = isRunning;
	}

	/** Now on the timeline, in ticks, including how far into the current tick we are. */
	public static double now() {
		double frac = Math.min(1.0, (System.nanoTime() - tickNanos) / 50_000_000.0);
		return timeline + frac;
	}

	public static boolean isRunning() { return running; }

	public static void startRecording(UUID player) {
		synchronized (RECORDING) { RECORDING.put(player, new ArrayList<>()); }
	}

	/** Called from the voice chat thread for every voice packet a player sends. */
	public static void onMicrophone(UUID player, byte[] opus, boolean whisper) {
		if (!running || opus == null || opus.length == 0) return;
		synchronized (RECORDING) {
			List<Frame> list = RECORDING.get(player);
			if (list != null) list.add(new Frame(now(), whisper, opus.clone()));
		}
	}

	/** Ends the player's voice recording and writes it next to their clone's recording. */
	public static void finish(MinecraftServer server, UUID player, String recordingName) {
		List<Frame> frames;
		synchronized (RECORDING) { frames = RECORDING.remove(player); }
		if (frames == null || frames.isEmpty()) return;
		Path file = file(server, recordingName);
		try {
			Files.createDirectories(file.getParent());
			try (DataOutputStream out = new DataOutputStream(new BufferedOutputStream(Files.newOutputStream(file)))) {
				out.writeInt(1); // format version
				out.writeInt(frames.size());
				for (Frame f : frames) {
					out.writeDouble(f.time);
					out.writeBoolean(f.whisper);
					out.writeShort(f.opus.length);
					out.write(f.opus);
				}
			}
		} catch (IOException e) {
			Recur.LOGGER.warn("Could not save voice for {}", recordingName, e);
		}
	}

	public static void discard(UUID player) {
		synchronized (RECORDING) { RECORDING.remove(player); }
	}

	private static final Map<String, List<Frame>> CACHE = new HashMap<>();

	/** Starts the clone of this recording talking (no-op if it never spoke or voice chat isn't installed). */
	public static void play(MinecraftServer server, String recordingName, Entity clone) {
		Backend b = backend;
		if (b == null || clone == null) return;
		List<Frame> frames = CACHE.computeIfAbsent(recordingName, n -> load(server, n));
		if (!frames.isEmpty()) b.play(frames, clone);
	}

	public static void stopPlayback() {
		Backend b = backend;
		if (b != null) b.stopAll();
	}

	public static void clear() {
		stopPlayback();
		synchronized (RECORDING) { RECORDING.clear(); }
		CACHE.clear();
		running = false;
	}

	private static Path file(MinecraftServer server, String recordingName) {
		return server.getWorldPath(LevelResource.ROOT).resolve("recur").resolve("voice").resolve(recordingName + ".voice");
	}

	private static List<Frame> load(MinecraftServer server, String recordingName) {
		Path file = file(server, recordingName);
		List<Frame> frames = new ArrayList<>();
		if (!Files.exists(file)) return frames;
		try (DataInputStream in = new DataInputStream(new BufferedInputStream(Files.newInputStream(file)))) {
			if (in.readInt() != 1) return frames;
			int n = in.readInt();
			for (int i = 0; i < n; i++) {
				double t = in.readDouble();
				boolean w = in.readBoolean();
				byte[] data = new byte[in.readUnsignedShort()];
				in.readFully(data);
				frames.add(new Frame(t, w, data));
			}
		} catch (IOException e) {
			Recur.LOGGER.warn("Could not load voice for {}", recordingName, e);
		}
		return frames;
	}
}
