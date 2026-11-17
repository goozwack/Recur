package dev.recur.mixin;

import com.google.common.collect.ImmutableList;
import net.minecraft.world.entity.Entity;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Accessor;

/** Lets a clone take the driver's seat (the first passenger) of a vehicle it drives. */
@Mixin(Entity.class)
public interface EntityPassengersAccessor {
	@Accessor("passengers")
	void recur$setPassengers(ImmutableList<Entity> passengers);
}
