package dev.recur.client.mixin;

import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import com.llamalad7.mixinextras.injector.wrapoperation.WrapOperation;
import dev.recur.client.CloneFade;
import net.minecraft.client.model.Model;
import net.minecraft.client.renderer.SubmitNodeCollector;
import net.minecraft.client.renderer.entity.layers.CapeLayer;
import net.minecraft.client.renderer.rendertype.RenderType;
import net.minecraft.client.renderer.rendertype.RenderTypes;
import net.minecraft.resources.Identifier;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;

/** A fading clone's cape is drawn see-through at the clone's opacity. */
@Mixin(CapeLayer.class)
public abstract class CapeFadeMixin {
	private static final String SUBMIT = "submit(Lcom/mojang/blaze3d/vertex/PoseStack;Lnet/minecraft/client/renderer/SubmitNodeCollector;ILnet/minecraft/client/renderer/entity/state/AvatarRenderState;FF)V";

	@WrapOperation(method = SUBMIT, require = 0, at = @At(value = "INVOKE",
		target = "Lnet/minecraft/client/renderer/rendertype/RenderTypes;entitySolid(Lnet/minecraft/resources/Identifier;)Lnet/minecraft/client/renderer/rendertype/RenderType;"))
	private RenderType recur$seeThrough(Identifier texture, Operation<RenderType> original) {
		return CloneFade.drawing < 1f ? RenderTypes.entityTranslucent(texture) : original.call(texture);
	}

	@WrapOperation(method = SUBMIT, require = 0, at = @At(value = "INVOKE",
		target = "Lnet/minecraft/client/renderer/SubmitNodeCollector;submitModel(Lnet/minecraft/client/model/Model;Ljava/lang/Object;Lcom/mojang/blaze3d/vertex/PoseStack;Lnet/minecraft/client/renderer/rendertype/RenderType;III)V"))
	private void recur$fade(SubmitNodeCollector collector, Model<?> model, Object state, com.mojang.blaze3d.vertex.PoseStack pose,
							RenderType type, int light, int overlay, int outline, Operation<Void> original) {
		if (CloneFade.drawing >= 1f) { original.call(collector, model, state, pose, type, light, overlay, outline); return; }
		((SubmitNodeCollector) collector).submitModel((Model<Object>) model, state, pose, type, light, overlay, CloneFade.fade(-1), null, outline);
	}
}
