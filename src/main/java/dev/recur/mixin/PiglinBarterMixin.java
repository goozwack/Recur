package dev.recur.mixin;

import dev.recur.CloneMade;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.entity.monster.piglin.Piglin;
import net.minecraft.world.entity.monster.piglin.PiglinAi;
import net.minecraft.world.item.ItemStack;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * In timed resets, a piglin admiring gold that a clone threw gives nothing for it and just puts the gold away. What it
 * gave in the original run is already in the clone's recording.
 */
@Mixin(PiglinAi.class)
public abstract class PiglinBarterMixin {
	@Inject(method = "stopHoldingOffHandItem", at = @At("HEAD"), cancellable = true)
	private static void recur$noCloneBarter(ServerLevel level, Piglin body, boolean barteringEnabled, CallbackInfo ci) {
		if (!CloneMade.isMarked(body.getItemInHand(InteractionHand.OFF_HAND))) return;
		body.setItemInHand(InteractionHand.OFF_HAND, ItemStack.EMPTY);
		ci.cancel();
	}
}
