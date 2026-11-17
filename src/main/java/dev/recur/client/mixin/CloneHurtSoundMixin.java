package dev.recur.client.mixin;

import dev.recur.client.ClientLoopState;
import dev.recur.client.CloneSounds;
import net.minecraft.world.damagesource.DamageSource;
import net.minecraft.world.entity.LivingEntity;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/** A clone's hurt sound is played by each player's own game, so it's turned down to the clone action volume here. */
@Mixin(LivingEntity.class)
public abstract class CloneHurtSoundMixin {
	@Inject(method = "handleDamageEvent", at = @At("HEAD"))
	private void recur$beforeHurt(DamageSource source, CallbackInfo ci) {
		if (ClientLoopState.isClone(((LivingEntity) (Object) this).getId())) CloneSounds.scaling = true;
	}

	@Inject(method = "handleDamageEvent", at = @At("RETURN"))
	private void recur$afterHurt(DamageSource source, CallbackInfo ci) {
		CloneSounds.scaling = false;
	}
}
