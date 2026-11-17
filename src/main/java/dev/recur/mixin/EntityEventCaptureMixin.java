package dev.recur.mixin;

import com.mt1006.mocap.events.EntityEvent;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.entity.Entity;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/** Mob animations sent as entity events (attack swings and the like) go into the recordings that move those mobs. */
@Mixin(ServerLevel.class)
public abstract class EntityEventCaptureMixin {
	@Inject(method = "broadcastEntityEvent", at = @At("HEAD"))
	private void recur$entityEvent(Entity entity, byte event, CallbackInfo ci) {
		EntityEvent.onEntityEvent(entity, event);
	}
}
