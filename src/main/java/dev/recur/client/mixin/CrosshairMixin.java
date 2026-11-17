package dev.recur.client.mixin;

import com.mojang.renderpearl.api.pipeline.RenderPipeline;
import dev.recur.client.ClientLoot;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.Hud;
import net.minecraft.client.renderer.RenderPipelines;
import net.minecraft.resources.Identifier;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Redirect;

/** Timed resets: the crosshair is red while it's on something that won't give you an item. */
@Mixin(Hud.class)
public abstract class CrosshairMixin {
	@Redirect(method = "extractCrosshair", at = @At(value = "INVOKE", ordinal = 0,
		target = "Lnet/minecraft/client/gui/GuiGraphicsExtractor;blitSprite(Lcom/mojang/renderpearl/api/pipeline/RenderPipeline;Lnet/minecraft/resources/Identifier;IIII)V"))
	private void recur$tint(GuiGraphicsExtractor graphics, RenderPipeline pipeline, Identifier sprite, int x, int y, int w, int h) {
		if (ClientLoot.crosshairRed()) graphics.blitSprite(RenderPipelines.GUI_TEXTURED, sprite, x, y, w, h, dev.recur.client.Theme.WARNING);
		else graphics.blitSprite(pipeline, sprite, x, y, w, h);
	}
}
