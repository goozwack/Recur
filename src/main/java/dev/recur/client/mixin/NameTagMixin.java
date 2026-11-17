package dev.recur.client.mixin;

import dev.recur.client.ClientLoopState;
import net.minecraft.client.renderer.SubmitNodeCollection;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.ModifyVariable;

/** Fades a clone's name tag (text and background) along with its body. */
@Mixin(SubmitNodeCollection.class)
public abstract class NameTagMixin {
	@ModifyVariable(method = "nameTag", at = @At("HEAD"), ordinal = 1, argsOnly = true)
	private static int recur$fadeText(int color) {
		return scale(color, ClientLoopState.drawingTagAlpha);
	}

	@ModifyVariable(method = "nameTag", at = @At("HEAD"), ordinal = 2, argsOnly = true)
	private static int recur$fadeBackground(int color) {
		return scale(color, ClientLoopState.drawingTagAlpha);
	}

	private static int scale(int argb, float alpha) {
		if (alpha >= 1f) return argb;
		int a = Math.round(((argb >>> 24) & 0xFF) * alpha);
		return (a << 24) | (argb & 0xFFFFFF);
	}
}
