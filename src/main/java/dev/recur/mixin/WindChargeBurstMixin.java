package dev.recur.mixin;

import com.mt1006.mocap.mocap.playing.Playing;
import net.minecraft.world.entity.projectile.hurtingprojectile.windcharge.WindCharge;
import net.minecraft.world.level.Level;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.ModifyArg;

/**
 * A clone's wind charge bursts and pushes like a real one, but doesn't press buttons or open doors: its recording
 * already does what it did to blocks.
 */
@Mixin(WindCharge.class)
public abstract class WindChargeBurstMixin {
	@ModifyArg(method = "explode", index = 8, at = @At(value = "INVOKE",
		target = "Lnet/minecraft/world/level/Level;explode(Lnet/minecraft/world/entity/Entity;Lnet/minecraft/world/damagesource/DamageSource;Lnet/minecraft/world/level/ExplosionDamageCalculator;DDDFZLnet/minecraft/world/level/Level$ExplosionInteraction;Lnet/minecraft/core/particles/ParticleOptions;Lnet/minecraft/core/particles/ParticleOptions;Lnet/minecraft/util/random/WeightedList;Lnet/minecraft/core/Holder;)V"))
	private Level.ExplosionInteraction recur$noBlocks(Level.ExplosionInteraction interaction) {
		return ((WindCharge) (Object) this).entityTags().contains(Playing.MOCAP_ENTITY_TAG) ? Level.ExplosionInteraction.NONE : interaction;
	}
}
