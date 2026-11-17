package dev.recur.mixin;

import dev.recur.WorldLog;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.LivingEntity;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/** Items used over time (brushing suspicious sand, eating, charging...) change blocks while held: the player's action too. */
@Mixin(LivingEntity.class)
public abstract class ItemUseTickMixin {
	@Inject(method = "updatingUsingItem", at = @At("HEAD"))
	private void recur$begin(CallbackInfo ci) {
		if ((Object) this instanceof ServerPlayer p) WorldLog.beginAction(p);
	}

	@Inject(method = "updatingUsingItem", at = @At("RETURN"))
	private void recur$end(CallbackInfo ci) {
		if ((Object) this instanceof ServerPlayer p) WorldLog.endAction(p);
	}
}
