package dev.recur.mixin;

import dev.recur.CloneMade;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.entity.AgeableMob;
import net.minecraft.world.entity.animal.Animal;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/** Timed resets: a baby of animals a clone bred is clone-made (no loot from it). */
@Mixin(Animal.class)
public abstract class BreedMixin {
	@Inject(method = "finalizeSpawnChildFromBreeding", at = @At("HEAD"))
	private void recur$bred(ServerLevel level, Animal partner, AgeableMob offspring, CallbackInfo ci) {
		CloneMade.onBred((Animal) (Object) this, partner, offspring);
	}
}
