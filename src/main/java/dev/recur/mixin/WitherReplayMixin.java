package dev.recur.mixin;

import com.mt1006.mocap.mocap.playing.Controlled;
import dev.recur.BossReplay;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.entity.boss.wither.WitherBoss;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@Mixin(WitherBoss.class)
public abstract class WitherReplayMixin {
	@Inject(method = "aiStep", at = @At("HEAD"))
	private void recur$replayed(CallbackInfo ci) {
		WitherBoss self = (WitherBoss) (Object) this;
		if (self.level() instanceof ServerLevel level && Controlled.isControlled(self)) {
			BossReplay.powerUp(level, self);
			BossReplay.checkCrater(level, self);
			BossReplay.aimWitherHeads(level, self);
		}
	}
}
