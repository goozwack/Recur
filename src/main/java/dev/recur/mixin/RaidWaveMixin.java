package dev.recur.mixin;

import dev.recur.RaidWaves;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.entity.raid.Raid;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/** A raid's waves are recorded the first time and the same after that (see RaidWaves). */
@Mixin(Raid.class)
public abstract class RaidWaveMixin {
	@Shadow public abstract int getGroupsSpawned();

	@Inject(method = "tick", at = @At("HEAD"))
	private void recur$level(ServerLevel level, CallbackInfo ci) {
		RaidWaves.ticking = level;
	}

	/** A recorded wave comes at its own moment, so the raid doesn't roll one meanwhile. */
	@Inject(method = "shouldSpawnGroup", at = @At("RETURN"), cancellable = true)
	private void recur$waitForRecorded(CallbackInfoReturnable<Boolean> cir) {
		if (cir.getReturnValueZ() && RaidWaves.pending((Raid) (Object) this, getGroupsSpawned() + 1)) cir.setReturnValue(false);
	}

	@Inject(method = "spawnGroup", at = @At("RETURN"))
	private void recur$spawned(ServerLevel level, BlockPos pos, CallbackInfo ci) {
		RaidWaves.onWaveSpawned(level, (Raid) (Object) this, pos);
	}
}
