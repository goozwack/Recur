package dev.recur.mixin;

import com.mt1006.mocap.mocap.playing.Controlled;
import net.minecraft.world.entity.monster.Creeper;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * A creeper a clone plays back swells like it did, but it doesn't blow up by itself: its explosion comes from the
 * recording, with the same blocks broken every time (see WorldLog.KIND_EXPLOSION).
 */
@Mixin(Creeper.class)
public abstract class CreeperExplodeMixin {
	@Inject(method = "explodeCreeper", at = @At("HEAD"), cancellable = true)
	private void recur$recorded(CallbackInfo ci) {
		if (Controlled.isControlled((Creeper) (Object) this)) ci.cancel();
	}
}
