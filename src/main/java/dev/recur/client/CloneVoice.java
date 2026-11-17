package dev.recur.client;

import net.minecraft.client.Minecraft;
import net.minecraft.world.entity.Entity;

import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Applies the clone voice volume on this client. When enough clones talk over each other (ClientSettings.voiceCrowd),
 * each one is also scaled by the voice curve, by how recent it is among the clones talking right then.
 * Voice chat decodes audio on its own thread, so the clone UUIDs and ages are copied into thread-safe maps.
 */
public final class CloneVoice {
	private static final Set<UUID> CLONE_UUIDS = ConcurrentHashMap.newKeySet();
	/** Clone UUID -> the order its clone was made in. */
	private static final java.util.Map<UUID, Integer> AGES = new ConcurrentHashMap<>();
	/** Clone UUID -> when its voice was last heard (System.nanoTime). */
	private static final java.util.Map<UUID, Long> LAST_HEARD = new ConcurrentHashMap<>();
	/** A clone counts as talking for this long after its last bit of voice. */
	private static final long TALKING_NANOS = 300_000_000L;

	private CloneVoice() {}

	private static final boolean VOICE_CHAT = net.fabricmc.loader.api.FabricLoader.getInstance().isModLoaded("voicechat");
	private static int ticks;

	public static void tick(Minecraft mc) {
		if (!VOICE_CHAT) return;
		if (mc.level == null || ClientLoopState.cloneIds().isEmpty()) { CLONE_UUIDS.clear(); AGES.clear(); LAST_HEARD.clear(); return; }
		if (++ticks % 10 != 0) return; // clones don't change often: twice a second is plenty
		Set<UUID> now = new java.util.HashSet<>();
		for (int id : ClientLoopState.cloneIds()) {
			Entity e = mc.level.getEntity(id);
			if (e == null) continue;
			now.add(e.getUUID());
			AGES.put(e.getUUID(), ClientLoopState.cloneAge(id));
		}
		CLONE_UUIDS.retainAll(now);
		CLONE_UUIDS.addAll(now);
		AGES.keySet().retainAll(now);
		LAST_HEARD.keySet().retainAll(now);
	}

	/** Scales a clone's decoded voice (returns null when the sound isn't from a clone or needs no change). */
	public static short[] adjust(UUID entity, short[] audio) {
		if (audio == null || CLONE_UUIDS.isEmpty() || !CLONE_UUIDS.contains(entity)) return null;
		long time = System.nanoTime();
		LAST_HEARD.put(entity, time);
		float v = ClientSettings.effectiveVoice() * crowdVolume(entity, time);
		if (v >= 0.999f) return null;
		short[] out = new short[audio.length];
		if (v <= 0.001f) return out;
		for (int i = 0; i < audio.length; i++) out[i] = (short) Math.round(audio[i] * v);
		return out;
	}

	/** 1, or the voice curve's volume for this clone when enough clones are talking at once. */
	private static float crowdVolume(UUID entity, long time) {
		java.util.List<UUID> talking = new java.util.ArrayList<>();
		for (var e : LAST_HEARD.entrySet()) if (time - e.getValue() <= TALKING_NANOS) talking.add(e.getKey());
		if (talking.size() < Math.max(2, ClientSettings.voiceCrowd)) return 1f;
		// Oldest first. Clones of the same age (unknown) keep a stable order by UUID.
		talking.sort(java.util.Comparator.<UUID>comparingInt(id -> AGES.getOrDefault(id, -1)).thenComparing(id -> id));
		float recency = talking.indexOf(entity) / (float) (talking.size() - 1);
		return ClientSettings.voiceCurve.at(recency);
	}
}
