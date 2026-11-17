package dev.recur.mixin;

import dev.recur.MobTies;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.Mob;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/** Something made a mob a clone plays back go for a player (see MobTies.onTargetSet). */
@Mixin(Mob.class)
public abstract class TargetSetMixin {
	@Inject(method = "setTarget", at = @At("HEAD"))
	private void recur$target(LivingEntity target, CallbackInfo ci) {
		MobTies.onTargetSet((Mob) (Object) this, target);
	}
}
