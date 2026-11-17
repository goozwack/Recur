package dev.recur.mixin;

import net.minecraft.server.level.ServerPlayerGameMode;
import net.minecraft.world.level.GameType;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Invoker;

@Mixin(ServerPlayerGameMode.class)
public interface GameModeAccessor {
	/** Sets the game mode without telling anyone (changeGameModeForPlayer tells every player). */
	@Invoker("setGameModeForPlayer") void recur$set(GameType mode, GameType previous);
}
