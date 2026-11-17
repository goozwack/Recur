package dev.recur.mixin;

import dev.recur.ContainerDeltas;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.server.network.ServerGamePacketListenerImpl;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/** Clicks in a container screen (and the recipe book filling a furnace) are what a player adds to / takes from it. */
@Mixin(ServerGamePacketListenerImpl.class)
public abstract class ContainerClickMixin {
	@Shadow public ServerPlayer player;

	@Inject(method = {"handleContainerClick", "handlePlaceRecipe"}, at = @At("HEAD"))
	private void recur$before(CallbackInfo ci) {
		ContainerDeltas.beforeClick(this.player);
		dev.recur.Reservations.beforeClick(this.player);
	}

	@Inject(method = {"handleContainerClick", "handlePlaceRecipe"}, at = @At("RETURN"))
	private void recur$after(CallbackInfo ci) {
		// In timed resets, a click that took what a clone takes later is undone first, so it is not recorded either.
		dev.recur.Reservations.afterClick(this.player);
		ContainerDeltas.afterClick(this.player);
	}
}
