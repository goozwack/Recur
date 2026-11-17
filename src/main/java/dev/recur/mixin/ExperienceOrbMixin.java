package dev.recur.mixin;

import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import com.llamalad7.mixinextras.injector.wrapoperation.WrapOperation;
import com.mt1006.mocap.mocap.playing.Playing;
import com.mt1006.mocap.utils.FakePlayer;
import dev.recur.CloneMade;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.ExperienceOrb;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.level.Level;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/**
 * Experience from a clone's kill flies around by itself, but only its recording says when the clone picks it up. Clones
 * never pick up experience themselves, and with the anti-dupe rules a clone's experience only flies to clones and can't
 * be picked up by anyone (its player already got it). It never merges with other experience.
 */
@Mixin(ExperienceOrb.class)
public abstract class ExperienceOrbMixin {
	private boolean recur$clones() {
		return ((Entity) (Object) this).entityTags().contains(Playing.MOCAP_ENTITY_TAG);
	}

	@Inject(method = "playerTouch", at = @At("HEAD"), cancellable = true)
	private void recur$touch(Player player, CallbackInfo ci) {
		if (player instanceof FakePlayer || (recur$clones() && CloneMade.active())) ci.cancel();
	}

	@Inject(method = "canMerge(Lnet/minecraft/world/entity/ExperienceOrb;)Z", at = @At("HEAD"), cancellable = true)
	private void recur$merge(ExperienceOrb other, CallbackInfoReturnable<Boolean> cir) {
		if (recur$clones() || other.entityTags().contains(Playing.MOCAP_ENTITY_TAG)) cir.setReturnValue(false);
	}

	@WrapOperation(method = "followNearbyPlayer", at = @At(value = "INVOKE",
		target = "Lnet/minecraft/world/level/Level;getNearestPlayer(Lnet/minecraft/world/entity/Entity;D)Lnet/minecraft/world/entity/player/Player;"))
	private Player recur$follow(Level level, Entity orb, double range, Operation<Player> original) {
		boolean clones = recur$clones();
		if (clones && !CloneMade.active()) return original.call(level, orb, range);
		boolean toClones = clones; // a clone's goes to clones, everything else ignores them
		return level.getNearestPlayer(orb.getX(), orb.getY(), orb.getZ(), range,
			e -> !e.isSpectator() && (e instanceof FakePlayer) == toClones);
	}
}
