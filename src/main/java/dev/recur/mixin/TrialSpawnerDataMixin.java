package dev.recur.mixin;

import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import com.llamalad7.mixinextras.injector.wrapoperation.WrapOperation;
import dev.recur.TrialSpawns;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.block.entity.trialspawner.PlayerDetector;
import net.minecraft.world.level.block.entity.trialspawner.TrialSpawnerConfig;
import net.minecraft.world.level.block.entity.trialspawner.TrialSpawnerStateData;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

import java.util.List;
import java.util.UUID;

@Mixin(TrialSpawnerStateData.class)
public abstract class TrialSpawnerDataMixin {
	/** Clones don't set spawners off: their fights come from the recording. */
	@WrapOperation(method = "tryDetectPlayers", at = @At(value = "INVOKE",
		target = "Lnet/minecraft/world/level/block/entity/trialspawner/PlayerDetector;detect(Lnet/minecraft/server/level/ServerLevel;Lnet/minecraft/world/level/block/entity/trialspawner/PlayerDetector$EntitySelector;Lnet/minecraft/core/BlockPos;DZ)Ljava/util/List;"))
	private List<UUID> recur$noClones(PlayerDetector detector, ServerLevel level, PlayerDetector.EntitySelector selector, BlockPos pos,
									  double range, boolean sight, Operation<List<UUID>> original) {
		return TrialSpawns.withoutClones(level, original.call(detector, level, selector, pos, range, sight));
	}

	@Inject(method = "hasFinishedSpawningAllMobs", at = @At("HEAD"), cancellable = true)
	private void recur$finished(TrialSpawnerConfig config, int additionalPlayers, CallbackInfoReturnable<Boolean> cir) {
		Boolean finished = TrialSpawns.finished();
		if (finished != null) cir.setReturnValue(finished);
	}
}
