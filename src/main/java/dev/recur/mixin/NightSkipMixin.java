package dev.recur.mixin;

import dev.recur.WorldLog;
import net.minecraft.server.level.ServerLevel;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * The night is being skipped because enough players slept. The sleeping players' recordings note it, so when their
 * clones sleep in a later run the night is skipped again at the same moment (see WorldLog, kind 11).
 */
@Mixin(ServerLevel.class)
public abstract class NightSkipMixin {
	@Inject(method = "wakeUpAllPlayers", at = @At("HEAD"))
	private void recur$nightSkipped(CallbackInfo ci) {
		WorldLog.onNightSkip((ServerLevel) (Object) this);
	}
}
