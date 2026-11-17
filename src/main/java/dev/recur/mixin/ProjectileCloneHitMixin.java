package dev.recur.mixin;

import com.mt1006.mocap.utils.FakePlayer;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.Mob;
import net.minecraft.world.entity.projectile.Projectile;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/**
 * An arrow or fireball a mob shoots at a clone hits it, where in the recording it hit the player: the arrow stops, the
 * fireball blows up. Clones can't be hurt, so only what's around them feels it.
 */
@Mixin(Projectile.class)
public abstract class ProjectileCloneHitMixin {
	@Inject(method = "canHitEntity", at = @At("HEAD"), cancellable = true)
	private void recur$hitClone(Entity target, CallbackInfoReturnable<Boolean> cir) {
		if (target instanceof FakePlayer && ((Projectile) (Object) this).getOwner() instanceof Mob) cir.setReturnValue(true);
	}
}
