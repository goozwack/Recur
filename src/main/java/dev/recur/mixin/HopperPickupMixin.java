package dev.recur.mixin;

import dev.recur.CloneMade;
import net.minecraft.world.Container;
import net.minecraft.world.entity.item.ItemEntity;
import net.minecraft.world.level.block.entity.HopperBlockEntity;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/** Timed resets: hoppers (and hopper minecarts) don't suck up items a clone dropped. */
@Mixin(HopperBlockEntity.class)
public abstract class HopperPickupMixin {
	@Inject(method = "addItem(Lnet/minecraft/world/Container;Lnet/minecraft/world/entity/item/ItemEntity;)Z", at = @At("HEAD"), cancellable = true)
	private static void recur$noCloneItems(Container container, ItemEntity entity, CallbackInfoReturnable<Boolean> cir) {
		if (CloneMade.isCloneItem(entity)) cir.setReturnValue(false);
	}
}
