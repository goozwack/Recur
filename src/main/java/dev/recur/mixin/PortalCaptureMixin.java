package dev.recur.mixin;

import com.mt1006.mocap.utils.FakePlayer;
import dev.recur.WorldLog;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.Entity;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * Going through a portal can build one on the other side (the obsidian frame, the platform, the air cleared around
 * it). That counts as the player's doing, so it is recorded like their other block changes: the portal is there again
 * when their clone arrives, and when a run starts at a checkpoint made after it.
 */
@Mixin(Entity.class)
public abstract class PortalCaptureMixin {
	@Inject(method = "handlePortal", at = @At("HEAD"))
	private void recur$begin(CallbackInfo ci) {
		if ((Object) this instanceof ServerPlayer p && !(p instanceof FakePlayer)) WorldLog.beginAction(p);
	}

	@Inject(method = "handlePortal", at = @At("RETURN"))
	private void recur$end(CallbackInfo ci) {
		if ((Object) this instanceof ServerPlayer p && !(p instanceof FakePlayer)) WorldLog.endAction(p);
	}
}
