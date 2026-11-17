package dev.recur.mixin;

import com.mt1006.mocap.mocap.playing.Playing;
import dev.recur.WorldLog;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.projectile.Projectile;
import net.minecraft.world.entity.projectile.ProjectileDeflection;
import net.minecraft.world.phys.HitResult;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/**
 * What a player's projectile does when it hits (a target block, a button, an egg's chick, Channeling lightning...) is
 * recorded as that player's action, so their clone does it again.
 */
@Mixin(Projectile.class)
public abstract class ProjectileHitMixin {
	@Unique private ServerPlayer recur$shooter;

	@Inject(method = "hitTargetOrDeflectSelf", at = @At("HEAD"))
	private void recur$begin(HitResult hit, CallbackInfoReturnable<ProjectileDeflection> cir) {
		Projectile self = (Projectile) (Object) this;
		recur$shooter = null;
		if (self.level().isClientSide() || self.entityTags().contains(Playing.MOCAP_ENTITY_TAG)) return;
		if (self.getOwner() instanceof ServerPlayer p && !(p instanceof com.mt1006.mocap.utils.FakePlayer)) {
			recur$shooter = p;
			WorldLog.beginAction(p);
		}
	}

	@Inject(method = "hitTargetOrDeflectSelf", at = @At("RETURN"))
	private void recur$end(HitResult hit, CallbackInfoReturnable<ProjectileDeflection> cir) {
		if (recur$shooter != null) { WorldLog.endAction(recur$shooter); recur$shooter = null; }
	}
}
