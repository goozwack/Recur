package dev.recur.mixin;

import com.mt1006.mocap.mocap.playing.Playing;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.projectile.EyeOfEnder;
import net.minecraft.world.entity.projectile.Projectile;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * A clone's arrows, tridents, pearls, snowballs, eyes of ender... are copies that only follow the recorded path: they
 * never fly, hit, teleport or explode by themselves (what they did is replayed from the recording instead).
 */
@Mixin(ServerLevel.class)
public abstract class ProjectileCopyMixin {
	@Inject(method = "tickNonPassenger", at = @At("HEAD"), cancellable = true)
	private void recur$frozen(Entity entity, CallbackInfo ci) {
		if ((entity instanceof Projectile || entity instanceof EyeOfEnder) && entity.entityTags().contains(Playing.MOCAP_ENTITY_TAG)) ci.cancel();
	}
}
