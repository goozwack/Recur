package dev.recur.mixin;

import net.minecraft.world.entity.LivingEntity;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Accessor;

@Mixin(LivingEntity.class)
public interface LivingEntityHurtAccessor {
	@Accessor("lastHurt") float recur$lastHurt();
	@Accessor("lastHurt") void recur$setLastHurt(float amount);
}
