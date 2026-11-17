package dev.recur.mixin;

import net.minecraft.server.network.ServerGamePacketListenerImpl;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Invoker;

@Mixin(ServerGamePacketListenerImpl.class)
public interface ConnectionAccessor {
	/** Clears the "waiting for respawn" flag that dying sets; vanilla only clears it when the Respawn button is pressed. */
	@Invoker("restartClientLoadTimerAfterRespawn")
	void recur$restartClientLoadTimerAfterRespawn();
}
