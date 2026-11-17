package dev.recur.mixin;

import dev.recur.BossReplay;
import dev.recur.LoopOverlay;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.damagesource.DamageSource;
import net.minecraft.world.entity.LivingEntity;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/**
 * No i-frames on the wither and the dragon during a loop: with clones fighting them too, the half second after each hit
 * made most arrows bounce off.
 */
@Mixin(LivingEntity.class)
public abstract class BossHitMixin {
	@Inject(method = "hurtServer", at = @At("RETURN"))
	private void recur$noIFrames(ServerLevel level, DamageSource source, float amount, CallbackInfoReturnable<Boolean> cir) {
		LivingEntity self = (LivingEntity) (Object) this;
		if (LoopOverlay.active && BossReplay.isBoss(self)) self.setInvulnerableTime(0);
	}
}
