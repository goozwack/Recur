package dev.recur.client;

import dev.recur.LoopNetworking;
import net.minecraft.client.Minecraft;
import net.minecraft.client.resources.sounds.SimpleSoundInstance;
import net.minecraft.core.BlockPos;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.resources.Identifier;
import net.minecraft.sounds.SoundEvent;
import net.minecraft.sounds.SoundSource;
import net.minecraft.util.RandomSource;

/**
 * Sounds clones make (everything their player did, and getting hurt) play at the clone action volume from the pause
 * menu. While {@link #scaling} is on, every sound the game starts is turned down by that much (see SoundManagerMixin).
 */
public final class CloneSounds {
	private CloneSounds() {}

	/** True while a clone's sound or effect is being started. */
	public static boolean scaling;

	/** A sound or level event a clone makes, sent by the server. */
	public static void onEffect(LoopNetworking.CloneEffectPayload p) {
		Minecraft mc = Minecraft.getInstance();
		if (mc.level == null) return;
		float factor = ClientSettings.actionVolume;
		if (p.levelEvent()) {
			// A level event plays its own sound and particles. The particles always show, and the sound is turned down as
			// it starts.
			scaling = true;
			try { mc.level.levelEvent(null, p.eventType(), BlockPos.containing(p.x(), p.y(), p.z()), p.data()); }
			finally { scaling = false; }
			return;
		}
		if (factor <= 0.001f) return;
		Identifier id = Identifier.tryParse(p.sound());
		if (id == null) return;
		SoundEvent sound = BuiltInRegistries.SOUND_EVENT.getOptional(id).orElseGet(() -> SoundEvent.createVariableRangeEvent(id));
		SoundSource[] sources = SoundSource.values();
		SoundSource source = p.source() >= 0 && p.source() < sources.length ? sources[p.source()] : SoundSource.PLAYERS;
		mc.getSoundManager().play(new SimpleSoundInstance(sound, source, p.volume() * factor, p.pitch(), RandomSource.create(p.seed()),
			p.x(), p.y(), p.z()));
	}
}
