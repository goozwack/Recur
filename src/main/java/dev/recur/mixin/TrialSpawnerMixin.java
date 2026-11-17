package dev.recur.mixin;

import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import com.llamalad7.mixinextras.injector.wrapoperation.WrapOperation;
import dev.recur.TrialSpawns;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.core.Position;
import net.minecraft.resources.ResourceKey;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.entity.trialspawner.TrialSpawner;
import net.minecraft.world.level.block.entity.trialspawner.TrialSpawnerState;
import net.minecraft.world.level.storage.loot.LootTable;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

import java.util.Optional;
import java.util.UUID;

/** Trial spawner fights are recorded and replayed (see TrialSpawns). */
@Mixin(TrialSpawner.class)
public abstract class TrialSpawnerMixin {
	@Inject(method = "tickServer", at = @At("HEAD"))
	private void recur$enter(ServerLevel level, BlockPos pos, boolean ominous, CallbackInfo ci) {
		TrialSpawns.enter(level, pos);
	}

	@Inject(method = "tickServer", at = @At("RETURN"))
	private void recur$exit(ServerLevel level, BlockPos pos, boolean ominous, CallbackInfo ci) {
		TrialSpawns.exit();
	}

	@Inject(method = "setState", at = @At("HEAD"))
	private void recur$state(Level level, TrialSpawnerState next, CallbackInfo ci) {
		TrialSpawns.onState((TrialSpawner) (Object) this, next);
	}

	@Inject(method = "spawnMob", at = @At("HEAD"), cancellable = true)
	private void recur$maySpawn(ServerLevel level, BlockPos pos, CallbackInfoReturnable<Optional<UUID>> cir) {
		if (!TrialSpawns.maySpawn((TrialSpawner) (Object) this)) cir.setReturnValue(Optional.empty());
	}

	@Inject(method = "spawnMob", at = @At("RETURN"))
	private void recur$spawned(ServerLevel level, BlockPos pos, CallbackInfoReturnable<Optional<UUID>> cir) {
		TrialSpawns.spawned((TrialSpawner) (Object) this, cir.getReturnValue());
	}

	@Inject(method = "ejectReward", at = @At("HEAD"))
	private void recur$rewardStart(ServerLevel level, BlockPos pos, ResourceKey<LootTable> table, CallbackInfo ci) {
		TrialSpawns.startReward();
	}

	@Inject(method = "ejectReward", at = @At("RETURN"))
	private void recur$rewardEnd(ServerLevel level, BlockPos pos, ResourceKey<LootTable> table, CallbackInfo ci) {
		TrialSpawns.endReward();
	}

	@WrapOperation(method = "ejectReward", at = @At(value = "INVOKE",
		target = "Lnet/minecraft/core/dispenser/DefaultDispenseItemBehavior;spawnItem(Lnet/minecraft/world/level/Level;Lnet/minecraft/world/item/ItemStack;ILnet/minecraft/core/Direction;Lnet/minecraft/core/Position;)V"))
	private void recur$rewardItem(Level level, ItemStack stack, int speed, Direction dir, Position at, Operation<Void> original) {
		if (!TrialSpawns.blockingReward()) original.call(level, stack, speed, dir, at);
	}
}
