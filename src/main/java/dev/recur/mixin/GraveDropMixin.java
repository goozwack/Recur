package dev.recur.mixin;

import dev.recur.TimedReset;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.level.gamerules.GameRules;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * With timed resets on, a player's death drops stay together in the block they died in (see TimedReset.Graves), and a
 * clone that dies drops nothing (what it carries is only a replay of what its player had).
 */
@Mixin(Player.class)
public abstract class GraveDropMixin {
	@Shadow protected abstract void destroyVanishingCursedItems();

	@Inject(method = "dropEquipment", at = @At("HEAD"), cancellable = true)
	private void recur$grave(ServerLevel level, CallbackInfo ci) {
		if (!TimedReset.isActive() || !((Object) this instanceof ServerPlayer player)) return;
		if (player instanceof com.mt1006.mocap.utils.FakePlayer) {
			if (TimedReset.antiDupe()) ci.cancel();
			return;
		}
		if (Boolean.TRUE.equals(level.getGameRules().get(GameRules.KEEP_INVENTORY))) return;
		destroyVanishingCursedItems();
		TimedReset.Graves.make(player);
		ci.cancel();
	}
}
