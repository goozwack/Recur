package dev.recur.mixin;

import com.mt1006.mocap.utils.FakePlayer;
import net.minecraft.server.level.ChunkMap;
import net.minecraft.server.level.ServerPlayer;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/**
 * Clones are added to the world as players, which would make each one load (and simulate, and spawn mobs in) a whole
 * view distance of chunks, and "watch" every entity around it. Here they're ignored for chunk loading and never track
 * any chunks; {@link dev.recur.CloneTickets} keeps just a small area around each clone loaded instead.
 */
@Mixin(ChunkMap.class)
public abstract class CloneChunkMixin {
	@Inject(method = "skipPlayer", at = @At("HEAD"), cancellable = true)
	private void recur$clonesLoadNoChunks(ServerPlayer player, CallbackInfoReturnable<Boolean> cir) {
		if (player instanceof FakePlayer) cir.setReturnValue(true);
	}

	@Inject(method = "updateChunkTracking", at = @At("HEAD"), cancellable = true)
	private void recur$clonesTrackNoChunks(ServerPlayer player, CallbackInfo ci) {
		if (player instanceof FakePlayer) ci.cancel();
	}
}
