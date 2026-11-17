package dev.recur.mixin;

import dev.recur.TimedReset;
import net.minecraft.world.level.block.entity.BlockEntity;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/** A chest's items, a furnace's progress... changed: timed resets put that block entity back too. */
@Mixin(BlockEntity.class)
public abstract class BlockEntityChangedMixin {
	@Inject(method = "setChanged()V", at = @At("HEAD"))
	private void recur$changed(CallbackInfo ci) {
		BlockEntity self = (BlockEntity) (Object) this;
		if (self.getLevel() == null) return;
		TimedReset.beforeChange(self.getLevel(), self.getBlockPos());
		TimedReset.onChanged(self.getLevel(), self.getBlockPos());
	}
}
