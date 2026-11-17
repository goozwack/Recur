package dev.recur.mixin;

import net.minecraft.network.syncher.EntityDataAccessor;
import net.minecraft.world.entity.monster.Enderman;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Accessor;
import org.spongepowered.asm.mixin.gen.Invoker;

@Mixin(Enderman.class)
public interface EndermanAccessor {
	@Accessor("DATA_CREEPY")
	static EntityDataAccessor<Boolean> recur$creepy() { throw new AssertionError(); }

	@Invoker("isBeingStaredBy")
	boolean recur$isBeingStaredBy(net.minecraft.world.entity.player.Player player);
}
