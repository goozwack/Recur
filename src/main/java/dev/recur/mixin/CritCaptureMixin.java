package dev.recur.mixin;

import dev.recur.WorldLog;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.Entity;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/** A player landed a critical hit: their clone shows its particles again (see WorldLog.onCrit). */
@Mixin(ServerPlayer.class)
public abstract class CritCaptureMixin {
	@Inject(method = "crit", at = @At("HEAD"))
	private void recur$crit(Entity target, CallbackInfo ci) {
		WorldLog.onCrit((ServerPlayer) (Object) this, target, false);
	}

	@Inject(method = "magicCrit", at = @At("HEAD"))
	private void recur$magicCrit(Entity target, CallbackInfo ci) {
		WorldLog.onCrit((ServerPlayer) (Object) this, target, true);
	}
}
