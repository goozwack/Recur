package dev.recur.mixin;

import com.mt1006.mocap.mocap.actions.SetBaby;
import com.mt1006.mocap.mocap.playing.Controlled;
import net.minecraft.world.entity.AgeableMob;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/** A mob a clone plays back doesn't grow up by itself: it's a baby or grown up exactly when its recording says (see SetBaby). */
@Mixin(AgeableMob.class)
public abstract class ReplayAgeMixin {
	@Inject(method = "setAge", at = @At("HEAD"), cancellable = true)
	private void recur$recordedAge(int age, CallbackInfo ci) {
		if (!SetBaby.applying && Controlled.isControlled((AgeableMob) (Object) this)) ci.cancel();
	}
}
