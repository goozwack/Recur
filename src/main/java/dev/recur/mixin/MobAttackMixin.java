package dev.recur.mixin;

import dev.recur.MobTies;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.Mob;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/** A mob swings at something: if it's a player, the mob is tied to them (see MobTies.onAttempt). */
@Mixin(Mob.class)
public abstract class MobAttackMixin {
	@Inject(method = "doHurtTarget", at = @At("HEAD"))
	private void recur$attempt(ServerLevel level, Entity target, CallbackInfoReturnable<Boolean> cir) {
		MobTies.onAttempt((Mob) (Object) this, target);
	}
}
