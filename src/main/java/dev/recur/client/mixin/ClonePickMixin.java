package dev.recur.client.mixin;

import dev.recur.client.ClientLoopState;
import net.minecraft.client.Minecraft;
import net.minecraft.world.entity.player.Player;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/**
 * Your crosshair ignores clones: clicks and hits go straight through to whatever is behind them. While you crouch you
 * can target them (hitting one then kills it).
 */
@Mixin(Player.class)
public abstract class ClonePickMixin {
	@Inject(method = "isPickable", at = @At("HEAD"), cancellable = true)
	private void recur$clickThroughClones(CallbackInfoReturnable<Boolean> cir) {
		Player self = (Player) (Object) this;
		if (!self.level().isClientSide() || !ClientLoopState.isClone(self.getId())) return;
		Player me = Minecraft.getInstance().player;
		if (me != null && me.isShiftKeyDown()) return;
		cir.setReturnValue(false);
	}
}
