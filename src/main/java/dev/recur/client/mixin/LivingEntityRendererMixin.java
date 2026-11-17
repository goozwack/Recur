package dev.recur.client.mixin;

import dev.recur.client.CloneAlpha;
import net.minecraft.client.renderer.entity.LivingEntityRenderer;
import net.minecraft.client.renderer.entity.state.LivingEntityRenderState;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Constant;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.ModifyConstant;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

@Mixin(LivingEntityRenderer.class)
public abstract class LivingEntityRendererMixin {
	/** The state being drawn right now (rendering happens on one thread). */
	@Unique private static float recur$alpha = 1f;

	@Inject(method = "submit(Lnet/minecraft/client/renderer/entity/state/LivingEntityRenderState;Lcom/mojang/blaze3d/vertex/PoseStack;Lnet/minecraft/client/renderer/SubmitNodeCollector;Lnet/minecraft/client/renderer/state/level/CameraRenderState;)V",
		at = @At("HEAD"), require = 0)
	private void recur$remember(LivingEntityRenderState state, com.mojang.blaze3d.vertex.PoseStack pose,
									net.minecraft.client.renderer.SubmitNodeCollector collector,
									net.minecraft.client.renderer.state.level.CameraRenderState camera, CallbackInfo ci) {
		recur$alpha = ((CloneAlpha) state).recur$getAlpha();
		dev.recur.client.CloneFade.drawing = recur$alpha;
	}

	@Inject(method = "submit(Lnet/minecraft/client/renderer/entity/state/LivingEntityRenderState;Lcom/mojang/blaze3d/vertex/PoseStack;Lnet/minecraft/client/renderer/SubmitNodeCollector;Lnet/minecraft/client/renderer/state/level/CameraRenderState;)V",
		at = @At("RETURN"), require = 0)
	private void recur$done(LivingEntityRenderState state, com.mojang.blaze3d.vertex.PoseStack pose,
							net.minecraft.client.renderer.SubmitNodeCollector collector,
							net.minecraft.client.renderer.state.level.CameraRenderState camera, CallbackInfo ci) {
		dev.recur.client.CloneFade.drawing = 1f;
	}

	/**
	 * While a clone fades in or out, its armor and cape fade with it (see ArmorFadeMixin, CapeFadeMixin). Everything else
	 * on it (what it holds, stuck arrows) can't be drawn see-through, so it only shows once the clone is solid.
	 */
	@com.llamalad7.mixinextras.injector.wrapoperation.WrapOperation(
		method = "submit(Lnet/minecraft/client/renderer/entity/state/LivingEntityRenderState;Lcom/mojang/blaze3d/vertex/PoseStack;Lnet/minecraft/client/renderer/SubmitNodeCollector;Lnet/minecraft/client/renderer/state/level/CameraRenderState;)V",
		at = @At(value = "INVOKE", target = "Lnet/minecraft/client/renderer/entity/layers/RenderLayer;submit(Lcom/mojang/blaze3d/vertex/PoseStack;Lnet/minecraft/client/renderer/SubmitNodeCollector;ILnet/minecraft/client/renderer/entity/state/EntityRenderState;FF)V"),
		require = 0)
	private void recur$layer(net.minecraft.client.renderer.entity.layers.RenderLayer<?, ?> layer, com.mojang.blaze3d.vertex.PoseStack pose,
							 net.minecraft.client.renderer.SubmitNodeCollector collector, int light,
							 net.minecraft.client.renderer.entity.state.EntityRenderState state, float yRot, float xRot,
							 com.llamalad7.mixinextras.injector.wrapoperation.Operation<Void> original) {
		if (dev.recur.client.CloneFade.drawing < 1f && !(layer instanceof net.minecraft.client.renderer.entity.layers.HumanoidArmorLayer)
			&& !(layer instanceof net.minecraft.client.renderer.entity.layers.CapeLayer)) return;
		original.call(layer, pose, collector, light, state, yRot, xRot);
	}

	/** Vanilla draws see-through players at a fixed 15% opacity; a fading clone uses its own opacity instead. */
	@ModifyConstant(method = "submit(Lnet/minecraft/client/renderer/entity/state/LivingEntityRenderState;Lcom/mojang/blaze3d/vertex/PoseStack;Lnet/minecraft/client/renderer/SubmitNodeCollector;Lnet/minecraft/client/renderer/state/level/CameraRenderState;)V",
		constant = @Constant(intValue = 654311423), require = 0)
	private int recur$fadeAlpha(int original) {
		if (recur$alpha >= 1f) return original;
		return ((int) (recur$alpha * 255f) << 24) | 0xFFFFFF;
	}

	@Inject(method = "shouldRenderLayers", at = @At("HEAD"), cancellable = true)
	private void recur$noLayersWhileFading(LivingEntityRenderState state, CallbackInfoReturnable<Boolean> cir) {
		if (((CloneAlpha) state).recur$getAlpha() <= 0.01f) cir.setReturnValue(false); // not drawn at all
	}
}
