package dev.recur.mixin;

import com.mt1006.mocap.utils.FakePlayer;
import dev.recur.CloneMade;
import dev.recur.TimedReset;
import net.minecraft.world.entity.item.ItemEntity;
import net.minecraft.world.entity.player.Player;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/**
 * A grave's items don't merge with each other (or anything else), so each one can be followed until it's picked up.
 * In timed resets, items a clone drops never merge with real items and can't be picked up by players either.
 */
@Mixin(ItemEntity.class)
public abstract class GraveItemMergeMixin {
	@Inject(method = "isMergable", at = @At("HEAD"), cancellable = true)
	private void recur$noMerge(CallbackInfoReturnable<Boolean> cir) {
		ItemEntity self = (ItemEntity) (Object) this;
		if (TimedReset.Graves.isGraveItem(self) || CloneMade.isCloneItem(self)) cir.setReturnValue(false);
	}

	/** Mocap sets a clone item's stack from its recording: keep it marked as a clone's. */
	@Inject(method = "setItem", at = @At("RETURN"))
	private void recur$keepMarked(net.minecraft.world.item.ItemStack stack, CallbackInfo ci) {
		CloneMade.markIfClone((ItemEntity) (Object) this);
	}

	@Inject(method = "playerTouch", at = @At("HEAD"), cancellable = true)
	private void recur$noPickup(Player player, CallbackInfo ci) {
		if (!(player instanceof FakePlayer) && CloneMade.isCloneItem((ItemEntity) (Object) this)) ci.cancel();
	}
}
