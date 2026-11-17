package dev.recur.voice;

import de.maxhenkel.voicechat.api.VoicechatApi;
import de.maxhenkel.voicechat.api.VoicechatPlugin;
import de.maxhenkel.voicechat.api.VoicechatServerApi;
import de.maxhenkel.voicechat.api.VolumeCategory;
import de.maxhenkel.voicechat.api.audiochannel.EntityAudioChannel;
import de.maxhenkel.voicechat.api.events.EventRegistration;
import de.maxhenkel.voicechat.api.events.MicrophonePacketEvent;
import de.maxhenkel.voicechat.api.events.VoicechatServerStartedEvent;
import de.maxhenkel.voicechat.api.events.VoicechatServerStoppedEvent;
import dev.recur.Recur;
import dev.recur.VoiceBridge;
import net.minecraft.world.entity.Entity;

import java.util.List;
import java.util.UUID;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;

/**
 * Simple Voice Chat plugin (loaded by voice chat through the "voicechat" entrypoint, so only when it is installed).
 * Records every player's voice packets, and plays clones' recorded voices from the clone, every 20 ms in time.
 */
public class RecurVoicePlugin implements VoicechatPlugin, VoiceBridge.Backend {
	private static int voiceFailures;

	private static final String CATEGORY = "recur_clones";
	/** Voice older than this (in ticks) when it's due is skipped rather than played late. */
	private static final double STALE_TICKS = 4;

	private volatile VoicechatServerApi api;
	private final List<Stream> streams = new CopyOnWriteArrayList<>();
	private ScheduledExecutorService timer;
	private ScheduledFuture<?> task;

	private static final class Stream {
		final List<VoiceBridge.Frame> frames;
		final EntityAudioChannel channel;
		final Entity clone;
		int next;

		Stream(List<VoiceBridge.Frame> frames, EntityAudioChannel channel, Entity clone, double now) {
			this.frames = frames; this.channel = channel; this.clone = clone;
			while (next < frames.size() && frames.get(next).time() < now - STALE_TICKS) next++; // skip what's already past
		}
	}

	@Override public String getPluginId() { return Recur.MOD_ID; }

	@Override public void initialize(VoicechatApi api) { VoiceBridge.backend = this; }

	@Override
	public void registerEvents(EventRegistration registration) {
		registration.registerEvent(VoicechatServerStartedEvent.class, e -> {
			api = e.getVoicechat();
			VolumeCategory category = api.volumeCategoryBuilder().setId(CATEGORY).setName("Clones")
				.setDescription("Voices of your past selves (Recur)").build();
			api.registerVolumeCategory(category);
			timer = Executors.newSingleThreadScheduledExecutor(r -> {
				Thread t = new Thread(r, "Recur clone voices");
				t.setDaemon(true);
				return t;
			});
			task = timer.scheduleAtFixedRate(this::pump, 20, 20, TimeUnit.MILLISECONDS);
		});
		registration.registerEvent(VoicechatServerStoppedEvent.class, e -> {
			stopAll();
			if (task != null) task.cancel(false);
			if (timer != null) timer.shutdownNow();
			api = null;
		});
		// On the client, this applies the clone voice volume and mute from the pause menu. It never runs on a dedicated server.
		if (net.fabricmc.loader.api.FabricLoader.getInstance().getEnvironmentType() == net.fabricmc.api.EnvType.CLIENT) {
			registration.registerEvent(de.maxhenkel.voicechat.api.events.ClientReceiveSoundEvent.EntitySound.class, e -> {
				short[] out = dev.recur.client.CloneVoice.adjust(e.getEntityId(), e.getRawAudio());
				if (out != null) e.setRawAudio(out);
			});
		}
		registration.registerEvent(MicrophonePacketEvent.class, e -> {
			if (e.getSenderConnection() == null) return;
			UUID player = e.getSenderConnection().getPlayer().getUuid();
			VoiceBridge.onMicrophone(player, e.getPacket().getOpusEncodedData(), e.getPacket().isWhispering());
		});
	}

	@Override
	public void play(List<VoiceBridge.Frame> frames, Entity clone) {
		VoicechatServerApi a = api;
		if (a == null) return;
		EntityAudioChannel channel = a.createEntityAudioChannel(UUID.randomUUID(), a.fromEntity(clone));
		if (channel == null) return;
		channel.setCategory(CATEGORY);
		streams.add(new Stream(frames, channel, clone, VoiceBridge.now()));
	}

	@Override
	public void stopAll() {
		for (Stream s : streams) s.channel.flush();
		streams.clear();
	}

	/** Every 20 ms: send each clone's voice packets that are due now. */
	private void pump() {
		try {
			if (streams.isEmpty() || !VoiceBridge.isRunning()) return;
			double now = VoiceBridge.now();
			for (Stream s : streams) {
				if (s.clone.isRemoved() || s.next >= s.frames.size()) {
					s.channel.flush();
					streams.remove(s);
					continue;
				}
				while (s.next < s.frames.size() && s.frames.get(s.next).time() <= now) {
					VoiceBridge.Frame f = s.frames.get(s.next++);
					if (now - f.time() > STALE_TICKS) continue;
					s.channel.setWhispering(f.whisper());
					s.channel.send(f.opus());
				}
			}
		} catch (Throwable t) {
			if (voiceFailures++ < 3) Recur.LOGGER.warn("Clone voice playback failed", t); // once is enough to see why
		}
	}
}
