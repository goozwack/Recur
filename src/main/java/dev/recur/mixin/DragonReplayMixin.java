package dev.recur.mixin;

import com.mt1006.mocap.mocap.playing.Controlled;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.entity.boss.enderdragon.EnderDragon;
import net.minecraft.world.entity.boss.enderdragon.phases.DragonPhaseInstance;
import net.minecraft.world.phys.Vec3;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.Redirect;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * While a clone plays the dragon back, its phase doesn't run (no new attacks, no phase changes) and it doesn't fly by
 * itself: the recording moves it and sets its phase. Everything else still runs, so its wings flap, its hitboxes follow
 * it, it knocks players back, crystals heal it and the boss bar updates.
 */
@Mixin(EnderDragon.class)
public abstract class DragonReplayMixin {
	@Inject(method = "aiStep", at = @At("HEAD"))
	private void recur$dying(CallbackInfo ci) {
		EnderDragon self = (EnderDragon) (Object) this;
		if (self.isDeadOrDying() && Controlled.isControlled(self)) Controlled.release(self);
	}

	@Redirect(method = "aiStep", at = @At(value = "INVOKE",
		target = "Lnet/minecraft/world/entity/boss/enderdragon/phases/DragonPhaseInstance;doServerTick(Lnet/minecraft/server/level/ServerLevel;)V"))
	private void recur$phaseTick(DragonPhaseInstance phase, ServerLevel level) {
		if (!Controlled.isControlled((EnderDragon) (Object) this)) phase.doServerTick(level);
	}

	@Redirect(method = "aiStep", at = @At(value = "INVOKE",
		target = "Lnet/minecraft/world/entity/boss/enderdragon/phases/DragonPhaseInstance;getFlyTargetLocation()Lnet/minecraft/world/phys/Vec3;"))
	private Vec3 recur$flyTarget(DragonPhaseInstance phase) {
		return Controlled.isControlled((EnderDragon) (Object) this) ? null : phase.getFlyTargetLocation();
	}
}
