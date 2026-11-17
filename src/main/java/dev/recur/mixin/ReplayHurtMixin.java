package dev.recur.mixin;

import dev.recur.MobTies;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.damagesource.DamageSource;
import net.minecraft.world.entity.LivingEntity;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/** A player hurting a mob a clone plays back ends its replay first (see MobTies.onReplayHurt). */
@Mixin(LivingEntity.class)
public abstract class ReplayHurtMixin {
	@Inject(method = "hurtServer", at = @At("HEAD"))
	private void recur$hurt(ServerLevel level, DamageSource source, float amount, CallbackInfoReturnable<Boolean> cir) {
		MobTies.onReplayHurt((LivingEntity) (Object) this, source);
		dev.recur.SoundLog.startHurt((LivingEntity) (Object) this); // its hurt sound comes from it
	}

	@Inject(method = "hurtServer", at = @At("RETURN"))
	private void recur$hurtDone(ServerLevel level, DamageSource source, float amount, CallbackInfoReturnable<Boolean> cir) {
		dev.recur.SoundLog.endHurt();
	}
}
