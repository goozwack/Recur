package dev.recur.mixin;

import net.minecraft.server.MinecraftServer;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Accessor;

@Mixin(MinecraftServer.class)
public interface MinecraftServerAccessor {
	/** When the next tick is due. Setting it to "now" stops the server rushing through missed ticks after a long one. */
	@Accessor("nextTickTimeNanos")
	void recur$setNextTickTimeNanos(long nanos);
}
