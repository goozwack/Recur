package dev.recur.mixin;

import dev.recur.CloneMade;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.damagesource.DamageSource;
import net.minecraft.world.entity.LivingEntity;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/** Timed resets: clones, mobs a clone made (golems, bred babies) and mobs a clone killed drop no loot or experience. */
@Mixin(LivingEntity.class)
public abstract class DeathLootMixin {
	@Inject(method = "dropAllDeathLoot", at = @At("HEAD"), cancellable = true)
	private void recur$noLoot(ServerLevel level, DamageSource source, CallbackInfo ci) {
		if (CloneMade.blocksDeathLoot((LivingEntity) (Object) this)) { ci.cancel(); return; }
		dev.recur.MobDrops.startDrops((LivingEntity) (Object) this); // what it drops now remembers it
	}

	@Inject(method = "dropAllDeathLoot", at = @At("RETURN"))
	private void recur$dropped(ServerLevel level, DamageSource source, CallbackInfo ci) {
		dev.recur.MobDrops.endDrops();
	}
}
