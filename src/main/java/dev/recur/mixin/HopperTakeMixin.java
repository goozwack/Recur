package dev.recur.mixin;

import dev.recur.Reservations;
import net.minecraft.core.Direction;
import net.minecraft.world.Container;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.block.entity.HopperBlockEntity;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/**
 * Timed resets: hoppers can't pull out items a clone takes later. This is the check both vanilla's and Lithium's hopper
 * code ask before taking an item.
 */
@Mixin(HopperBlockEntity.class)
public abstract class HopperTakeMixin {
	@Inject(method = "canTakeItemFromContainer", at = @At("HEAD"), cancellable = true)
	private static void recur$reserved(Container into, Container from, ItemStack stack, int slot, Direction direction, CallbackInfoReturnable<Boolean> cir) {
		if (Reservations.blocksTake(from, slot, 1)) cir.setReturnValue(false);
	}
}
