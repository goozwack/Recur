package dev.recur.mixin;

import dev.recur.WorldLog;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.level.ServerExplosion;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/**
 * TNT a player lit explodes seconds later, outside any of their actions. Count the explosion as that player's action, so
 * the blocks it destroys are recorded for their clone (and chained TNT, which keeps the same igniter, too).
 */
@Mixin(ServerExplosion.class)
public abstract class ExplosionActionMixin {
	@Inject(method = "explode", at = @At("HEAD"))
	private void recur$begin(CallbackInfoReturnable<Integer> cir) {
		ServerExplosion self = (ServerExplosion) (Object) this;
		if (self.getIndirectSourceEntity() instanceof ServerPlayer p) WorldLog.beginAction(p);
		else WorldLog.beginMobExplosion(self.getDirectSourceEntity(), self.center(), self.radius());
	}

	@Inject(method = "explode", at = @At("RETURN"))
	private void recur$end(CallbackInfoReturnable<Integer> cir) {
		ServerExplosion self = (ServerExplosion) (Object) this;
		if (self.getIndirectSourceEntity() instanceof ServerPlayer p) WorldLog.endAction(p);
		else WorldLog.endMobExplosion(self.getDirectSourceEntity());
	}
}
