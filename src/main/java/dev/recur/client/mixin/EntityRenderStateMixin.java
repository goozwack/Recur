package dev.recur.client.mixin;

import dev.recur.client.CloneAlpha;
import net.minecraft.client.renderer.entity.state.EntityRenderState;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Unique;

@Mixin(EntityRenderState.class)
public abstract class EntityRenderStateMixin implements CloneAlpha {
	@Unique private float recur$alpha = 1f;
	@Unique private float recur$tagAlpha = 1f;
	@Override public float recur$getAlpha() { return recur$alpha; }
	@Override public void recur$setAlpha(float alpha) { recur$alpha = alpha; }
	@Override public float recur$getTagAlpha() { return recur$tagAlpha; }
	@Override public void recur$setTagAlpha(float alpha) { recur$tagAlpha = alpha; }
}
