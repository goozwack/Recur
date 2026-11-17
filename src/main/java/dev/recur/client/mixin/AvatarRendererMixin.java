package dev.recur.client.mixin;

import dev.recur.client.ClientLoopState;
import dev.recur.client.CloneAlpha;
import net.minecraft.client.renderer.entity.player.AvatarRenderer;
import net.minecraft.client.renderer.entity.state.AvatarRenderState;
import net.minecraft.world.entity.Avatar;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * Applies the clone view mode and the spawn fade: a see-through body uses the translucent (invisible-player) path,
 * a hidden body isn't drawn at all, and the name tag fades separately.
 */
@Mixin(AvatarRenderer.class)
public abstract class AvatarRendererMixin {
	@Inject(method = "extractRenderState(Lnet/minecraft/world/entity/Avatar;Lnet/minecraft/client/renderer/entity/state/AvatarRenderState;F)V", at = @At("TAIL"))
	private void recur$fade(Avatar entity, AvatarRenderState state, float partialTicks, CallbackInfo ci) {
		CloneAlpha alpha = (CloneAlpha) state;
		float fade = ClientLoopState.cloneFade(entity.getId(), partialTicks);
		if (fade < 0f) { alpha.recur$setAlpha(1f); alpha.recur$setTagAlpha(1f); return; } // not a clone

		float body = ClientLoopState.cloneView.body * fade;
		float tag = ClientLoopState.cloneView.tag * fade;
		alpha.recur$setAlpha(body);
		alpha.recur$setTagAlpha(tag);
		if (body < 1f) {
			state.isInvisible = true;
			state.isInvisibleToPlayer = body <= 0.01f; // true = body not drawn at all; false = drawn see-through
			state.shadowRadius *= body;
		}
		if (tag <= 0.01f) {
			state.nameTag = null;
			state.scoreText = null;
		}
	}
}
