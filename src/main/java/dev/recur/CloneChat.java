package dev.recur;

import net.minecraft.ChatFormatting;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.nbt.Tag;
import net.minecraft.network.chat.Component;
import net.minecraft.server.MinecraftServer;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * Chat messages a player sends during a run are said again by their clone ("<Name #N> message") at the same moment.
 * To keep it from getting spammy each run keeps at most 10 messages, and at most 3 within any 5 seconds.
 */
public final class CloneChat {
	public static final int MAX_MESSAGES = 10;
	public static final int BURST = 3;
	public static final int BURST_WINDOW_TICKS = 5 * 20;

	public record Line(long time, String text) {}

	private record Scheduled(long time, String speaker, String text) {}

	private static final Map<UUID, List<Line>> RECORDING = new HashMap<>();
	private static final List<Scheduled> SCHEDULED = new ArrayList<>();

	private CloneChat() {}

	public static void startRecording(UUID player) { RECORDING.put(player, new ArrayList<>()); }

	public static List<Line> finish(UUID player) {
		List<Line> lines = RECORDING.remove(player);
		return lines != null ? lines : new ArrayList<>();
	}

	public static void discard(UUID player) { RECORDING.remove(player); }

	/** A player said something in chat: keep it if they still have messages left and aren't over 3 in 5 seconds. */
	public static void onChat(UUID player, String text, long now) {
		List<Line> lines = RECORDING.get(player);
		if (lines == null || text == null || text.isBlank() || lines.size() >= MAX_MESSAGES) return;
		int recent = 0;
		for (Line l : lines) if (now - l.time < BURST_WINDOW_TICKS) recent++;
		if (recent >= BURST) return;
		lines.add(new Line(now, text));
	}

	/** A clone starts playing: queue its messages that are still ahead on the timeline. */
	/** Drops the scheduled messages of a clone that died or was removed. */
	public static void unschedule(String speaker) { SCHEDULED.removeIf(s -> s.speaker.equals(speaker)); }

	public static void schedule(List<Line> lines, String speaker, long now) {
		for (Line l : lines) if (l.time >= now) SCHEDULED.add(new Scheduled(l.time, speaker, l.text));
	}

	public static void tick(MinecraftServer server, long now) {
		if (SCHEDULED.isEmpty()) return;
		SCHEDULED.removeIf(s -> {
			if (s.time > now) return false;
			Component msg = format(s.speaker, s.text);
			var payload = new LoopNetworking.CloneChatPayload(s.speaker, s.text);
			for (var p : server.getPlayerList().getPlayers()) {
				// Players with Recur get it as a payload so they can mute clone chat; anyone else gets plain chat.
				if (net.fabricmc.fabric.api.networking.v1.ServerPlayNetworking.canSend(p, LoopNetworking.CloneChatPayload.TYPE))
					net.fabricmc.fabric.api.networking.v1.ServerPlayNetworking.send(p, payload);
				else p.sendSystemMessage(msg);
			}
			server.sendSystemMessage(msg);
			return true;
		});
	}

	public static Component format(String speaker, String text) {
		return Component.literal("<").append(Component.literal(speaker).withStyle(ChatFormatting.GRAY)).append("> " + text);
	}

	public static void clearScheduled() { SCHEDULED.clear(); }

	public static void clearAll() { SCHEDULED.clear(); RECORDING.clear(); }

	public static ListTag toTag(List<Line> lines) {
		ListTag list = new ListTag();
		for (Line l : lines) {
			CompoundTag t = new CompoundTag();
			t.putLong("t", l.time); t.putString("m", l.text);
			list.add(t);
		}
		return list;
	}

	public static List<Line> fromTag(ListTag list) {
		List<Line> out = new ArrayList<>();
		for (Tag t : list) if (t instanceof CompoundTag c) out.add(new Line(c.getLongOr("t", 0), c.getStringOr("m", "")));
		return out;
	}
}
