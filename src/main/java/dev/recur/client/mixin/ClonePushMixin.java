package dev.recur.client.mixin;

import dev.recur.client.ClientLoopState;
import net.minecraft.world.entity.LivingEntity;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/**
 * Pushing between players is worked out on each client: other players' bodies push YOUR player on your screen.
 * So on the client, clones neither push you nor can be pushed by you.
 */
@Mixin(LivingEntity.class)
public abstract class ClonePushMixin {
	@Inject(method = "isPushable", at = @At("HEAD"), cancellable = true)
	private void recur$clonesArentPushable(CallbackInfoReturnable<Boolean> cir) {
		if (recur$isClientClone()) cir.setReturnValue(false);
	}

	@Inject(method = "pushEntities", at = @At("HEAD"), cancellable = true)
	private void recur$clonesDontPush(CallbackInfo ci) {
		if (recur$isClientClone()) ci.cancel();
	}

	private boolean recur$isClientClone() {
		LivingEntity self = (LivingEntity) (Object) this;
		return self.level().isClientSide() && ClientLoopState.isClone(self.getId());
	}
}
