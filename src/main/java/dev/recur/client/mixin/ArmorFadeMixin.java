package dev.recur.client.mixin;

import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import com.llamalad7.mixinextras.injector.wrapoperation.WrapOperation;
import dev.recur.client.CloneFade;
import net.minecraft.client.model.Model;
import net.minecraft.client.renderer.OrderedSubmitNodeCollector;
import net.minecraft.client.renderer.entity.layers.EquipmentLayerRenderer;
import net.minecraft.client.renderer.rendertype.RenderType;
import net.minecraft.client.renderer.rendertype.RenderTypes;
import net.minecraft.client.renderer.texture.UvMapping;
import net.minecraft.resources.Identifier;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;

/** A fading clone's armor is drawn see-through at the clone's opacity, without its enchantment shine. */
@Mixin(EquipmentLayerRenderer.class)
public abstract class ArmorFadeMixin {
	private static final String RENDER_LAYERS = "renderLayers(Lnet/minecraft/client/resources/model/EquipmentClientInfo$LayerType;Lnet/minecraft/resources/ResourceKey;Lnet/minecraft/client/model/Model;Ljava/lang/Object;Lnet/minecraft/world/item/ItemStack;Lcom/mojang/blaze3d/vertex/PoseStack;Lnet/minecraft/client/renderer/SubmitNodeCollector;ILnet/minecraft/resources/Identifier;II)V";

	@WrapOperation(method = RENDER_LAYERS, require = 0, at = @At(value = "INVOKE",
		target = "Lnet/minecraft/client/renderer/rendertype/RenderTypes;armorCutoutNoCull(Lnet/minecraft/resources/Identifier;)Lnet/minecraft/client/renderer/rendertype/RenderType;"))
	private RenderType recur$plain(Identifier texture, Operation<RenderType> original) {
		return CloneFade.drawing < 1f ? RenderTypes.entityTranslucent(texture) : original.call(texture);
	}

	@WrapOperation(method = RENDER_LAYERS, require = 0, at = @At(value = "INVOKE",
		target = "Lnet/minecraft/client/renderer/rendertype/RenderTypes;armorCutoutNoCullGlint(Lnet/minecraft/resources/Identifier;)Lnet/minecraft/client/renderer/rendertype/RenderType;"))
	private RenderType recur$shiny(Identifier texture, Operation<RenderType> original) {
		return CloneFade.drawing < 1f ? RenderTypes.entityTranslucent(texture) : original.call(texture);
	}

	@WrapOperation(method = RENDER_LAYERS, require = 0, at = @At(value = "INVOKE",
		target = "Lnet/minecraft/client/renderer/rendertype/RenderTypes;armorTrim(Lnet/minecraft/resources/Identifier;Z)Lnet/minecraft/client/renderer/rendertype/RenderType;"))
	private RenderType recur$trim(Identifier atlas, boolean decal, Operation<RenderType> original) {
		return CloneFade.drawing < 1f ? RenderTypes.entityTranslucent(atlas) : original.call(atlas, decal);
	}

	@WrapOperation(method = RENDER_LAYERS, require = 0, at = @At(value = "INVOKE",
		target = "Lnet/minecraft/client/renderer/OrderedSubmitNodeCollector;submitModel(Lnet/minecraft/client/model/Model;Ljava/lang/Object;Lcom/mojang/blaze3d/vertex/PoseStack;Lnet/minecraft/client/renderer/rendertype/RenderType;IIILnet/minecraft/client/renderer/texture/UvMapping;I)V"))
	private void recur$fade(OrderedSubmitNodeCollector collector, Model<?> model, Object state, com.mojang.blaze3d.vertex.PoseStack pose,
							RenderType type, int light, int overlay, int color, UvMapping uv, int outline, Operation<Void> original) {
		if (CloneFade.drawing < 1f) {
			if (type == RenderTypes.trimmedArmorGlint()) return; // the shine can't be see-through
			color = CloneFade.fade(color);
		}
		original.call(collector, model, state, pose, type, light, overlay, color, uv, outline);
	}
}
