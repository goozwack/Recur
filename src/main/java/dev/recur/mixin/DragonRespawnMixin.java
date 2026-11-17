package dev.recur.mixin;

import dev.recur.WorldLog;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.dimension.end.EnderDragonFight;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/** A recording player placing the last End crystal starts the dragon respawn: their clone will start it again. */
@Mixin(EnderDragonFight.class)
public abstract class DragonRespawnMixin {
	@Shadow private ServerLevel level;

	@Inject(method = "tryRespawn", at = @At("HEAD"))
	private void recur$record(CallbackInfo ci) {
		WorldLog.onDragonRespawn(this.level);
	}
}
