package dev.recur.mixin;

import dev.recur.RandomTicks;
import net.minecraft.core.BlockPos;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.state.BlockState;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/** Lets a recorded random tick see every block it changes (a growing tree places many). Costs one flag check otherwise. */
@Mixin(Level.class)
public abstract class SetBlockCaptureMixin {
	@Inject(method = "setBlock(Lnet/minecraft/core/BlockPos;Lnet/minecraft/world/level/block/state/BlockState;II)Z", at = @At("HEAD"))
	private void recur$before(BlockPos pos, BlockState state, int flags, int limit, CallbackInfoReturnable<Boolean> cir) {
		Level level = (Level) (Object) this;
		dev.recur.TimedReset.beforeChange(level, pos);
		dev.recur.WorldLog.setBlockDepth++;
		RandomTicks.beforeSetBlock(level, pos);
		dev.recur.WorldLog.beforeSetBlock(level, pos);
		dev.recur.CloneMade.beforeSetBlock(level, pos);
	}

	@Inject(method = "setBlock(Lnet/minecraft/core/BlockPos;Lnet/minecraft/world/level/block/state/BlockState;II)Z", at = @At("RETURN"))
	private void recur$after(BlockPos pos, BlockState state, int flags, int limit, CallbackInfoReturnable<Boolean> cir) {
		Level level = (Level) (Object) this;
		if (dev.recur.WorldLog.setBlockDepth > 0) dev.recur.WorldLog.setBlockDepth--;
		RandomTicks.afterSetBlock(level, pos);
		dev.recur.WorldLog.afterSetBlock(level, pos);
		dev.recur.TimedReset.onChanged(level, pos);
		dev.recur.MobTies.afterSetBlock(level, pos);
		dev.recur.CloneMade.afterSetBlock(level, pos, cir.getReturnValueZ());
	}
}
