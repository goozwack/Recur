package dev.recur.mixin;

import dev.recur.LoopOverlay;
import dev.recur.RandomTicks;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.util.RandomSource;
import net.minecraft.world.level.block.state.BlockBehaviour;
import net.minecraft.world.level.block.state.BlockState;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * See {@link RandomTicks}: growth on blocks players changed is recorded, and replayed for their clones.
 */
@Mixin(BlockBehaviour.BlockStateBase.class)
public abstract class RandomTickMixin {
	@Inject(method = "randomTick", at = @At("HEAD"), cancellable = true)
	private void recur$before(ServerLevel level, BlockPos pos, RandomSource random, CallbackInfo ci) {
		if (LoopOverlay.active && RandomTicks.before((BlockState) (Object) this, level, pos)) ci.cancel();
	}

	@Inject(method = "randomTick", at = @At("TAIL"))
	private void recur$after(ServerLevel level, BlockPos pos, RandomSource random, CallbackInfo ci) {
		if (LoopOverlay.active) RandomTicks.after(level, pos);
	}
}
