package dev.recur.mixin;

import net.minecraft.world.entity.LivingEntity;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/** A neutral mob a clone angered may go after that clone (mobs ignore clones otherwise). */
@Mixin(LivingEntity.class)
public class CloneAngerMixin {
	@Inject(method = "canAttack(Lnet/minecraft/world/entity/LivingEntity;)Z", at = @At("HEAD"), cancellable = true)
	private void recur$angeredBy(LivingEntity target, CallbackInfoReturnable<Boolean> cir) {
		if (dev.recur.CloneAnger.mayAttack((LivingEntity) (Object) this, target)) cir.setReturnValue(true);
	}
}
