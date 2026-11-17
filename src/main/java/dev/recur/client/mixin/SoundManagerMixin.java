package dev.recur.client.mixin;

import dev.recur.client.ClientSettings;
import dev.recur.client.CloneSounds;
import net.minecraft.client.resources.sounds.AbstractSoundInstance;
import net.minecraft.client.resources.sounds.SoundInstance;
import net.minecraft.client.sounds.SoundManager;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/** Turns a clone's sounds down to the clone action volume while CloneSounds.scaling is on. */
@Mixin(SoundManager.class)
public abstract class SoundManagerMixin {
	@Inject(method = "play", at = @At("HEAD"))
	private void recur$scalePlay(SoundInstance instance, CallbackInfoReturnable<?> cir) {
		recur$scale(instance);
	}

	@Inject(method = "playDelayed", at = @At("HEAD"))
	private void recur$scaleDelayed(SoundInstance instance, int delay, CallbackInfo ci) {
		recur$scale(instance);
	}

	@Unique
	private static void recur$scale(SoundInstance instance) {
		if (!CloneSounds.scaling || !(instance instanceof AbstractSoundInstance sound)) return;
		AbstractSoundInstanceAccessor accessor = (AbstractSoundInstanceAccessor) sound;
		accessor.recur$setVolume(accessor.recur$getVolume() * ClientSettings.actionVolume);
	}
}
