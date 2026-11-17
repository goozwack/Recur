package dev.recur.client.mixin;

import com.mojang.blaze3d.vertex.PoseStack;
import dev.recur.client.ClientLoopState;
import dev.recur.client.CloneAlpha;
import net.minecraft.client.renderer.SubmitNodeCollector;
import net.minecraft.client.renderer.culling.Frustum;
import net.minecraft.client.renderer.entity.EntityRenderer;
import net.minecraft.client.renderer.entity.state.EntityRenderState;
import net.minecraft.client.renderer.state.level.CameraRenderState;
import net.minecraft.world.entity.Entity;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

@Mixin(EntityRenderer.class)
public abstract class EntityRendererMixin {
	/** A clone with neither body nor name tag showing isn't drawn at all (no shadow either). */
	@Inject(method = "shouldRender", at = @At("HEAD"), cancellable = true)
	private void recur$hideClones(Entity entity, Frustum culler, double camX, double camY, double camZ, float partialTicks,
								   CallbackInfoReturnable<Boolean> cir) {
		float fade = ClientLoopState.cloneFade(entity.getId(), partialTicks);
		if (fade < 0f) return;
		if (dev.recur.client.CloneCulling.hidden(entity)) { cir.setReturnValue(false); return; } // far away and out of sight
		if (fade <= 0f || (ClientLoopState.cloneView.body <= 0f && ClientLoopState.cloneView.tag <= 0f)) cir.setReturnValue(false);
	}

	/** Tells the name-tag drawing how opaque this entity's tag should be while it is being drawn. */
	@Inject(method = "submit", at = @At("HEAD"))
	private void recur$tagAlphaStart(EntityRenderState state, PoseStack pose, SubmitNodeCollector collector,
									  CameraRenderState camera, CallbackInfo ci) {
		ClientLoopState.drawingTagAlpha = ((CloneAlpha) state).recur$getTagAlpha();
	}

	@Inject(method = "submit", at = @At("TAIL"))
	private void recur$tagAlphaEnd(EntityRenderState state, PoseStack pose, SubmitNodeCollector collector,
									CameraRenderState camera, CallbackInfo ci) {
		ClientLoopState.drawingTagAlpha = 1f;
	}
}
