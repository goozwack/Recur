package dev.recur.mixin;

import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.entity.raid.Raids;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Mutable;
import org.spongepowered.asm.mixin.gen.Accessor;

@Mixin(ServerLevel.class)
public interface ServerLevelAccessor {
	@Mutable
	@Accessor("raids")
	void recur$setRaids(Raids raids);
}
