package dev.recur.mixin;

import dev.recur.WorldLog;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.server.level.ServerPlayerGameMode;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/** Block breaking that finishes during the tick rather than in a packet is also the player's action. */
@Mixin(ServerPlayerGameMode.class)
public abstract class PlayerTickActionMixin {
	@Shadow @Final protected ServerPlayer player;

	@Inject(method = "tick", at = @At("HEAD"))
	private void recur$begin(CallbackInfo ci) { WorldLog.beginAction(this.player); }

	@Inject(method = "tick", at = @At("RETURN"))
	private void recur$end(CallbackInfo ci) { WorldLog.endAction(this.player); }
}
