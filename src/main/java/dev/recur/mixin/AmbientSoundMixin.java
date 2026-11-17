package dev.recur.mixin;

import dev.recur.SoundLog;
import net.minecraft.world.entity.Mob;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/** A mob's idle noise: a mob being played back still makes these by itself, so they're never recorded. */
@Mixin(Mob.class)
public abstract class AmbientSoundMixin {
	@Inject(method = "playAmbientSound", at = @At("HEAD"))
	private void recur$start(CallbackInfo ci) {
		SoundLog.ambient++;
	}

	@Inject(method = "playAmbientSound", at = @At("RETURN"))
	private void recur$end(CallbackInfo ci) {
		if (SoundLog.ambient > 0) SoundLog.ambient--;
	}
}
