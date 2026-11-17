package dev.recur.mixin;

import dev.recur.WorldLog;
import net.minecraft.server.level.ServerPlayer;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/** Records the contents of whatever container the player is closing (chest, furnace, chest boat...). */
@Mixin(ServerPlayer.class)
public abstract class ContainerCloseMixin {
	@Inject(method = "doCloseContainer", at = @At("HEAD"))
	private void recur$onClose(CallbackInfo ci) {
		WorldLog.onContainerClosed((ServerPlayer) (Object) this);
	}
}
